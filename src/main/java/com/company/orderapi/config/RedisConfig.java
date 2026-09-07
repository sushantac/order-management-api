package com.company.orderapi.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * PR #28 - application-level caching support.
 *
 * <p>{@code @EnableCaching} turns on Spring's cache abstraction so that
 * {@code @Cacheable}/{@code @CacheEvict} (see ProductCatalogueService) are
 * honoured. The actual store is chosen by {@code spring.cache.type}:
 * <ul>
 *   <li>default profile -> {@code simple} (in-memory, no Redis needed by the
 *       test contexts);</li>
 *   <li>dev/prod profiles -> {@code redis} with a 10-minute TTL, key prefix and
 *       serializers configured in the matching application-*.yml.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class RedisConfig {
}
