package com.company.orderapi.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PR #41 - the {@link Order#cancel()} state machine, tested directly on the
 * entity (the rule must hold for EVERY mutation path, not just the MCP tool).
 */
class OrderStatusTransitionTest {

    @Test
    void placedOrderCanBeCancelled() {
        Order order = new Order(mock(Customer.class), OrderStatus.PLACED, new BigDecimal("19.99"));

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void confirmedOrderCanBeCancelled() {
        Order order = new Order(mock(Customer.class), OrderStatus.CONFIRMED, new BigDecimal("19.99"));

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void shippedOrderCannotBeCancelled() {
        Order order = new Order(mock(Customer.class), OrderStatus.SHIPPED, new BigDecimal("19.99"));

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be cancelled once SHIPPED");
    }

    @Test
    void deliveredOrderCannotBeCancelled() {
        Order order = new Order(mock(Customer.class), OrderStatus.DELIVERED, new BigDecimal("19.99"));

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be cancelled once DELIVERED");
    }

    @Test
    void cancellingTwiceIsAnIdempotencyErrorNotASilentNoOp() {
        Order order = new Order(mock(Customer.class), OrderStatus.PLACED, new BigDecimal("19.99"));
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already cancelled");
    }
}