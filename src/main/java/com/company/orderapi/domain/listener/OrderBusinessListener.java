package com.company.orderapi.domain.listener;

import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PR #18 - a CUSTOM entity listener registered on {@link Order} via
 * {@code @EntityListeners}.
 *
 * <p>Entity listeners keep persistence cross-cutting concerns OUT of the entity
 * and OUT of services:
 * <ul>
 *   <li>{@code @PostPersist} is a hook where an "order placed" event would be
 *       published (e.g. into Spring's {@code ApplicationEventPublisher} or an
 *       outbox table). Here it increments a counter so tests can observe that
 *       the hook fired - decoupling the event from the insert.</li>
 *   <li>{@code @PreUpdate} enforces a business rule that depends on the new
 *       state before the UPDATE is flushed.</li>
 * </ul>
 *
 * <p>Hibernate instantiates this class itself (it is NOT a Spring bean), so
 * cross-observation for tests goes through a static counter; in production the
 * PostPersist hook would call an injected publisher obtained via the JVM-wide
 * holder pattern or an outbox.
 */
public class OrderBusinessListener {

    private static final Logger log = LoggerFactory.getLogger(OrderBusinessListener.class);

    /** Observability for tests: how many orders were persisted via this JVM. */
    private static final AtomicLong POST_PERSIST_CALLS = new AtomicLong();

    @PostPersist
    public void onOrderPersisted(Order order) {
        POST_PERSIST_CALLS.incrementAndGet();
        log.debug("Order {} persisted - publishing 'order placed' style event", order.getId());
        // In production: publish to ApplicationEventPublisher / outbox here.
    }

    @PreUpdate
    public void enforceBusinessRules(Order order) {
        if (order.getStatus() == OrderStatus.SHIPPED && order.getShippingAddress() == null) {
            throw new IllegalStateException(
                    "Order " + order.getId() + " cannot be SHIPPED without a shipping address");
        }
    }

    /** Test hook: number of @PostPersist invocations in this JVM. */
    public static long postPersistCalls() {
        return POST_PERSIST_CALLS.get();
    }

    public static void resetPostPersistCounter() {
        POST_PERSIST_CALLS.set(0);
    }
}
