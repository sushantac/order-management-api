package com.company.orderapi.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/**
 * PR #41 - common shape for the GUARDED WRITE MCP tools.
 *
 * <p><b>Deliberately NOT a subclass of {@link AbstractMcpReadOnlyTool}.</b> The
 * two tool families are kept structurally separate so a write tool can never
 * satisfy {@code List<AbstractMcpReadOnlyTool>} and silently appear where only
 * read-only functionality is allowed (tool list assertions, the agent's
 * function-calling surface in {@link com.company.orderapi.agent.AgentToolSet}).
 * The small helper duplication is the price of that hard boundary.
 *
 * <p>Every write tool must:
 * <ul>
 *   <li>be OFF by default - the bean exists only when
 *       {@code app.mcp.write-tool.enabled=true} (an explicit deploy-time
 *       decision);</li>
 *   <li>describe itself as MUTATING data in its description, so a model-assisted
 *       caller knows the consequence before calling;</li>
 *   <li>require an explicit per-call confirmation argument
 *       {@code confirmed=true} in its JSON Schema;</li>
 *   <li>delegate the actual mutation to a service method that carries its own
 *       {@code @PreAuthorize} scope check and domain rules (never to a bare
 *       repository save).</li>
 * </ul>
 *
 * <p>PR #48: tool invocations are audited via {@link McpAuditService}. The
 * {@code specification(McpAuditService)} overload wraps the call handler to
 * record the actor, session_id, tool name, arguments, and success/error outcome.
 */
public abstract class AbstractMcpWriteTool {

    /** Stable tool name used in {@code tools/call} (lower_snake_case). */
    public abstract String name();

    /** Human-readable summary. MUST state that the tool mutates data. */
    public abstract String description();

    /** JSON Schema for the arguments object. MUST require the confirmation arg. */
    public abstract JsonSchema inputSchema();

    /**
     * Executes the mutating operation and returns the text content for the caller.
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
     *                     no audit entry is written (backward compatible)
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

    /** Programmatic invocation of the tool (used by the MCP call handler). */
    public final String execute(Map<String, Object> arguments) {
        return run(arguments);
    }

    protected static JsonSchema objectSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    protected static String optionalText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text : null;
    }
}