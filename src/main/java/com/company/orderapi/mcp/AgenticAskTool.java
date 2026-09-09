package com.company.orderapi.mcp;

import com.company.orderapi.agent.AgentService;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: delegate a whole task to the server-side agent (PR #39).
 *
 * <p>{@code agentic_ask} is the MCP surface for {@link AgentService}: the caller
 * gives one natural-language task and the agent DECIDES which read-only tools to
 * call (product_search, order_status, docs_search, api_health), chaining several
 * calls if needed. From the protocol's point of view this is still a single,
 * read-only, PII-free tool - the multi-step reasoning stays server-side.
 *
 * <p>Only available when the RAG feature is enabled (the agent needs the DeepSeek
 * chat model and the {@link com.company.orderapi.rag.RagService}-backed docs tool).
 *
 * <p>PR #40: an optional {@code conversationId} turns the call into a multi-turn
 * conversation - pass the same id across calls and the agent remembers the
 * previous turns. Omit it for a fresh, stateless question.
 */
@Component
@ConditionalOnBean(AgentService.class)
public class AgenticAskTool extends AbstractMcpReadOnlyTool {

    private final AgentService agentService;

    public AgenticAskTool(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public String name() {
        return "agentic_ask";
    }

    @Override
    public String description() {
        return "Delegate a task to the server-side agent. The agent decides which "
                + "read-only tools to call (product_search, order_status, docs_search, "
                + "api_health) and may chain several calls to answer. Pass the same "
                + "conversationId across calls to keep multi-turn memory. Read-only; "
                + "no customer personal data.";
    }

    @Override
    public JsonSchema inputSchema() {
        return objectSchema(Map.of(
                "task", Map.of(
                        "type", "string",
                        "description", "A natural-language task or question for the agent."),
                "conversationId", Map.of(
                        "type", "string",
                        "description", "Optional stable id grouping turns into one conversation "
                                + "with memory. Omit for a stateless question.")),
                List.of("task"));
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        String task = optionalText(arguments, "task");
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String conversationId = optionalText(arguments, "conversationId");
        return agentService.ask(task, conversationId);
    }
}