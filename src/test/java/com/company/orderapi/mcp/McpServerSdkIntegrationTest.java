package com.company.orderapi.mcp;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #37 integration tests: the MCP server built on the OFFICIAL MCP Java SDK
 * Spring transport, exercised end-to-end with the official MCP Java client.
 *
 * <p>The app runs on a random port (real Tomcat), the client speaks the real
 * Streamable-HTTP transport to {@code POST /mcp}, and tools are read against
 * real Postgres data. Security is disabled here (exercised in
 * SecurityIntegrationTest); production MCP callers must authenticate exactly
 * like any other endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "integration.database.tag=McpServerSdkIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "app.security.enabled=false"
})
class McpServerSdkIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String EMAIL_MUST_NOT_LEAK = "mcp-leak-check@example.com";

    @LocalServerPort
    private int port;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    private McpSyncClient newClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport
                        .builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .build())
                .clientInfo(new McpSchema.Implementation("order-management-api-test", "1.0.0"))
                .build();
    }

    @Test
    void initializeAndListToolsExposesOnlyReadOnlyTools() {
        try (McpSyncClient client = newClient()) {
            McpSchema.InitializeResult init = client.initialize();
            assertThat(init).isNotNull();
            assertThat(client.getServerInfo().name()).isEqualTo("order-management-api-mcp");

            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools)
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("api_health", "order_status", "product_search");

            McpSchema.Tool orderStatus = tools.stream()
                    .filter(t -> t.name().equals("order_status"))
                    .findFirst().orElseThrow();
            assertThat(orderStatus.description()).contains("order number", "status");
            assertThat(orderStatus.inputSchema().type()).isEqualTo("object");
            assertThat(orderStatus.inputSchema().required()).containsExactly("orderId");

            McpSchema.Tool apiHealth = tools.stream()
                    .filter(t -> t.name().equals("api_health"))
                    .findFirst().orElseThrow();
            assertThat(apiHealth.inputSchema().type()).isEqualTo("object");
            assertThat(apiHealth.inputSchema().properties()).isEmpty();
        }
    }
    @Test
    void productSearchReturnsSeededProductsAndHonoursQuery() {
        productRepository.saveAndFlush(new Product("Widget Deluxe", new BigDecimal("29.99"), 7));
        productRepository.saveAndFlush(new Product("Gadget Mini", new BigDecimal("4.50"), 42));

        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = call(client, "product_search",
                    Map.of("query", "widget", "maxResults", 5));

            assertThat(Boolean.FALSE).isEqualTo(result.isError());
            String text = textOf(result);
            assertThat(text).contains("Widget Deluxe").contains("29.99");
            assertThat(text).doesNotContain("Gadget Mini");
        }
    }

    @Test
    void orderStatusReturnsPublicFactsAndNeverCustomerPii() {
        Order order = savedOrder();

        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = call(client, "order_status",
                    Map.of("orderId", order.getId()));

            assertThat(Boolean.FALSE).isEqualTo(result.isError());
            String text = textOf(result);
            assertThat(text)
                    .contains("Order " + order.getId())
                    .contains("status PLACED")
                    .contains(order.getOrderNumber());
            assertThat(text).doesNotContain(EMAIL_MUST_NOT_LEAK);
        }
    }

    @Test
    void orderStatusForUnknownOrderReturnsToolErrorNotCrash() {
        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = call(client, "order_status",
                    Map.of("orderId", 999999999L));

            assertThat(Boolean.TRUE).isEqualTo(result.isError());
            assertThat(textOf(result)).contains("Unknown order id 999999999");
        }
    }

    @Test
    void invalidToolArgumentsAreReportedAsToolErrors() {
        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = call(client, "order_status",
                    Map.of("orderId", "abc"));

            assertThat(Boolean.TRUE).isEqualTo(result.isError());
            assertThat(textOf(result)).contains("orderId must be an integer");
        }
    }

    @Test
    void apiHealthNeedsNoArgumentsOrDatabase() {
        try (McpSyncClient client = newClient()) {
            client.initialize();
            McpSchema.CallToolResult result = call(client, "api_health", Map.of());

            assertThat(Boolean.FALSE).isEqualTo(result.isError());
            assertThat(textOf(result)).contains("OK").contains("order-management-api-mcp");
        }
    }

    @Test
    void statelessTransportRequiresStreamingAcceptAndAcksNotifications() throws Exception {
        String initializeBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                  "clientInfo":{"name":"raw","version":"1.0"}}}
                """;
        // Official stateless transport serves JSON-RPC over the MCP Streamable
        // HTTP transport, which demands both Accept media types.
        HttpResponse<String> wrongAccept = post("/mcp", initializeBody, "application/json");
        assertThat(wrongAccept.statusCode()).isEqualTo(400);

        HttpResponse<String> ok = post("/mcp", initializeBody,
                "application/json, text/event-stream");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"jsonrpc\":\"2.0\"").contains("\"id\":1");

        HttpResponse<String> notification = post("/mcp", """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """, "application/json, text/event-stream");
        assertThat(notification.statusCode()).isEqualTo(202);
    }

    private HttpResponse<String> post(String path, String body, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }


    private McpSchema.CallToolResult call(McpSyncClient client, String name,
                                          Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(name, arguments));
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

    private Order savedOrder() {
        Customer customer = customerRepository.saveAndFlush(
                new Customer(EMAIL_MUST_NOT_LEAK, "MCP Leak Check"));
        return orderRepository.saveAndFlush(
                new Order(customer, OrderStatus.PLACED, new BigDecimal("19.99")));
    }
}
