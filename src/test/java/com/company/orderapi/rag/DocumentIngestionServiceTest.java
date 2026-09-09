package com.company.orderapi.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the incremental document re-indexer: loading markdown files from
 * {@code docs/} (via the classpath), chunking them, embedding them, and -
 * the PR #42 core - re-embedding ONLY what actually changed.
 *
 * <p>Pure unit test: the vector-facing side is the in-memory
 * {@link InMemoryVectorIndexStore} below, which plays the same role as the
 * pgvector table in production (add/delete/list + content hashes).
 */
class DocumentIngestionServiceTest {

    private InMemoryVectorIndexStore indexStore;
    private DocumentIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        indexStore = new InMemoryVectorIndexStore();
        RagProperties props = new RagProperties(true, "classpath:docs/**/*.md", 800, 200, 3);
        ingestionService = new DocumentIngestionService(indexStore, new DefaultResourceLoader(), props);
    }

    @Test
    void loadFilesReadsMarkdownFromDocsLocation() throws IOException {
        List<DocumentIngestionService.SourceFile> files = ingestionService.loadFiles();

        assertThat(files).isNotEmpty();
        assertThat(files).allMatch(f -> f.name().endsWith(".md"));
    }

    @Test
    void reindexChunksCarrySourceAndContentHashMetadata() throws IOException {
        DocumentIngestionService.ReindexReport report = ingestionService.reindex();

        assertThat(report.filesReindexed()).isGreaterThan(0);
        List<Document> chunks = indexStore.allDocuments();
        assertThat(chunks).isNotEmpty();
        boolean hasSource = chunks.stream()
                .anyMatch(doc -> doc.getMetadata().get("source") instanceof String s && s.endsWith(".md"));
        boolean hasHash = chunks.stream()
                .anyMatch(doc -> doc.getMetadata().get("content-hash") instanceof String);
        assertThat(hasSource).isTrue();
        assertThat(hasHash).isTrue();
    }

    @Test
    void reindexIsIdempotentAndSkipsUnchangedFiles() throws IOException {
        DocumentIngestionService.ReindexReport first = ingestionService.reindex();
        int chunksAfterFirst = indexStore.allDocuments().size();

        DocumentIngestionService.ReindexReport second = ingestionService.reindex();

        assertThat(second.chunksAdded()).isZero();
        assertThat(second.chunksDeleted()).isZero();
        assertThat(second.filesUnchanged()).isEqualTo(first.filesReindexed());
        assertThat(indexStore.allDocuments().size()).isEqualTo(chunksAfterFirst);
    }

    @Test
    void reindexReembedsOnlyAChangedFileAndDropsItsStaleChunks() throws IOException {
        ingestionService.reindex();
        String target = indexStore.allDocuments().stream()
                .findFirst().map(doc -> doc.getMetadata().get("source").toString()).orElseThrow();
        List<VectorIndexStore.StoredChunk> stale = indexStore.chunksBySource(target);
        indexStore.replaceHash(target, "fake-stale-hash");

        DocumentIngestionService.ReindexReport report = ingestionService.reindex();

        assertThat(report.chunksDeleted()).isEqualTo(stale.size());
        assertThat(report.chunksAdded()).isGreaterThanOrEqualTo(1);
        assertThat(indexStore.chunksBySource(target))
                .allMatch(chunk -> !chunk.contentHash().equals("fake-stale-hash"));
    }

    @Test
    void reindexRemovesSourcesNoLongerOnDisk() throws IOException {
        ingestionService.reindex();
        indexStore.seed("forgotten-notes.md",
                new VectorIndexStore.StoredChunk("11111111-1111-1111-1111-111111111111", "old-hash"));

        DocumentIngestionService.ReindexReport report = ingestionService.reindex();

        assertThat(report.sourcesRemoved()).contains("forgotten-notes.md");
        assertThat(indexStore.sources()).doesNotContain("forgotten-notes.md");
    }

    @Test
    void afterSuccessfulReindexIsIndexed() {
        ingestionService.ingestDocuments();

        assertThat(ingestionService.isIndexed()).isTrue();
    }

    /**
     * In-memory twin of {@link PgVectorIndexStore}: tracks source + content
     * hash per chunk and answers the "what is already here" questions the
     * incremental re-indexer needs, without any real embedding or database.
     */
    static final class InMemoryVectorIndexStore implements VectorIndexStore {

        private final Map<String, List<StoredChunk>> bySource = new LinkedHashMap<>();

        @Override
        public void addChunks(List<Document> chunks) {
            for (Document chunk : chunks) {
                String source = String.valueOf(chunk.getMetadata().get("source"));
                String hash = String.valueOf(chunk.getMetadata().get("content-hash"));
                bySource.computeIfAbsent(source, key -> new ArrayList<>())
                        .add(new StoredChunk(chunk.getId(), hash));
            }
        }

        @Override
        public void deleteChunks(List<String> ids) {
            bySource.values().forEach(list -> list.removeIf(c -> ids.contains(c.id())));
            bySource.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }

        @Override
        public List<StoredChunk> chunksBySource(String source) {
            return List.copyOf(bySource.getOrDefault(source, List.of()));
        }

        @Override
        public List<String> sources() {
            return List.copyOf(bySource.keySet());
        }

        List<Document> allDocuments() {
            List<Document> docs = new ArrayList<>();
            for (Map.Entry<String, List<StoredChunk>> entry : bySource.entrySet()) {
                for (StoredChunk chunk : entry.getValue()) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", entry.getKey());
                    metadata.put("content-hash", chunk.contentHash());
                    docs.add(new Document(chunk.id(), metadata));
                }
            }
            return docs;
        }

        void replaceHash(String source, String newHash) {
            bySource.getOrDefault(source, List.of())
                    .replaceAll(c -> new StoredChunk(c.id(), newHash));
        }

        void seed(String source, StoredChunk chunk) {
            bySource.computeIfAbsent(source, key -> new ArrayList<>()).add(chunk);
        }
    }
}