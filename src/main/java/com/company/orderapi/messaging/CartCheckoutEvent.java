package com.company.orderapi.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Event consumed from the cart-service: a checkout was initiated.
 *
 * <p>Consumer idempotency is handled via the EXISTING idempotency_keys table
 * (eventId is the key). If shippingAddress is absent in the payload, the
 * consumer creates a default placeholder address.
 */
public record CartCheckoutEvent(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("cartId") Long cartId,
        @JsonProperty("userId") Long userId,
        @JsonProperty("items") List<CartItem> items,
        @JsonProperty("shippingAddress") ShippingAddress shippingAddress,
        @JsonProperty("occurredAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime occurredAt) {

    public record CartItem(
            @JsonProperty("productId") Long productId,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("price") BigDecimal price) {
    }

    public record ShippingAddress(
            @JsonProperty("street") String street,
            @JsonProperty("city") String city,
            @JsonProperty("state") String state,
            @JsonProperty("postalCode") String postalCode,
            @JsonProperty("country") String country) {
    }
}
