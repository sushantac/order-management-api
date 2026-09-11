package com.company.orderapi.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * PR #51 - semantic reranking: re-orders retrieved docs by lexical overlap with the question.
 * Lightweight heuristic; replace with cross-encoder in prod.
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "reranking.enabled", havingValue = "true")
public class SemanticReranker {

    private static final Logger log = LoggerFactory.getLogger(SemanticReranker.class);

    public List<Document> rerank(String question, List<Document> docs) {
        String qLower = question.toLowerCase();
        List<Document> ranked = docs.stream()
                .sorted(Comparator.comparingInt((Document d) -> score(qLower, d.getText().toLowerCase())).reversed())
                .toList();
        log.debug("reranked {} docs for '{}'", docs.size(), question);
        return ranked;
    }

    private int score(String question, String doc) {
        int s = 0;
        for (String token : question.split("\\W+")) {
            if (token.length() > 2 && doc.contains(token)) s++;
        }
        return s;
    }
}
