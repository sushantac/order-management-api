package com.company.orderapi.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the streaming RAG service: retrieving relevant documentation chunks
 * and streaming the LLM-generated answer token by token.
 *
 * <p>Uses Project Reactor's {@link StepVerifier} to assert on the Flux
 * emitted by {@link RagStreamingService#answerStream(String)}.
 */
class RagStreamingServiceTest {

    private RetrievalEngine retrievalEngine;
    private ChatModel chatModel;
    private RagStreamingService ragStreamingService;

    @BeforeEach
    void setUp() {
        retrievalEngine = mock(RetrievalEngine.class);
        chatModel = mock(ChatModel.class);
        RagProperties props = new RagProperties(true, "classpath:docs/**/*.md", 800, 200, 3);
        ragStreamingService = new RagStreamingService(retrievalEngine, chatModel, props);
    }

    @Test
    void answerStreamEmitsTokensAndCompletionSignal() {
        Document doc = new Document("Orders use optimistic locking.",
                Map.of("source", "01-orders.md"));
        when(retrievalEngine.retrieve(any(String.class), anyInt())).thenReturn(List.of(doc));

        // Simulate streaming: three tokens then completion
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(
                        chatResponse("Optimistic "),
                        chatResponse("locking "),
                        chatResponse("is used.")));

        Flux<String> stream = ragStreamingService.answerStream("How does locking work?");

        StepVerifier.create(stream)
                .expectNext("Optimistic ")
                .expectNext("locking ")
                .expectNext("is used.")
                .expectNext("")  // completion signal
                .verifyComplete();

        verify(retrievalEngine).retrieve("How does locking work?", 3);
    }

    @Test
    void answerStreamReturnsHelpfulMessageWhenNoChunksFound() {
        when(retrievalEngine.retrieve(any(String.class), anyInt())).thenReturn(List.of());

        Flux<String> stream = ragStreamingService.answerStream("Quantum computing?");

        StepVerifier.create(stream)
                .expectNext("No relevant documentation found for your question. "
                        + "The documentation index may not be loaded yet.")
                .verifyComplete();

        verify(chatModel, never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void answerStreamFiltersEmptyTokens() {
        Document doc = new Document("Test content.", Map.of("source", "test.md"));
        when(retrievalEngine.retrieve(any(String.class), anyInt())).thenReturn(List.of(doc));

        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(
                        chatResponse(""),       // empty - should be filtered
                        chatResponse("Hello"),
                        chatResponse(""),       // empty - should be filtered
                        chatResponse(" world")));

        Flux<String> stream = ragStreamingService.answerStream("test");

        StepVerifier.create(stream)
                .expectNext("Hello")
                .expectNext(" world")
                .expectNext("")  // completion signal
                .verifyComplete();
    }

    @Test
    void retrieveDelegatesToRetrievalEngine() {
        Document doc = new Document("Kafka outbox.", Map.of("source", "09-integration.md"));
        when(retrievalEngine.retrieve(any(String.class), anyInt())).thenReturn(List.of(doc));

        List<Document> results = ragStreamingService.retrieve("How does publishing work?");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getText()).contains("Kafka");
    }

    private static org.springframework.ai.chat.model.ChatResponse chatResponse(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }
}
