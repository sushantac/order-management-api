package com.company.orderapi.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
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
 *
 * <p>PR #48: tool invocations are audited via {@link McpAuditService}. The
 * {@code specification(McpAuditService)} overload wraps the call handler to
 * record the actor, session_id, tool name, arguments, and success/error outcome.
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
        return specification(null);
    }

    /**
     * Builds the tool specification with audit recording enabled.
     *
     * @param auditService the audit service to record tool invocations; if null,
     *                     no audit entry is written (backward compatible with
     *                     direct {@code execute()} calls from the agent surface)
     */
    public final McpStatelessServerFeatures.SyncToolSpecification specification(
            McpAuditService auditService) {
        Tool tool = Tool.builder()
                .name(name())
                .description(description())
                .inputSchema(inputSchema())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((transportContext, request) -> {
                    String sessionId = extractSessionId(transportContext);
                    String actor = extractActor(transportContext);
                    Object rawArgs = request.arguments();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> arguments = (rawArgs instanceof Map) ? (Map<String, Object>) rawArgs : Map.of();
                    try {
                        String result = execute(arguments);
                        if (auditService != null) {
                            auditService.record(sessionId, actor, name(), arguments, true, null);
                        }
                        return new CallToolResult(result, false);
                    } catch (IllegalArgumentException e) {
                        String message = e.getMessage() == null ? "Tool failed." : e.getMessage();
                        if (auditService != null) {
                            auditService.record(sessionId, actor, name(), arguments, false, message);
                        }
                        return new CallToolResult(message, true);
                    }
                })
                .build();
    }

    private static String extractActor(McpTransportContext ctx) {
        return (String) ctx.get("mcp_actor");
    }

    private static String extractSessionId(McpTransportContext ctx) {
        Object sid = ctx.get("mcp_session_id");
        return sid != null ? sid.toString() : "unknown";
    }

    /**
     * Programmatic invocation of the tool, shared by the MCP call handler (above)
     * and the server-side agent's function-calling surface (PR #39). Keeps ONE
     * implementation of the read-only surface behind two protocols.
     *
     * @throws IllegalArgumentException with a safe, human message when the
     *                                  request cannot be fulfilled.
     */
    public final String execute(Map<String, Object> arguments) {
        return run(arguments);
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