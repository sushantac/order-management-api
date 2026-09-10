package com.company.orderapi.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Event published to Kafka when an order status changes.
 *
 * <p>Published through the transactional outbox (same pattern as OrderPlacedMessage).
 */
public record OrderStatusChangedMessage(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("orderId") Long orderId,
        @JsonProperty("orderNumber") String orderNumber,
        @JsonProperty("oldStatus") String oldStatus,
        @JsonProperty("newStatus") String newStatus,
        @JsonProperty("changedAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime changedAt) {
}
