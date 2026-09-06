package com.company.orderapi.domain;

/**
 * Lifecycle of a payment. Mirrors the payment domain events of the spec
 * (PaymentProcessed / PaymentFailed).
 *
 * <p>Stored as {@code VARCHAR(32)} via {@code @Enumerated(STRING)}. No DB CHECK
 * constraint exists on {@code payments.status} (see PR #2 changelog), so the
 * enum is the single source of truth for valid values.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSED,
    FAILED,
    REFUNDED
}
