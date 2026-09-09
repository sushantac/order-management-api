package com.company.orderapi.agent;

import com.company.orderapi.mcp.DocsSearchTool;
import com.company.orderapi.rag.DocumentIngestionService;
import com.company.orderapi.rag.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests the server-side agent: the ChatClient wiring (system instructions +
 * function-calling tools offered to the model) and task handling.
 *
 * <p>Pure unit test - the chat model is mocked; the tools are the REAL agent
 * tool set so we prove the exact four callbacks reach the model's options.
 */
class AgentServiceTest {

    private ChatModel chatModel;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        agentService = new AgentService(chatModel, realToolCallbacks());
    }

    @Test
    void askReturnsTheGeneratedAnswer() {
        when(chatModel.call(any(Prompt.class))).thenReturn(agentResponse("3 items in stock."));

        String answer = agentService.ask("How many widgets are in stock?");

        assertThat(answer).isEqualTo("3 items in stock.");
    }

    @Test
    void askRejectsBlankTask() {
        assertThatThrownBy(() -> agentService.ask("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task must not be blank");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void promptCarriesSystemInstructionsAndTheUserTask() {
        when(chatModel.call(any(Prompt.class))).thenReturn(agentResponse("ok"));

        agentService.ask("Summarise the catalogue.");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt capturedPrompt = promptCaptor.getValue();

        String systemText = capturedPrompt.getInstructions().get(0).getText();
        assertThat(systemText).contains("Order Management API agent")
                .contains("product_search").contains("docs_search");
        assertThat(capturedPrompt.getInstructions().get(1).getText()).isEqualTo("Summarise the catalogue.");
    }

    @Test
    void promptOptionsExposeAllFourAgentToolsForFunctionCalling() {
        when(chatModel.call(any(Prompt.class))).thenReturn(agentResponse("ok"));

        agentService.ask("Find the red widget and check order 7");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());

        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(ToolCallingChatOptions.class, options -> {
                    List<String> names = options.getToolCallbacks().stream()
                            .map(callback -> callback.getToolDefinition().name())
                            .toList();
                    assertThat(names).containsExactlyInAnyOrder(
                            "api_health", "product_search", "order_status", "docs_search");
                });
    }

    private static ToolCallbackProvider realToolCallbacks() {
        RagService ragService = mock(RagService.class);
        DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
        AgentToolSet toolSet = new AgentToolSet(
                new com.company.orderapi.mcp.ApiHealthTool(),
                new com.company.orderapi.mcp.ProductSearchTool(mock(com.company.orderapi.domain.repository.ProductRepository.class)),
                new com.company.orderapi.mcp.OrderStatusTool(mock(com.company.orderapi.domain.repository.OrderRepository.class)),
                new DocsSearchTool(ragService, ingestionService));
        return MethodToolCallbackProvider.builder().toolObjects(toolSet).build();
    }

    private static ChatResponse agentResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}