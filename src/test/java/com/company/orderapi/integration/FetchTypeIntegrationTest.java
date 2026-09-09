package com.company.orderapi.integration;

import com.company.orderapi.domain.Address;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #5 integration tests: LAZY vs EAGER fetching.
 *
 * <p>All associations in this project are mapped LAZY (the production default):
 * a "load entity" query must NOT drag in its neighbours. These tests prove it
 * with {@link PersistenceUnitUtil#isLoaded} (metadata-level truth, no guessing)
 * and count the actual JDBC statements via Hibernate {@link Statistics} to make
 * the cost of naive lazy access visible (the N+1 pattern PR #6 fixes).
 *
 * <p>SQL logging is switched on for this test class only
 * ({@code spring.jpa.show-sql=true}) so you can SEE the lazy SELECTs appear
 * exactly when a collection or many-to-one is touched.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.show-sql=true",
        "spring.jpa.properties.hibernate.format_sql=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // PR #5/#6 demonstrate raw LAZY behaviour - disable the global batch
        // fetching (PR #7) so the N+1 pattern stays visible in this class.
        "spring.jpa.properties.hibernate.default_batch_fetch_size=1",
        // L2 is disabled in tests except the dedicated cache test.
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class FetchTypeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void lazyOneToManyIsNotLoadedUntilAccessed() {
        Customer customer = new Customer("lazy@example.com", "Lazy Loader");
        customer.addAddress(new Address(customer, "1 Lazy Lane", "Melbourne", "AU"));
        customer.addAddress(new Address(customer, "2 Lazy Lane", "Melbourne", "AU"));
        em.persist(customer);
        em.flush();
        long customerId = customer.getId();

        em.clear();
        Customer loaded = em.find(Customer.class, customerId);

        // LAZY: the addresses SELECT must NOT have run yet ...
        assertThat(util().isLoaded(loaded, "addresses"))
                .as("LAZY collection stays uninitialized after find()")
                .isFalse();

        // ... and only now (when the code actually needs the data) it loads.
        assertThat(loaded.getAddresses()).hasSize(2);
        assertThat(util().isLoaded(loaded, "addresses"))
                .as("collection becomes initialized after access")
                .isTrue();
    }

    @Test
    void lazyManyToOneIsNotLoadedUntilAccessed() {
        Product gadget = new Product("Gadget", new BigDecimal("3.50"), 40);
        em.persist(gadget);
        em.flush();

        Customer customer = new Customer("lazy-mto@example.com", "Many To One");
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("3.50"));
        OrderItem line = new OrderItem(gadget, 1, new BigDecimal("3.50"));
        order.addItem(line);
        customer.addOrder(order);
        em.persist(customer);
        em.flush();
        long lineId = line.getId();

        em.clear();
        OrderItem item = em.find(OrderItem.class, lineId);

        // Same story for the many-to-one side (note: JPA's DEFAULT for
        // @ManyToOne is EAGER - we deliberately override it to LAZY).
        assertThat(util().isLoaded(item, "product"))
                .as("LAZY many-to-one stays uninitialized after find()")
                .isFalse();
        assertThat(item.getProduct().getName()).isEqualTo("Gadget");
        assertThat(util().isLoaded(item, "product")).isTrue();
    }

    @Test
    void naiveLazyTraversalProducesTheNPlusOnePattern() {
        for (int i = 1; i <= 4; i++) {
            Customer customer = new Customer("nplus" + i + "@example.com", "N+1 #" + i);
            customer.addAddress(new Address(customer, i + " Demo Street", "Sydney", "AU"));
            em.persist(customer);
        }
        em.flush();
        em.clear();

        Statistics stats = statistics();
        stats.clear();

        // ONE query for all customers ...
        List<Customer> customers =
                em.createQuery("select c from Customer c", Customer.class).getResultList();
        long queriesAfterList = stats.getPrepareStatementCount();
        assertThat(queriesAfterList).isEqualTo(1);

        // ... then ONE EXTRA query per customer for its (lazy) addresses.
        for (Customer customer : customers) {
            customer.getAddresses().size();
        }
        long queriesAfterTraversal = stats.getPrepareStatementCount();
        long extraQueries = queriesAfterTraversal - queriesAfterList;

        // 1 (list) + N (addresses) = the N+1 problem, demonstrated and counted.
        assertThat(extraQueries)
                .as("each of the %d customers triggers its own lazy addresses query", customers.size())
                .isEqualTo(customers.size());
    }

    private PersistenceUnitUtil util() {
        return entityManagerFactory.getPersistenceUnitUtil();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
