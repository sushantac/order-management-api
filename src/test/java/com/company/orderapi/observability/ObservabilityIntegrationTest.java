package com.company.orderapi.observability;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.OrderService;
import com.company.orderapi.domain.service.PaymentGateway;
import com.company.orderapi.domain.service.ProductCatalogueService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #32 integration tests: correlation ids on requests, the custom health
 * indicator, and that @Timed produces real Micrometer timers (+ Prometheus
 * text format on /actuator/prometheus).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=ObservabilityIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "app.security.enabled=false",
        "management.health.redis.enabled=false"
})
class ObservabilityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PrometheusMeterRegistry prometheusRegistry;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductCatalogueService catalogue;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private ProductRepository products;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void correlationIdIsEchoedAndGeneratedWhenMissing() throws Exception {
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header(CorrelationIdFilter.HEADER, "corr-test-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-test-123"));

        mockMvc.perform(get("/api/v1/customers?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }

    @Test
    void customHealthIndicatorAppearsInActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.appInfo.status").value("UP"))
                .andExpect(jsonPath("$.components.appInfo.details.component")
                        .value("order-management-api"));
    }

    @Test
    void timedMethodsProduceMicrometerTimersAndPrometheusOutput() throws Exception {
        Customer customer = customers.saveAndFlush(
                new Customer("obs@example.com", "Obs Customer"));
        Product product = products.saveAndFlush(
                new Product("Obs Widget", new BigDecimal("4.00"), 5));

        orderService.placeOrder(customer.getId(),
                List.of(new OrderService.OrderLine(product.getId(), 1)));
        catalogue.get(product.getId());

        assertThat(meterRegistry.get("order.place").timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("product.get").timer().count()).isGreaterThanOrEqualTo(1);

        // Timers are visible on the metrics endpoint...
        String metrics = mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("order.place").contains("product.get");

        // ...and exported in Prometheus text format (what a scraper reads).
        String scrape = prometheusRegistry.scrape();
        assertThat(scrape).contains("order_place_seconds_count")
                .contains("product_get_seconds_count");
    }
}
