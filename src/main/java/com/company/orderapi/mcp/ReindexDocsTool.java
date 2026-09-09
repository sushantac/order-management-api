package com.company.orderapi.mcp;

import com.company.orderapi.rag.DocumentIngestionService;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * PR #42 - the re-index TRIGGER as a guarded write tool.
 *
 * <p>RAG productionization's obvious problem (from the PR #38 doc): docs are
 * indexed once at startup, so edits to the corpus are invisible until a
 * restart. This tool makes re-indexing a first-class, guarded action:
 * <ul>
 *   <li>it MUTATES the persistent pgvector index, so it rides the exact same
 *       {@link AbstractMcpWriteTool} rails as {@code cancel_order} (PR #41):
 *       opt-in via {@code app.mcp.write-tool.enabled}, required
 *       {@code confirmed=true}, service-level side effects, structured audit
 *       log), and</li>
 *   <li>the re-index itself is incremental ({@link DocumentIngestionService#reindex()}
 *       skips unchanged files, so calling it is cheap and idempotent).</li>
 * </ul>
 *
 * <p>Exists only when RAG is enabled (the indexer bean exists) AND write tools
 * are enabled.
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "write-tool.enabled", havingValue = "true")
@ConditionalOnBean(DocumentIngestionService.class)
public class ReindexDocsTool extends AbstractMcpWriteTool {

    private static final Logger AUDIT = LoggerFactory.getLogger("mcp.reindex-docs");

    private final DocumentIngestionService ingestionService;

    public ReindexDocsTool(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public String name() {
        return "reindex_docs";
    }

    @Override
    public String description() {
        return "MUTATES DATA: refresh the documentation index. Re-reads the docs "
                + "corpus from the classpath and re-embeds ONLY files that changed "
                + "(content-addressed, idempotent - unchanged files are skipped, "
                + "removed files are dropped from the index). Requires "
                + "confirmed=true. Returns only counts, never doc content.";
    }

    @Override
    public JsonSchema inputSchema() {
        return objectSchema(Map.of(
                        "confirmed", Map.of(
                                "type", "boolean",
                                "description", "Must be true to execute this mutating tool. "
                                        + "Anything else refuses the call.")),
                List.of("confirmed"));
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        Object confirmed = arguments.get("confirmed");
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new IllegalArgumentException(
                    "Refusing to reindex docs: confirmed must be exactly true. "
                            + "Please confirm, then call again with confirmed=true.");
        }

        DocumentIngestionService.ReindexReport report;
        try {
            report = ingestionService.reindex();
        } catch (IOException e) {
            throw new IllegalStateException("Re-index failed: " + e.getMessage(), e);
        }
        AUDIT.info("reindex_docs confirmed=true filesReindexed={} chunksAdded={} "
                        + "chunksDeleted={} filesUnchanged={} sourcesRemoved={}",
                report.filesReindexed(), report.chunksAdded(), report.chunksDeleted(),
                report.filesUnchanged(), report.sourcesRemoved().size());
        return "Docs index refreshed: " + report.filesReindexed() + " file(s) re-embedded ("
                + report.chunksAdded() + " chunks added, " + report.chunksDeleted()
                + " stale chunks deleted), " + report.filesUnchanged() + " unchanged file(s) skipped, "
                + report.sourcesRemoved().size() + " removed source(s) cleaned up.";
    }
}