package com.company.orderapi.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the RAG (Retrieval-Augmented Generation) feature.
 *
 * <p>Bound to {@code app.rag.*} in application.yml. The feature is opt-in:
 * set {@code app.rag.enabled=true} (or activate the {@code rag} profile) to
 * enable document ingestion and the docs_search MCP tool.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean enabled,
        String docsLocation,
        int chunkSize,
        int chunkOverlap,
        int topK
) {
    public RagProperties {
        if (chunkSize <= 0) chunkSize = 800;
        if (chunkOverlap < 0) chunkOverlap = 200;
        if (topK <= 0) topK = 5;
    }
}
