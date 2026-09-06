package com.company.orderapi.integration;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.ProductStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #8 integration tests: optimistic locking under real concurrency.
 *
 * <p>100 worker threads each decrement the SAME product's stock by 1. Every
 * write goes through {@code @Version} + {@code @Retryable}: stale writers are
 * rejected by the database (0 rows matched) and immediately retried against the
 * newest version.
 *
 * <p>The assertions prove no update was lost:
 * <ul>
 *   <li>{@code stockQuantity == 0} - all 100 decrements landed</li>
 *   <li>{@code version == 100}     - all 100 writes committed exactly once</li>
 * </ul>
 *
 * <p>Without optimistic locking, concurrent read-modify-write would "lose"
 * some decrements and stock would stay above zero (overselling).
 */
@Testcontainers
@SpringBootTest
class OptimisticLockingTest {

    private static final int THREADS = 100;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductStockService productStockService;

    @Test
    void oneHundredConcurrentDecrementsNeverLoseAnUpdate() throws Exception {
        // Seed and COMMIT the product before racing (each worker runs its own
        // transaction, so it must be able to see the row).
        Product product =
                productRepository.saveAndFlush(new Product("Hot Ticket", new BigDecimal("9.99"), THREADS));
        Long productId = product.getId();

        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(executor.submit(() -> productStockService.decreaseStock(productId, 1)));
            }
            // get() throws if ANY worker exhausted its retries - i.e. a lost update.
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        Product reloaded = productRepository.findById(productId).orElseThrow();
        assertThat(reloaded.getStockQuantity())
                .as("all %d decrements must have landed - no lost update", THREADS)
                .isZero();
        assertThat(reloaded.getVersion())
                .as("every successful write committed exactly once")
                .isEqualTo(THREADS);
    }
}
