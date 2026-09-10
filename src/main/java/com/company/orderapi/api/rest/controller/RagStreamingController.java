package com.company.orderapi.api.rest.controller;

import com.company.orderapi.rag.DocumentIngestionService;
import com.company.orderapi.rag.RagService;
import com.company.orderapi.rag.RagStreamingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PR #45 - Server-Sent Events (SSE) streaming for the RAG answer endpoint.
 *
 * <p>Bridges Spring AI's reactive {@code Flux}-based streaming to the servlet
 * world using {@link SseEmitter} — the idiomatic streaming pattern for a
 * Spring MVC (non-WebFlux) application. A dedicated virtual-thread executor
 * subscribes to the Flux so the servlet container's thread pool is never
 * blocked for the whole generation.
 *
 * <p>SSE events carry a {@code type} field so clients can distinguish content
 * from metadata: {@code token} (incremental text), {@code sources} (retrieved
 * chunk metadata, sent once), {@code done} (completion) and {@code error}.
 *
 * <p>The counterpart to {@code /api/v1/stream/agent/ask} lives in
 * {@link AgentStreamingController}. This controller is deliberately split so
 * each endpoint activates only when the service it depends on exists.
 *
 * <p>Only active when {@code app.rag.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/stream")
@ConditionalOnBean(RagStreamingService.class)
@Tag(name = "Streaming", description = "SSE streaming endpoints for AI features (PR #45)")
public class RagStreamingController {

    private static final Logger log = LoggerFactory.getLogger(RagStreamingController.class);

    /** Virtual-thread executor for Flux → SseEmitter bridging. */
    private static final ExecutorService STREAMING_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final RagStreamingService ragStreamingService;
    private final RagService ragService;
    private final DocumentIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public RagStreamingController(RagStreamingService ragStreamingService,
                                  RagService ragService,
                                  DocumentIngestionService ingestionService,
                                  ObjectMapper objectMapper) {
        this.ragStreamingService = ragStreamingService;
        this.ragService = ragService;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Streams a RAG-generated answer to the documentation question.
     *
     * @param question the documentation question to answer
     */
    @GetMapping(value = "/rag/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream a RAG answer for a documentation question")
    public SseEmitter streamRagAnswer(
            @Parameter(description = "The documentation question") @RequestParam String question) {

        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        // Ensure ingestion has run before retrieval
        if (!ingestionService.isIndexed()) {
            ingestionService.ingestDocuments();
        }

        // Retrieve chunks first (synchronous — it's a local DB call)
        List<Document> chunks = ragService.retrieve(question);

        STREAMING_EXECUTOR.submit(() -> {
            try {
                // Send source metadata as the first event
                List<Map<String, String>> sources = chunks.stream()
                        .map(doc -> Map.of(
                                "source", String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                                "text", truncate(doc.getText(), 200)))
                        .toList();
                emitter.send(SseEmitter.event()
                        .name("sources")
                        .data(objectMapper.writeValueAsString(sources)));

                // Stream the answer tokens
                AtomicReference<String> lastToken = new AtomicReference<>("");
                ragStreamingService.answerStream(question)
                        .doOnNext(token -> {
                            try {
                                if (!token.isEmpty()) {
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(Map.of("text", token)));
                                    lastToken.set(token);
                                }
                            } catch (IOException e) {
                                log.debug("Client disconnected during RAG stream", e);
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
                log.error("RAG stream failed for question='{}'", question, e);
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

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}