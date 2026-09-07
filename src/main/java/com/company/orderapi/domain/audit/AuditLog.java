package com.company.orderapi.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * PR #27 - one row of the compliance audit trail (GDPR Art. 5(2)/30:
 * accountability + records of processing activities).
 *
 * <p>Deliberately does NOT extend {@code BaseEntity} and carries NO foreign key
 * to {@code customers}: erasure requests are recorded even after the customer
 * row is gone, and audit data is append-mostly by design (like the event
 * store). The {@code detail} column is a small JSON document that NEVER
 * contains raw PII - GDPR applies to the audit trail too.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Customer the action was about; kept even when the customer is erased. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** e.g. CUSTOMER_ERASED, CUSTOMER_ANONYMIZED, PORTABILITY_EXPORTED. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** Who performed the action (OAuth2 subject or API-key client). */
    @Column(name = "actor", nullable = false, length = 255)
    private String actor;

    /** Non-PII JSON facts (order/address counts, mode, ...). */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    void stampTime() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }

    protected AuditLog() {
        // for JPA
    }

    public static AuditLog of(Long customerId, String action, String actor, String detail) {
        AuditLog entry = new AuditLog();
        entry.customerId = customerId;
        entry.action = action;
        entry.actor = actor;
        entry.detail = detail;
        return entry;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
