package com.company.orderapi.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration properties for the RAG (Retrieval-Augmented Generation) feature.
 *
 * <p>Bound to {@code app.rag.*} in application.yml. The feature is opt-in:
 * set {@code app.rag.enabled=true} (or activate the {@code rag} profile) to
 * enable document ingestion and the docs_search MCP tool.
 *
 * <p>PR #42 additions: the vector store is now the persistent {@code pgvector}
 * table on the app's own PostgreSQL ({@code vector-table} /
 * {@code embedding-dimensions}), and the retrieval-eval harness
 * ({@code app.rag.eval.*}) can gate deploys on measured top-k retrieval quality.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean enabled,
        String docsLocation,
        int chunkSize,
        int chunkOverlap,
        int topK,
        int embeddingDimensions,
        String vectorTable,
        RagEvalProperties eval
) {
    /**
     * Bound by constructor. The compact body exists so that unset properties
     * fall back to sane defaults instead of failing (binding supplies null for
     * keys absent from the environment, e.g. when only {@code app.rag.enabled}
     * is set). The {@code @ConstructorBinding} marker is required because the
     * secondary convenience constructor below stops Spring Boot from assuming
     * automatic constructor binding on a record with more than one constructor.
     */
    @ConstructorBinding
    public RagProperties {
        if (docsLocation == null || docsLocation.isBlank()) docsLocation = "classpath:docs/**/*.md";
        if (chunkSize <= 0) chunkSize = 800;
        if (chunkOverlap < 0) chunkOverlap = 200;
        if (topK <= 0) topK = 5;
        if (embeddingDimensions <= 0) embeddingDimensions = 768; // nomic-embed-text default
        if (vectorTable == null || vectorTable.isBlank()) vectorTable = "vector_store";
        if (eval == null) {
            eval = new RagEvalProperties(false, "classpath:rag/eval/golden-questions.json", 0.0);
        }
    }

    /** Compact convenience constructor used by the pre-#42 unit tests. */
    public RagProperties(boolean enabled, String docsLocation, int chunkSize,
                         int chunkOverlap, int topK) {
        this(enabled, docsLocation, chunkSize, chunkOverlap, topK, 768, "vector_store", null);
    }

    /**
     * Retrieval-eval harness settings ({@code app.rag.eval.*}). The harness runs
     * a fixed set of golden questions against the top-k retriever and scores
     * whether the expected source document(s) are actually retrieved.
     *
     * <p>{@code min-hit-rate} of {@code 0} = report-only (the only safe default
     * before a corpus's retrieval quality has been measured once). Raise it to
     * gate deploys on retrieval quality regressions.
     */
    public record RagEvalProperties(
            boolean enabled,
            String goldensLocation,
            double minHitRate
    ) {
        public RagEvalProperties {
            if (goldensLocation == null || goldensLocation.isBlank()) {
                goldensLocation = "classpath:rag/eval/golden-questions.json";
            }
            if (minHitRate < 0) minHitRate = 0;
        }
    }
}