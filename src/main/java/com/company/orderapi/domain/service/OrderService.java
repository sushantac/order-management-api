package com.company.orderapi.domain.service;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Payment;
import com.company.orderapi.domain.PaymentMethod;
import com.company.orderapi.domain.PaymentStatus;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.outbox.OutboxEntry;
import com.company.orderapi.domain.outbox.OutboxRepository;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.messaging.OrderPlacedEventMessage;
import com.company.orderapi.messaging.OrderPlacedMessage;
import com.company.orderapi.messaging.OrderStatusChangedMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PR #20 - the order service: business logic + transactions + ACID.
 *
 * <p>{@code placeOrder} does FOUR things that must be atomic:
 * <ol>
 *   <li>deduct product stock (versioned - optimistic locking, PR #8),</li>
 *   <li>create the Order + OrderItems (snapshot prices),</li>
 *   <li>charge the customer (may fail!),</li>
 *   <li>record the Payment as PROCESSED.</li>
 * </ol>
 * If ANY step fails, the whole transaction rolls back - no order, no stock
 * deduction, no payment. {@code @Retryable} absorbs optimistic-lock conflicts
 * on hot products by re-running the whole method in a fresh transaction.
 */
@Service
public class OrderService {

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final PaymentGateway paymentGateway;
    private final ProductCatalogueService catalogue;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OrderService(CustomerRepository customers, ProductRepository products,
                        OrderRepository orders, PaymentGateway paymentGateway,
                        ProductCatalogueService catalogue,
                        OutboxRepository outbox, ObjectMapper objectMapper) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.paymentGateway = paymentGateway;
        this.catalogue = catalogue;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /** One requested order line: product + quantity (DTOs arrive in PR #21). */
    public record OrderLine(Long productId, int quantity) {
    }

    @Transactional
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 5,
            backoff = @Backoff(delay = 20))
    @Timed(value = "order.place", description = "Time to place an order",
            percentiles = 0.95)
    public Order placeOrder(Long customerId, List<OrderLine> lines) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + customerId));

        Order order = new Order(customer, OrderStatus.PLACED, BigDecimal.ZERO);
        BigDecimal total = BigDecimal.ZERO;

        for (OrderLine line : lines) {
            Product product = products.findById(line.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown product " + line.productId()));
            if (product.getStockQuantity() < line.quantity()) {
                throw new IllegalStateException(
                        "Insufficient stock for product " + line.productId()
                                + " (available " + product.getStockQuantity() + ")");
            }
            product.setStockQuantity(product.getStockQuantity() - line.quantity()); // versioned UPDATE
            // PR #28 - stock changed outside the catalogue: evict the cached
            // ProductResponse so the next GET reflects the new stock level.
            catalogue.evict(line.productId());
            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
            order.addItem(new OrderItem(product, line.quantity(), product.getPrice()));
            total = total.add(lineTotal);
        }

        order.setTotalAmount(total);
        orders.save(order); // cascade ALL persists items

        // Step 3+4: charge, then record the processed payment. A failure here
        // propagates a RuntimeException -> the transaction rolls everything back.
        paymentGateway.charge(total);
        Payment payment = new Payment(order, total, PaymentMethod.CREDIT_CARD);
        payment.setStatus(PaymentStatus.PROCESSED);
        payment.setTransactionId("sim-tx-" + System.nanoTime());
        payment.setPaymentDate(LocalDateTime.now());
        order.setPayment(payment);
        orders.saveAndFlush(order); // flush inside tx so the test sees the graph

        // PR #31 - OUTBOX: append the event in the SAME transaction as the
        // order. Kafka is told about it later by the polling publisher; if this
        // transaction rolls back, the outbox row rolls back with it.
        OrderPlacedMessage message = new OrderPlacedMessage(order.getId(),
                order.getOrderNumber(), order.getTotalAmount(), order.getOrderDate());
        outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(order.getId()),
                "OrderPlacedMessage", writeJson(message)));

        // Rich order.placed event for the new platform topic.
        OrderPlacedEventMessage eventMessage = new OrderPlacedEventMessage(
                UUID.randomUUID().toString(),
                order.getId(),
                order.getOrderNumber(),
                null,
                order.getTotalAmount(),
                order.getItems().stream()
                        .map(item -> new OrderPlacedEventMessage.OrderItem(
                                item.getProduct().getId(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getTotalPrice()))
                        .toList(),
                order.getOrderDate());
        outbox.save(OutboxEntry.pending("Order", String.valueOf(order.getId()),
                "OrderPlacedEventMessage", writeJson(eventMessage)));

        return order;
    }

    /**
     * Updates an order's status and writes an outbox entry for the
     * {@code order.status.changed} event. Validates that the order exists.
     *
     * @return the old status before the change
     */
    @Transactional
    public OrderStatus updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + orderId));
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        orders.save(order);

        OrderStatusChangedMessage message = new OrderStatusChangedMessage(
                UUID.randomUUID().toString(),
                order.getId(),
                order.getOrderNumber(),
                oldStatus.name(),
                newStatus.name(),
                LocalDateTime.now());
        outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(order.getId()),
                "OrderStatusChangedMessage", writeJson(message)));

        return oldStatus;
    }

    private String writeJson(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise outbox event", e);
        }
    }

    /**
     * PR #41 - the guarded write: {@link Order#cancel() cancel} an order.
     *
     * <p>Security is layered, not a single token:
     * <ol>
     *   <li><b>AuthN</b> at the HTTP layer (the MCP endpoint is authenticated).</li>
     *   <li><b>AuthZ</b> here: a caller needs the {@code order_write} scope (or an
     *       API key) to run ANY code path that reaches this method.</li>
     *   <li><b>Domain rule</b>: {@code cancel()} itself rejects shipped/delivered
     *       or already-cancelled orders.</li>
     *   <li><b>Idempotency</b>: the second cancel is an error, never a silent lie.</li>
     * </ol>
     * The confirmation token enforced at the protocol layer (the MCP tool) is an
     * ergonomic guard rail that stops accidental calls; it protects against a
     * model misfiring, NOT against a determined attacker - which is why it is
     * NOT the only control.
     */
    @Transactional
    @PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')")
    @Timed(value = "order.cancel", description = "Time to cancel an order",
            percentiles = 0.95)
    public Order cancelOrder(Long orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
        order.cancel();
        return orders.save(order);
    }

    @Transactional
    @PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')")
    @Timed(value = "order.confirm", description = "Time to confirm an order",
            percentiles = 0.95)
    public Order confirmOrder(Long orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
        order.confirm();
        Order saved = orders.save(order);
        OrderStatusChangedMessage msg = new OrderStatusChangedMessage(
                UUID.randomUUID().toString(), saved.getId(), saved.getOrderNumber(),
                OrderStatus.PLACED.name(), OrderStatus.CONFIRMED.name(), LocalDateTime.now());
        outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(saved.getId()),
                "OrderStatusChangedMessage", writeJson(msg)));
        return saved;
    }

    @Transactional
    @PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')")
    @Timed(value = "order.ship", description = "Time to ship an order",
            percentiles = 0.95)
    public Order shipOrder(Long orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
        order.ship();
        Order saved = orders.save(order);
        OrderStatusChangedMessage msg = new OrderStatusChangedMessage(
                UUID.randomUUID().toString(), saved.getId(), saved.getOrderNumber(),
                OrderStatus.CONFIRMED.name(), OrderStatus.SHIPPED.name(), LocalDateTime.now());
        outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(saved.getId()),
                "OrderStatusChangedMessage", writeJson(msg)));
        return saved;
    }
}
