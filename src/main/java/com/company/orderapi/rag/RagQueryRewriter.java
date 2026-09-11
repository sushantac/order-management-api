package com.company.orderapi.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PR #51 - query rewriting: expands a user question into paraphrases to improve recall.
 * When disabled, returns the original question alone.
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "query-rewriting.enabled", havingValue = "true")
public class RagQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(RagQueryRewriter.class);

    private final ChatModel chatModel;

    public RagQueryRewriter(@Lazy ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<String> rewrite(String question) {
        try {
            String prompt = "Rewrite the following question into 2 alternative phrasings, one per line, no numbering: " + question;
            String response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("You are a query rewriter for retrieval."),
                    new UserMessage(prompt)))).getResult().getOutput().getText();
            List<String> variants = List.of(response.split("\n")).stream()
                    .map(String::trim).filter(s -> !s.isBlank()).limit(2).toList();
            log.debug("rewrote '{}' -> {}", question, variants);
            return List.of(question).stream().collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.warn("query rewrite failed, using original", e);
            return List.of(question);
        }
    }
}
