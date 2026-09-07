package com.company.orderapi.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * PR #31 - publishes order events to the {@code order-events} topic.
 *
 * <p>Only created when {@code app.kafka.enabled=true} (dev/prod profiles or a
 * Kafka integration test); all other contexts never see a broker. The sender
 * is synchronous ({@code get}) so the outbox publisher can mark a row
 * PUBLISHED only after the broker really accepted it.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderEventProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderEventProducer(KafkaTemplate<Object, Object> kafkaTemplate,
                              @Value("${app.kafka.topics.order-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(String key, OrderPlacedMessage message) {
        try {
            kafkaTemplate.send(topic, key, message).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed for order " + key, e);
        }
    }
}
