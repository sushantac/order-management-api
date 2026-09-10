package com.company.orderapi.messaging;

import com.company.orderapi.domain.outbox.OutboxEntry;
import com.company.orderapi.domain.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR #31 - the polling OUTBOX publisher.
 *
 * <p>{@code @Scheduled} wakes up every {@code poll-millis}, claims the oldest
 * PENDING batch (FOR UPDATE SKIP LOCKED - safe for several app instances),
 * publishes each event to Kafka, and marks the row PUBLISHED only after the
 * broker acknowledged the send. A failed send just bumps {@code attempts} and
 * the row stays PENDING for the next poll - at-least-once semantics.
 *
 * <p>{@link #publishPendingNow()} is public so integration tests can drive the
 * relay deterministically (and is what the scheduler calls).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final OrderEventProducer legacyProducer;
    private final OrderPlacedEventProducer orderPlacedProducer;
    private final OrderStatusChangedEventProducer statusChangedProducer;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final boolean schedulerEnabled;

    public OutboxPublisher(OutboxRepository outbox,
                           OrderEventProducer legacyProducer,
                           OrderPlacedEventProducer orderPlacedProducer,
                           OrderStatusChangedEventProducer statusChangedProducer,
                           ObjectMapper objectMapper,
                           @Value("${app.outbox.batch-size:50}") int batchSize,
                           @Value("${app.outbox.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.outbox = outbox;
        this.legacyProducer = legacyProducer;
        this.orderPlacedProducer = orderPlacedProducer;
        this.statusChangedProducer = statusChangedProducer;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.schedulerEnabled = schedulerEnabled;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-millis:2000}")
    public void scheduledPoll() {
        if (schedulerEnabled) {
            publishPendingNow();
        }
    }

    @Transactional
    public int publishPendingNow() {
        int published = 0;
        for (OutboxEntry entry : outbox.lockPendingBatch(batchSize)) {
            try {
                publishEntry(entry);
                entry.markPublished();
                published++;
            } catch (Exception e) {
                entry.registerAttempt();
                log.warn("Outbox row {} could not be published (attempt {}) - will retry",
                        entry.getId(), entry.getAttempts(), e);
            }
            outbox.save(entry);
        }
        outbox.flush();
        return published;
    }

    private void publishEntry(OutboxEntry entry) throws Exception {
        switch (entry.getEventType()) {
            case "OrderPlacedEventMessage" -> {
                OrderPlacedEventMessage message = objectMapper.readValue(
                        entry.getPayload(), OrderPlacedEventMessage.class);
                orderPlacedProducer.publish(entry.getAggregateId(), message);
            }
            case "OrderStatusChangedMessage" -> {
                OrderStatusChangedMessage message = objectMapper.readValue(
                        entry.getPayload(), OrderStatusChangedMessage.class);
                statusChangedProducer.publish(entry.getAggregateId(), message);
            }
            default -> {
                // Legacy OrderPlacedMessage: backward-compatible path.
                OrderPlacedMessage message = objectMapper.readValue(
                        entry.getPayload(), OrderPlacedMessage.class);
                legacyProducer.publish(entry.getAggregateId(), message);
            }
        }
    }
}
