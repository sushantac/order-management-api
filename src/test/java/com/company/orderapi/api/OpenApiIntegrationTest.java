package com.company.orderapi.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #25 integration tests: springdoc serves the OpenAPI spec and Swagger UI,
 * and the deprecated alias advertises Deprecation/Sunset headers.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=OpenApiIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        // Security is exercised in SecurityIntegrationTest (PR #26).
        "app.security.enabled=false"
})
class OpenApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiSpecIsGeneratedAtV3ApiDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.info.title").value("Order Management API"))
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.summary").value("Place an order"))
                .andExpect(jsonPath("$.paths['/api/v1/customers'].get").exists());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void deprecatedAliasAdvertisesDeprecationAndSunset() throws Exception {
        mockMvc.perform(get("/api/v1/customers/legacy?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Sunset"));
    }
}
