package com.company.orderapi.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR #19 - fired when an order is confirmed (moved from PLACED to CONFIRMED).
 */
public record OrderConfirmedEvent(
        UUID aggregateId,
        int version,
        LocalDateTime occurredAt,
        String confirmedBy) implements DomainEvent {
}
