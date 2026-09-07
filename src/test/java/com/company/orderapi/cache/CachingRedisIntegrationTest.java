package com.company.orderapi.cache;

import com.company.orderapi.domain.service.PaymentGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #28 integration tests against REAL Redis: product GETs populate the cache
 * (first call = miss, second = hit), writes evict it, the TTL is applied, and
 * Redis keyspace hit/miss metrics are registered. Security stays off here - the
 * cache behaviour is orthogonal to PR #26/27 and covered there.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=CachingRedisIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "app.security.enabled=false"
})
class CachingRedisIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Mirrors application-dev.yml so the TTL wiring itself is tested.
        registry.add("spring.cache.redis.time-to-live", () -> "5m");
        registry.add("spring.cache.redis.key-prefix", () -> "orderapi:");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private MeterRegistry meterRegistry;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void productReadIsCachedThenServedFromRedisOnTheSecondCall() throws Exception {
        long id = createProduct("Cached Widget", "5.00", 10);
        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cached Widget"));

        // The first read missed and populated the cache - and Redis says so.
        assertThat(stat("keyspace_misses")).isGreaterThanOrEqualTo(1);
        assertThat(redisKeys("products::" + id)).isNotEmpty();

        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cached Widget"));
        assertThat(stat("keyspace_hits")).isGreaterThanOrEqualTo(1);

        // Cache hit/miss metrics are published as gauges.
        assertThat(meterRegistry.find("redis.keyspace.hits").gauge()).isNotNull();
        assertThat(meterRegistry.find("redis.keyspace.misses").gauge()).isNotNull();
    }

    @Test
    void catalogueWritesEvictTheCacheAndTtlIsApplied() throws Exception {
        long id = createProduct("Stale Widget", "1.00", 3);
        mockMvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isOk());
        assertThat(ttlSeconds(id)).isBetween(1L, 300L); // 5m configured TTL

        mockMvc.perform(put("/api/v1/products/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fresh Widget\",\"price\":\"2.00\","
                                + "\"stockQuantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fresh Widget"));

        // @CacheEvict(allEntries) dropped the stale entry: the GET below must
        // re-read from the DB and see the new name.
        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fresh Widget"));
    }

    @Test
    void deletingProductRemovesCachedEntry() throws Exception {
        long id = createProduct("Doomed Widget", "0.50", 1);
        mockMvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isOk());
        assertThat(redisKeys("products::" + id)).isNotEmpty();

        mockMvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isNotFound());
        assertThat(redisKeys("products::" + id)).isEmpty();
    }

    @Test
    void orderPlacementEvictsCachedProductSoStockStaysFresh() throws Exception {
        long productId = createProduct("Stock Widget", "5.00", 10);
        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(10));
        assertThat(redisKeys("products::" + productId)).isNotEmpty();

        long customerId = createCustomer();
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());

        // OrderService evicted the single entry; GET re-reads stock = 9.
        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(9));
    }

    // --- helpers ------------------------------------------------------------

    private long stat(String name) {
        try (var connection = redisConnectionFactory.getConnection()) {
            var info = connection.serverCommands().info("stats");
            String value = info == null ? null : info.getProperty(name);
            return value == null ? 0L : Long.parseLong(value.trim());
        }
    }

    private Set<String> redisKeys(String fragment) {
        try (var connection = redisConnectionFactory.getConnection()) {
            byte[] pattern = ("*" + fragment + "*").getBytes(StandardCharsets.UTF_8);
            return connection.keys(pattern).stream()
                    .map(b -> new String(b, StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private long ttlSeconds(long productId) {
        try (var connection = redisConnectionFactory.getConnection()) {
            byte[] key = ("orderapi:products::" + productId).getBytes(StandardCharsets.UTF_8);
            Long ttl = connection.keyCommands().ttl(key);
            return ttl == null ? -1L : ttl;
        }
    }

    private long createProduct(String name, String price, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"price\":\"" + price
                                + "\",\"stockQuantity\":" + stock + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return parseId(result);
    }

    private long createCustomer() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cache" + System.nanoTime() + "@example.com\","
                                + "\"fullName\":\"Cache Customer\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return parseId(result);
    }

    private long parseId(MvcResult result) throws Exception {
        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }
}
