package com.company.orderapi.mcp;

import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.repository.OrderRepository;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PR #37 - MCP tool: look up the public status of a single order.
 *
 * <p><b>PII boundary.</b> The tool deliberately returns only order facts
 * (id, number, status, total, date) and NEVER touches the (lazy) customer
 * association. {@code customer.email} would open a lazy load across the wire
 * and leak personal data to an assistant prompt; the tool signature simply
 * does not allow it.
 */
@Component
public class OrderStatusTool extends AbstractMcpReadOnlyTool {

    private final OrderRepository orderRepository;

    public OrderStatusTool(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
    public JsonSchema inputSchema() {
        return objectSchema(Map.of("orderId", Map.of(
                "type", "integer",
                "description", "Numeric order id.")),
                List.of("orderId"));
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        long orderId = requiredPositiveLong(arguments, "orderId");
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

    private static long requiredPositiveLong(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        long parsed;
        if (value == null) {
            throw new IllegalArgumentException(key + " is required and must be an integer.");
        } else if (value instanceof Number number) {
            parsed = number.longValue();
        } else if (value instanceof String text) {
            try {
                parsed = Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer.");
            }
        } else {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive.");
        }
        return parsed;
    }
}
