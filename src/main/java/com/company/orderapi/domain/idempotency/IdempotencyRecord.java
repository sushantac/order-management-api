package com.company.orderapi.domain.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * PR #24 - one stored result per client-supplied {@code Idempotency-Key}.
 *
 * <p>Not a {@code BaseEntity} on purpose: this is request bookkeeping, not a
 * domain aggregate (no optimistic-lock/audit semantics). The key is unique so
 * concurrent duplicates cannot double-execute the write.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 255)
    private String requestPath;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    public static IdempotencyRecord of(String key, String method, String path,
                                       int status, String body) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.idempotencyKey = key;
        record.httpMethod = method;
        record.requestPath = path;
        record.responseStatus = status;
        record.responseBody = body;
        return record;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
