package com.company.orderapi.a2a;

import java.util.List;
import java.util.Map;

public record AgentCard(String name, String version, String description, String endpoint, List<String> capabilities, Map<String, String> metadata) {
    public static AgentCard defaultCard(String baseUrl) {
        return new AgentCard("order-management-api-agent", "1.0.0",
                "Order Management API agent - handles order, product and docs queries via MCP + RAG",
                baseUrl + "/a2a/message",
                List.of("mcp.tools", "rag.query", "agent.chat"),
                Map.of("protocol", "A2A-0.1", "mcp_endpoint", baseUrl + "/mcp"));
    }
}
