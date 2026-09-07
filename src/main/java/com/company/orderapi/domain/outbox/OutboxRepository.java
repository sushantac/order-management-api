package com.company.orderapi.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** PR #31 - persistence for the transactional outbox. */
public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    long countByStatus(OutboxStatus status);

    /**
     * Claims the oldest PENDING batch for publishing. {@code FOR UPDATE SKIP
     * LOCKED} lets several app instances poll concurrently without ever
     * claiming the same row twice.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
            ORDER BY id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntry> lockPendingBatch(@Param("limit") int limit);
}
