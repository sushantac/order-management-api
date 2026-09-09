package com.company.orderapi.mcp;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #41 - the guarded WRITE tool end to end: the write tool exists only
 * because this test EXPLICITLY enables it ({@code app.mcp.write-tool.enabled}),
 * every {@code cancel_order} call still needs {@code confirmed=true}, and the
 * mutation is visible in the committed database.
 *
 * <p>Security is disabled here (exercised in SecurityIntegrationTest); the
 * service-level {@code @PreAuthorize(order_write)} short-circuits via the
 * disabled flag exactly as it does for every other guard in the app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "integration.database.tag=McpServerWriteToolIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.security.enabled=false",
        "app.mcp.write-tool.enabled=true"
})
class McpServerWriteToolIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String EMAIL_PREFIX = "mcp-write-leak-check+";

    @LocalServerPort
    private int port;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OrderRepository orderRepository;

    private McpSyncClient newClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport
                        .builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .build())
                .clientInfo(new McpSchema.Implementation("order-management-api-write-test", "1.0.0"))
                .build();
    }

    @Test
    void cancelOrderWithConfirmationCancelsAndCommitsNoPiiInReply() {
        Order order = savedPlacedOrder();

        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "cancel_order", Map.of("orderId", order.getId(), "confirmed", true)));

            assertThat(Boolean.FALSE).isEqualTo(result.isError());
            String text = textOf(result);
            assertThat(text).contains("cancelled (status CANCELLED)");
            assertThat(text).doesNotContain(order.getCustomer().getEmail());

            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Test
    void cancelOrderWithoutConfirmationIsRefusedAndLeavesTheOrderPlaced() {
        Order order = savedPlacedOrder();

        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "cancel_order", Map.of("orderId", order.getId(), "confirmed", false)));

            assertThat(Boolean.TRUE).isEqualTo(result.isError());
            assertThat(textOf(result)).contains("confirmed must be exactly true");
            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PLACED);
        }
    }

    @Test
    void toolsListShowsTheWriteToolWhenExplicitlyEnabledAndItsSchemaRequiresConfirmation() {
        try (McpSyncClient client = newClient()) {
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();

            McpSchema.Tool cancelOrder = tools.stream()
                    .filter(t -> t.name().equals("cancel_order"))
                    .findFirst().orElseThrow();
            assertThat(cancelOrder.description()).contains("MUTATES DATA");
            assertThat(cancelOrder.inputSchema().required()).containsExactly("orderId", "confirmed");
        }
    }

    private Order savedPlacedOrder() {
        // the shared container is reused across tests in this class, so the
        // email must be unique per invocation (unique constraint uq_customers_email)
        Customer customer = customerRepository.saveAndFlush(
                new Customer(EMAIL_PREFIX + System.nanoTime() + "@example.com",
                        "MCP Write Leak Check"));
        return orderRepository.saveAndFlush(
                new Order(customer, OrderStatus.PLACED, new BigDecimal("19.99")));
    }

    private String textOf(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            }
        }
        return text.toString();
    }
}