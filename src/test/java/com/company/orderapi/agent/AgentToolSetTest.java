package com.company.orderapi.agent;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.mcp.ApiHealthTool;
import com.company.orderapi.mcp.DocsSearchTool;
import com.company.orderapi.mcp.OrderStatusTool;
import com.company.orderapi.mcp.ProductSearchTool;
import com.company.orderapi.rag.DocumentIngestionService;
import com.company.orderapi.rag.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests the server-side agent's function-calling surface: that each {@code @Tool}
 * method maps to the MCP tool names and delegates to the real read-only tools.
 *
 * <p>Pure unit test - repositories, the RAG service and the docs ingestion
 * service are mocked; the tools themselves are the real components.
 */
class AgentToolSetTest {

    private ProductRepository productRepository;
    private OrderRepository orderRepository;
    private RagService ragService;
    private DocumentIngestionService ingestionService;
    private AgentToolSet toolSet;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        orderRepository = mock(OrderRepository.class);
        ragService = mock(RagService.class);
        ingestionService = mock(DocumentIngestionService.class);

        toolSet = new AgentToolSet(
                new ApiHealthTool(),
                new ProductSearchTool(productRepository),
                new OrderStatusTool(orderRepository),
                new DocsSearchTool(ragService, ingestionService));
    }

    @Test
    void registersFourToolCallbacksWithTheMcpToolNames() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(toolSet)
                .build();

        Set<String> names = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "api_health", "product_search", "order_status", "docs_search");
    }

    @Test
    void apiHealthReturnsOk() {
        assertThat(toolSet.apiHealth()).startsWith("OK | order-management-api-mcp");
    }

    @Test
    void productSearchDelegatesToTheCatalogueTool() {
        when(productRepository.findAll(any(Sort.class))).thenReturn(List.of(
                new Product("Alphanumeric Keyboard", new BigDecimal("45.00"), 12),
                new Product("Compact Mouse", new BigDecimal("19.50"), 3)));

        String result = toolSet.productSearch("comp", 10);

        assertThat(result).contains("Compact Mouse").contains("19.50").contains("stock 3");
        verify(productRepository).findAll(any(Sort.class));
    }

    @Test
    void orderStatusNeverLeaksCustomerData() {
        Customer customer = mock(Customer.class);
        when(customer.getEmail()).thenReturn("private@example.com");
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("12.50"));
        when(orderRepository.findById(1L)).thenReturn(java.util.Optional.of(order));

        String result = toolSet.orderStatus(1);

        assertThat(result).contains("PLACED");
        assertThat(result).doesNotContain("private@example.com");
    }

    @Test
    void docsSearchDelegatesToTheRagService() {
        when(ingestionService.isIndexed()).thenReturn(true);
        when(ragService.answer("How does optimistic locking work?"))
                .thenReturn("Optimistic locking is automatic.");

        String result = toolSet.docsSearch("How does optimistic locking work?");

        assertThat(result).isEqualTo("Optimistic locking is automatic.");
        verify(ragService).answer("How does optimistic locking work?");
    }
}