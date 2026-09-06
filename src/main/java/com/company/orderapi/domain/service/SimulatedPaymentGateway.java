package com.company.orderapi.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PR #20 - simulated payment gateway with a 10% failure rate (spec requirement).
 *
 * <p>Random failure models a real-world PSP: cards get declined, timeouts
 * happen. Because {@link OrderService#placeOrder} runs in one transaction, a
 * failure here must leave NO trace - that is exactly what the service tests
 * verify with a deterministic fake.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);
    private static final double FAILURE_RATE = 0.10;

    @Override
    public void charge(BigDecimal amount) {
        if (ThreadLocalRandom.current().nextDouble() < FAILURE_RATE) {
            log.warn("Simulated payment failure for {}", amount);
            throw new PaymentFailedException("Payment of " + amount + " was declined (simulated 10%)");
        }
        log.info("Payment of {} processed by simulated gateway", amount);
    }
}
