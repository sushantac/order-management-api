package com.company.orderapi.messaging;

import com.company.orderapi.domain.Address;
import com.company.orderapi.domain.AddressType;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.service.OrderService;
import com.company.orderapi.domain.idempotency.IdempotencyKeyRepository;
import com.company.orderapi.domain.idempotency.IdempotencyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Consumes {@code cart.checkout.initiated} to place an order.
 *
 * <p>Consumer group is {@code order-api} (separate from the existing
 * {@code order-service} group that listens on {@code order-events}).
 *
 * <p>Deduplication: the eventId is used as the idempotency key against the
 * EXISTING {@code idempotency_keys} table (same pattern as the REST
 * Idempotency-Key header). If the event was already processed the listener
 * returns immediately (at-least-once safety).
 *
 * <p>Default address: if shippingAddress is absent, a placeholder address is
 * attached to the customer so the order entity constraints are satisfied.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CartCheckoutEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(CartCheckoutEventConsumer.class);

    private final OrderService orderService;
    private final CustomerRepository customers;
    private final IdempotencyKeyRepository idempotencyKeys;

    public CartCheckoutEventConsumer(OrderService orderService,
                                     CustomerRepository customers,
                                     IdempotencyKeyRepository idempotencyKeys) {
        this.orderService = orderService;
        this.customers = customers;
        this.idempotencyKeys = idempotencyKeys;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.cart-checkout-initiated}",
            groupId = "order-api")
    @Transactional
    public void onCheckout(CartCheckoutEvent event, Acknowledgment acknowledgment) {
        if (event == null || event.eventId() == null || event.userId() == null
                || event.items() == null || event.items().isEmpty()) {
            throw new IllegalArgumentException("Malformed cart checkout event: " + event);
        }

        // Deduplication: check the EXISTING idempotency_keys table.
        if (idempotencyKeys.existsByIdempotencyKey(event.eventId())) {
            log.info("Duplicate cart checkout event ignored: {}", event.eventId());
            acknowledgment.acknowledge();
            return;
        }

        log.info("Processing cart checkout: cartId={} userId={}", event.cartId(), event.userId());

        // Resolve customer: find or create by userId (mapped to a synthetic email).
        Customer customer = findOrCreateCustomer(event.userId());

        // Map cart items to order lines.
        List<OrderService.OrderLine> lines = event.items().stream()
                .map(item -> new OrderService.OrderLine(item.productId(), item.quantity()))
                .toList();

        // Place the order via the existing service.
        orderService.placeOrder(customer.getId(), lines);

        // Persist the idempotency record so duplicate deliveries are a no-op.
        idempotencyKeys.save(IdempotencyRecord.of(
                event.eventId(),
                "CART_CHECKOUT",
                "kafka/cart.checkout.initiated",
                200,
                "{\"status\":\"processed\"}"));

        log.info("Cart checkout processed: eventId={} userId={}", event.eventId(), event.userId());
        acknowledgment.acknowledge();
    }

    private Customer findOrCreateCustomer(Long userId) {
        String syntheticEmail = "user-" + userId + "@platform.local";
        return customers.findByEmail(syntheticEmail).orElseGet(() -> {
            Customer customer = new Customer(syntheticEmail, "User " + userId);
            Address defaultAddress = new Address(customer, "123 Default St",
                    "Default City", "US");
            defaultAddress.setAddressType(AddressType.SHIPPING);
            customer.addAddress(defaultAddress);
            return customers.save(customer);
        });
    }
}
