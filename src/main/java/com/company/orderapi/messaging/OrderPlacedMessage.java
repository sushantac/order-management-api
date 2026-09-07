package com.company.orderapi.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PR #31 - the event published to Kafka when an order is placed.
 *
 * <p>JSON payload (record). The value is deliberately small and free of PII
 * (no customer e-mail, no address): downstream consumers that need more ask
 * the API. Schema evolution would use Avro + Schema Registry - the JSON record
 * keeps the learning journey dependency-light while showing the same contract.
 */
public record OrderPlacedMessage(
        @JsonProperty("orderId") Long orderId,
        @JsonProperty("orderNumber") String orderNumber,
        @JsonProperty("totalAmount") BigDecimal totalAmount,
        @JsonProperty("occurredAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime occurredAt) {
}
