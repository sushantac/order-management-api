package com.company.orderapi.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Comparator;
import java.util.List;

/**
 * PR #37 - official MCP server wiring (Model Context Protocol Java SDK,
 * Spring WebMVC transport).
 *
 * <p>How the pieces fit together:
 * <ol>
 *   <li>{@link WebMvcStatelessServerTransport} - the SDK's stateless
 *       Streamable-HTTP transport for Spring MVC. Exposes {@code POST /mcp}
 *       and speaks JSON-RPC 2.0. It is fed our Spring {@link ObjectMapper}
 *       through the SDK's Jackson 2 bridge ({@link JacksonMcpJsonMapper}).</li>
 *   <li>{@code mcpRouterFunction} - registers the transport's endpoints with
 *       Spring MVC (Boot discovers {@link RouterFunction} beans).</li>
 *   <li>{@link McpStatelessSyncServer} - the SDK-managed server. Built from
 *       the transport plus one {@link io.modelcontextprotocol.spec.McpSchema.Tool}
 *       per {@link AbstractMcpReadOnlyTool} and {@link AbstractMcpWriteTool} bean.
 *       It owns JSON-RPC request handling, protocol negotiation and the
 *       {@code tools/list} + {@code tools/call} surface.</li>
 * </ol>
 *
 * <p>Read-only and write tools are injected as SEPARATE lists (see
 * {@link AbstractMcpWriteTool}): write tools are off by default, so in the
 * default configuration this list is empty and {@code tools/list} stays purely
 * read-only. Enabling {@code app.mcp.write-tool.enabled=true} is an explicit,
 * reviewable operational decision.
 *
 * <p>Authentication is NOT handled here: {@code /mcp} sits behind the same
 * Spring Security chain as every other endpoint, so MCP callers need a valid
 * bearer token / API key in production (see {@code SecurityConfig}).
 */
@Configuration(proxyBeanMethods = false)
public class McpServerConfiguration {

    private static final String MCP_ENDPOINT = "/mcp";

    @Bean
    public WebMvcStatelessServerTransport mcpTransport(ObjectMapper objectMapper) {
        return WebMvcStatelessServerTransport.builder()
                .messageEndpoint(MCP_ENDPOINT)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcStatelessServerTransport transport) {
        return transport.getRouterFunction();
    }

    @Bean(destroyMethod = "close")
    public McpStatelessSyncServer mcpServer(
            WebMvcStatelessServerTransport transport,
            List<AbstractMcpReadOnlyTool> readTools,
            List<AbstractMcpWriteTool> writeTools,
            McpDocsResourceCatalog docsCatalog,
            List<AbstractMcpPrompt> prompts) {

        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new java.util.ArrayList<>();
        readTools.stream()
                .sorted(Comparator.comparing(AbstractMcpReadOnlyTool::name))
                .map(AbstractMcpReadOnlyTool::specification)
                .forEach(specifications::add);
        writeTools.stream()
                .sorted(Comparator.comparing(AbstractMcpWriteTool::name))
                .map(AbstractMcpWriteTool::specification)
                .forEach(specifications::add);

        return McpServer.sync(transport)
                .serverInfo("order-management-api-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .resources(false, false)
                        .prompts(false)
                        .build())
                .tools(specifications.toArray(McpStatelessServerFeatures.SyncToolSpecification[]::new))
                .resources(docsCatalog.specifications()
                        .toArray(McpStatelessServerFeatures.SyncResourceSpecification[]::new))
                .prompts(prompts.stream()
                        .sorted(Comparator.comparing(AbstractMcpPrompt::name))
                        .map(AbstractMcpPrompt::specification)
                        .toArray(McpStatelessServerFeatures.SyncPromptSpecification[]::new))
                .build();
    }
}
