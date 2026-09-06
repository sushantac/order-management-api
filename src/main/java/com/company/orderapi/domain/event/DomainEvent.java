package com.company.orderapi.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR #19 - a domain event: one fact that already happened (past tense naming).
 *
 * <p>Event sourcing stores these facts APPEND-ONLY and rebuilds state by
 * replaying them, instead of overwriting rows. The interface is the contract
 * every event must satisfy so the {@code EventStore} can persist it uniformly.
 *
 * <p>Design choice: accessors (not get-prefixed) so Java records can implement
 * the interface with their canonical components.
 */
public interface DomainEvent {

    /** The aggregate (e.g. an Order) this event happened to. */
    UUID aggregateId();

    /** Monotonic per-aggregate sequence number; the store enforces uniqueness. */
    int version();

    LocalDateTime occurredAt();

    /** "Order" for OrderPlacedEvent etc. - derived, not stored redundantly. */
    default String aggregateType() {
        String simple = getClass().getSimpleName();
        return simple.endsWith("Event") ? simple.substring(0, simple.length() - "Event".length()) : simple;
    }

    default String eventType() {
        return getClass().getSimpleName();
    }
}
