package com.company.orderapi.domain.outbox;

/**
 * PR #31 - lifecycle of an outbox row.
 *
 * <p>PENDING  - written atomically with the business transaction, waiting for
 *               the polling publisher.
 * PUBLISHED - the broker confirmed the send; never processed again.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
