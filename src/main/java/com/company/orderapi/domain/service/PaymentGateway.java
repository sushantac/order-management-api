package com.company.orderapi.domain.service;

import java.math.BigDecimal;

/**
 * PR #20 - payment gateway abstraction.
 *
 * <p>The order service depends on this INTERFACE, not on a concrete provider,
 * so tests can substitute a deterministic fake (fail always / succeed always)
 * and future PRs can plug a real PSP implementation.
 */
public interface PaymentGateway {

    /**
     * Charges {@code amount} or throws {@link PaymentFailedException}.
     * Implementations must be idempotent-safe at the caller's discretion.
     */
    void charge(BigDecimal amount);
}
