package com.company.orderapi.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * PR #43 - the pre-#43 retrieval behaviour, unchanged: pure embedding
 * similarity via the configured {@link VectorStore} (pgvector cosine).
 *
 * <p>Dense search is terrible at exact terms, names and identifiers; that is
 * the gap {@link HybridRetrievalEngine} closes by fusing this with the
 * lexical {@link LexicalRetrievalEngine}.
 */
public class DenseRetrievalEngine implements RetrievalEngine {

    private final VectorStore vectorStore;

    public DenseRetrievalEngine(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<Document> retrieve(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
    }
}