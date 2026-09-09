package com.company.orderapi.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * PR #39 - server-side agent (agentic tool calling).
 *
 * <p>Whereas the MCP tools let an EXTERNAL assistant call the API one tool at a
 * time, {@code AgentService} gives the DeepSeek chat model the same read-only
 * surface as <em>functions it can decide to call</em>. The model advertises the
 * tools, picks one, receives its result, and may pick another - an autonomous,
 * potentially multi-step loop owned by Spring AI's function-calling machinery.
 *
 * <p>{@link #ask(String)} is purely conversational: no structured exit, so the
 * model is free to chain {@code product_search} + {@code order_status} +
 * {@code docs_search} to answer a task that spans catalogue, orders and docs.
 *
 * <p>The agent is read-only by construction: every tool it can call comes from
 * {@link AgentToolSet}, which delegates to the same PII-free MCP tools.
 *
 * <p>PR #40 adds multi-turn chat memory: a {@link MessageChatMemoryAdvisor}
 * keeps a sliding message window per {@code conversationId}. Callers pass a
 * stable id to carry context across turns; omitting it makes each ask a fresh,
 * stateless question (a random per-call id is used so no context leaks between
 * unrelated asks).
 */
@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
@ConditionalOnBean(ChatModel.class)
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final String SYSTEM_PROMPT = """
            You are the Order Management API agent. You help users by calling the
            read-only tools you have been given (api_health, product_search,
            order_status, docs_search) to gather facts about the API, its product
            catalogue, order statuses and its documentation.

            Rules:
            - Call a tool instead of guessing or inventing data. The tool result is
              added to your context automatically.
            - You may call several tools in sequence to complete a task.
            - You remember previous questions and answers from the same conversation;
              refer to them when they help.
            - You have NO access to customer personal data and never claim to have it.
            - If the tools cannot answer, say so clearly.
            """;

    private final ChatClient chatClient;

    public AgentService(ChatModel chatModel, ToolCallbackProvider toolCallbacks, ChatMemory chatMemory) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(DefaultToolCallingChatOptions.builder()
                        .toolCallbacks(List.of(toolCallbacks.getToolCallbacks()))
                        .build())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * Asks the agent to perform a task, letting it decide which tools to call.
     *
     * @param task           a natural-language task or question
     * @param conversationId a stable id that groups turns into one conversation, or
     *                       {@code null}/{@code ""} for a stateless question
     * @return the agent's final answer
     */
    public String ask(String task, String conversationId) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String effectiveConversationId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId.trim();
        String answer = chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, effectiveConversationId))
                .user(task)
                .call()
                .content();
        log.debug("agent: conversation={}, task='{}', answer length={}",
                effectiveConversationId, task, answer == null ? 0 : answer.length());
        return answer;
    }
}