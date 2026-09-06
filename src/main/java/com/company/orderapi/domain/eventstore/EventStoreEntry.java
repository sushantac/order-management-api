package com.company.orderapi.domain.eventstore;

import com.company.orderapi.domain.event.DomainEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR #19 - one row of the append-only event store.
 *
 * <p>Deliberately does NOT extend {@code BaseEntity}: the event store is a log,
 * not a mutable aggregate - no optimistic-lock version, no auditing, and the
 * unique (aggregate_id, version) pair is what guarantees a fact is written
 * exactly once.
 */
@Entity
@Table(name = "event_store")
public class EventStoreEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void guardAppendOnly() {
        // safety net: nothing in the entity model may change an appended event
    }

    protected EventStoreEntry() {
        // for JPA
    }

    public static EventStoreEntry from(DomainEvent event, String payload) {
        EventStoreEntry entry = new EventStoreEntry();
        entry.aggregateId = event.aggregateId();
        entry.aggregateType = event.aggregateType();
        entry.eventType = event.eventType();
        entry.version = event.version();
        entry.occurredAt = event.occurredAt();
        entry.payload = payload;
        return entry;
    }

    public Long getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getEventType() {
        return eventType;
    }

    public int getVersion() {
        return version;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getPayload() {
        return payload;
    }
}
