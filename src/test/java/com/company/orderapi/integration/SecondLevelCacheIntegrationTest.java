package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #16 integration tests: Hibernate second-level cache (JCache + Ehcache).
 *
 * <p>{@code Product} is {@code @Cacheable} with a {@code READ_WRITE} strategy.
 * These tests use two separate repository calls (two persistence contexts), so
 * the SECOND lookup can only be served from the second-level cache - and the
 * JDBC statement counter proves it.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=SecondLevelCacheIntegrationTest",
        // Re-enable the second-level cache ONLY here (disabled in test profile
        // to stop Ehcache's JVM-wide default cache manager leaking between
        // contexts).
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=true"
})
class SecondLevelCacheIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void secondReadIsServedFromTheCacheNotTheDatabase() {
        Product product =
                productRepository.saveAndFlush(new Product("Cached Widget", new BigDecimal("4.00"), 10));
        Long id = product.getId();

        Statistics stats = statistics();
        stats.clear();

        // Session 1: cache miss -> one JDBC SELECT, entity stored in L2.
        productRepository.findById(id).orElseThrow();
        assertThat(stats.getSecondLevelCacheMissCount()).isEqualTo(1);
        assertThat(stats.getSecondLevelCacheHitCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);

        // Session 2: cache HIT -> NO new SELECT.
        Product again = productRepository.findById(id).orElseThrow();
        assertThat(stats.getSecondLevelCacheHitCount()).isEqualTo(1);
        assertThat(stats.getPrepareStatementCount())
                .as("the cached read must not touch the database")
                .isEqualTo(1);
        assertThat(again.getName()).isEqualTo("Cached Widget");
    }

    @Test
    void writesInvalidateTheCacheEntry() {
        Product product =
                productRepository.saveAndFlush(new Product("Mutable Widget", new BigDecimal("5.00"), 10));
        Long id = product.getId();

        Statistics stats = statistics();
        stats.clear(); // counters are global per SessionFactory - reset first
        productRepository.findById(id); // warm the cache (miss -> stored)
        productRepository.findById(id); // hit
        assertThat(stats.getSecondLevelCacheHitCount()).isEqualTo(1);

        // Update the row through its own transaction; READ_WRITE invalidates L2.
        Product managed = productRepository.findById(id).orElseThrow();
        managed.setStockQuantity(99);
        productRepository.saveAndFlush(managed);

        stats.clear();
        Product reloaded = productRepository.findById(id).orElseThrow();

        // READ_WRITE is write-through: the commit REFRESHES the cached entry,
        // so the next lookup is a cache HIT that already carries the new state
        // (no database round trip, no stale read).
        assertThat(stats.getSecondLevelCacheHitCount())
                .as("the write refreshed the cache entry - next read is a hit")
                .isEqualTo(1);
        assertThat(stats.getPrepareStatementCount())
                .as("the refreshed entry is served without a SELECT")
                .isZero();
        assertThat(reloaded.getStockQuantity()).isEqualTo(99);
    }

    @Test
    void onlyCacheableEntitiesParticipate() {
        // Customer is NOT @Cacheable -> every new session reads from the DB.
        // (L2 caches are per entity type; selective mode ignores non-cacheable
        // entities entirely.)
        assertThat(entityManagerFactory.getCache().contains(Product.class, 1L)).isFalse();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
