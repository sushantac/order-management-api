package com.company.orderapi.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpAuditService {

    private final McpToolAuditRepository repository;
    private final ObjectMapper mapper;

    public McpAuditService(McpToolAuditRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String sessionId, String actor, String toolName,
                       Object arguments, boolean success, String errorMessage) {
        String argsJson = serializeArgs(arguments);
        McpToolAudit audit = McpToolAudit.builder()
                .sessionId(sessionId)
                .actor(actor)
                .toolName(toolName)
                .argumentsJson(argsJson)
                .success(success)
                .errorMessage(errorMessage)
                .build();
        repository.save(audit);
    }

    private String serializeArgs(Object arguments) {
        if (arguments == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            return "{\"serialization_error\": \"" + e.getMessage() + "\"}";
        }
    }
}