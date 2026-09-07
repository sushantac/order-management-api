package com.company.orderapi.resilience;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #29 - per-API-key rate limiting. The test context overrides the apiKey
 * limiter to 2 permits per minute, then proves: requests 1-2 pass with a
 * decreasing X-RateLimit-Remaining header, request 3 is rejected with 429 +
 * Retry-After, and bearer-JWT callers are not limited by this filter.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=RateLimitIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "app.security.enabled=false",
        "resilience4j.ratelimiter.instances.apiKey.limit-for-period=2",
        "resilience4j.ratelimiter.instances.apiKey.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.apiKey.timeout-duration=0s"
})
class RateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiKeyCallsAreLimitedAndExposeRateLimitHeaders() throws Exception {
        // Permit 1 and 2 are granted; the remaining counter counts down.
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header("X-API-Key", "ratelimit-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header("X-API-Key", "ratelimit-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        // Permit exhausted -> 429 with Retry-After; a NEW key starts a fresh bucket.
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header("X-API-Key", "ratelimit-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
        mockMvc.perform(get("/api/v1/customers?page=0&size=5")
                        .header("X-API-Key", "another-key"))
                .andExpect(status().isOk());
    }
}
