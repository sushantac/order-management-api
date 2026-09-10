package com.company.orderapi.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * PR #46 - common shape for MCP PROMPTS backed by the official MCP Java SDK.
 *
 * <p>Where Tools are actions and Resources are data, Prompts are
 * <em>reusable instruction templates</em>: a client-agnostic way to package
 * "how to drive this server for a task" as parameterized message templates.
 * The client retrieves them via {@code prompts/list} and {@code prompts/get}
 * and injects the rendered messages into its own conversation with the model.
 *
 * <p>Prompts are pure templates: they never execute tools or read resources
 * themselves. Each one produces {@code PromptMessage}s (typically a system
 * message setting the role + a user message carrying the task).
 */
public abstract class AbstractMcpPrompt {

    /** Stable prompt name used in {@code prompts/get} (lower_snake_case). */
    public abstract String name();

    /** Human-readable summary the assistant uses to decide which prompt to use. */
    public abstract String description();

    /** Declared arguments of this prompt template (may be empty). */
    public abstract List<McpSchema.PromptArgument> arguments();

    /**
     * Renders the prompt template into concrete messages given the caller's
     * arguments. Argument values must be extracted defensively - the assistant
     * may supply any types or omit optional ones.
     */
    protected abstract List<McpSchema.PromptMessage> messages(Map<String, Object> arguments);

    /** Builds the official SDK prompt specification from this contract. */
    public final McpStatelessServerFeatures.SyncPromptSpecification specification() {
        McpSchema.Prompt prompt = new McpSchema.Prompt(name(), description(), arguments());
        return new McpStatelessServerFeatures.SyncPromptSpecification(
                prompt, (transportContext, request) ->
                        new McpSchema.GetPromptResult(description(), messages(request.arguments())));
    }

    /** Convenience: one USER-role text message (the common case). */
    protected static McpSchema.PromptMessage userMessage(String text) {
        return new McpSchema.PromptMessage(
                McpSchema.Role.USER,
                new McpSchema.TextContent(text));
    }

    /** Convenience: one ASSISTANT-role text message. */
    protected static McpSchema.PromptMessage assistantMessage(String text) {
        return new McpSchema.PromptMessage(
                McpSchema.Role.ASSISTANT,
                new McpSchema.TextContent(text));
    }
}