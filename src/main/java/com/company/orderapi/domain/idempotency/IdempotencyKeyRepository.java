package com.company.orderapi.domain.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * PR #24 - repository over the idempotency_keys table.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
