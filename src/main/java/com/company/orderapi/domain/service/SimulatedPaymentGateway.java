package com.company.orderapi.domain.service;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PR #20 - simulated payment gateway with a 10% failure rate (spec requirement).
 *
 * <p>Random failure models a real-world PSP: cards get declined, timeouts
 * happen. Because {@link OrderService#placeOrder} runs in one transaction, a
 * failure here must leave NO trace - that is exactly what the service tests
 * verify with a deterministic fake.
 *
 * <p>PR #29 - every call now crosses the Resilience4j stack, composed
 * explicitly (no AOP annotations) so the order is visible and testable:
 * {@code ThreadPoolBulkhead} isolates gateway work on its own small thread
 * pool; inside it the {@code CircuitBreaker} stops calling a sick provider;
 * inside that the {@code Retry} absorbs transient declines with exponential
 * backoff. Config lives in {@code resilience4j.*} (application.yml).
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    /** Resilience4j instance names - shared with the application.yml blocks. */
    public static final String RESILIENCE_INSTANCE = "paymentGateway";

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);
    private static final double FAILURE_RATE = 0.10;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ThreadPoolBulkhead bulkhead;

    public SimulatedPaymentGateway(CircuitBreakerRegistry circuitBreakerRegistry,
                                   RetryRegistry retryRegistry,
                                   ThreadPoolBulkheadRegistry bulkheadRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE);
        this.bulkhead = bulkheadRegistry.bulkhead(RESILIENCE_INSTANCE);
    }

    @Override
    public void charge(BigDecimal amount) {
        try {
            // Outermost = bulkhead (own thread pool) -> breaker -> retry -> call.
            bulkhead.executeRunnable(() ->
                    circuitBreaker.executeRunnable(() ->
                            retry.executeRunnable(() -> attempt(amount))))
                    .toCompletableFuture().join();
        } catch (CompletionException e) {
            throw translate(e.getCause());
        } catch (RuntimeException e) {
            // e.g. BulkheadFullException when the isolation pool is saturated.
            throw translate(e);
        }
    }

    /**
     * The actual "provider call". Subclasses/tests override this hook to drive
     * the resilience stack deterministically (chaos testing); the wrapper in
     * {@link #charge} stays in charge of retry/breaker/bulkhead behaviour.
     */
    protected void attempt(BigDecimal amount) {
        if (ThreadLocalRandom.current().nextDouble() < FAILURE_RATE) {
            log.warn("Simulated payment failure for {}", amount);
            throw new PaymentFailedException(
                    "Payment of " + amount + " was declined (simulated 10%)");
        }
        log.info("Payment of {} processed by simulated gateway", amount);
    }

    /** Normalises every failure mode into the domain exception the API knows. */
    private PaymentFailedException translate(Throwable failure) {
        if (failure instanceof PaymentFailedException paymentFailed) {
            return paymentFailed;
        }
        String message = failure == null || failure.getMessage() == null
                ? failure == null ? "unknown" : failure.getClass().getSimpleName()
                : failure.getMessage();
        return new PaymentFailedException("Payment provider unavailable (" + message + ")");
    }
}
