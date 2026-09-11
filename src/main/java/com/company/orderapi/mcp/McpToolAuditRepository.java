package com.company.orderapi.mcp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface McpToolAuditRepository extends JpaRepository<McpToolAudit, Long> {

    List<McpToolAudit> findBySessionIdOrderByOccurredAtAsc(String sessionId);

    List<McpToolAudit> findByActorOrderByOccurredAtDesc(String actor);

    List<McpToolAudit> findByToolNameOrderByOccurredAtDesc(String toolName);

    @Query("SELECT a FROM McpToolAudit a WHERE a.occurredAt >= :since ORDER BY a.occurredAt DESC")
    List<McpToolAudit> findRecent(Instant since);
}