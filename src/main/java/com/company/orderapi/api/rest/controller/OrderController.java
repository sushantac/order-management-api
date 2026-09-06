package com.company.orderapi.api.rest.controller;

import com.company.orderapi.api.dto.OrderMapper;
import com.company.orderapi.api.dto.OrderRequest;
import com.company.orderapi.api.dto.OrderResponse;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * PR #22 - REST for orders.
 *
 * <ul>
 *   <li>POST /orders - place an order (transactional, PR #20)</li>
 *   <li>POST /orders/bulk - place many orders at once</li>
 *   <li>GET /orders + /orders/{id} - read (ETag on the single resource)</li>
 *   <li>PATCH /orders/{id} - RFC-style JSON Patch (replace /status)</li>
 *   <li>DELETE /orders/{id} with If-Match (ETag precondition)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orders;

    public OrderController(OrderService orderService, OrderRepository orders) {
        this.orderService = orderService;
        this.orders = orders;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<OrderResponse> create(@RequestBody OrderRequest request) {
        Order order = orderService.placeOrder(request.customerId(),
                request.items().stream()
                        .map(i -> new OrderService.OrderLine(i.productId(), i.quantity()))
                        .toList());
        return ResponseEntity
                .created(URI.create("/api/v1/orders/" + order.getId()))
                .body(OrderMapper.toOrderResponse(order));
    }

    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<List<OrderResponse>> createBulk(@RequestBody List<OrderRequest> requests) {
        List<OrderResponse> created = requests.stream()
                .map(r -> OrderMapper.toOrderResponse(orderService.placeOrder(r.customerId(),
                        r.items().stream()
                                .map(i -> new OrderService.OrderLine(i.productId(), i.quantity()))
                                .toList())))
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<OrderResponse> list(Pageable pageable) {
        return orders.findOrdersPaged(pageable).map(OrderMapper::toOrderResponse);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<OrderResponse> get(@PathVariable Long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + id));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(order))
                .body(OrderMapper.toOrderResponse(order));
    }

    /**
     * PATCH with a JSON Patch document: only "replace /status" is supported here
     * (a full RFC 6902 engine would handle add/remove/move/test). Demonstrates
     * PATCH semantics: a partial update instead of a full PUT.
     */
    @PatchMapping("/{id}")
    @Transactional
    public OrderResponse patch(@PathVariable Long id, @RequestBody JsonNode patch) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + id));
        if (patch.isArray()) {
            for (JsonNode op : patch) {
                String opName = op.path("op").asText();
                String path = op.path("path").asText();
                if ("replace".equals(opName) && "/status".equals(path)) {
                    order.setStatus(OrderStatus.valueOf(op.path("value").asText()));
                } else {
                    throw new IllegalArgumentException("Unsupported patch op: " + opName + " " + path);
                }
            }
        } else {
            throw new IllegalArgumentException("JSON Patch must be an array of operations");
        }
        orders.flush();
        return OrderMapper.toOrderResponse(order);
    }

    /** DELETE guarded by an ETag precondition: stale clients get 412. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + id));
        if (ifMatch == null || !etag(order).equals(ifMatch.trim())) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }
        orders.delete(order);
        return ResponseEntity.noContent().build();
    }

    private String etag(Order order) {
        return "\"v" + order.getVersion() + "\"";
    }
}
