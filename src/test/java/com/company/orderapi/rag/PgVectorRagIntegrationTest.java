package com.company.orderapi.rag;

import com.company.orderapi.rag.eval.GoldenQuestion;
import com.company.orderapi.rag.eval.RagRetrievalEvaluator;
import com.company.orderapi.rag.support.HashEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR #42 - RAG retrieval against REAL persistent pgvector storage, end to end:
 * schema creation (vector extension, HNSW index), content-addressed incremental
 * re-indexing, idempotency, stale-chunk replacement on edits, retrieval from
 * Postgres, and the eval harness consuming the live {@link RagService}.
 *
 * <p>Postgres isn't just "a test DB" here - pgvector is the point. The container
 * is {@code pgvector/pgvector:pg16} so {@link PgVectorStore} can create the
 * {@code vector} extension it needs. The deterministic
 * {@link HashEmbeddingModel} keeps everything offline and reproducible; the
 * chat model is a stub because these tests exercise retrieval, not generation.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "integration.database.tag=PgVectorRagIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.rag.enabled=true",
        "app.rag.eval.enabled=false",
        "app.security.enabled=false"
})
class PgVectorRagIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final String TABLE = "public.vector_store";

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RagService ragService;

    @Autowired
    private RagRetrievalEvaluator evaluator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VectorStore vectorStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingModel hashEmbeddingModel() {
            return new HashEmbeddingModel(768);
        }

        @Bean
        ChatModel stubChatModel() {
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.call(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.prompt.Prompt>any()))
                    .thenReturn(null);
            return chatModel;
        }
    }

    @Test
    void reindexPersistsContentAddressedChunksIntoPgvector() throws Exception {
        ingestionService.reindex();

        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
        assertThat(rows).isGreaterThan(0);

        // nomic-embed-text produces 768-dim vectors; the schema must have used
        // our embedding-dimensions property, not a mis-guessed default.
        Integer dims = jdbc.queryForObject(
                "SELECT vector_dims(embedding) FROM " + TABLE + " LIMIT 1", Integer.class);
        assertThat(dims).isEqualTo(768);

        // HNSW index created over the embedding column (pgvector_hnsw).
        Boolean hnsw = jdbc.queryForObject(
                "SELECT count(*) > 0 FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'vector_store' "
                        + "AND indexdef ILIKE '%hnsw%'", Boolean.class);
        assertThat(hnsw).isTrue();

        List<String> sources = jdbc.queryForList(
                "SELECT metadata->>'source' FROM " + TABLE
                        + " WHERE metadata::jsonb ? 'content-hash' LIMIT 20", String.class);
        assertThat(sources).isNotEmpty().allMatch(s -> s.endsWith(".md"));
    }

    @Test
    void reindexingTheUnchangedCorpusIsIdempotent() throws Exception {
        ingestionService.reindex();
        Long baseline = jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);

        DocumentIngestionService.ReindexReport second = ingestionService.reindex();
        Long after = jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);

        assertThat(second.chunksAdded()).isZero();
        assertThat(second.chunksDeleted()).isZero();
        assertThat(second.filesUnchanged()).isGreaterThan(0);
        assertThat(after).isEqualTo(baseline);
    }

    @Test
    void editedDocumentIsReembeddedAndStaleChunksAreReplaced() throws Exception {
        ingestionService.reindex();
        String target = jdbc.queryForObject(
                "SELECT metadata->>'source' FROM " + TABLE + " GROUP BY metadata->>'source' LIMIT 1",
                String.class);
        int targetBaseline = jdbc.queryForObject(
                "SELECT count(*)::int FROM " + TABLE + " WHERE metadata->>'source' = ?",
                Integer.class, target);

        // Simulate an edit: flip the content-hash of that file's rows to STALE.
        jdbc.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(metadata::jsonb, '{content-hash}', '\"STALE\"') "
                + "WHERE metadata->>'source' = ?", target);

        DocumentIngestionService.ReindexReport report = ingestionService.reindex();

        // The stale rows are gone and the exact same file freshly re-embedded.
        Long stale = jdbc.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'content-hash' = 'STALE'", Long.class);
        assertThat(stale).isZero();
        assertThat(report.chunksDeleted()).isEqualTo(targetBaseline);
        assertThat(report.chunksAdded()).isEqualTo(targetBaseline);
        assertThat(jdbc.queryForObject(
                "SELECT count(*)::int FROM " + TABLE + " WHERE metadata->>'source' = ?",
                Integer.class, target))
                .isEqualTo(targetBaseline);
    }

    @Test
    void retrievalAgainstPersistentPgvectorReturnsSourceTaggedChunks() throws Exception {
        ingestionService.reindex();

        List<Document> results = ragService.retrieve(
                "how does the outbox pattern make order event publishing reliable");

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(doc ->
                String.valueOf(doc.getMetadata().get("source")).endsWith(".md"));
        assertThat(results).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void evalHarnessScoresTheLiveRetrieverOverTheShippedGoldenSet() throws Exception {
        ingestionService.reindex();
        List<GoldenQuestion> goldens = objectMapper.readValue(
                new ClassPathResource("rag/eval/golden-questions.json").getInputStream(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });

        RagRetrievalEvaluator.RetrievalEvalReport report =
                evaluator.evaluate(ragService::retrieve, goldens, 5);

        assertThat(report.total()).isEqualTo(goldens.size());
        assertThat(report.items()).hasSize(goldens.size());
        assertThat(report.items()).allMatch(item -> item.retrievedSources().size() <= 5);
        assertThat(report.hits()).isBetween(0, goldens.size());
    }

    @Test
    void vectorStoreBeanIsThePersistentPgVectorStore() {
        assertThat(vectorStore)
                .as("production wiring must use pgvector, not the in-memory SimpleVectorStore")
                .isInstanceOf(org.springframework.ai.vectorstore.pgvector.PgVectorStore.class);
    }
}