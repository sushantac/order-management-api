package com.company.orderapi.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Production {@link VectorIndexStore} over the pgvector table that the
 * {@code VectorStore} bean writes to. Reuses the same table name (and schema)
 * so list/queries always see exactly what {@code VectorStore.add/delete} wrote.
 *
 * <p>The metadata column is JSON; {@code ->'key'} extraction works on both
 * {@code json} and {@code jsonb}. Ids are UUID text (PgVectorStore's default
 * id type), which is what {@code VectorStore.delete(List<String>)} accepts.
 */
public class PgVectorIndexStore implements VectorIndexStore {

    private static final String SCHEMA = "public";

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final String table;

    public PgVectorIndexStore(JdbcTemplate jdbcTemplate, VectorStore vectorStore, String table) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.table = table;
    }

    private String fullyQualified() {
        return SCHEMA + "." + table;
    }

    @Override
    public void addChunks(List<Document> chunks) {
        vectorStore.add(chunks);
    }

    @Override
    public void deleteChunks(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
    }

    @Override
    public List<StoredChunk> chunksBySource(String source) {
        return jdbcTemplate.query(
                "SELECT id::text, metadata->>'content-hash' AS hash FROM "
                        + fullyQualified() + " WHERE metadata->>'source' = ? ORDER BY id",
                (rs, rowNum) -> new StoredChunk(rs.getString(1), rs.getString(2)),
                source);
    }

    @Override
    public List<String> sources() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT metadata->>'source' FROM " + fullyQualified(),
                String.class);
    }
}