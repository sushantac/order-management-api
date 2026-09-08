package com.company.orderapi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * PR #36 - MCP tool: zero-dependency health probe an assistant can call to
 * confirm the MCP server itself is reachable and responding.
 *
 * <p>No repositories are touched, so it works even while the database is being
 * migrated or is briefly unavailable (unlike the catalogue tools above).
 */
@Component
public class ApiHealthTool implements McpTool {

    private static final String VERSION = "1.0.0";

    private final ObjectMapper objectMapper;
    private final Instant startedAt;

    public ApiHealthTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.startedAt = Instant.now();
    }

    @Override
    public String name() {
        return "api_health";
    }

    @Override
    public String description() {
        return "Confirm the Order Management API MCP server is up. Returns "
                + "service name, version and uptime. Takes no arguments.";
    }

    @Override
    public JsonNode inputSchema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    @Override
    public String execute(Map<String, JsonNode> arguments) {
        long uptimeSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
        return "OK | order-management-api-mcp " + VERSION
                + " | up " + uptimeSeconds + "s";
    }
}
