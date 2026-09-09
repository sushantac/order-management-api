package com.company.orderapi.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PR #43 - hybrid retrieval: fuse the dense (embedding) and lexical (Postgres
 * full-text) rankings with <b>Reciprocal Rank Fusion</b> (RRF), then optionally
 * re-rank with <b>Maximal Marginal Relevance</b> (MMR) for diversity.
 *
 * <p>Reciprocal Rank Fusion is a rank-based ensemble: each document accrues
 * {@code 1 / (rrfK + rank)} per list it appears in. It needs no score
 * calibration between two incomparable relevance signals (cosine similarity vs
 * {@code ts_rank}) and is famously robust - that is the whole point.
 *
 * <p>MMR then trades a little relevance for coverage: {@code mmrScore =
 * lambda * sim(query, doc) - (1 - lambda) * maxSim(doc, alreadySelected)}.
 * This stops a single dominant topic from monopolizing the whole top-k when a
 * multi-part question touches several docs (e.g. PR #42's eval-harness goldens
 * ask about pgvector <i>and</i> the re-index trigger at once).
 */
public class HybridRetrievalEngine implements RetrievalEngine {

    /** How many candidates each single engine contributes before fusion. */
    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int MIN_CANDIDATES = 20;

    private final RetrievalEngine dense;
    private final RetrievalEngine lexical;
    private final EmbeddingModel embeddingModel;
    private final RagProperties.RetrievalSettings settings;

    public HybridRetrievalEngine(RetrievalEngine dense, RetrievalEngine lexical,
                                 EmbeddingModel embeddingModel,
                                 RagProperties.RetrievalSettings settings) {
        this.dense = dense;
        this.lexical = lexical;
        this.embeddingModel = embeddingModel;
        this.settings = settings;
    }

    @Override
    public List<Document> retrieve(String query, int topK) {
        int candidates = Math.max(topK * CANDIDATE_MULTIPLIER, MIN_CANDIDATES);
        List<Document> denseDocs = dense.retrieve(query, candidates);
        List<Document> lexDocs = lexical.retrieve(query, candidates);

        Map<String, Document> byId = new LinkedHashMap<>();
        denseDocs.forEach(doc -> byId.putIfAbsent(doc.getId(), doc));
        lexDocs.forEach(doc -> byId.putIfAbsent(doc.getId(), doc));

        Map<String, Double> fused = rrfScores(denseDocs, lexDocs, settings.rrfK());
        List<Document> ordered = byId.keySet().stream()
                .sorted(Comparator.comparingDouble((String id) -> fused.getOrDefault(id, 0.0)).reversed())
                .map(byId::get)
                .toList();

        List<Document> reranked = settings.mmrEnabled()
                ? mmrRerank(query, ordered, topK)
                : ordered;
        return reranked.stream().limit(topK).toList();
    }

    private static Map<String, Double> rrfScores(List<Document> first, List<Document> second, int k) {
        Map<String, Double> scores = new HashMap<>();
        accumulate(first, scores, k);
        accumulate(second, scores, k);
        return scores;
    }

    private static void accumulate(List<Document> docs, Map<String, Double> scores, int k) {
        for (int i = 0; i < docs.size(); i++) {
            scores.merge(docs.get(i).getId(), 1.0 / (k + i + 1), Double::sum);
        }
    }

    private List<Document> mmrRerank(String query, List<Document> candidates, int topK) {
        float[] queryVector = embeddingModel.embed(query);
        Map<String, float[]> vectors = new ConcurrentHashMap<>();
        List<Document> selected = new ArrayList<>();
        List<Document> remaining = new ArrayList<>(candidates);
        double lambda = settings.mmrLambda();

        while (selected.size() < topK && !remaining.isEmpty()) {
            Document best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Document candidate : remaining) {
                float[] candidateVector = vectors.computeIfAbsent(
                        candidate.getId(), id -> embeddingModel.embed(candidate.getText()));
                double relevancy = cosine(queryVector, candidateVector);
                double maxSimilarityToSelected = selected.stream()
                        .mapToDouble(selectedDoc -> cosine(
                                vectors.computeIfAbsent(selectedDoc.getId(),
                                        id -> embeddingModel.embed(selectedDoc.getText())),
                                candidateVector))
                        .max()
                        .orElse(0.0);
                double mmr = lambda * relevancy - (1.0 - lambda) * maxSimilarityToSelected;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = candidate;
                }
            }
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0.0 : dot / denominator;
    }
}