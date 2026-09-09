package com.company.orderapi.mcp;

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
 *       ({@code confirmed=true}) in its JSON Schema;</li>
 *   <li>delegate the actual mutation to a service method that carries its own
 *       {@code @PreAuthorize} scope check and domain rules (never to a bare
 *       repository save).</li>
 * </ul>
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
        Tool tool = Tool.builder()
                .name(name())
                .description(description())
                .inputSchema(inputSchema())
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((transportContext, request) -> {
                    try {
                        return new CallToolResult(execute(request.arguments()), false);
                    } catch (IllegalArgumentException e) {
                        String message = e.getMessage() == null ? "Tool failed." : e.getMessage();
                        return new CallToolResult(message, true);
                    }
                })
                .build();
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