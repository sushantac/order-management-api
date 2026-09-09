package com.company.orderapi.rag.eval;

import com.company.orderapi.rag.RagProperties;
import com.company.orderapi.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code app.rag.eval} deploy gate: report-only by default (min-hit-rate
 * = 0), a loud startup failure once a threshold is set and retrieval drops
 * below it. Golden questions load from the real classpath file.
 */
class RagEvalRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RagEvalRunner runnerWith(RagService ragService, double minHitRate) {
        RagProperties.RagEvalProperties eval =
                new RagProperties.RagEvalProperties(true, "classpath:rag/eval/golden-questions.json", minHitRate);
        RagProperties props = new RagProperties(
                true, "classpath:docs/**/*.md", 800, 200, 3, 768,
                "vector_store", RagProperties.RetrievalMode.HYBRID, null, eval);
        return new RagEvalRunner(ragService, props, new RagRetrievalEvaluator(),
                objectMapper, new DefaultResourceLoader());
    }

    @Test
    void reportOnlyModeNeverBlocksStartupEvenWhenEverythingMisses() throws Exception {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of()); // retrieval finds nothing at all

        RagEvalRunner runner = runnerWith(ragService, 0.0);

        runner.run(new DefaultApplicationArguments());
    }

    @Test
    void gatedModeFailsStartupWhenHitRateDropsBelowTheThreshold() {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());

        RagEvalRunner runner = runnerWith(ragService, 0.9);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG retrieval eval gate FAILED")
                .hasMessageContaining("below the required");
    }

    @Test
    void goldenQuestionsLoadFromTheShippedClasspathFile() throws Exception {
        RagService ragService = mock(RagService.class);
        RagEvalRunner runner = runnerWith(ragService, 1.0);

        // With hit-rate required at 1.0 the runner can only ever pass if the
        // goldens load AND every question retrieves its expected source; here
        // retrieval is empty, so it must fail on the gate (proving the goldens
        // actually wired up a non-empty set through the evaluator).
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG retrieval eval gate FAILED")
                .hasMessageContaining("min-hit-rate=1.0");
    }

    /** Proves {@link RagService#retrieve} returns documents shaped for the evaluator. */
    @Test
    void evaluatorConsumesRagServiceRetrieveDirectly() {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve("how does outbox work"))
                .thenReturn(List.of(new Document("the outbox pattern",
                        java.util.Map.of("source", "06-event-driven-and-kafka.md"))));

        RagRetrievalEvaluator.RetrievalEvalReport report = new RagRetrievalEvaluator()
                .evaluate(ragService::retrieve, List.of(new GoldenQuestion(
                        "how does outbox work", "06-event-driven-and-kafka.md")), 3);

        assertThat(report.hitRateAtK()).isEqualTo(1.0);
        assertThat(report.items().get(0).retrievedSources()).contains("06-event-driven-and-kafka.md");
    }
}