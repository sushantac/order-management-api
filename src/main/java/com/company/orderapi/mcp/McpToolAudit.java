package com.company.orderapi.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_tool_audit", indexes = {
        @Index(name = "idx_mcp_tool_audit_session_id", columnList = "session_id"),
        @Index(name = "idx_mcp_tool_audit_actor", columnList = "actor"),
        @Index(name = "idx_mcp_tool_audit_tool_name", columnList = "tool_name")
})
public class McpToolAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    @Column(name = "actor", nullable = false, length = 255)
    private String actor;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "arguments_json", columnDefinition = "text")
    private String argumentsJson;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private McpToolAudit() {
    }

    private McpToolAudit(Builder b) {
        this.sessionId = b.sessionId;
        this.actor = b.actor;
        this.toolName = b.toolName;
        this.argumentsJson = b.argumentsJson;
        this.success = b.success;
        this.errorMessage = b.errorMessage;
        this.occurredAt = b.occurredAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActor() {
        return actor;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public static final class Builder {
        private String sessionId;
        private String actor;
        private String toolName;
        private String argumentsJson;
        private boolean success;
        private String errorMessage;
        private Instant occurredAt = Instant.now();

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder actor(String v) {
            this.actor = v;
            return this;
        }

        public Builder toolName(String v) {
            this.toolName = v;
            return this;
        }

        public Builder argumentsJson(String v) {
            this.argumentsJson = v;
            return this;
        }

        public Builder success(boolean v) {
            this.success = v;
            return this;
        }

        public Builder errorMessage(String v) {
            this.errorMessage = v;
            return this;
        }

        public Builder occurredAt(Instant v) {
            this.occurredAt = v;
            return this;
        }

        public McpToolAudit build() {
            return new McpToolAudit(this);
        }
    }
}