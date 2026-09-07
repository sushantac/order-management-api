package com.company.orderapi.api.rest;

import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.service.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #24 integration tests: RFC 7807 problem details + Idempotency-Key retries.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=ExceptionAndIdempotencyIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        // Security is exercised in SecurityIntegrationTest (PR #26).
        "app.security.enabled=false"
})
class ExceptionAndIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void unknownResourceProducesRfc7807ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/customers/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.hint").isNotEmpty());
    }

    @Test
    void validationFailureProducesProblemCode() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\",\"fullName\":\"Valid Name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void sameIdempotencyKeyReplaysTheFirstResponseWithoutDuplicatingTheOrder() throws Exception {
        long customerId = createCustomer();
        long productId = createProduct("Idem Widget", "2.50", 30);
        String body = "{\"customerId\":" + customerId
                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";
        String key = "order-key-" + System.nanoTime();

        long ordersBefore = orderRepository.count();

        String first = postOrder(key, body);
        assertThat(orderRepository.count()).isEqualTo(ordersBefore + 1);

        // Retry with the SAME key: identical 201 response, NO second order.
        String second = postOrder(key, body);
        assertThat(objectMapper.readTree(second))
                .as("replayed body is numerically identical to the original")
                .isEqualTo(objectMapper.readTree(first));
        assertThat(orderRepository.count())
                .as("idempotent retry must not create another order")
                .isEqualTo(ordersBefore + 1);
    }

    @Test
    void differentKeyCreatesASeparateOrder() throws Exception {
        long customerId = createCustomer();
        long productId = createProduct("Idem2 Widget", "1.00", 50);
        String body = "{\"customerId\":" + customerId
                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";
        long ordersBefore = orderRepository.count();

        postOrder("key-a-" + System.nanoTime(), body);
        postOrder("key-b-" + System.nanoTime(), body);

        assertThat(orderRepository.count()).isEqualTo(ordersBefore + 2);
    }

    private String postOrder(String key, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private long createCustomer() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"e" + System.nanoTime() + "@example.com\","
                                + "\"fullName\":\"Idem Customer\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createProduct(String name, String price, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"price\":\"" + price
                                + "\",\"stockQuantity\":" + stock + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
