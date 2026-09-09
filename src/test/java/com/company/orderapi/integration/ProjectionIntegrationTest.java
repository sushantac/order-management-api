package com.company.orderapi.integration;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.repository.CustomerOrderTotal;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
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
 * PR #17 integration tests: DTO projections vs full entity queries.
 *
 * <p>Projections select ONLY the needed columns and never load the entity
 * graph (no lazy associations, no audit baggage). Two flavours are covered:
 * interface-based (Spring Data proxies) and class-based JPQL constructor
 * expressions (which support aggregates).
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=ProjectionIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class ProjectionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void interfaceProjectionReturnsOnlyTheSelectedColumns() {
        persist(alice());
        persist(bob());

        List<CustomerRepository.CustomerNameProjection> names =
                customerRepository.findAllCustomerNameProjections();

        assertThat(names).hasSize(2);
        assertThat(names).extracting(CustomerRepository.CustomerNameProjection::getFullName)
                .containsExactly("Alice", "Bob"); // ordered by fullName
        assertThat(names.get(0).getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void classProjectionViaConstructorExpressionAggregates() {
        persist(alice()); // 1160.00 across 4 orders
        persist(bob());   // 5.00 across 1 order

        List<CustomerOrderTotal> totals = orderRepository.findCustomerOrderTotals();

        assertThat(totals).hasSize(2);
        assertThat(totals.get(0).getEmail()).isEqualTo("alice@example.com");
        assertThat(totals.get(0).getTotalSpent()).isEqualByComparingTo("1160.00");
        assertThat(totals.get(1).getEmail()).isEqualTo("bob@example.com");
        assertThat(totals.get(1).getTotalSpent()).isEqualByComparingTo("5.00");
    }

    @Test
    void entityQueryAndProjectionProduceTheSameInformation() {
        persist(alice());
        persist(bob());

        // Full entity load (all columns + object graph plumbing)...
        List<Customer> entities = customerRepository.findAll();

        // ...vs a projection that only touches two columns.
        List<CustomerRepository.CustomerNameProjection> projections =
                customerRepository.findAllCustomerNameProjections();

        assertThat(entities).hasSize(projections.size());
        for (int i = 0; i < projections.size(); i++) {
            assertThat(projections.get(i).getEmail())
                    .isEqualTo(entities.get(i).getEmail());
            assertThat(projections.get(i).getFullName())
                    .isEqualTo(entities.get(i).getFullName());
        }
    }

    // --- fixtures -----------------------------------------------------------

    private Customer alice() {
        Customer alice = new Customer("alice@example.com", "Alice");
        alice.addOrder(new Order(alice, OrderStatus.PLACED, new BigDecimal("10.00")));
        alice.addOrder(new Order(alice, OrderStatus.PLACED, new BigDecimal("50.00")));
        alice.addOrder(new Order(alice, OrderStatus.PLACED, new BigDecimal("100.00")));
        Order cancelled = new Order(alice, OrderStatus.PLACED, new BigDecimal("1000.00"));
        cancelled.setStatus(OrderStatus.CANCELLED);
        alice.addOrder(cancelled);
        return alice;
    }

    private Customer bob() {
        Customer bob = new Customer("bob@example.com", "Bob");
        bob.addOrder(new Order(bob, OrderStatus.PLACED, new BigDecimal("5.00")));
        return bob;
    }

    private void persist(Customer customer) {
        em.persist(customer);
        em.flush();
    }
}
