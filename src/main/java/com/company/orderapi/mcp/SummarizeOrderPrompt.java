package com.company.orderapi.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PR #46 - the {@code summarize_order} prompt template.
 *
 * <p>Packages "how to write a customer-safe order summary" as a reusable,
 * parameterized template any assistant can fetch via {@code prompts/get} and
 * inject into its conversation. The template sets the persona + constraints
 * (never leak customer PII, cite totals/status) and asks the assistant to run
 * the {@code order_status} tool for the given order id - the prompt plus the
 * existing read-only tool surface compose into a safe summary workflow.
 */
@Component
public class SummarizeOrderPrompt extends AbstractMcpPrompt {

    @Override
    public String name() {
        return "summarize_order";
    }

    @Override
    public String description() {
        return "Produce a customer-safe summary of an order for a support agent. "
                + "Requires an orderId. The model should call order_status to fetch facts.";
    }

    @Override
    public List<McpSchema.PromptArgument> arguments() {
        return List.of(new McpSchema.PromptArgument(
                "orderId", "The numeric id of the order to summarise.", true));
    }

    @Override
    protected List<McpSchema.PromptMessage> messages(Map<String, Object> arguments) {
        long orderId = arguments.get("orderId") instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(arguments.get("orderId")));

        String system = """
                You are a support agent for the Order Management API.
                Summarise order %d for the customer-facing support team.
                Call the order_status tool to fetch the public order facts.
                Rules:
                - Only report facts returned by the tool. Never invent prices or dates.
                - Never reveal customer personal data or account details.
                - End with the order number, total amount and current status.""".formatted(orderId);

        String task = "Please summarise order " + orderId + ".";
        // MCP PromptMessage roles are limited to USER/ASSISTANT, so the persona +
        // rules travel as a USER-role instruction message before the task message.
        return List.of(userMessage(system), userMessage(task));
    }
}