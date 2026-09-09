package com.company.orderapi.security;

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
import com.company.orderapi.domain.service.PaymentGateway;
import com.company.orderapi.domain.idempotency.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #26 integration tests: OAuth2 resource server with JWT scopes, API-key
 * fallback, CORS and security headers.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=SecurityIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class SecurityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SecurityProperties properties;

    @Autowired
    private IdempotencyService idempotency;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void requestsWithoutATokenAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/customers?page=0&size=5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopedTokenAllowsWriteAndRead() throws Exception {
        String fullToken = token("order_read order_write");
        long customerId = createCustomer(fullToken);
        long productId = createProduct(fullToken, "Secured Widget", "5.00", 10);

        mockMvc.perform(get("/api/v1/customers/{id}", customerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fullToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fullToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void readOnlyScopeCannotCreateOrders() throws Exception {
        String readOnly = token("order_read");
        long customerId = createCustomer(readOnly);
        long productId = createProduct(readOnly, "ReadOnly Widget", "1.00", 5);

        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validApiKeyAuthenticatesMachineClient() throws Exception {
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header("X-API-Key", properties.getApiKey()))
                .andExpect(status().isOk());
    }

    @Test
    void corsPreflightAllowsConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:3000"));
    }

    @Test
    void securityHeadersArePresentOnResponses() throws Exception {
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .secure(true) // HSTS is only sent over HTTPS.
                        .header("X-API-Key", properties.getApiKey()))
                .andExpect(status().isOk())
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // --- helpers ------------------------------------------------------------

    private long createCustomer(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sec" + System.nanoTime() + "@example.com\","
                                + "\"fullName\":\"Sec Customer\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createProduct(String bearer, String name, String price, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"price\":\"" + price
                                + "\",\"stockQuantity\":" + stock + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Signs an HS256 JWT with the configured secret - simulates the auth server. */
    private String token(String scopes) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
                .issuer("order-api")
                .claim("scope", scopes)
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner(properties.getJwtSecret()));
        return signed.serialize();
    }
}
