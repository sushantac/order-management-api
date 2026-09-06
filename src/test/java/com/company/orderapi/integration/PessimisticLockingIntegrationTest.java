package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.ProductInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #9 integration tests: pessimistic locking on a real PostgreSQL.
 *
 * <ul>
 *   <li>a PESSIMISTIC_WRITE holder makes concurrent writers WAIT for commit
 *       (they never abort - unlike optimistic locking they simply queue);</li>
 *   <li>PESSIMISTIC_READ (FOR SHARE) coexists with other readers;</li>
 *   <li>opposite lock orders produce a real database deadlock that PostgreSQL
 *       detects - and the service's {@code @Retryable} resolves it.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=PessimisticLockingIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class PessimisticLockingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductInventoryService productInventoryService;

    @Test
    void pessimisticWriteLockBlocksAConcurrentWriterUntilCommit() throws Exception {
        Product product = seed("Blocked Ticket", 100);
        Long id = product.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Holder grabs FOR UPDATE, then sleeps inside the transaction.
            Future<?> holder =
                    executor.submit(() -> productInventoryService.decrementStockHoldingLock(id, 1, 600));
            Thread.sleep(300); // give the holder time to acquire the row lock

            long start = System.nanoTime();
            productInventoryService.decrementStockPessimistic(id, 1); // must BLOCK here
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            holder.get(30, TimeUnit.SECONDS);

            // It really waited for the holder's commit (not just ran immediately).
            assertThat(waitedMs).isGreaterThanOrEqualTo(200);
            Product reloaded = productRepository.findById(id).orElseThrow();
            assertThat(reloaded.getStockQuantity()).isEqualTo(98); // both decrements landed
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void pessimisticReadShareLocksCoexistWithOtherReaders() throws Exception {
        Product product = seed("Shared Ticket", 50);
        Long id = product.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> readerA = executor.submit(() -> productInventoryService.peekStockPessimisticRead(id));
            Future<Integer> readerB = executor.submit(() -> productInventoryService.peekStockPessimisticRead(id));
            assertThat(readerA.get(30, TimeUnit.SECONDS)).isEqualTo(50);
            assertThat(readerB.get(30, TimeUnit.SECONDS)).isEqualTo(50);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deadlockFromOppositeLockOrdersIsDetectedAndResolvedByRetry() throws Exception {
        Product left = seed("Deadlock Left", 100);
        Product right = seed("Deadlock Right", 100);
        Long leftId = left.getId();
        Long rightId = right.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Classic deadlock: the two transactions lock the SAME two rows in
            // opposite orders. PostgreSQL aborts one; @Retryable re-runs it.
            Future<?> t1 = executor.submit(() -> productInventoryService.moveStockPessimistic(leftId, rightId, 10));
            Future<?> t2 = executor.submit(() -> productInventoryService.moveStockPessimistic(rightId, leftId, 10));
            t1.get(60, TimeUnit.SECONDS);
            t2.get(60, TimeUnit.SECONDS);

            // Net effect of +10 each way on both products: unchanged, no loss.
            assertThat(productRepository.findById(leftId).orElseThrow().getStockQuantity()).isEqualTo(100);
            assertThat(productRepository.findById(rightId).orElseThrow().getStockQuantity()).isEqualTo(100);
        } finally {
            executor.shutdownNow();
        }
    }

    private Product seed(String name, int stock) {
        return productRepository.saveAndFlush(new Product(name, new BigDecimal("5.00"), stock));
    }
}
