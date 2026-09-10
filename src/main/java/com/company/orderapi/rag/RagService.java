package com.company.orderapi.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Retrieval-Augmented Generation service: retrieves relevant documentation
 * chunks and generates grounded answers using the configured chat model.
 *
 * <p>Only active when {@code app.rag.enabled=true}.
 *
 * <p>PR #43: retrieval is delegated to the configured {@link RetrievalEngine}
 * (dense-only or hybrid per {@code app.rag.retrieval-mode}) instead of talking
 * to the {@code VectorStore} directly.
 */
@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

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

    public RagService(RetrievalEngine retrievalEngine, @Lazy ChatModel chatModel,
                      RagProperties ragProperties) {
        this.retrievalEngine = retrievalEngine;
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    /**
     * Retrieves relevant chunks and generates a grounded answer.
     *
     * @param question the user's question
     * @return the answer text with source attribution
     */
    public String answer(String question) {
        List<Document> relevantDocs = retrievalEngine.retrieve(question, ragProperties.topK());

        if (relevantDocs.isEmpty()) {
            return "No relevant documentation found for your question. "
                    + "The documentation index may not be loaded yet.";
        }

        String context = relevantDocs.stream()
                .map(doc -> {
                    String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
                    return "[Source: " + source + "]\n" + doc.getText();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemMessageText = SYSTEM_PROMPT.formatted(context);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemMessageText),
                new UserMessage(question)));

        String answer = chatModel.call(prompt).getResult().getOutput().getText();
        log.debug("RAG: question='{}', chunks={}, answer length={}",
                question, relevantDocs.size(), answer.length());
        return answer;
    }

    /**
     * Retrieves relevant chunks without LLM generation (for debugging/testing).
     */
    public List<Document> retrieve(String question) {
        return retrievalEngine.retrieve(question, ragProperties.topK());
    }
}
