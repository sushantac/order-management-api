package com.company.orderapi.domain.eventstore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PR #19 - repository over the append-only event store.
 *
 * <p>All queries are by aggregate id; history is naturally ordered by version,
 * and the (aggregate_id, version) unique constraint is enforced by the schema.
 */
public interface EventStoreRepository extends JpaRepository<EventStoreEntry, Long> {

    List<EventStoreEntry> findByAggregateIdOrderByVersionAsc(UUID aggregateId);

    Optional<EventStoreEntry> findTopByAggregateIdOrderByVersionDesc(UUID aggregateId);

    boolean existsByAggregateIdAndVersion(UUID aggregateId, int version);
}
