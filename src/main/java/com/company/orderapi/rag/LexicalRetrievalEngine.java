package com.company.orderapi.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PR #43 - lexical BM25-style retrieval over the same pgvector table, using
 * PostgreSQL's native full-text: {@code tsvector} against the {@code content}
 * column, scored with {@code ts_rank}.
 *
 * <p>Why this exists: dense embeddings are weak on exact vocabulary - method
 * names, tool ids, error codes and identifiers (think {@code reindex_docs},
 * {@code cancel_order}, {@code vector_store}) either never get embedded
 * distinctly enough or get dragged away by near-duplicate chunks. Full-text
 * matching catches precisely those, cheaply and with zero extra indexing
 * storage (a single GIN index over the generated tsvector).
 *
 * <p>The index is created lazily and idempotently on the first query so the
 * bean wiring order never matters.
 */
public class LexicalRetrievalEngine implements RetrievalEngine {

    private static final Logger log = LoggerFactory.getLogger(LexicalRetrievalEngine.class);

    private static final String SCHEMA = "public";

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean ftsIndexReady = new AtomicBoolean(false);

    public LexicalRetrievalEngine(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = SCHEMA + "." + ragProperties.vectorTable();
    }

    @Override
    public List<Document> retrieve(String query, int topK) {
        ensureFtsIndex();
        String tsQuery = "plainto_tsquery('english', ?)";
        String sql = """
                SELECT id::text AS id, content, metadata,
                       ts_rank(to_tsvector('english', content), %1$s) AS rank
                FROM %2$s
                WHERE to_tsvector('english', content) @@ %1$s
                ORDER BY rank DESC
                LIMIT ?
                """.formatted(tsQuery, table);
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(jsonToMetadata(rs.getString("metadata")))
                        .score(rs.getDouble("rank"))
                        .build(),
                query, query, topK);
    }

    private void ensureFtsIndex() {
        if (!ftsIndexReady.compareAndSet(false, true)) {
            return;
        }
        String indexName = "idx_" + table.replace('.', '_') + "_content_fts";
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName
                    + " ON " + table + " USING GIN (to_tsvector('english', content))");
        } catch (DataAccessException e) {
            // Table not created yet (schema init happens on the vector store's
            // first use); retry on the next retrieve instead of failing startup.
            ftsIndexReady.set(false);
            log.warn("RAG: full-text index not created (will retry): {}", e.getMessage());
        }
    }

    private Map<String, Object> jsonToMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            log.warn("RAG: unreadable metadata for a lexical hit: {}", e.getMessage());
            return Map.of();
        }
    }
}