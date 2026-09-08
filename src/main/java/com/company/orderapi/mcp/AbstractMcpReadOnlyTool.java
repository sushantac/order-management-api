package com.company.orderapi.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/**
 * PR #37 - common shape for the READ-ONLY MCP tools backed by the official MCP
 * Java SDK.
 *
 * <p>Each tool contributes a {@link McpStatelessServerFeatures.SyncToolSpecification}
 * that the SDK turns into a {@code tools/list} entry and a {@code tools/call}
 * handler. The transport (and therefore the server) is the official one; the
 * only code we own is the business logic behind each tool.
 *
 * <p>By contract every tool here is read-only and PII-free (implemented by the
 * subclasses). A tool failure is reported as an MCP tool error ({@code isError}
 * = true) with a human message, never as an uncaught exception.
 */
public abstract class AbstractMcpReadOnlyTool {

    /** Stable tool name used in {@code tools/call} (lower_snake_case). */
    public abstract String name();

    /** Human-readable summary the assistant uses to decide when to call it. */
    public abstract String description();

    /** JSON Schema for the arguments object (empty object = no arguments). */
    public abstract JsonSchema inputSchema();

    /**
     * Executes the tool and returns the text content for the assistant.
     *
     * @throws IllegalArgumentException with a safe, human message when the
     *                                  request cannot be fulfilled.
     */
    protected abstract String run(Map<String, Object> arguments);

    /** Builds the official SDK tool specification from this tool's contract. */
    public final McpStatelessServerFeatures.SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(name())
                .description(description())
                .inputSchema(inputSchema())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((transportContext, request) -> {
                    try {
                        return new CallToolResult(run(request.arguments()), false);
                    } catch (IllegalArgumentException e) {
                        String message = e.getMessage() == null ? "Tool failed." : e.getMessage();
                        return new CallToolResult(message, true);
                    }
                })
                .build();
    }

    protected static JsonSchema objectSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    protected static JsonSchema emptyObjectSchema() {
        return new JsonSchema("object", Map.of(), List.of(), null, Map.of(), Map.of());
    }

    protected static String optionalText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text : null;
    }

    protected static int optionalInt(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    protected static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
