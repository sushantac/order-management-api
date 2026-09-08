package com.company.orderapi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR #36 - a minimal Model Context Protocol (MCP) server over plain HTTP.
 *
 * <p>MCP uses JSON-RPC 2.0. This endpoint implements the subset that matters
 * for assistant tool use, using the JSON-only mode of the MCP "Streamable
 * HTTP" transport (client sends {@code Accept: application/json}):
 *
 * <ul>
 *   <li>{@code initialize} / {@code notifications/initialized}</li>
 *   <li>{@code ping}</li>
 *   <li>{@code tools/list} - the assistant discovers what it can call</li>
 *   <li>{@code tools/call} - the assistant invokes a tool</li>
 * </ul>
 *
 * <p>Only READ-ONLY tools are registered (catalogue + order status): an
 * assistant may look, not mutate. Design notes for production: an official
 * MCP SDK on a supported Spring Boot line would replace this hand-rolled
 * transport; the tool abstraction and the JSON-RPC surface stay the same.
 */
@RestController
@RequestMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
public class McpController {

    static final String PROTOCOL_VERSION = "2024-11-05";

    private static final int JSONRPC_ERROR_INVALID_REQUEST = -32600;
    private static final int JSONRPC_ERROR_METHOD_NOT_FOUND = -32601;
    private static final int JSONRPC_ERROR_INVALID_PARAMS = -32602;

    private final List<McpTool> tools;
    private final ObjectMapper objectMapper;

    public McpController(List<McpTool> tools, ObjectMapper objectMapper) {
        this.tools = tools.stream()
                .sorted(Comparator.comparing(McpTool::name))
                .toList();
        this.objectMapper = objectMapper;
    }

    /** Entry point: accepts a single JSON-RPC request or a batch (array). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode handle(@RequestBody JsonNode body) {
        if (body == null || (!body.isObject() && !body.isArray())) {
            return error(null, JSONRPC_ERROR_INVALID_REQUEST,
                    "Request body must be a JSON-RPC request object or batch array.");
        }
        if (body.isArray()) {
            ArrayNode responses = objectMapper.createArrayNode();
            for (JsonNode request : body) {
                if (request.isObject()) {
                    JsonNode response = dispatch(request);
                    if (response != null) {
                        responses.add(response);
                    }
                }
            }
            return responses;
        }
        JsonNode response = dispatch(body);
        return response == null ? objectMapper.createObjectNode() : response;
    }

    private JsonNode dispatch(JsonNode request) {
        String method = text(request, "method");
        JsonNode id = request.get("id");
        if (method == null) {
            return error(id, JSONRPC_ERROR_INVALID_REQUEST, "Missing 'method'.");
        }

        // Notifications carry no id and expect no response.
        if (method.startsWith("notifications/")) {
            return null;
        }

        JsonNode params = request.get("params");
        switch (method) {
            case "initialize" -> {
                return result(id, initResult());
            }
            case "ping" -> {
                return result(id, objectMapper.createObjectNode());
            }
            case "tools/list" -> {
                return result(id, toolsResult());
            }
            case "tools/call" -> {
                return callTool(id, params);
            }
            default -> {
                return error(id, JSONRPC_ERROR_METHOD_NOT_FOUND,
                        "Method not found: " + method);
            }
        }
    }
    private JsonNode callTool(JsonNode id, JsonNode params) {
        if (params == null || !params.isObject() || !params.hasNonNull("name")) {
            return error(id, JSONRPC_ERROR_INVALID_PARAMS,
                    "tools/call requires params.name");
        }
        String name = params.get("name").asText();
        McpTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElse(null);
        if (tool == null) {
            return error(id, JSONRPC_ERROR_INVALID_PARAMS,
                    "Unknown tool: " + name);
        }

        JsonNode argumentsNode = params.get("arguments");
        Map<String, JsonNode> arguments = argumentsNode == null || !argumentsNode.isObject()
                ? Map.of()
                : fields(argumentsNode);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "text");
        try {
            content.put("text", tool.execute(arguments));
            ObjectNode result = objectMapper.createObjectNode();
            result.set("content", arrayOf(content));
            result.put("isError", false);
            return result(id, result);
        } catch (IllegalArgumentException e) {
            content.put("text", e.getMessage() == null ? "Tool failed." : e.getMessage());
            ObjectNode result = objectMapper.createObjectNode();
            result.set("content", arrayOf(content));
            result.put("isError", true);
            return result(id, result);
        }
    }

    private JsonNode toolsResult() {
        ArrayNode list = objectMapper.createArrayNode();
        for (McpTool tool : tools) {
            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("name", tool.name());
            meta.put("description", tool.description());
            meta.set("inputSchema", tool.inputSchema());
            list.add(meta);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", list);
        return result;
    }

    private JsonNode initResult() {
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.set("tools", objectMapper.createObjectNode().put("listChanged", false));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.set("capabilities", capabilities);
        result.set("serverInfo", objectMapper.createObjectNode()
                .put("name", "order-management-api-mcp")
                .put("version", "1.0.0"));
        return result;
    }

    private JsonNode result(JsonNode id, JsonNode resultValue) {
        ObjectNode response = envelope(id);
        response.set("result", resultValue);
        return response;
    }

    private JsonNode error(JsonNode id, int code, String message) {
        ObjectNode response = envelope(id);
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }

    private ObjectNode envelope(JsonNode id) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            envelope.set("id", id);
        }
        return envelope;
    }

    private Map<String, JsonNode> fields(JsonNode object) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            fields.put(name, object.get(name));
        }
        return fields;
    }

    private ArrayNode arrayOf(JsonNode value) {
        ArrayNode array = objectMapper.createArrayNode();
        array.add(value);
        return array;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

}
