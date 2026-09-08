package com.company.orderapi.mcp;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * PR #37 - MCP tool: zero-dependency health probe an assistant can call to
 * confirm the MCP server itself is reachable and responding.
 *
 * <p>No repositories are touched, so it works even while the database is being
 * migrated or is briefly unavailable (unlike the catalogue tools above).
 */
@Component
public class ApiHealthTool extends AbstractMcpReadOnlyTool {

    private static final String VERSION = "1.0.0";

    private final Instant startedAt;

    public ApiHealthTool() {
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
    public JsonSchema inputSchema() {
        return emptyObjectSchema();
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        long uptimeSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
        return "OK | order-management-api-mcp " + VERSION
                + " | up " + uptimeSeconds + "s";
    }
}
