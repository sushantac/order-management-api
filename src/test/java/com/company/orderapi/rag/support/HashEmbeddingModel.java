package com.company.orderapi.rag.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;

/**
 * A deterministic, all-Java {@link EmbeddingModel} for tests: maps each word
 * token to a fixed dimension (by hash) with a fixed sign, then normalizes.
 * Two texts that share tokens therefore have non-zero cosine similarity, which
 * makes retrieval behave realistically while staying fast, offline and
 * reproducible - perfect for the retrieval-eval and pgvector tests. It is NOT a
 * semantic model; use it to test PLUMBING, not to judge real retrieval quality.
 */
public class HashEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public HashEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> results = request.getInstructions().stream()
                .map(text -> new Embedding(embed(text), null))
                .toList();
        return new EmbeddingResponse(results, new EmbeddingResponseMetadata());
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.isBlank()) {
                continue;
            }
            int dimension = Math.floorMod(token.hashCode(), dimensions);
            int sign = (token.hashCode() & 1) == 0 ? 1 : -1;
            vector[dimension] += sign;
        }
        normalize(vector);
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static void normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        double length = Math.sqrt(sum);
        if (length == 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / length);
        }
    }
}