package com.company.orderapi.mcp;

import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.service.OrderService;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "write-tool.enabled", havingValue = "true")
public class ShipOrderTool extends AbstractMcpWriteTool {

    private static final Logger AUDIT = LoggerFactory.getLogger("mcp.ship-order");

    private final OrderService orderService;

    public ShipOrderTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String name() {
        return "ship_order";
    }

    @Override
    public String description() {
        return "MUTATES DATA: ship the order with the given id. Only CONFIRMED orders can be shipped; "
                + "PLACED/SHIPPED/DELIVERED/CANCELLED orders are refused. Requires confirmed=true. "
                + "Returns order number and new status.";
    }

    @Override
    public JsonSchema inputSchema() {
        return objectSchema(Map.of(
                "orderId", Map.of("type", "integer", "description", "Numeric id of the order to ship."),
                "confirmed", Map.of("type", "boolean", "description", "Must be true to execute.")),
                List.of("orderId", "confirmed"));
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        long orderId = requiredPositiveLong(arguments, "orderId");
        if (!Boolean.TRUE.equals(arguments.get("confirmed"))) {
            throw new IllegalArgumentException("Refusing to ship order " + orderId + ": confirmed must be exactly true.");
        }
        Order order = orderService.shipOrder(orderId);
        AUDIT.info("ship_order confirmed=true orderId={} orderNumber={} -> SHIPPED", order.getId(), order.getOrderNumber());
        return "Order " + order.getOrderNumber() + " shipped (status SHIPPED).";
    }

    private static long requiredPositiveLong(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        long parsed;
        if (value == null) throw new IllegalArgumentException(key + " is required and must be an integer.");
        else if (value instanceof Number n) parsed = n.longValue();
        else if (value instanceof String s) {
            try { parsed = Long.parseLong(s); } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer."); }
        } else throw new IllegalArgumentException(key + " must be an integer.");
        if (parsed <= 0) throw new IllegalArgumentException(key + " must be positive.");
        return parsed;
    }
}
