package com.company.orderapi.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Published to the {@code order.placed} topic when an order is placed.
 *
 * <p>Richer than the legacy {@link OrderPlacedMessage}: includes userId and
 * line items for downstream consumers (admin, cart-service). Published through
 * the transactional outbox.
 */
public record OrderPlacedEventMessage(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("orderId") Long orderId,
        @JsonProperty("orderNumber") String orderNumber,
        @JsonProperty("userId") Long userId,
        @JsonProperty("totalAmount") BigDecimal totalAmount,
        @JsonProperty("items") List<OrderItem> items,
        @JsonProperty("occurredAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime occurredAt) {

    public record OrderItem(
            @JsonProperty("productId") Long productId,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("unitPrice") BigDecimal unitPrice,
            @JsonProperty("totalPrice") BigDecimal totalPrice) {
    }
}
