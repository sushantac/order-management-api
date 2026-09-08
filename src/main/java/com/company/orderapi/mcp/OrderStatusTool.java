package com.company.orderapi.mcp;

import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PR #36 - MCP tool: look up the public status of a single order.
 *
 * <p><b>PII boundary.</b> The tool deliberately returns only order facts
 * (id, number, status, total, date) and NEVER touches the (lazy) customer
 * association. {@code customer.email} would open a lazy load across the wire
 * and leak personal data to an assistant prompt; the tool signature simply
 * does not allow it.
 */
@Component
public class OrderStatusTool implements McpTool {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderStatusTool(OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "order_status";
    }

    @Override
    public String description() {
        return "Look up a single order by its numeric id and return its public "
                + "status: order number, status, total amount and order date. "
                + "Returns no customer personal data.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("orderId", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("description", "Numeric order id."));
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", objectMapper.createArrayNode().add("orderId"));
        return schema;
    }

    @Override
    public String execute(Map<String, JsonNode> arguments) {
        JsonNode orderIdNode = arguments.get("orderId");
        long orderId;
        if (orderIdNode == null || orderIdNode.isNull()) {
            throw new IllegalArgumentException("orderId is required and must be an integer.");
        } else if (orderIdNode.isNumber()) {
            orderId = orderIdNode.asLong();
        } else if (orderIdNode.isTextual()) {
            try {
                orderId = Long.parseLong(orderIdNode.asText());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("orderId must be an integer.");
            }
        } else {
            throw new IllegalArgumentException("orderId must be an integer.");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive.");
        }

        return orderRepository.findById(orderId)
                .map(this::format)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
    }

    private String format(Order order) {
        return "Order " + order.getId()
                + " | number " + (order.getOrderNumber() == null ? "-" : order.getOrderNumber())
                + " | status " + order.getStatus()
                + " | total " + order.getTotalAmount().toPlainString()
                + " | placed " + order.getOrderDate();
    }
}
