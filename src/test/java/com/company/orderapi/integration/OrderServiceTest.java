package com.company.orderapi.integration;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.OrderService;
import com.company.orderapi.domain.service.PaymentFailedException;
import com.company.orderapi.domain.service.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #20 integration tests: {@link OrderService} ACID semantics with a
 * deterministic payment fake.
 *
 * <p>Deliberately NOT {@code @Transactional}: each {@code placeOrder} runs in
 * its OWN transaction, so after a payment failure we read the COMMITTED
 * database and can prove the rollback really happened.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=OrderServiceTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class OrderServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    /** Deterministic stand-in for the random SimulatedPaymentGateway. */
    static final FakePaymentGateway FAKE_GATEWAY = new FakePaymentGateway();

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return FAKE_GATEWAY;
        }
    }

    @BeforeEach
    void resetGateway() {
        FAKE_GATEWAY.allow();
    }

    @Test
    void placeOrderPersistsGraphDeductsStockAndCharges() {
        Customer customer = seedCustomer("svc1@example.com", "Service One");
        Product product = seedProduct("SV-Item", new BigDecimal("5.00"), 10);

        Order order = orderService.placeOrder(customer.getId(),
                List.of(new OrderService.OrderLine(product.getId(), 2)));

        assertThat(order.getId()).isNotNull();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("10.00");
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getPayment().getStatus().name()).isEqualTo("PROCESSED");
        assertThat(FAKE_GATEWAY.charges()).containsExactly(new BigDecimal("10.00"));

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(8); // 10 - 2
    }

    @Test
    void paymentFailureRollsBackTheWholeOrder() {
        Customer customer = seedCustomer("svc2@example.com", "Service Two");
        Product product = seedProduct("SV-Atomic", new BigDecimal("3.00"), 5);
        long ordersBefore = orderRepository.count();
        FAKE_GATEWAY.decline();

        assertThatThrownBy(() -> orderService.placeOrder(customer.getId(),
                List.of(new OrderService.OrderLine(product.getId(), 1))))
                .isInstanceOf(PaymentFailedException.class);

        // Atomicity, read from the COMMITTED database:
        assertThat(orderRepository.count())
                .as("no order may survive a failed payment")
                .isEqualTo(ordersBefore);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStockQuantity())
                .as("stock deduction must roll back with the failed payment")
                .isEqualTo(5);
    }

    @Test
    void insufficientStockIsRejectedWithoutSideEffects() {
        Customer customer = seedCustomer("svc3@example.com", "Service Three");
        Product product = seedProduct("SV-Rare", new BigDecimal("1.00"), 1);
        long ordersBefore = orderRepository.count();

        assertThatThrownBy(() -> orderService.placeOrder(customer.getId(),
                List.of(new OrderService.OrderLine(product.getId(), 10))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(1);
    }

    private Customer seedCustomer(String email, String name) {
        return customerRepository.saveAndFlush(new Customer(email, name));
    }

    private Product seedProduct(String name, BigDecimal price, int stock) {
        return productRepository.saveAndFlush(new Product(name, price, stock));
    }

    /** Simple controllable fake gateway visible to the tests. */
    static class FakePaymentGateway implements PaymentGateway {
        private volatile boolean fail = false;
        private final List<BigDecimal> charges = new CopyOnWriteArrayList<>();

        void allow() {
            this.fail = false;
        }

        void decline() {
            this.fail = true;
        }

        List<BigDecimal> charges() {
            return charges;
        }

        @Override
        public void charge(BigDecimal amount) {
            if (fail) {
                throw new PaymentFailedException("Declined by fake gateway");
            }
            charges.add(amount);
        }
    }
}
