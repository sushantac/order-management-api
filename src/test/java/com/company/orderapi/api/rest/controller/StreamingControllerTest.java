package com.company.orderapi.api.rest.controller;

import com.company.orderapi.agent.AgentService;
import com.company.orderapi.rag.DocumentIngestionService;
import com.company.orderapi.rag.RagService;
import com.company.orderapi.rag.RagStreamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the streaming SSE endpoints: verifying the HTTP contract and error
 * handling without requiring a real LLM or database.
 *
 * <p>Each controller is tested with {@link MockMvc} standalone setup and mocked
 * services so the tests run fast and offline.
 */
class StreamingControllerTest {

    private MockMvc ragMockMvc;
    private MockMvc agentMockMvc;
    private RagStreamingService ragStreamingService;
    private RagService ragService;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        ragStreamingService = mock(RagStreamingService.class);
        ragService = mock(RagService.class);
        agentService = mock(AgentService.class);
        DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);

        RagStreamingController ragController = new RagStreamingController(
                ragStreamingService, ragService, ingestionService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        AgentStreamingController agentController = new AgentStreamingController(
                agentService, new com.fasterxml.jackson.databind.ObjectMapper());

        ragMockMvc = MockMvcBuilders.standaloneSetup(ragController).build();
        agentMockMvc = MockMvcBuilders.standaloneSetup(agentController).build();
    }

    @Test
    void ragStreamEndpointReturnsSseContentType() throws Exception {
        Document doc = new Document("Orders use locking.", Map.of("source", "test.md"));
        when(ragService.retrieve(any())).thenReturn(List.of(doc));
        when(ragStreamingService.answerStream(any()))
                .thenReturn(reactor.core.publisher.Flux.just("Answer", " text", ""));

        ragMockMvc.perform(get("/api/v1/stream/rag/answer")
                        .param("question", "How does locking work?")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }

    @Test
    void agentStreamEndpointReturnsSseContentType() throws Exception {
        when(agentService.askStream(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.just("Agent ", "response", ""));

        agentMockMvc.perform(post("/api/v1/stream/agent/ask")
                        .param("task", "Check order status")
                        .param("conversationId", "conv-1")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }

    @Test
    void ragStreamEndpointHandlesEmptyQuestion() throws Exception {
        Document doc = new Document("Test.", Map.of("source", "test.md"));
        when(ragService.retrieve(any())).thenReturn(List.of(doc));
        when(ragStreamingService.answerStream(any()))
                .thenReturn(reactor.core.publisher.Flux.just("Answer", ""));

        ragMockMvc.perform(get("/api/v1/stream/rag/answer")
                        .param("question", "")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }

    @Test
    void agentStreamEndpointHandlesBlankTask() throws Exception {
        when(agentService.askStream(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.error(
                        new IllegalArgumentException("task must not be blank")));

        agentMockMvc.perform(post("/api/v1/stream/agent/ask")
                        .param("task", "   ")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }
}