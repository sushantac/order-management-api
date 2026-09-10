package com.company.orderapi.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PR #45 - Streaming variant of {@link RagService}: retrieves relevant
 * documentation chunks and streams the LLM-generated answer token by token.
 *
 * <p>Whereas {@link RagService#answer(String)} blocks until the full response
 * arrives, {@link #answerStream(String)} returns a {@link Flux} of token
 * strings that a controller can bridge to Server-Sent Events (SSE). This
 * eliminates perceived latency for long generations — the client receives the
 * first token in milliseconds, not seconds.
 *
 * <p>The retrieval step is still synchronous (it's a local database call);
 * only the LLM generation is streamed. The context-building logic is shared
 * with {@link RagService} via the private {@link #buildContext(List)} helper.
 *
 * <p>Only active when {@code app.rag.enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagStreamingService {

    private static final Logger log = LoggerFactory.getLogger(RagStreamingService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant for the Order Management API project.
            Answer the user's question using ONLY the provided documentation context.
            If the context does not contain enough information to answer, say so clearly.
            Be concise and direct. When referencing a specific document, mention its source filename.

            ## Documentation context:
            %s
            """;

    private final RetrievalEngine retrievalEngine;
    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public RagStreamingService(RetrievalEngine retrievalEngine, ChatModel chatModel,
                               RagProperties ragProperties) {
        this.retrievalEngine = retrievalEngine;
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    /**
     * Retrieves relevant chunks and streams the LLM-generated answer.
     *
     * <p>The returned {@link Flux} emits:
     * <ul>
     *   <li>Token strings as the LLM generates them</li>
     *   <li>An empty string as the completion signal (the controller maps this
     *       to a {@code done} SSE event)</li>
     * </ul>
     *
     * <p>If no relevant chunks are found, the Flux emits a single helpful
     * message and completes (no LLM call is made).
     *
     * @param question the user's question
     * @return a Flux of token strings
     */
    public Flux<String> answerStream(String question) {
        List<Document> relevantDocs = retrievalEngine.retrieve(question, ragProperties.topK());

        if (relevantDocs.isEmpty()) {
            return Flux.just("No relevant documentation found for your question. "
                    + "The documentation index may not be loaded yet.");
        }

        String context = buildContext(relevantDocs);
        String systemMessageText = SYSTEM_PROMPT.formatted(context);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemMessageText),
                new UserMessage(question)));

        log.debug("RAG stream: question='{}', chunks={}", question, relevantDocs.size());

        return chatModel.stream(prompt)
                .map(response -> {
                    String text = response.getResult().getOutput().getText();
                    return text != null ? text : "";
                })
                .filter(text -> !text.isEmpty())
                .concatWith(Flux.just(""))  // completion signal
                .doOnComplete(() -> log.debug("RAG stream complete: question='{}'", question));
    }

    /**
     * Retrieves relevant chunks for a question (shared with {@link RagService}).
     */
    public List<Document> retrieve(String question) {
        return retrievalEngine.retrieve(question, ragProperties.topK());
    }

    /**
     * Builds the context string from retrieved documents with source attribution.
     */
    static String buildContext(List<Document> docs) {
        return docs.stream()
                .map(doc -> {
                    String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
                    return "[Source: " + source + "]\n" + doc.getText();
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
