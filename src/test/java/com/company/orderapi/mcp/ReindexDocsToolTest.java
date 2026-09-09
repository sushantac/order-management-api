package com.company.orderapi.mcp;

import com.company.orderapi.rag.DocumentIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * PR #42 - the re-index TRIGGER as a guarded write tool. Same contract as
 * {@code cancel_order} (PR #41): never mutates without an explicit
 * {@code confirmed=true}, and the actual side effects run through the
 * domain service ({@link DocumentIngestionService#reindex()}).
 */
class ReindexDocsToolTest {

    private DocumentIngestionService ingestionService;
    private ReindexDocsTool tool;

    @BeforeEach
    void setUp() {
        ingestionService = mock(DocumentIngestionService.class);
        tool = new ReindexDocsTool(ingestionService);
    }

    @Test
    void refusesWithoutConfirmationAndNeverReindexes() throws IOException {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed must be exactly true");
        assertThatThrownBy(() -> tool.execute(Map.of("confirmed", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed must be exactly true");

        verify(ingestionService, never()).reindex();
    }

    @Test
    void confirmedTrueReindexesAndReportsOnlyCounts() throws IOException {
        DocumentIngestionService.ReindexReport report =
                new DocumentIngestionService.ReindexReport(3, 12, 5, 20, List.of("gone.md"));
        when(ingestionService.reindex()).thenReturn(report);

        String result = tool.execute(Map.of("confirmed", true));

        verify(ingestionService).reindex();
        assertThat(result).contains("3 file(s) re-embedded")
                .contains("12 chunks added")
                .contains("5 stale chunks deleted")
                .contains("20 unchanged")
                .contains("1 removed source");
        assertThat(result).doesNotContain("#include").doesNotContain("docs/");
    }

    @Test
    void reindexFailureBecomesAnErrorNotASilentSuccess() throws Exception {
        when(ingestionService.reindex()).thenThrow(new IOException("corpus missing"));

        assertThatThrownBy(() -> tool.execute(Map.of("confirmed", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Re-index failed")
                .hasMessageContaining("corpus missing");
    }

    @Test
    void writeToolSchemaRequiresExplicitConfirmation() {
        assertThat(tool.name()).isEqualTo("reindex_docs");
        assertThat(tool.description()).contains("MUTATES DATA");
        assertThat(tool.inputSchema().required()).containsExactly("confirmed");
    }
}