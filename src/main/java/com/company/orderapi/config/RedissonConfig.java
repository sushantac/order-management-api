package com.company.orderapi.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * PR #30 - builds the Redisson client for the distributed lock.
 *
 * <p>The connection is {@code @Lazy}: the client is only created (and only
 * connects to Redis) the first time a lock is actually used, so contexts that
 * never take a distributed lock pay nothing. Address scheme: Redisson needs
 * {@code redis://host:port}.
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
