package com.company.orderapi.domain.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR #19 - fired when an order is placed. Immutable fact: what happened, when,
 * and on which aggregate version. Event payloads carry the data the projection
 * needs to rebuild state without querying the (possibly long gone) order row.
 */
public record OrderPlacedEvent(
        UUID aggregateId,
        int version,
        LocalDateTime occurredAt,
        String orderNumber,
        BigDecimal totalAmount) implements DomainEvent {
}
