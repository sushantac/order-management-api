package com.company.orderapi.domain.eventstore;

import com.company.orderapi.domain.event.DomainEvent;
import com.company.orderapi.domain.event.OrderConfirmedEvent;
import com.company.orderapi.domain.event.OrderPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * PR #19 - writes and reads the event store.
 *
 * <p>{@code append} serialises the event to JSON (the payload) and stores it
 * together with its metadata. The schema's UNIQUE (aggregate_id, version) makes
 * a duplicate append impossible - the true source of event-sourcing integrity.
 */
@Service
public class EventStoreService {

    private final EventStoreRepository store;
    private final ObjectMapper objectMapper;

    public EventStoreService(EventStoreRepository store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void append(DomainEvent event) {
        if (store.existsByAggregateIdAndVersion(event.aggregateId(), event.version())) {
            throw new IllegalStateException(
                    "Event version " + event.version() + " already exists for aggregate "
                            + event.aggregateId());
        }
        String payload = write(event);
        store.saveAndFlush(EventStoreEntry.from(event, payload));
    }

    @Transactional(readOnly = true)
    public List<DomainEvent> readHistory(UUID aggregateId) {
        return store.findByAggregateIdOrderByVersionAsc(aggregateId).stream()
                .map(this::toEvent)
                .toList();
    }

    private String write(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise " + event.eventType(), e);
        }
    }

    private DomainEvent toEvent(EventStoreEntry entry) {
        try {
            return switch (entry.getEventType()) {
                case "OrderPlacedEvent" ->
                        objectMapper.readValue(entry.getPayload(), OrderPlacedEvent.class);
                case "OrderConfirmedEvent" ->
                        objectMapper.readValue(entry.getPayload(), OrderConfirmedEvent.class);
                default -> throw new IllegalStateException(
                        "Unknown event type in store: " + entry.getEventType());
            };
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not deserialise " + entry.getEventType() + " (v" + entry.getVersion() + ")",
                    e);
        }
    }
}
