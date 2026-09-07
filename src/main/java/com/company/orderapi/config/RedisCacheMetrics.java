package com.company.orderapi.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * PR #28 - cache hit/miss metrics from the Redis server itself.
 *
 * <p>Redis exposes real counters for every key lookup: {@code keyspace_hits}
 * (a GET found the key) and {@code keyspace_misses} (it did not). We publish
 * them as Micrometer gauges under {@code redis.keyspace.hits/misses} - visible
 * on /actuator/metrics - so a cache that stops paying for itself shows up in
 * dashboards. The supplier reads Redis on each scrape (fine at this scale).
 *
 * <p>Only active when the Redis cache backend is selected
 * ({@code spring.cache.type=redis}).
 */
@Component
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
public class RedisCacheMetrics {

    private final RedisConnectionFactory connectionFactory;

    public RedisCacheMetrics(RedisConnectionFactory connectionFactory,
                             MeterRegistry meterRegistry) {
        this.connectionFactory = connectionFactory;
        meterRegistry.gauge("redis.keyspace.hits", this, RedisCacheMetrics::keyspaceHits);
        meterRegistry.gauge("redis.keyspace.misses", this, RedisCacheMetrics::keyspaceMisses);
    }

    long keyspaceHits() {
        return stat("keyspace_hits");
    }

    long keyspaceMisses() {
        return stat("keyspace_misses");
    }

    private long stat(String name) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Properties info = connection.serverCommands().info("stats");
            String value = info == null ? null : info.getProperty(name);
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return 0L; // Redis briefly unavailable -> report 0, never crash
        }
    }
}
