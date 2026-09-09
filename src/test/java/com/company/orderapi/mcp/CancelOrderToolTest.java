package com.company.orderapi.mcp;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * PR #41 - the guarded write tool. Verifies the confirmation gate and that the
 * real mutation only ever happens through {@link OrderService} (which carries
 * the authZ scope check and the {@link Order#cancel()} domain rules).
 */
class CancelOrderToolTest {

    private OrderService orderService;
    private CancelOrderTool tool;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        tool = new CancelOrderTool(orderService);
    }

    @Test
    void refusesWithoutConfirmationAndNeverCallsTheService() {
        assertThatThrownBy(() -> tool.execute(Map.of("orderId", 7L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed must be exactly true");
        assertThatThrownBy(() -> tool.execute(Map.of("orderId", 7L, "confirmed", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed must be exactly true");

        verify(orderService, never()).cancelOrder(anyLong());
    }

    @Test
    void confirmedTrueCancelsThroughTheServiceAndReturnsOnlyPublicFacts() {
        Order cancelled = new Order(mock(Customer.class), OrderStatus.PLACED, new BigDecimal("19.99"));
        when(orderService.cancelOrder(7L)).thenReturn(cancelled);

        String result = tool.execute(Map.of("orderId", 7L, "confirmed", true));

        verify(orderService).cancelOrder(7L);
        assertThat(result).contains("cancelled (status CANCELLED)");
        assertThat(result).doesNotContain("customer").doesNotContainIgnoringCase("email");
    }

    @Test
    void badOrderIdIsAToolErrorNotACrash() {
        assertThatThrownBy(() -> tool.execute(Map.of("orderId", "abc", "confirmed", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId must be an integer");
        assertThatThrownBy(() -> tool.execute(Map.of("confirmed", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId is required");
        verify(orderService, never()).cancelOrder(anyLong());
    }

    @Test
    void writeToolSchemaRequiresOrderIdAndConfirmation() {
        assertThat(tool.name()).isEqualTo("cancel_order");
        assertThat(tool.description()).contains("MUTATES DATA");
        assertThat(tool.inputSchema().required()).containsExactly("orderId", "confirmed");
    }
}