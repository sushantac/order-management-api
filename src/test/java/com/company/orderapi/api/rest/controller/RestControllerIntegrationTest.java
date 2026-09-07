package com.company.orderapi.api.rest.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #22 integration tests: REST controllers behind MockMvc. The real payment
 * gateway fails 10% of the time, so tests substitute an always-success fake.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=RestControllerIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        // Security is exercised in SecurityIntegrationTest (PR #26).
        "app.security.enabled=false"
})
class RestControllerIntegrationTest {

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
            return amount -> { /* always succeeds */ };
        }
    }

    @Test
    void customerCrudLifecycle() throws Exception {
        long id = createCustomer("rest1@example.com", "Rest One");
        mockMvc.perform(get("/api/v1/customers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("rest1@example.com"));
        mockMvc.perform(get("/api/v1/customers?page=0&size=5&sort=fullName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == 'rest1@example.com')]").exists());
        mockMvc.perform(put("/api/v1/customers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rest1@example.com\",\"fullName\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed"));
        mockMvc.perform(delete("/api/v1/customers/{id}", id))
                .andExpect(status().isNoContent());
    }

    @Test
    void productCrudLifecycle() throws Exception {
        long id = createProduct("Rest Widget", "9.50", 12);
        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rest Widget"));
        mockMvc.perform(put("/api/v1/products/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rest Widget XL\",\"price\":\"11.00\",\"stockQuantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(11.0));
        mockMvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isNoContent());
    }

    @Test
    void categoryCrud() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stationery\",\"description\":\"Paper goods\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Stationery"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Paper goods"));
    }

    @Test
    void orderFlowWithPatchAndETag() throws Exception {
        long customerId = createCustomer("rest2@example.com", "Ordering Customer");
        long productId = createProduct("Ordered Widget", "4.00", 20);
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(8.0))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();
        long orderId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        MvcResult fetched = mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();
        String etag = fetched.getResponse().getHeader("ETag");
        mockMvc.perform(patch("/api/v1/orders/{id}", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"CONFIRMED\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Missing If-Match -> 412. The PATCH bumped the version, so the OLD
        // ETag is now stale as well.
        mockMvc.perform(delete("/api/v1/orders/{id}", orderId))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(delete("/api/v1/orders/{id}", orderId).header("If-Match", etag))
                .andExpect(status().isPreconditionFailed());

        // A fresh ETag satisfies the precondition and deletes the order.
        MvcResult refreshed = mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andReturn();
        String freshEtag = refreshed.getResponse().getHeader("ETag");
        mockMvc.perform(delete("/api/v1/orders/{id}", orderId).header("If-Match", freshEtag))
                .andExpect(status().isNoContent());
    }

    @Test
    void bulkCreateCreatesManyOrders() throws Exception {
        long customerId = createCustomer("rest3@example.com", "Bulk Customer");
        long productId = createProduct("Bulk Widget", "1.00", 100);
        String body = "["
                + "{\"customerId\":" + customerId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]},"
                + "{\"customerId\":" + customerId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}"
                + "]";
        mockMvc.perform(post("/api/v1/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void fieldsFilteringAndResourceInclusion() throws Exception {
        long id = createCustomer("rest4@example.com", "Projection Customer");
        mockMvc.perform(get("/api/v1/customers/{id}/view?fields=id,fullName", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.fullName").value("Projection Customer"))
                .andExpect(jsonPath("$.email").doesNotExist());
        mockMvc.perform(get("/api/v1/customers/{id}/view?include=orders", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isArray());
    }

    @Test
    void deletingACustomerWithOrdersIsRefused() throws Exception {
        long customerId = createCustomer("rest5@example.com", "Protected");
        long productId = createProduct("Prot Widget", "1.00", 10);
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/customers/{id}", customerId))
                .andExpect(status().isConflict());
    }

    private long createCustomer(String email, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"fullName\":\"" + name + "\"}"))
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
