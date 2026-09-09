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

/**
 * PR #41 - the first WRITE tool: cancel an order.
 *
 * <p>This is the guarded mutation and the anti-thesis of the read-only surface:
 * <ul>
 *   <li><b>Off by default.</b> The bean exists only when
 *       {@code app.mcp.write-tool.enabled=true}; without that explicit
 *       deploy-time decision the capability is not even advertised.</li>
 *   <li><b>Explicit confirmation.</b> {@code confirmed} is a REQUIRED argument;
 *       anything other than {@code true} refuses the call. This guards against a
 *       model or caller firing the tool by accident (e.g. during function
 *       calling). It is an ergonomic rail, NOT the security boundary - the real
 *       boundary is the service-level {@code @PreAuthorize(order_write)} scope
 *       check plus the {@link Order#cancel()} domain rules.</li>
 *   <li><b>Audited.</b> Every successful mutation writes a structured INFO line
 *       on a dedicated logger (JSON in prod), so the decision and its inputs are
 *       reviewable.</li>
 *   <li><b>PII-free reply.</b> The result contains the order number + new status
 *       only - no customer data, consistent with every other tool.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "write-tool.enabled", havingValue = "true")
public class CancelOrderTool extends AbstractMcpWriteTool {

    private static final Logger AUDIT = LoggerFactory.getLogger("mcp.cancel-order");

    private final OrderService orderService;

    public CancelOrderTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String name() {
        return "cancel_order";
    }

    @Override
    public String description() {
        return "MUTATES DATA: cancel the order with the given id. Only PLACED and "
                + "CONFIRMED orders can be cancelled; shipped/delivered/cancelled "
                + "orders are refused. Requires confirmed=true. Returns only the "
                + "order number and its new status - never customer data.";
    }

    @Override
    public JsonSchema inputSchema() {
        return objectSchema(Map.of(
                "orderId", Map.of(
                        "type", "integer",
                        "description", "Numeric id of the order to cancel."),
                "confirmed", Map.of(
                        "type", "boolean",
                        "description", "Must be true to execute this mutating tool. "
                                + "Anything else refuses the call.")),
                List.of("orderId", "confirmed"));
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        long orderId = requiredPositiveLong(arguments, "orderId");
        Object confirmed = arguments.get("confirmed");
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new IllegalArgumentException(
                    "Refusing to cancel order " + orderId + ": confirmed must be exactly true. "
                            + "Please confirm, then call again with confirmed=true.");
        }

        Order order = orderService.cancelOrder(orderId);
        AUDIT.info("cancel_order confirmed=true orderId={} orderNumber={} -> CANCELLED",
                order.getId(), order.getOrderNumber());
        return "Order " + order.getOrderNumber() + " cancelled (status CANCELLED).";
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