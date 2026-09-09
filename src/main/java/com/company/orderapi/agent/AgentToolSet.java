package com.company.orderapi.agent;

import com.company.orderapi.mcp.ApiHealthTool;
import com.company.orderapi.mcp.DocsSearchTool;
import com.company.orderapi.mcp.OrderStatusTool;
import com.company.orderapi.mcp.ProductSearchTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * PR #39 - the server-side agent's function-calling surface.
 *
 * <p>Every {@link Tool} method exposes ONE of the existing read-only MCP tools
 * to the LLM as a function. This is the same surface an MCP assistant sees via
 * {@code tools/list}, adapted to Spring AI function calling: the model declares
 * which tool it wants to call, and {@code AgentService} executes it. The tool
 * names deliberately match the MCP tool names so a capability is identical no
 * matter which protocol reaches it.
 *
 * <p>Delegation goes through {@link AbstractMcpReadOnlyTool#execute(Map)}, the
 * single implementation of the read-only surface (two protocols, one code path).
 *
 * <p>Only active when {@code app.rag.enabled=true}, matching the RAG feature it
 * builds on.
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class AgentToolSet {

    private final ApiHealthTool apiHealthTool;
    private final ProductSearchTool productSearchTool;
    private final OrderStatusTool orderStatusTool;
    private final DocsSearchTool docsSearchTool;

    public AgentToolSet(
            ApiHealthTool apiHealthTool,
            ProductSearchTool productSearchTool,
            OrderStatusTool orderStatusTool,
            DocsSearchTool docsSearchTool) {
        this.apiHealthTool = apiHealthTool;
        this.productSearchTool = productSearchTool;
        this.orderStatusTool = orderStatusTool;
        this.docsSearchTool = docsSearchTool;
    }

    @Tool(name = "api_health",
            description = "Confirm the Order Management API agent services are up. "
                    + "Returns service name, version and uptime. Takes no arguments.")
    public String apiHealth() {
        return apiHealthTool.execute(Map.of());
    }

    @Tool(name = "product_search",
            description = "Search the product catalogue by name. Returns product id, "
                    + "name, price and available stock for up to maxResults matches.")
    public String productSearch(
            @ToolParam(description = "Substring to match against product names.")
            String query,
            @ToolParam(description = "Maximum matches to return (1-50).", required = false)
            Integer maxResults) {
        Map<String, Object> args = new HashMap<>();
        args.put("query", query == null ? "" : query);
        args.put("maxResults", maxResults == null ? 10 : maxResults);
        return productSearchTool.execute(args);
    }

    @Tool(name = "order_status",
            description = "Look up a single order by its numeric id and return its "
                    + "public status: order number, status, total amount and order "
                    + "date. Returns no customer personal data.")
    public String orderStatus(
            @ToolParam(description = "Numeric order id.")
            long orderId) {
        return orderStatusTool.execute(Map.of("orderId", orderId));
    }

    @Tool(name = "docs_search",
            description = "Search project documentation and get AI-generated answers "
                    + "about the Order Management API. Use this to answer questions "
                    + "about architecture, features, setup, configuration, or how "
                    + "things work.")
    public String docsSearch(
            @ToolParam(description = "The question to answer from project documentation.")
            String question) {
        return docsSearchTool.execute(Map.of("question", question));
    }
}