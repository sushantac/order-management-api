package com.company.orderapi.domain.service;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Payment;
import com.company.orderapi.domain.PaymentMethod;
import com.company.orderapi.domain.PaymentStatus;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    public OrderService(CustomerRepository customers, ProductRepository products,
                        OrderRepository orders, PaymentGateway paymentGateway) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.paymentGateway = paymentGateway;
    }

    /** One requested order line: product + quantity (DTOs arrive in PR #21). */
    public record OrderLine(Long productId, int quantity) {
    }

    @Transactional
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 5,
            backoff = @Backoff(delay = 20))
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
        return order;
    }
}
