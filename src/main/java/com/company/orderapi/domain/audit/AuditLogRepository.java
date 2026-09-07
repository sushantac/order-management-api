package com.company.orderapi.domain.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** PR #27 - persistence for the compliance audit trail. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByCustomerIdOrderByOccurredAtDesc(Long customerId);
}
