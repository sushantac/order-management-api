package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.CustomerSpecifications;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #12 integration tests: dynamic queries with JPA Specifications.
 *
 * <p>Fixture:
 * <ul>
 *   <li>Alice - PLACED 100.00 + CANCELLED 50.00</li>
 *   <li>Bob   - PLACED 5.00</li>
 *   <li>Carol - no orders</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=SpecificationIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
@Transactional
class SpecificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void seedAllCustomers() {
        persist(alice());
        persist(bob());
        persist(carol());
    }

    @Test
    void nameFilterIsCaseInsensitiveAndNullSafe() {
        // "a" matches alice + carol (case-insensitive substring)
        assertThat(customerRepository.findAll(CustomerSpecifications.nameContains("a")))
                .extracting(Customer::getFullName)
                .containsExactlyInAnyOrder("Alice", "Carol");

        // no name filter -> everyone
        assertThat(customerRepository.findAll(CustomerSpecifications.nameContains(null)))
                .hasSize(3);
    }

    @Test
    void statusSpecificationJoinsThroughOrdersWithoutDuplicates() {
        List<Customer> placed = customerRepository.findAll(
                CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED));

        assertThat(placed)
                .extracting(Customer::getFullName)
                .containsExactlyInAnyOrder("Alice", "Bob"); // Carol has no orders

        List<Customer> cancelled = customerRepository.findAll(
                CustomerSpecifications.hasOrderStatus(OrderStatus.CANCELLED));
        assertThat(cancelled).extracting(Customer::getFullName).containsExactly("Alice");
    }

    @Test
    void amountSpecificationFiltersOnOrderTotals() {
        // Alice's orders are 100 and 50 (both >= 10); Bob's is only 5.
        List<Customer> bigSpenders = customerRepository.findAll(
                CustomerSpecifications.hasOrderTotalAtLeast(new BigDecimal("10.00")));

        assertThat(bigSpenders).extracting(Customer::getFullName).containsExactly("Alice");
    }

    @Test
    void specificationsComposeLikePredicates() {
        Specification<Customer> search =
                CustomerSpecifications.nameContains("a")
                        .and(CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED));

        // "a" = Alice + Carol; with orders = Alice + Bob; intersection = Alice.
        assertThat(customerRepository.findAll(search))
                .extracting(Customer::getFullName)
                .containsExactly("Alice");

        // count() honours the same Specification.
        assertThat(customerRepository.count(
                CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED)))
                .isEqualTo(2);
    }

    // --- fixture -------------------------------------------------------------

    private Customer persist(Customer customer) {
        em.persist(customer);
        em.flush();
        return customer;
    }

    private Customer alice() {
        Customer alice = new Customer("alice@example.com", "Alice");
        alice.addOrder(new Order(alice, OrderStatus.PLACED, new BigDecimal("100.00")));
        Order cancelled = new Order(alice, OrderStatus.PLACED, new BigDecimal("50.00"));
        cancelled.setStatus(OrderStatus.CANCELLED);
        alice.addOrder(cancelled);
        return alice;
    }

    private Customer bob() {
        Customer bob = new Customer("bob@example.com", "Bob");
        bob.addOrder(new Order(bob, OrderStatus.PLACED, new BigDecimal("5.00")));
        return bob;
    }

    private Customer carol() {
        return new Customer("carol@example.com", "Carol");
    }
}
