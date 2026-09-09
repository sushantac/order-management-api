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
 *
 * <p>PR #43 addition: {@code retrieval-mode} switches the retrieval half of RAG
 * between dense-only (pre-#43 behaviour) and hybrid. Hybrid fuses dense
 * embeddings with Postgres full-text via {@code rrf-k} (Reciprocal Rank Fusion)
 * and re-ranks for diversity with MMR ({@code retrieval.mmr-enabled} /
 * {@code retrieval.mmr-lambda}).
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
        RetrievalMode retrievalMode,
        RetrievalSettings retrieval,
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
        if (retrievalMode == null) retrievalMode = RetrievalMode.HYBRID;
        if (retrieval == null) retrieval = new RetrievalSettings(true, 0.5, 60);
        if (eval == null) {
            eval = new RagEvalProperties(false, "classpath:rag/eval/golden-questions.json", 0.0);
        }
    }

    /** Compact convenience constructor used by the pre-#42 unit tests. */
    public RagProperties(boolean enabled, String docsLocation, int chunkSize,
                         int chunkOverlap, int topK) {
        this(enabled, docsLocation, chunkSize, chunkOverlap, topK, 768,
                "vector_store", RetrievalMode.HYBRID, null, null);
    }

    /**
     * Which retrieval strategy to wire as the {@code RetrievalEngine} bean
     * ({@code app.rag.retrieval-mode}).
     */
    public enum RetrievalMode {
        /** Embedding similarity only - the pre-#43 behaviour. */
        DENSE,
        /** Dense + lexical full-text fused with RRF, then MMR-reranked. */
        HYBRID
    }

    /**
     * Hybrid retrieval tuning ({@code app.rag.retrieval.*}).
     *
     * <p>{@code rrf-k}: the constant in the RRF formula {@code 1 / (rrfK +
     * rank)}; the standard of 60 is rarely worth touching.
     *
     * <p>{@code mmr-lambda} in {@code [0,1]}: {@code 1} = pure relevance (keeps
     * dense/lexical order), lower values trade relevance for topic diversity
     * across a multi-part query. {@code 0.5} is the typical starting point.
     */
    public record RetrievalSettings(
            boolean mmrEnabled,
            double mmrLambda,
            int rrfK
    ) {
        public RetrievalSettings {
            if (mmrLambda < 0) mmrLambda = 0;
            if (mmrLambda > 1) mmrLambda = 1;
            if (rrfK <= 0) rrfK = 60;
        }
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