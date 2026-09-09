package com.company.orderapi.mcp;

import com.company.orderapi.rag.DocumentIngestionService;
import com.company.orderapi.rag.RagService;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: search project documentation using RAG (Retrieval-Augmented Generation).
 *
 * <p>Retrieves relevant documentation chunks from the vector store and
 * generates a grounded answer using DeepSeek. Only available when the
 * RAG feature is enabled ({@code app.rag.enabled=true}).
 *
 * <p>Read-only, no customer data. The documentation index is built on
 * application startup from the project's markdown files in {@code docs/}.
 */
@Component
@ConditionalOnBean(RagService.class)
public class DocsSearchTool extends AbstractMcpReadOnlyTool {

    private final RagService ragService;
    private final DocumentIngestionService ingestionService;

    public DocsSearchTool(RagService ragService, DocumentIngestionService ingestionService) {
        this.ragService = ragService;
        this.ingestionService = ingestionService;
    }

    @Override
    public String name() {
        return "docs_search";
    }

    @Override
    public String description() {
        return "Search project documentation and get AI-generated answers about "
                + "the Order Management API. Use this to answer questions about "
                + "architecture, features, setup, configuration, or how things work.";
    }

    @Override
    public JsonSchema inputSchema() {
        return objectSchema(Map.of(
                "question", Map.of(
                        "type", "string",
                        "description", "The question to answer from project documentation.")),
                List.of("question"));
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        String question = optionalText(arguments, "question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        if (!ingestionService.isIndexed()) {
            return "Documentation index is not loaded yet. "
                    + "Please try again after the application has fully started.";
        }

        return ragService.answer(question);
    }
}
