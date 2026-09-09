package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.ProductInventoryService;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #9 - performance comparison: optimistic vs pessimistic locking on the same
 * workload (N concurrent single-unit decrements of one hot row).
 *
 * <p>Typical outcome on a single row: pessimistic wins at HIGH contention
 * (writers queue cleanly, zero aborted work) while optimistic burns work on
 * retries; at LOW contention optimistic wins because it never locks. Timing is
 * logged, not asserted - wall-clock on shared machines is not a stable oracle.
 * Functional invariants ARE asserted for both strategies.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=LockingPerformanceComparisonTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class LockingPerformanceComparisonTest {

    private static final int INCREMENTS = 40;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductStockService productStockService;

    @Autowired
    private ProductInventoryService productInventoryService;

    @Test
    void pessimisticAndOptimisticBothSurviveContention() throws Exception {
        runAndTime("OPTIMISTIC (PR #8)", productRepository.saveAndFlush(
                new Product("Optimistic Row", new BigDecimal("1.00"), INCREMENTS)),
                productId -> productStockService.decreaseStock(productId, 1));
        runAndTime("PESSIMISTIC (PR #9)", productRepository.saveAndFlush(
                new Product("Pessimistic Row", new BigDecimal("1.00"), INCREMENTS)),
                productId -> productInventoryService.decrementStockPessimistic(productId, 1));
    }

    private void runAndTime(String label, Product product, IntConsumer operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < INCREMENTS; i++) {
                futures.add(executor.submit(() -> operation.accept(product.getId())));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStockQuantity())
                .as("%s: every decrement must land", label)
                .isZero();
        System.out.printf(Locale.ROOT, "LOCKING-BENCH %s: %d decrements in %d ms (stock=%d)%n",
                label, INCREMENTS, elapsedMs, reloaded.getStockQuantity());
    }

    @FunctionalInterface
    private interface IntConsumer {
        void accept(Long productId);
    }
}
