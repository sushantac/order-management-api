package com.company.orderapi.domain.service;

/**
 * PR #20 - thrown when a payment attempt fails. Extends {@link RuntimeException}
 * so a failing charge automatically rolls back the surrounding transaction
 * (ACID atomicity: no order, no stock deduction).
 */
public class PaymentFailedException extends RuntimeException {

    public PaymentFailedException(String message) {
        super(message);
    }
}
