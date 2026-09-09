package com.company.orderapi.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads project documentation from markdown files, chunks them into
 * overlapping segments, embeds them via Ollama, and stores the vectors
 * in an in-memory vector store.
 *
 * <p>Only active when {@code app.rag.enabled=true}. Runs once on
 * application startup ({@link ApplicationReadyEvent}).
 */
@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourceResolver;
    private final RagProperties ragProperties;

    private volatile boolean indexed = false;

    public DocumentIngestionService(
            VectorStore vectorStore,
            ResourceLoader resourceLoader,
            RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.resourceResolver = new PathMatchingResourcePatternResolver(resourceLoader);
        this.ragProperties = ragProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestDocuments() {
        if (indexed) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            List<Document> allDocuments = loadAndChunk();
            if (allDocuments.isEmpty()) {
                log.warn("No documents found at '{}'. RAG search will return empty results.",
                        ragProperties.docsLocation());
                return;
            }
            vectorStore.add(allDocuments);
            indexed = true;
            long elapsed = System.currentTimeMillis() - start;
            log.info("RAG: ingested {} chunks from documentation ({}ms)",
                    allDocuments.size(), elapsed);
        } catch (IOException e) {
            log.error("RAG: failed to ingest documents from '{}': {}",
                    ragProperties.docsLocation(), e.getMessage());
        }
    }

    /**
     * Loads all matching resources, reads each one, enriches with metadata,
     * and splits into token-based chunks.
     */
    List<Document> loadAndChunk() throws IOException {
        Resource[] resources = resourceResolver.getResources(ragProperties.docsLocation());
        log.info("RAG: found {} markdown files at '{}'", resources.length, ragProperties.docsLocation());

        List<Document> allDocuments = new ArrayList<>();
        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.chunkSize(),
                ragProperties.chunkOverlap(),
                10, 10000, true);

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            try {
                String content = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                if (content.isBlank()) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", filename);
                metadata.put("resource-path", resource.getURI().toString());

                Document doc = new Document(content, metadata);
                List<Document> chunks = splitter.apply(List.of(doc));
                for (Document chunk : chunks) {
                    chunk.getMetadata().putAll(metadata);
                }
                allDocuments.addAll(chunks);
            } catch (IOException e) {
                log.warn("RAG: skipping unreadable resource {}: {}", filename, e.getMessage());
            }
        }
        return allDocuments;
    }

    /**
     * Returns true if documents have been successfully ingested.
     */
    public boolean isIndexed() {
        return indexed;
    }

    /**
     * Force re-indexing (useful for tests or manual refresh).
     */
    public void reindex() throws IOException {
        indexed = false;
        ingestDocuments();
    }
}
