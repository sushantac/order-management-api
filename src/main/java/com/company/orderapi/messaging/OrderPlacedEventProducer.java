package com.company.orderapi.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code order.placed} events to the dedicated topic.
 *
 * <p>Richer payload than the legacy {@link OrderEventProducer}: includes userId
 * and line items for downstream consumers (admin, cart-service).
 *
 * <p>Only created when {@code app.kafka.enabled=true}. The sender is
 * synchronous ({@code get}) so the outbox publisher can mark the row
 * PUBLISHED only after the broker really accepted it.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderPlacedEventProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderPlacedEventProducer(KafkaTemplate<Object, Object> kafkaTemplate,
                                    @Value("${app.kafka.topics.order-placed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(String key, OrderPlacedEventMessage message) {
        try {
            kafkaTemplate.send(topic, key, message).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed for order " + key, e);
        }
    }
}
