package com.company.orderapi.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PR #46 - the {@code ask_docs} prompt template.
 *
 * <p>A minimal prompt template that packages "ask the documentation a
 * question" as a reusable instruction set. Unlike {@code SummarizeOrderPrompt}
 * it takes free text and is meant to pair with the {@code docs_search} RAG
 * tool - the template reminds the model to ground the answer in the retrieved
 * documentation and name its sources.
 */
@Component
public class DocsQuestionPrompt extends AbstractMcpPrompt {

    @Override
    public String name() {
        return "ask_docs";
    }

    @Override
    public String description() {
        return "Ask a documentation question about the Order Management API. "
                + "The model should use docs_search and answer from the retrieved context.";
    }

    @Override
    public List<McpSchema.PromptArgument> arguments() {
        return List.of(new McpSchema.PromptArgument(
                "question", "The documentation question to answer.", true));
    }

    @Override
    protected List<McpSchema.PromptMessage> messages(Map<String, Object> arguments) {
        String question = String.valueOf(arguments.get("question"));

        String rules = """
                You answer questions about the Order Management API project.
                Call the docs_search tool and answer using ONLY the retrieved
                documentation context. If the docs do not cover the question,
                say so clearly and name the source files you did find.
                """;

        return List.of(userMessage(rules), userMessage(question));
    }
}