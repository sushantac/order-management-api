package com.company.orderapi.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code order.status.changed} events to the dedicated topic.
 *
 * <p>Only created when {@code app.kafka.enabled=true}. The sender is
 * synchronous ({@code get}) so the outbox publisher can mark the row
 * PUBLISHED only after the broker really accepted it.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderStatusChangedEventProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderStatusChangedEventProducer(KafkaTemplate<Object, Object> kafkaTemplate,
                                           @Value("${app.kafka.topics.order-status-changed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(String key, OrderStatusChangedMessage message) {
        try {
            kafkaTemplate.send(topic, key, message).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed for status change " + key, e);
        }
    }
}
