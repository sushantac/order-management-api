package com.company.orderapi.rag.eval;

import com.company.orderapi.rag.RagProperties;
import com.company.orderapi.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private RagEvalRunner runnerWith(RagService ragService, double minHitRate, String reportLocation) {
        RagProperties.RagEvalProperties eval =
                new RagProperties.RagEvalProperties(true,
                        "classpath:rag/eval/golden-questions.json", minHitRate, reportLocation);
        RagProperties props = new RagProperties(
                true, "classpath:docs/**/*.md", 800, 200, 3, 768,
                "vector_store", RagProperties.RetrievalMode.HYBRID, null, eval);
        return new RagEvalRunner(ragService, props, new RagRetrievalEvaluator(),
                objectMapper, new DefaultResourceLoader());
    }

    @Test
    void reportOnlyModeNeverBlocksStartupEvenWhenEverythingMisses(@TempDir Path tempDir) throws Exception {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of()); // retrieval finds nothing at all
        String reportLocation = tempDir.resolve("report.json").toString();

        RagEvalRunner runner = runnerWith(ragService, 0.0, reportLocation);

        runner.run();
    }

    @Test
    void gatedModeFailsStartupWhenHitRateDropsBelowTheThreshold(@TempDir Path tempDir) {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());

        RagEvalRunner runner = runnerWith(ragService, 0.9, tempDir.resolve("report.json").toString());

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG retrieval eval gate FAILED")
                .hasMessageContaining("below the required");
    }

    @Test
    void goldenQuestionsLoadFromTheShippedClasspathFile(@TempDir Path tempDir) {
        RagService ragService = mock(RagService.class);
        RagEvalRunner runner = runnerWith(ragService, 1.0, tempDir.resolve("report.json").toString());

        // With hit-rate required at 1.0 the runner can only ever pass if the
        // goldens load AND every question retrieves its expected source; here
        // retrieval is empty, so it must fail on the gate (proving the goldens
        // actually wired up a non-empty set through the evaluator).
        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG retrieval eval gate FAILED")
                .hasMessageContaining("min-hit-rate=1.0");
    }

    @Test
    void runWritesASelfDescribingReportWithTheAuditTrail(@TempDir Path tempDir) throws Exception {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve("Why must cancel_order be called with confirmed exactly equal to true?"))
                .thenReturn(List.of(new Document("the guarded write tool requires confirmed=true",
                        java.util.Map.of("source", "additions/04-guarded-write-tool.md"))));
        when(ragService.retrieve("How does the outbox pattern make order event publishing reliable and safe?"))
                .thenReturn(List.of(new Document("outbox pattern",
                        java.util.Map.of("source", "interview-cheat-sheets/06-event-driven-and-kafka.md"))));
        // Any unmatched golden simply misses; the two stubbed hits are enough to
        // prove the snapshot carries the real audit trail.
        String reportLocation = tempDir.resolve("reports").resolve("last-eval.json").toString();

        RagEvalRunner runner = runnerWith(ragService, 0.0, reportLocation);

        runner.run();

        Path report = Path.of(reportLocation);
        assertThat(report).exists();
        RagEvalRunner.RagEvalReportSnapshot snapshot = objectMapper.readValue(
                report.toFile(), RagEvalRunner.RagEvalReportSnapshot.class);
        assertThat(snapshot.retrievalMode()).isEqualTo("HYBRID");
        assertThat(snapshot.topK()).isEqualTo(3);
        assertThat(snapshot.goldens()).isEqualTo(12);
        assertThat(snapshot.runAt()).isNotNull();
        assertThat(snapshot.hits()).isEqualTo(2);
        assertThat(snapshot.items()).hasSize(12);
        assertThat(snapshot.items().stream().filter(RagEvalRunner.RagEvalReportSnapshot.Item::hit).count())
                .isEqualTo(snapshot.hits());
        assertThat(snapshot.items().stream()
                .filter(item -> item.question().contains("outbox"))
                .findFirst().orElseThrow().expectedSource())
                .isEqualTo("interview-cheat-sheets/06-event-driven-and-kafka.md");
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