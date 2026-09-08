package com.company.orderapi.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * PR #36 - a single MCP tool the assistant can call.
 *
 * <p>MCP (Model Context Protocol) lets an AI assistant discover and invoke
 * "tools" on a server. Each tool declares its JSON input schema and returns
 * plain text content. Tools here are deliberately READ-ONLY: an assistant can
 * inspect the catalogue and orders, never mutate them. Write tools (guarded,
 * with confirmation) are out of scope and documented as such.
 */
public interface McpTool {

    /** Stable tool name used in {@code tools/call} (lower_snake_case). */
    String name();

    /** Human-readable summary the assistant uses to decide when to call it. */
    String description();

    /** JSON Schema for the arguments object (empty object = no arguments). */
    JsonNode inputSchema();

    /**
     * Executes the tool and returns the text content for the assistant.
     *
     * @throws IllegalArgumentException with a safe, human message when the
     *                                  request cannot be fulfilled.
     */
    String execute(Map<String, JsonNode> arguments);
}
