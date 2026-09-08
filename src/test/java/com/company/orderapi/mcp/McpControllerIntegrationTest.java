package com.company.orderapi.mcp;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #36 integration tests: the MCP JSON-RPC surface and its read-only tools.
 *
 * <p>Security is disabled here (exercised in SecurityIntegrationTest): a
 * production MCP caller must present a valid bearer token / API key, exactly
 * like any other endpoint behind {@code anyRequest().authenticated()}.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "integration.database.tag=McpControllerIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "app.security.enabled=false"
})
class McpControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String EMAIL_MUST_NOT_LEAK = "mcp-leak-check@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    // ---------- protocol handshake ----------

    @Test
    void initializeReturnsProtocolVersionAndCapabilities() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize",
                                 "params":{"protocolVersion":"2024-11-05",
                                  "capabilities":{},"clientInfo":{"name":"test"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2024-11-05"))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
                .andExpect(jsonPath("$.result.serverInfo.name")
                        .value("order-management-api-mcp"));
    }

    @Test
    void toolsListExposesOnlyReadOnlyTools() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"discover","method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("discover"))
                .andExpect(jsonPath("$.result.tools", hasSize(3)))
                .andExpect(jsonPath("$.result.tools[0].name").value("api_health"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"))
                .andExpect(jsonPath("$.result.tools[1].name").value("order_status"))
                .andExpect(jsonPath("$.result.tools[1].description").isNotEmpty())
                .andExpect(jsonPath("$.result.tools[2].name").value("product_search"));
    }
    // ---------- tool calls ----------

    @Test
    void productSearchReturnsSeededProductsAndHonoursQuery() throws Exception {
        productRepository.saveAndFlush(new Product("Widget Deluxe", new BigDecimal("29.99"), 7));
        productRepository.saveAndFlush(new Product("Gadget Mini", new BigDecimal("4.50"), 42));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                                 "params":{"name":"product_search",
                                  "arguments":{"query":"widget","maxResults":5}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text", containsString("Widget Deluxe")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("29.99")))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value(not(containsString("Gadget Mini"))));
    }

    @Test
    void orderStatusReturnsPublicFactsAndNeverCustomerPii() throws Exception {
        Order order = savedOrder();

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                                 "params":{"name":"order_status","arguments":{"orderId":ORDER_ID}}}
                                """
                                .replace("ORDER_ID", String.valueOf(order.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("Order " + order.getId())))
                .andExpect(jsonPath("$.result.content[0].text", containsString("status PLACED")))
                .andExpect(jsonPath("$.result.content[0].text", containsString(order.getOrderNumber())))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value(not(containsString(EMAIL_MUST_NOT_LEAK))));
    }

    @Test
    void orderStatusForUnknownOrderReturnsToolErrorNotCrash() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                                 "params":{"name":"order_status",
                                  "arguments":{"orderId":999999999}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text", containsString("Unknown order id 999999999")));
    }

    @Test
    void invalidToolArgumentsAreReported() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                                 "params":{"name":"order_status","arguments":{"orderId":"abc"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text", containsString("orderId must be an integer")));
    }

    @Test
    void apiHealthNeedsNoArgumentsOrDatabase() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":6,"method":"tools/call",
                                 "params":{"name":"api_health"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("OK")));
    }
    // ---------- JSON-RPC error handling & batch ----------

    @Test
    void unknownMethodReturnsJsonRpcError() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":7,"method":"prompts/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.message", containsString("prompts/list")));
    }

    @Test
    void toolsCallWithoutNameIsInvalidParams() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void notificationsGetNoErrorAndBatchReturnsOneResponsePerRequest() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","method":"notifications/initialized"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                 {"jsonrpc":"2.0","id":10,"method":"ping"},
                                 {"jsonrpc":"2.0","id":11,"method":"tools/list"}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].result").isMap())
                .andExpect(jsonPath("$[1].result.tools", hasSize(3)));
    }

    // ---------- helpers ----------

    private Order savedOrder() {
        Customer customer = customerRepository.saveAndFlush(
                new Customer(EMAIL_MUST_NOT_LEAK, "MCP Leak Check"));
        return orderRepository.saveAndFlush(
                new Order(customer, OrderStatus.PLACED, new BigDecimal("19.99")));
    }


}
