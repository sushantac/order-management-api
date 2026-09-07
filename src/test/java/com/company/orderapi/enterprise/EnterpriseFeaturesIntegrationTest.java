package com.company.orderapi.enterprise;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
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

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #35 - enterprise slice: CSV export (flag-gated) and i18n message
 * resolution by Accept-Language.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=EnterpriseFeaturesIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "app.security.enabled=false"
})
class EnterpriseFeaturesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository products;

    @Test
    void productCatalogueExportsAsCsv() throws Exception {
        products.saveAndFlush(new Product("CSV Widget", new BigDecimal("2.50"), 7));

        mockMvc.perform(get("/api/v1/products/export.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id,name,price,stockQuantity\n")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CSV Widget")));
    }

    @Test
    void i18nMessagesResolveByAcceptLanguage() throws Exception {
        mockMvc.perform(get("/api/v1/messages/order.created")
                        .param("arg", "ORD-123")
                        .header("Accept-Language", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("de"))
                .andExpect(jsonPath("$.message")
                        .value("Bestellung ORD-123 wurde aufgegeben."));

        mockMvc.perform(get("/api/v1/messages/greeting.hello")
                        .param("arg", "Ada")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.message").value("Hello Ada!"));
    }
}
