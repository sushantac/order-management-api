package com.company.orderapi.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests the document ingestion pipeline: loading markdown files from {@code docs/}
 * (via the classpath), chunking them, and storing them in the vector store.
 *
 * <p>Pure unit test - the vector store is mocked and the resource resolver reads
 * from the real classpath (docs are copied there by the Maven resources plugin).
 */
class DocumentIngestionServiceTest {

    private VectorStore vectorStore;
    private DocumentIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        RagProperties props = new RagProperties(true, "classpath:docs/**/*.md", 800, 200, 3);
        ingestionService = new DocumentIngestionService(vectorStore, new DefaultResourceLoader(), props);
    }

    @Test
    void loadAndChunkLoadsMarkdownFromDocsLocation() throws IOException {
        List<Document> chunks = ingestionService.loadAndChunk();

        assertThat(chunks).isNotEmpty();
    }

    @Test
    void chunksCarrySourceMetadataPointingAtMarkdownFiles() throws IOException {
        List<Document> chunks = ingestionService.loadAndChunk();

        boolean hasMarkdownSource = chunks.stream()
                .anyMatch(doc -> doc.getMetadata().get("source") instanceof String s && s.endsWith(".md"));
        assertThat(hasMarkdownSource).isTrue();
    }

    @Test
    void ingestDocumentsStoresChunksInVectorStore() {
        ingestionService.ingestDocuments();

        verify(vectorStore).add(any());
        assertThat(ingestionService.isIndexed()).isTrue();
    }

    @Test
    void ingestionIsIdempotent() {
        ingestionService.ingestDocuments();
        ingestionService.ingestDocuments();

        verify(vectorStore, times(1)).add(any());
    }
}