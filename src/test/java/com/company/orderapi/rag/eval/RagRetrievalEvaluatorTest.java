package com.company.orderapi.rag.eval;

import com.company.orderapi.rag.support.HashEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval-eval harness test - the part PR #42 argues almost nobody learns.
 *
 * <p>{@code RagRetrievalEvaluator} scores RAG's *retrieval* half: given a
 * golden set of (question -> expected source), how often does the top-k
 * retriever actually return the right document? This test proves the scoring
 * is real and sensitive by driving it with a REAL cosine-similarity retriever
 * (SimpleVectorStore + deterministic HashEmbeddingModel) over a tiny synthetic
 * corpus - no remote models, fully reproducible.
 */
class RagRetrievalEvaluatorTest {

    private static final int DIMS = 16;

    private VectorStore vectorStore;
    private final RagRetrievalEvaluator evaluator = new RagRetrievalEvaluator();

    @BeforeEach
    void setUp() {
        vectorStore = SimpleVectorStore.builder(new HashEmbeddingModel(DIMS)).build();
    }

    @Test
    void hitRateAtKIsPerfectWhenEveryGoldenQuestionRetrievesItsExpectedSource() {
        seedCorpus(corpusWithDistinctTopics());
        List<GoldenQuestion> goldens = List.of(
                new GoldenQuestion(
                        "How does the api handle failing payment charges?",
                        "payments.md"),
                new GoldenQuestion(
                        "Where is optimistic locking used against stock overselling?",
                        "orders.md"),
                new GoldenQuestion(
                        "Which transport does the kafka producer use for order events?",
                        "messaging.md"));

        RagRetrievalEvaluator.RetrievalEvalReport report =
                evaluator.evaluate(question -> vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query(question).topK(3).build()), goldens, 3);

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.hits()).isEqualTo(3);
        assertThat(report.hitRateAtK()).isEqualTo(1.0);
    }

    @Test
    void aMissIsCountedAndDragsTheScoreDown() {
        seedCorpus(corpusWithDistinctTopics());
        // A golden whose expected source simply does not exist in the corpus.
        List<GoldenQuestion> goldens = List.of(
                new GoldenQuestion(
                        "How does the api handle failing payment charges?",
                        "payments.md"),
                new GoldenQuestion(
                        "What is the caching policy for unrelated feature X?",
                        "unrelated.md"));

        RagRetrievalEvaluator.RetrievalEvalReport report =
                evaluator.evaluate(question -> vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query(question).topK(3).build()), goldens, 3);

        assertThat(report.hits()).isEqualTo(1);
        assertThat(report.hitRateAtK()).isEqualTo(0.5);
        assertThat(report.items()).hasSize(2);
        assertThat(report.items().get(1).hit()).isFalse();
        assertThat(report.items().get(1).retrievedSources())
                .as("the source the golden WANTED is absent from the corpus")
                .doesNotContain("unrelated.md");
    }

    @Test
    void reportSurfacesRetrievedSourcesPerQuestionForDebugging() {
        seedCorpus(corpusWithDistinctTopics());

        RagRetrievalEvaluator.RetrievalEvalReport report =
                evaluator.evaluate(question -> vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query(question).topK(2).build()),
                        List.of(new GoldenQuestion(
                                "How does the api handle failing payment charges?",
                                "payments.md")), 2);

        assertThat(report.items().get(0).retrievedSources())
                .contains("payments.md");
    }

    private VectorStore seedCorpus(List<Document> chunks) {
        vectorStore.add(chunks);
        return vectorStore;
    }

    private static List<Document> corpusWithDistinctTopics() {
        List<Document> docs = new ArrayList<>();
        docs.add(new Document("When a payment charge fails, the whole order is rolled back "
                + "and the customer is informed.",
                Map.of("source", "payments.md")));
        docs.add(new Document("Orders use optimistic locking on stock so simultaneous buyers "
                + "cannot oversell the same widget.",
                Map.of("source", "orders.md")));
        docs.add(new Document("Order events flow through the kafka producer on the outbox "
                + "transport to consumers.",
                Map.of("source", "messaging.md")));
        return docs;
    }
}