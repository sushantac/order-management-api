package com.company.orderapi.api.rest.controller;

import com.company.orderapi.agent.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PR #45 - Server-Sent Events (SSE) streaming for the agent endpoint.
 *
 * <p>Streams the server-side agent's response token by token. The agent may
 * call multiple tools before producing a final answer; tool calls execute
 * synchronously (the model waits for results) and the text tokens stream as
 * the model generates them.
 *
 * <p>Activated only when an {@link AgentService} bean exists (i.e. when
 * {@code app.rag.enabled=true} and a {@code ChatModel} is available). See
 * {@link RagStreamingController} for the RAG-only streaming endpoint — the two
 * are deliberately separate so each activates on the dependency it needs.
 */
@RestController
@RequestMapping("/api/v1/stream")
@ConditionalOnBean(AgentService.class)
@Tag(name = "Streaming", description = "SSE streaming endpoints for AI features (PR #45)")
public class AgentStreamingController {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamingController.class);

    /** Virtual-thread executor for Flux → SseEmitter bridging. */
    private static final ExecutorService STREAMING_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    public AgentStreamingController(AgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    /**
     * Streams the agent's response, including tool-calling steps.
     *
     * @param task           the natural-language task or question
     * @param conversationId optional conversation id for multi-turn context
     */
    @PostMapping(value = "/agent/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream the agent's response to a task")
    public SseEmitter streamAgentAsk(
            @Parameter(description = "The task or question for the agent") @RequestParam String task,
            @Parameter(description = "Optional conversation id for multi-turn context")
            @RequestParam(required = false) String conversationId) {

        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        STREAMING_EXECUTOR.submit(() -> {
            try {
                AtomicReference<String> lastToken = new AtomicReference<>("");
                agentService.askStream(task, conversationId)
                        .doOnNext(token -> {
                            try {
                                if (!token.isEmpty()) {
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(Map.of("text", token)));
                                    lastToken.set(token);
                                }
                            } catch (IOException e) {
                                log.debug("Client disconnected during agent stream", e);
                                throw new RuntimeException("Client disconnected", e);
                            }
                        })
                        .doOnError(e -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("message", e.getMessage() != null
                                                ? e.getMessage() : "Unknown error")));
                            } catch (IOException ignored) {
                            }
                        })
                        .doFinally(signal -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(""));
                                emitter.complete();
                            } catch (IOException ignored) {
                            }
                        })
                        .blockLast(); // Block the virtual thread until the Flux completes
            } catch (Exception e) {
                log.error("Agent stream failed for task='{}'", task, e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", "Stream failed: " + e.getMessage())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}