package com.company.orderapi.concurrency;

import com.company.orderapi.domain.service.DistributedLockService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #30 integration tests: Redisson distributed locks on real Redis.
 *
 * <p>1. Two concurrent holders of the same lock key can NEVER be inside the
 *    critical section at the same time (mutual exclusion across threads).
 * 2. A lock whose holder "crashes" is reclaimed after its lease expires
 *    (watchdog/lease semantics) - no permanent deadlock.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=DistributedLockIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class DistributedLockIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private DistributedLockService lockService;

    @Autowired
    private RedissonClient redisson;

    @Test
    void criticalSectionIsMutuallyExclusiveAcrossConcurrentVirtualThreads()
            throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        int threads = 8;
        int iterations = 25;

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < iterations; i++) {
                    lockService.runExclusive("critical-section", Duration.ofSeconds(5),
                            Duration.ofSeconds(10), () -> {
                                int now = inside.incrementAndGet();
                                maxObserved.accumulateAndGet(now, Math::max);
                                try {
                                    Thread.sleep(2); // widen the race window
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                inside.decrementAndGet();
                                return null;
                            });
                }
                return null;
            });
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get();
            }
        }

        assertThat(maxObserved.get())
                .as("only one thread may hold the critical section at a time")
                .isEqualTo(1);
        assertThat(inside.get()).isZero();
    }

    @Test
    void leaseExpiryReclaimsALockAfterItsHolderCrashes() throws Exception {
        // Simulate a crashed holder on ANOTHER thread: it locks and never
        // releases; only the lease expiry can free the lock.
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        try (var holder = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> crashedHolder = holder.submit(() -> {
                RLock crashed = redisson.getLock("app-lock:crashed-holder");
                crashed.lock(300, TimeUnit.MILLISECONDS);
                acquired.countDown();
                try {
                    Thread.sleep(800); // "crash": sleep past the lease, no unlock
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });

            assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();

            // While it is held, a contender with a short wait gives up fast.
            assertThatThrownBy(() -> lockService.runExclusive("crashed-holder",
                    Duration.ofMillis(50), Duration.ofSeconds(5), () -> "never"))
                    .isInstanceOf(IllegalStateException.class);

            // Once the lease expires (the "crash" can never unlock), the lock
            // is reclaimed and the contender succeeds.
            Thread.sleep(400);
            String result = lockService.runExclusive("crashed-holder",
                    Duration.ofSeconds(2), Duration.ofSeconds(5), () -> "recovered");
            assertThat(result).isEqualTo("recovered");
            crashedHolder.get();
        }
    }
}
