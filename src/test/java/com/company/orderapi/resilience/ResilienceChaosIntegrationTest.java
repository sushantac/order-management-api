package com.company.orderapi.resilience;

import com.company.orderapi.domain.service.PaymentFailedException;
import com.company.orderapi.domain.service.PaymentGateway;
import com.company.orderapi.domain.service.SimulatedPaymentGateway;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #29 chaos/resilience integration tests (no HTTP): drive the real
 * Resilience4j stack on the payment gateway through a deterministic
 * {@link SimulatedPaymentGateway} subclass and prove the circuit breaker
 * opens on failures and on SLOW calls (latency injection), fast-fails while
 * open, and recovers in half-open; and that the thread-pool bulkhead is
 * configured for isolation.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=ResilienceChaosIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.security.enabled=false",
        // Small, fast knobs so the breaker opens deterministically during the test.
        "resilience4j.circuitbreaker.instances.paymentGateway.minimum-number-of-calls=5",
        "resilience4j.circuitbreaker.instances.paymentGateway.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.paymentGateway.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.paymentGateway.slow-call-duration-threshold=150ms",
        "resilience4j.circuitbreaker.instances.paymentGateway.slow-call-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.paymentGateway.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.paymentGateway.permitted-number-of-calls-in-half-open-state=2",
        // Noisy retries slow the test down; keep them short.
        "resilience4j.retry.instances.paymentGateway.max-attempts=3",
        "resilience4j.retry.instances.paymentGateway.wait-duration=20ms"
})
class ResilienceChaosIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PaymentGateway paymentGateway; // resolves to the chaos simulator

    @Autowired
    private ChaosSimulatedPaymentGateway chaos;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ChaosSimulatedPaymentGateway chaosSimulator(CircuitBreakerRegistry cbRegistry,
                                                    RetryRegistry retryRegistry,
                                                    ThreadPoolBulkheadRegistry bulkheadRegistry) {
            return new ChaosSimulatedPaymentGateway(cbRegistry, retryRegistry, bulkheadRegistry);
        }
    }

    @AfterEach
    void resetState() {
        chaos.reset();
        circuitBreakerRegistry.circuitBreaker(SimulatedPaymentGateway.RESILIENCE_INSTANCE)
                .reset();
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndItFastFailsUntilHalfOpenRecovery()
            throws InterruptedException {
        chaos.fail(true);

        // Retry is INSIDE the breaker: each request hides its inner attempts,
        // so the breaker records one failure per REQUEST. minimum-number-of-calls
        // (5) must be reached at 100% failure -> opens on the 5th request.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> paymentGateway.charge(BigDecimal.ONE))
                    .isInstanceOf(PaymentFailedException.class);
        }

        CircuitBreaker breaker = circuitBreakerRegistry
                .circuitBreaker(SimulatedPaymentGateway.RESILIENCE_INSTANCE);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // While OPEN, calls fail FAST - no new attempt reaches the provider.
        int attemptsBeforeOpen = chaos.attempts();
        assertThatThrownBy(() -> paymentGateway.charge(BigDecimal.ONE))
                .isInstanceOf(PaymentFailedException.class);
        assertThat(chaos.attempts()).isEqualTo(attemptsBeforeOpen);

        // Provider recovers; after wait-duration the breaker lets ONE probe
        // through (HALF_OPEN); a success closes it again. The transition can
        // land one call later, so a follow-up success must also be green.
        chaos.fail(false);
        Thread.sleep(1100); // > wait-duration-in-open-state (1s)
        paymentGateway.charge(BigDecimal.ONE); // HALF_OPEN probe
        paymentGateway.charge(BigDecimal.ONE); // closes if the probe only opened
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void slowCallsAreTreatedAsFailuresAndOpenTheCircuit() {
        // Chaos via LATENCY injection: the provider answers, but too slowly.
        chaos.latencyMillis(300);

        // 5 slow calls >= minimum-number-of-calls at a 100% slow rate -> open.
        for (int i = 0; i < 5; i++) {
            paymentGateway.charge(BigDecimal.TEN); // each ~300ms, all "slow"
        }

        CircuitBreaker breaker = circuitBreakerRegistry
                .circuitBreaker(SimulatedPaymentGateway.RESILIENCE_INSTANCE);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Once open, the next call returns immediately instead of waiting.
        int attemptsBeforeOpen = chaos.attempts();
        long started = System.nanoTime();
        assertThatThrownBy(() -> paymentGateway.charge(BigDecimal.TEN))
                .isInstanceOf(PaymentFailedException.class);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(150); // fast-fail, not a 300ms wait
        assertThat(chaos.attempts()).isEqualTo(attemptsBeforeOpen);
    }

    @Test
    void threadPoolBulkheadIsConfiguredForGatewayIsolation() {
        var bulkhead = threadPoolBulkheadRegistry
                .bulkhead(SimulatedPaymentGateway.RESILIENCE_INSTANCE);
        // Matches resilience4j.thread-pool-bulkhead.instances.paymentGateway.
        assertThat(bulkhead.getMetrics().getMaximumThreadPoolSize()).isEqualTo(2);
        assertThat(bulkhead.getMetrics().getQueueCapacity()).isEqualTo(5);
    }

    /** Deterministic stand-in for the simulated provider's {@code attempt()}. */
    static class ChaosSimulatedPaymentGateway extends SimulatedPaymentGateway {

        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean fail = false;
        private volatile long latencyMillis = 0;

        ChaosSimulatedPaymentGateway(CircuitBreakerRegistry cbRegistry,
                                     RetryRegistry retryRegistry,
                                     ThreadPoolBulkheadRegistry bulkheadRegistry) {
            super(cbRegistry, retryRegistry, bulkheadRegistry);
        }

        @Override
        protected void attempt(BigDecimal amount) {
            attempts.incrementAndGet();
            if (latencyMillis > 0) {
                try {
                    Thread.sleep(latencyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PaymentFailedException("Chaos gateway interrupted");
                }
            }
            if (fail) {
                throw new PaymentFailedException("Chaos decline (injected)");
            }
        }

        int attempts() {
            return attempts.get();
        }

        void fail(boolean fail) {
            this.fail = fail;
        }

        void latencyMillis(long latencyMillis) {
            this.latencyMillis = latencyMillis;
        }

        void reset() {
            attempts.set(0);
            fail = false;
            latencyMillis = 0;
        }
    }
}
