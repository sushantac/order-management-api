package com.company.orderapi.rag.eval;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Retrieval-eval harness: scores retrieval quality against a golden set.
 *
 * <p>RAG quality is a retrieval problem before it is a generation problem - if
 * the right chunk never reaches the prompt, DeepSeek cannot answer. This
 * harness turns "is our retrieval good?" into a measurable, gate-able number:
 * for each golden question it runs the top-k retriever and checks whether the
 * expected source document is in the retrieved set.
 *
 * <p>Scores (all per the golden set, top-k = {@code k}):
 * <ul>
 *   <li>{@code hitRateAtK}  - fraction of goldens whose expected source appears
 *       in the top-k retrieved chunks (the headline number).</li>
 *   <li>{@code top1Accuracy} - fraction where the expected source is the FIRST
 *       hit (highest similarity - what the LLM actually sees most strongly).</li>
 *   <li>{@code precisionAtK} - relevant retrieved chunks / (goldens * k);
 *       penalises goldens that were answered by noise.</li>
 * </ul>
 *
 * <p>The harness is deterministic and model-independent: it needs only the
 * retriever (top-k over the corpus), not the chat model. {@link
 * RagEvalRunner} runs it at startup (report-only, or gated by
 * {@code app.rag.eval.min-hit-rate}).
 */
public class RagRetrievalEvaluator {

    /** Retrieval side of RAG: top-k {@link Document}s for a question. */
    public interface Retriever {
        List<Document> retrieve(String question);
    }

    /** Per-question outcome - visible in logs so a miss is debuggable. */
    public record RetrievalEvalItem(
            String question,
            String expectedSource,
            List<String> retrievedSources,
            double topScore,
            boolean hit
    ) {
    }

    /** Aggregate + per-item scores for one evaluation run. */
    public record RetrievalEvalReport(
            int total,
            int hits,
            double hitRateAtK,
            double top1Accuracy,
            double precisionAtK,
            List<RetrievalEvalItem> items
    ) {
    }

    /**
     * Runs every golden question through the retriever and scores the result.
     *
     * @param retriever the top-k retriever (e.g. {@code ragService::retrieve})
     * @param goldens   the golden question set
     * @param k         how many chunks each question retrieves
     */
    public RetrievalEvalReport evaluate(Retriever retriever, List<GoldenQuestion> goldens, int k) {
        List<RetrievalEvalItem> items = goldens.stream()
                .map(golden -> evaluateOne(retriever, golden, k))
                .toList();

        long hits = items.stream().filter(RetrievalEvalItem::hit).count();
        long top1 = items.stream()
                .filter(item -> !item.retrievedSources().isEmpty())
                .filter(item -> item.expectedSource().equals(item.retrievedSources().get(0)))
                .count();
        double precisionDenominator = (double) Math.max(items.size(), 1) * Math.max(k, 1);
        double precisionAtK = items.stream()
                .mapToDouble(item -> item.hit() ? 1.0 : 0.0)
                .sum() / precisionDenominator;

        return new RetrievalEvalReport(
                items.size(),
                (int) hits,
                items.isEmpty() ? 0.0 : (double) hits / items.size(),
                items.isEmpty() ? 0.0 : (double) top1 / items.size(),
                precisionAtK,
                items);
    }

    private RetrievalEvalItem evaluateOne(Retriever retriever, GoldenQuestion golden, int k) {
        List<Document> docs = retriever.retrieve(golden.question());
        List<String> sources = docs.stream()
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("source", "")))
                .toList();
        double topScore = docs.isEmpty() ? 0.0
                : (docs.get(0).getScore() == null ? 0.0 : docs.get(0).getScore());
        return new RetrievalEvalItem(
                golden.question(),
                golden.expectedSource(),
                sources,
                topScore,
                !sources.isEmpty() && sources.subList(0, Math.min(k, sources.size())).contains(golden.expectedSource()));
    }
}