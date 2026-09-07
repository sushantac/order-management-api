package com.company.orderapi.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PR #31 - consumes {@code order-events} from the order-service consumer group.
 *
 * <p>Manual offset management ({@code Acknowledgment}): the listener processes
 * the message and ONLY THEN calls {@code acknowledge()} - if processing throws,
 * no ack happens, the broker redelivers (at-least-once), and after the retry
 * budget the record goes to the dead-letter topic.
 *
 * <p>The payload is validated defensively; anything invalid is treated as
 * poison and routed to the DLT instead of blocking the group forever.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();

    @KafkaListener(topics = "${app.kafka.topics.order-events}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderPlaced(OrderPlacedMessage message, Acknowledgment acknowledgment) {
        if (message == null || message.orderId() == null || message.orderNumber() == null
                || message.totalAmount() == null) {
            // Poison message: throw -> no ack -> retries -> dead-letter topic.
            throw new IllegalArgumentException("Malformed order event: " + message);
        }
        log.info("Order event consumed: {} ({} {})", message.orderNumber(),
                message.orderId(), message.totalAmount());
        processed.incrementAndGet();
        acknowledgment.acknowledge(); // manual offset commit
    }

    @KafkaListener(topics = "${app.kafka.topics.order-events-dlt}",
            groupId = "${spring.kafka.consumer.group-id}-dlt")
    public void onDeadLetter(OrderPlacedMessage message, Acknowledgment acknowledgment) {
        deadLetters.incrementAndGet();
        log.warn("Order event moved to DLT: orderId={} number={}",
                message == null ? null : message.orderId(),
                message == null ? null : message.orderNumber());
        acknowledgment.acknowledge();
    }

    public long processed() {
        return processed.get();
    }

    public long deadLetters() {
        return deadLetters.get();
    }
}
