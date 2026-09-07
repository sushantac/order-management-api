package com.company.orderapi.api.rest;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #23 integration tests: Bean Validation, custom constraints and groups.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=ValidationIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        // Security is exercised in SecurityIntegrationTest (PR #26).
        "app.security.enabled=false"
})
class ValidationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void invalidEmailAndBlankNameAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createGroupIsStricterThanUpdateGroup() throws Exception {
        // Create: fullName must be >= 2 chars.
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"groups@example.com\",\"fullName\":\"A\"}"))
                .andExpect(status().isBadRequest());

        long id = createCustomer();

        // Update group: the min-length(2) rule does NOT apply, so this passes.
        mockMvc.perform(put("/api/v1/customers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"groups@example.com\",\"fullName\":\"B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("B"));
    }

    @Test
    void customValidStockRejectsAbsurdStock() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Warehouse\",\"price\":\"1.00\",\"stockQuantity\":2000000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativePriceIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad Price\",\"price\":\"-1\",\"stockQuantity\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossFieldValidOrderRejectsDuplicateProducts() throws Exception {
        long customerId = createCustomer();
        long productId = createProduct("Dup", "2.00", 50);

        String duplicateItems = "{\"customerId\":" + customerId
                + ",\"items\":["
                + "{\"productId\":" + productId + ",\"quantity\":1},"
                + "{\"productId\":" + productId + ",\"quantity\":2}]}";

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateItems))
                .andExpect(status().isBadRequest());
    }

    private long createCustomer() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"v" + System.nanoTime() + "@example.com\","
                                + "\"fullName\":\"Validation\"}"))
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
