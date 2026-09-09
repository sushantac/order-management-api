package com.company.orderapi.integration;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.listener.OrderBusinessListener;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #18 integration tests: a custom @EntityListeners class on Order.
 *
 * <ul>
 *   <li>@PostPersist fires on every insert (event-publishing hook, counted);</li>
 *   <li>@PreUpdate enforces a business rule before the UPDATE is flushed;</li>
 *   <li>the inherited AuditingEntityListener still runs alongside (audit fields
 *       are populated) - listeners compose.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=EntityListenerIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class EntityListenerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @BeforeEach
    void resetListenerCounter() {
        OrderBusinessListener.resetPostPersistCounter();
    }

    @Test
    void postPersistHookFiresAndAuditingStillPopulates() {
        Customer customer = new Customer("listener1@example.com", "Listener One");
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("10.00"));
        customer.addOrder(order);
        em.persist(customer);
        em.flush();

        // The custom listener's @PostPersist fired for this insert.
        assertThat(OrderBusinessListener.postPersistCalls()).isEqualTo(1);

        // And the inherited AuditingEntityListener still ran (listeners compose).
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getCreatedBy()).isEqualTo("system");
    }

    @Test
    void preUpdateBusinessRuleRejectsShippingWithoutAddress() {
        Customer customer = new Customer("listener2@example.com", "Listener Two");
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("20.00"));
        customer.addOrder(order);
        em.persist(customer);
        em.flush();

        order.setStatus(OrderStatus.SHIPPED); // no shippingAddress set

        assertThatThrownBy(() -> em.flush())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED without a shipping address");
    }
}
