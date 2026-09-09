package com.company.orderapi.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests the RAG service: retrieving relevant documentation chunks and
 * generating grounded answers using a mocked LLM and retrieval engine.
 */
class RagServiceTest {

    private RetrievalEngine retrievalEngine;
    private ChatModel chatModel;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        retrievalEngine = mock(RetrievalEngine.class);
        chatModel = mock(ChatModel.class);
        RagProperties props = new RagProperties(true, "classpath:docs/**/*.md", 800, 200, 3);
        ragService = new RagService(retrievalEngine, chatModel, props);
    }

    @Test
    void answerRetrievesChunksAndGeneratesGroundedResponse() {
        Document doc = new Document("Orders use optimistic locking for stock.",
                Map.of("source", "01-orders.md"));
        when(retrievalEngine.retrieve(anyString(), anyInt())).thenReturn(List.of(doc));
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("Optimistic locking is used."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String answer = ragService.answer("How does stock locking work?");

        assertThat(answer).isEqualTo("Optimistic locking is used.");
        verify(retrievalEngine).retrieve("How does stock locking work?", 3);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void answerReturnsHelpfulMessageWhenNoChunksFound() {
        when(retrievalEngine.retrieve(anyString(), anyInt())).thenReturn(List.of());

        String answer = ragService.answer("Quantum computing basics?");

        assertThat(answer).contains("No relevant documentation found");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void retrieveReturnsDocumentsFromRetrievalEngine() {
        Document doc = new Document("Kafka outbox pattern.",
                Map.of("source", "09-integration.md"));
        when(retrievalEngine.retrieve(anyString(), anyInt())).thenReturn(List.of(doc));

        List<Document> results = ragService.retrieve("How does event publishing work?");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getText()).contains("Kafka");
    }

    @Test
    void promptIncludesSourceAttributionInContext() {
        Document doc = new Document("DeepSeek handles answer generation.",
                Map.of("source", "11-mcp-ai-integration.md"));
        when(retrievalEngine.retrieve(anyString(), anyInt())).thenReturn(List.of(doc));
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("The RAG pipeline works."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ragService.answer("What is the RAG pipeline?");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt captured = promptCaptor.getValue();
        String systemMessageText = captured.getInstructions().get(0).getText();
        assertThat(systemMessageText).contains("Source: 11-mcp-ai-integration.md");
        assertThat(systemMessageText).contains("DeepSeek handles answer generation.");
    }
}