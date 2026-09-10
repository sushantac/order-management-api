package com.company.orderapi.rag.eval;

import com.company.orderapi.rag.RagProperties;
import com.company.orderapi.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Startup evaluation gate for RAG retrieval quality.
 *
 * <p>Only active when {@code app.rag.eval.enabled=true} (and RAG itself is on).
 * Loads the golden questions, runs the top-k retriever against each, logs the
 * {@link RagRetrievalEvaluator} score.
 *
 * <p>Runs on {@link ApplicationReadyEvent} at {@link Ordered#LOWEST_PRECEDENCE}
 * - deliberately <b>not</b> an {@code ApplicationRunner} - so it always
 * evaluates AFTER {@code DocumentIngestionService}'s ready-event re-index
 * ({@link Ordered#HIGHEST_PRECEDENCE}). A runner fires before ready-event
 * listeners and would measure an empty store: PR #44 found that exact bug when
 * the harness was first run end-to-end with a real embedding model.
 *
 * <p>PR #44: the run is also persisted to
 * {@code app.rag.eval.report-location} (a self-describing JSON snapshot -
 * timestamp, retrieval mode, per-question HIT/MISS trail) so the gate has an
 * audit trail and the dense-vs-hybrid comparison survives log rotation.
 *
 * <p>Threshold behaviour: {@code app.rag.eval.min-hit-rate} of {@code 0} means
 * report-only - logs the score but never blocks startup, which is the only sane
 * default before your corpus's quality has been measured once. Raise it (e.g.
 * {@code 0.7}) to turn the harness into a deploy gate: startup fails loudly if
 * retrieval regresses below the agreed standard.
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.eval", name = "enabled", havingValue = "true")
@ConditionalOnBean(RagService.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class RagEvalRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(RagEvalRunner.class);

    private final RagService ragService;
    private final RagProperties ragProperties;
    private final RagRetrievalEvaluator evaluator;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    public RagEvalRunner(RagService ragService, RagProperties ragProperties,
                         RagRetrievalEvaluator evaluator, ObjectMapper objectMapper,
                         ResourceLoader resourceLoader) {
        this.ragService = ragService;
        this.ragProperties = ragProperties;
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        run();
    }

    /** Self-describing snapshot of one evaluation run, written to the report file. */
    public record RagEvalReportSnapshot(
            Instant runAt,
            String retrievalMode,
            int topK,
            int goldens,
            int hits,
            double hitRateAtK,
            double top1Accuracy,
            double precisionAtK,
            List<Item> items
    ) {
        public record Item(
                String question,
                String expectedSource,
                boolean hit,
                List<String> retrievedSources
        ) {
        }
    }

    public void run() {
        try {
            List<GoldenQuestion> goldens = loadGoldens();
            int k = ragProperties.topK();
            RagRetrievalEvaluator.RetrievalEvalReport report =
                    evaluator.evaluate(ragService::retrieve, goldens, k);

            StringBuilder logLine = new StringBuilder();
            logLine.append("RAG retrieval eval: goldens=").append(report.total())
                    .append(" hitRate@").append(k).append('=').append(percent(report.hitRateAtK()))
                    .append(" top1Accuracy=").append(percent(report.top1Accuracy()))
                    .append(" precision@").append(k).append('=').append(percent(report.precisionAtK()))
                    .append(System.lineSeparator());
            for (RagRetrievalEvaluator.RetrievalEvalItem item : report.items()) {
                logLine.append("  [").append(item.hit() ? "HIT" : "MISS").append("] ")
                        .append(item.question()).append(" -> expected ")
                        .append(item.expectedSource()).append(" ; retrieved ")
                        .append(item.retrievedSources()).append(System.lineSeparator());
            }
            log.info(logLine.toString().strip());

            writeReport(report);

            double minHitRate = ragProperties.eval().minHitRate();
            if (minHitRate > 0 && report.hitRateAtK() < minHitRate) {
                throw new IllegalStateException("RAG retrieval eval gate FAILED: hitRate@" + k
                        + "=" + percent(report.hitRateAtK()) + " is below the required "
                        + percent(minHitRate) + " (app.rag.eval.min-hit-rate=" + minHitRate + "). "
                        + "Fix the corpus, the chunking, or the goldens before shipping.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("RAG retrieval eval could not load the golden questions: "
                    + e.getMessage(), e);
        }
    }

    private void writeReport(RagRetrievalEvaluator.RetrievalEvalReport report) {
        String location = ragProperties.eval().reportLocation();
        if (location == null || location.isBlank()) {
            return;
        }
        RagEvalReportSnapshot snapshot = new RagEvalReportSnapshot(
                Instant.now(),
                ragProperties.retrievalMode().name(),
                ragProperties.topK(),
                report.total(),
                report.hits(),
                report.hitRateAtK(),
                report.top1Accuracy(),
                report.precisionAtK(),
                report.items().stream()
                        .map(item -> new RagEvalReportSnapshot.Item(
                                item.question(), item.expectedSource(), item.hit(),
                                item.retrievedSources()))
                        .toList());
        try {
            Files.createDirectories(Path.of(location).getParent());
            objectMapper.writeValue(Path.of(location).toFile(), snapshot);
            log.info("RAG retrieval eval report written to {}", location);
        } catch (IOException e) {
            log.warn("RAG retrieval eval report could not be written to '{}': {}",
                    location, e.getMessage());
        }
    }

    private List<GoldenQuestion> loadGoldens() throws IOException {
        Resource resource = resourceLoader.getResource(ragProperties.eval().goldensLocation());
        if (!resource.exists()) {
            throw new IllegalStateException("Golden questions not found at '"
                    + ragProperties.eval().goldensLocation() + "'. Set app.rag.eval.goldens-location.");
        }
        return List.of(objectMapper.readValue(resource.getInputStream(), GoldenQuestion[].class));
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }
}