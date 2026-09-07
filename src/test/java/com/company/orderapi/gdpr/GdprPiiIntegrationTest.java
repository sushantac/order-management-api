package com.company.orderapi.gdpr;

import com.company.orderapi.domain.audit.AuditLog;
import com.company.orderapi.domain.audit.AuditLogRepository;
import com.company.orderapi.domain.service.PaymentGateway;
import com.company.orderapi.security.SecurityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #27 integration tests (security ON): response masking by scope, GDPR
 * erasure (delete vs anonymize), portability export, authorization of the
 * pii_* scopes and the audit trail each action leaves behind.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=GdprPiiIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class GdprPiiIntegrationTest {

    /** Subject name minted into every test token (= the audit "actor"). */
    private static final String ACTOR = "gdpr-user";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private AuditLogRepository auditLogs;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void customerPiiIsMaskedForPlainReadScopesButRawForPiiScope() throws Exception {
        String admin = token("order_read order_write pii_read pii_write");
        long id = createCustomer(admin, "gdpr1@example.com", "GDPR One");

        // order_read alone: masked view.
        mockMvc.perform(get("/api/v1/customers/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("order_read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("g***@e***.com"))
                .andExpect(jsonPath("$.fullName").value("G***e"));

        // pii_read: the raw personal data.
        mockMvc.perform(get("/api/v1/customers/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("gdpr1@example.com"))
                .andExpect(jsonPath("$.fullName").value("GDPR One"));
    }

    @Test
    void erasureDeletesCustomerWithoutOrderHistoryAndAudits() throws Exception {
        String admin = token("order_read order_write pii_read pii_write");
        long id = createCustomer(admin, "gdpr2@example.com", "GDPR Two");

        mockMvc.perform(delete("/api/v1/customers/{id}/data", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DELETED"));

        mockMvc.perform(get("/api/v1/customers/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNotFound());

        assertAudit(id, "CUSTOMER_ERASED");
    }

    @Test
    void erasureAnonymizesCustomerWithOrdersAndKeepsHistory() throws Exception {
        String admin = token("order_read order_write pii_read pii_write");
        long id = createCustomer(admin, "gdpr3@example.com", "GDPR Three");
        long productId = createProduct(admin, "Retain Widget", "2.00", 10);
        placeOrder(admin, id, productId);

        mockMvc.perform(delete("/api/v1/customers/{id}/data", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ANONYMIZED"));

        // Personal fields are gone; the (retained) history still resolves.
        mockMvc.perform(get("/api/v1/customers/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("erased-" + id + "@erased.invalid"))
                .andExpect(jsonPath("$.fullName").value("Erased User"))
                .andExpect(jsonPath("$.phoneNumber").value(nullValue()));

        // The retained order is still in the portability export (legal retention).
        mockMvc.perform(get("/api/v1/customers/{id}/portability", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1));

        // Erasing again stays idempotent (still anonymized, no unique clash).
        mockMvc.perform(delete("/api/v1/customers/{id}/data", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ANONYMIZED"));

        assertAudit(id, "CUSTOMER_ANONYMIZED");
    }

    @Test
    void portabilityExportsFullProfileAndOrderHistory() throws Exception {
        String admin = token("order_read order_write pii_read pii_write");
        long id = createCustomer(admin, "gdpr4@example.com", "GDPR Four");
        long productId = createProduct(admin, "Port Widget", "3.00", 10);
        placeOrder(admin, id, productId);

        mockMvc.perform(get("/api/v1/customers/{id}/portability", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("gdpr4@example.com"))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].items[0].productName").value("Port Widget"))
                .andExpect(jsonPath("$.statement").exists());

        assertAudit(id, "PORTABILITY_EXPORTED");
    }

    @Test
    void ordinaryReadScopeCannotTriggerGdprActions() throws Exception {
        String readOnly = token("order_read");
        String admin = token("order_read order_write pii_read pii_write");
        long id = createCustomer(admin, "gdpr5@example.com", "GDPR Five");

        mockMvc.perform(delete("/api/v1/customers/{id}/data", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers/{id}/portability", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                .andExpect(status().isForbidden());
        assertThat(auditLogs.findByCustomerIdOrderByOccurredAtDesc(id)).isEmpty();
    }

    @Test
    void apiKeySeesRawPiiAndCanErase() throws Exception {
        String admin = token("order_read order_write pii_read pii_write");
        long id = createCustomer(admin, "gdpr6@example.com", "GDPR Six");

        mockMvc.perform(get("/api/v1/customers/{id}", id)
                        .header("X-API-Key", security.getApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("gdpr6@example.com"));

        mockMvc.perform(delete("/api/v1/customers/{id}/data", id)
                        .header("X-API-Key", security.getApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DELETED"));
    }

    // --- helpers ------------------------------------------------------------

    private void assertAudit(long customerId, String action) {
        List<AuditLog> entries = auditLogs.findByCustomerIdOrderByOccurredAtDesc(customerId);
        assertThat(entries).extracting(AuditLog::getAction)
                .as("audit trail for customer " + customerId)
                .contains(action);
        assertThat(entries).extracting(AuditLog::getActor)
                .contains(ACTOR);
    }

    private long createCustomer(String bearer, String email, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"fullName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private long createProduct(String bearer, String name, String price, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"price\":\"" + price
                                + "\",\"stockQuantity\":" + stock + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private void placeOrder(String bearer, long customerId, long productId) throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
    }

    private long readId(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    /** Signs an HS256 JWT with the configured secret, like the auth server. */
    private String token(String scopes) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ACTOR)
                .issuer("order-api")
                .claim("scope", scopes)
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner(security.getJwtSecret()));
        return signed.serialize();
    }
}
