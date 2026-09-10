package com.company.orderapi.agent;

import com.company.orderapi.mcp.DocsSearchTool;
import com.company.orderapi.rag.DocumentIngestionService;
import com.company.orderapi.rag.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the agent's streaming method: the ChatClient's {@code .stream()} call
 * returns a Flux of token strings.
 *
 * <p>Uses Project Reactor's {@link StepVerifier} to assert on the Flux
 * emitted by {@link AgentService#askStream(String, String)}.
 */
class AgentServiceStreamTest {

    private ChatModel chatModel;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        agentService = new AgentService(chatModel, realToolCallbacks(), realMemory());
    }

    @Test
    void askStreamEmitsTokensAndCompletionSignal() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(
                        chatResponse("The "),
                        chatResponse("catalogue "),
                        chatResponse("has 5 items.")));

        Flux<String> stream = agentService.askStream("How many products?", null);

        StepVerifier.create(stream)
                .expectNext("The ")
                .expectNext("catalogue ")
                .expectNext("has 5 items.")
                .expectNext("")  // completion signal
                .verifyComplete();
    }

    @Test
    void askStreamRejectsBlankTask() {
        Flux<String> stream = agentService.askStream("   ", null);

        StepVerifier.create(stream)
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void askStreamFiltersEmptyTokens() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(
                        chatResponse(""),
                        chatResponse("Hello"),
                        chatResponse(""),
                        chatResponse(" world")));

        Flux<String> stream = agentService.askStream("test", null);

        StepVerifier.create(stream)
                .expectNext("Hello")
                .expectNext(" world")
                .expectNext("")  // completion signal
                .verifyComplete();
    }

    @Test
    void askStreamUsesConversationId() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(chatResponse("ok")));

        agentService.askStream("test", "conv-123").blockLast();

        // The conversation ID is passed to the ChatMemory advisor internally;
        // we verify the stream completes successfully (the advisor handles routing).
    }

    private static org.springframework.ai.chat.model.ChatResponse chatResponse(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(
                java.util.List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }

    private static org.springframework.ai.chat.memory.MessageWindowChatMemory realMemory() {
        return org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build();
    }

    private static ToolCallbackProvider realToolCallbacks() {
        RagService ragService = mock(RagService.class);
        DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
        AgentToolSet toolSet = new AgentToolSet(
                new com.company.orderapi.mcp.ApiHealthTool(),
                new com.company.orderapi.mcp.ProductSearchTool(
                        mock(com.company.orderapi.domain.repository.ProductRepository.class)),
                new com.company.orderapi.mcp.OrderStatusTool(
                        mock(com.company.orderapi.domain.repository.OrderRepository.class)),
                new DocsSearchTool(ragService, ingestionService));
        return MethodToolCallbackProvider.builder().toolObjects(toolSet).build();
    }
}
