package com.company.orderapi.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * PR #43 - the retrieval half of RAG, behind a single seam so the strategy
 * ({@code app.rag.retrieval-mode}) is a wiring decision, not a code change.
 *
 * <p>{@link RagService} no longer talks to the {@code VectorStore} directly:
 * it asks whatever {@code RetrievalEngine} is configured for top-k chunks.
 * Implementations: {@link DenseRetrievalEngine} (embedding similarity only,
 * the pre-#43 behaviour) and {@link HybridRetrievalEngine} (dense + lexical
 * BM25 fused with RRF, then MMR-reranked).
 */
public interface RetrievalEngine {

    /**
     * Returns the top-k most relevant chunks for the query, best first.
     * The returned {@link Document}s carry a {@code source} metadata key and a
     * relevance score (embedding similarity, ts_rank, or RRF/MMR-derived).
     */
    List<Document> retrieve(String query, int topK);
}