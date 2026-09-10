package com.company.orderapi.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * PR #46 - common shape for MCP RESOURCES backed by the official MCP Java SDK.
 *
 * <p>Where Tools are capabilities the assistant decides to <em>call</em>,
 * Resources are <em>data documents</em> the assistant can enumerate
 * ({@code resources/list}) and read ({@code resources/read}) without executing
 * anything. This is the protocol's read-the-world surface: files, database
 * records, API responses.
 *
 * <p>Each resource contributes a
 * {@link McpStatelessServerFeatures.SyncResourceSpecification} that the SDK
 * turns into a {@code resources/list} entry and a {@code resources/read}
 * handler. The content is returned verbatim from {@link #read()}.
 */
public abstract class AbstractMcpResource {

    /** Stable resource URI (must be unique across the whole server). */
    public abstract String uri();

    /** Human-readable name the assistant uses to find this resource. */
    public abstract String name();

    /** What this resource contains and when a client would want it. */
    public abstract String description();

    /** MIME type of the content (e.g. {@code text/markdown}). */
    public abstract String mimeType();

    /**
     * Returns the full text content of this resource.
     *
     * @throws IllegalStateException when the backing file/content is unavailable.
     */
    public abstract String read();

    /** Builds the official SDK resource specification from this contract. */
    public final McpStatelessServerFeatures.SyncResourceSpecification specification() {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(uri())
                .name(name())
                .description(description())
                .mimeType(mimeType())
                .build();
        return new McpStatelessServerFeatures.SyncResourceSpecification(
                resource, (transportContext, request) -> {
                    McpSchema.TextResourceContents contents =
                            new McpSchema.TextResourceContents(uri(), mimeType(), read());
                    return new McpSchema.ReadResourceResult(List.of(contents));
                });
    }
}