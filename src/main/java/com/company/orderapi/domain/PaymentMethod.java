package com.company.orderapi.domain;

/**
 * How a payment was made.
 *
 * <p>Stored as {@code VARCHAR(32)} via {@code @Enumerated(STRING)}. There is no
 * DB CHECK constraint on {@code payments.payment_method} (see PR #2 changelog),
 * so keep values consistent by convention here and in tests.
 *
 * <p>PR #27 - the enum says HOW the money moved, never the card details that
 * made it move. That separation keeps the {@code payments} table out of PCI DSS
 * scope (see {@link Payment}).
 */
public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    PAYPAL,
    BANK_TRANSFER
}
