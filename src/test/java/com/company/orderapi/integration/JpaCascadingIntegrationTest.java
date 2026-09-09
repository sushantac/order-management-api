package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Address;
import com.company.orderapi.domain.Category;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Payment;
import com.company.orderapi.domain.PaymentMethod;
import com.company.orderapi.domain.Product;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #4 integration tests: cascading strategies.
 *
 * <p>These tests prove that a cascade configured on an annotation actually
 * propagates the operation through the object graph - they assert the ROWS
 * created on disk after flushing ONLY the root entity. Native queries run on
 * the same transaction so they see the flushed but uncommitted data.
 *
 * <p>Cascades under test (from the spec):
 * <ul>
 *   <li>{@code PERSIST} Customer → Addresses / Orders</li>
 *   <li>{@code ALL}     Order → OrderItems (+ orphanRemoval DELETE)</li>
 *   <li>{@code ALL}     Order → Payment</li>
 *   <li>{@code MERGE}   Product → Categories</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=JpaCascadingIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class JpaCascadingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Test
    void persistingOnlyTheCustomerCreatesTheWholeOrderGraph() {
        // Product is a root of its own - it needs an id before order_items can
        // reference it, so it gets its own flush first.
        Product widget = new Product("Widget", new BigDecimal("4.99"), 100);
        em.persist(widget);
        em.flush();

        Customer customer = new Customer("cascade@example.com", "Cascade Buyer");
        // Customer -> Addresses: cascade PERSIST
        Address address = new Address(customer, "10 Cascade Street", "Sydney", "AU");
        customer.addAddress(address);

        // Customer -> Orders: cascade PERSIST; Order -> Items + Payment: ALL
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("9.98"));
        order.addItem(new OrderItem(widget, 2, new BigDecimal("4.99")));
        order.setPayment(new Payment(order, new BigDecimal("9.98"), PaymentMethod.CREDIT_CARD));
        customer.addOrder(order);

        // ONLY the customer is persisted - every cascade below must fire.
        em.persist(customer);
        em.flush();

        assertRowCount("addresses", "customer_id", customer.getId(), 1);
        assertRowCount("orders", "customer_id", customer.getId(), 1);
        assertRowCount("order_items", "order_id", order.getId(), 1);
        assertRowCount("payments", "order_id", order.getId(), 1);
    }

    @Test
    void cascadeAllMakesOrphanRemovalDeleteItems() {
        Product widget = new Product("Gadget", new BigDecimal("3.50"), 40);
        em.persist(widget);
        em.flush();

        Customer customer = new Customer("orphan@example.com", "Orphan Tester");
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("3.50"));
        order.addItem(new OrderItem(widget, 1, new BigDecimal("3.50")));
        customer.addOrder(order);
        em.persist(customer);
        em.flush();
        long orderId = order.getId();

        // Fresh state: remove every item from the order -> orphanRemoval must
        // DELETE the rows (proven in PR #3 to need cascade, now present).
        em.clear();
        Order managed = em.find(Order.class, orderId);
        managed.getItems().clear();
        em.flush();

        assertRowCount("order_items", "order_id", orderId, 0);
    }

    @Test
    void mergeCascadesToADetachedCategoryWithoutDuplicatingIt() {
        Category stationery = new Category("Office Supplies");
        em.persist(stationery);
        em.flush();
        em.clear(); // stationery is now DETACHED

        Product stapler = new Product("Stapler", new BigDecimal("2.50"), 300);
        stapler.addCategory(stationery); // references the detached category

        Product merged = em.merge(stapler); // MERGE cascade must re-attach category
        em.flush();

        // Exactly ONE category row survived - merge must not create a duplicate.
        Number categories = (Number) em.createNativeQuery("SELECT count(*) FROM categories")
                .getSingleResult();
        assertThat(categories.longValue()).isEqualTo(1);

        Object[] joinRow = (Object[]) em.createNativeQuery(
                        "SELECT product_id, category_id FROM product_categories WHERE product_id = ?1")
                .setParameter(1, merged.getId())
                .getSingleResult();
        assertThat(((Number) joinRow[0]).longValue()).isEqualTo(merged.getId());
        assertThat(((Number) joinRow[1]).longValue()).isEqualTo(stationery.getId());
    }

    private void assertRowCount(String table, String fkColumn, Long id, int expected) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM " + table + " WHERE " + fkColumn + " = ?1")
                .setParameter(1, id)
                .getSingleResult();
        assertThat(count.longValue())
                .as("rows in %s for %s = %d", table, fkColumn, id)
                .isEqualTo(expected);
    }
}
