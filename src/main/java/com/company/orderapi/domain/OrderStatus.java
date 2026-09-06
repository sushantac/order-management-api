package com.company.orderapi.domain;

/**
 * Lifecycle of an order. Mirrors the domain events of the spec
 * (OrderPlaced / OrderConfirmed / OrderShipped / OrderDelivered /
 * OrderCancelled).
 *
 * <p>Stored as {@code VARCHAR(32)} (STRING enum). The constant names MUST
 * match the database CHECK constraint from PR #2
 * ({@code ck_orders_status}). Renaming requires a joint Liquibase change.
 */
public enum OrderStatus {
    PLACED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
