package com.company.orderapi.concurrency;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.DashboardService;
import com.company.orderapi.domain.service.DashboardService.DashboardSummary;
import com.company.orderapi.domain.service.OrderService;
import com.company.orderapi.domain.service.PaymentGateway;
import com.company.orderapi.domain.service.ProductStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #30 integration tests: virtual threads are enabled, the dashboard
 * fan-out aggregates three independent queries concurrently, and 100
 * concurrent "buy the last unit" attempts through virtual threads end with
 * exactly one winner (optimistic locking still guards the row).
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=VirtualThreadsIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class VirtualThreadsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Environment environment;

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private ProductRepository products;

    @Autowired
    private ProductStockService stockService;

    @Autowired
    private OrderService orderService;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void virtualThreadsAreEnabledByConfiguration() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled"))
                .isEqualTo("true");
    }

    @Test
    void dashboardFansOutIndependentAggregatesConcurrently() {
        customers.saveAndFlush(new Customer("vt@example.com", "VT Customer"));
        Product product = products.saveAndFlush(
                new Product("VT Widget", new BigDecimal("5.00"), 10));
        long productId = product.getId();
        long customerId = customers.findAll().get(0).getId();
        orderService.placeOrder(customerId,
                List.of(new OrderService.OrderLine(productId, 2)));

        DashboardSummary summary = dashboard.fetch();

        assertThat(summary.customerCount()).isEqualTo(1);
        assertThat(summary.productCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.totalRevenue()).isEqualByComparingTo("10.00"); // 2 x 5.00
    }

    @Test
    void hundredConcurrentBuyersForTheLastUnitHaveExactlyOneWinner() throws Exception {
        Product lastUnit = products.saveAndFlush(
                new Product("Last Unit", new BigDecimal("1.00"), 1));
        long productId = lastUnit.getId();

        int buyers = 100;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            tasks.add(() -> {
                try {
                    stockService.decreaseStock(productId, 1);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = executor.invokeAll(tasks);
            long winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
    }
}
