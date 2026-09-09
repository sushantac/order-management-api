package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #11 integration tests: every {@code @Query} flavour in
 * {@link OrderRepository}.
 *
 * <p>Seed data: customer A has PLACED orders of 10 / 50 / 100 and a CANCELLED
 * order of 1000; customer B has a single PLACED order of 5.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=CustomQueryIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class CustomQueryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void complexJpqlFiltersAndOrders() {
        Customer alice = persist(alice()); // 10 / 50 / 100 PLACED + 1000 CANCELLED

        // recent PLACED orders >= 20 => the 50 and 100 orders.
        List<Order> result = orderRepository.findRecentOrdersByCustomer(
                alice.getId(), OrderStatus.PLACED, new BigDecimal("20.00"));

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(Order::getTotalAmount)
                .containsExactlyInAnyOrder(new BigDecimal("50.00"), new BigDecimal("100.00"));
    }

    @Test
    void nativeSqlAggregationWithProjection() {
        persist(alice());
        persist(bob()); // a single 5.00 order

        List<OrderRepository.CustomerSpend> spend = orderRepository.findCustomerSpendNative();

        assertThat(spend).hasSize(2);
        // Alice: 10+50+100+1000 = 1160.00 - more than Bob, so she comes first.
        assertThat(spend.get(0).getEmail()).isEqualTo("alice@example.com");
        assertThat(spend.get(0).getTotalSpent())
                .isEqualByComparingTo(new BigDecimal("1160.00"));
        assertThat(spend.get(1).getEmail()).isEqualTo("bob@example.com");
        assertThat(spend.get(1).getTotalSpent()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void paginationSlicesTheResultSet() {
        persist(alice()); // 4 orders
        persist(bob());   // 1 order  -> 5 in total

        Page<Order> firstPage = orderRepository.findOrdersPaged(PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(2);

        Page<Order> lastPage = orderRepository.findOrdersPaged(PageRequest.of(2, 2));
        assertThat(lastPage.getContent()).hasSize(1);
    }

    @Test
    void spelExpressionsBuildAnOptionalFilter() {
        persist(alice());
        persist(bob());

        // min only: Alice's 50, 100, 1000
        assertThat(orderRepository.findByAmountRange(
                new OrderRepository.AmountRange(new BigDecimal("50.00"), null)))
                .extracting(Order::getTotalAmount)
                .containsExactlyInAnyOrder(new BigDecimal("50.00"), new BigDecimal("100.00"),
                        new BigDecimal("1000.00"));

        // max only (<= 8): just Bob's 5
        assertThat(orderRepository.findByAmountRange(
                new OrderRepository.AmountRange(null, new BigDecimal("8.00"))))
                .extracting(Order::getTotalAmount)
                .containsExactly(new BigDecimal("5.00"));

        // both bounds: Alice's 50 and 100 (10 too small, 1000 too large, Bob out)
        assertThat(orderRepository.findByAmountRange(
                new OrderRepository.AmountRange(new BigDecimal("20.00"), new BigDecimal("200.00"))))
                .extracting(Order::getTotalAmount)
                .containsExactlyInAnyOrder(new BigDecimal("50.00"), new BigDecimal("100.00"));

        // no bounds: all 5 orders
        assertThat(orderRepository.findByAmountRange(
                new OrderRepository.AmountRange(null, null)))
                .hasSize(5);
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

    private Customer persist(Customer customer) {
        em.persist(customer);
        em.flush();
        return customer;
    }
}
