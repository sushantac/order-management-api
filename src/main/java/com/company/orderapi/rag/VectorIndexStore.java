package com.company.orderapi.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * The small seam the incremental re-indexer needs over the vector index:
 * add chunks, delete by id, and - the part the plain {@code VectorStore}
 * interface hides - list what is ALREADY stored, per source and per content
 * hash. Knowing the current state is what lets {@code reindex()} re-embed
 * ONLY the files that actually changed.
 *
 * <p>PR #42: the production implementation is {@link PgVectorIndexStore}
 * (a thin SQL adapter over the pgvector table); tests use an in-memory twin.
 */
public interface VectorIndexStore {

    /** A stored chunk's identity + the sha-256 of its source file's content. */
    record StoredChunk(String id, String contentHash) {
    }

    /** Adds chunks (embedding them via the configured EmbeddingModel). */
    void addChunks(List<Document> chunks);

    /** Deletes chunks by their deterministic ids (no-op for an empty list). */
    void deleteChunks(List<String> ids);

    /** Existing chunks for one source file, or an empty list. */
    List<StoredChunk> chunksBySource(String source);

    /** Distinct source filenames currently in the index. */
    List<String> sources();
}