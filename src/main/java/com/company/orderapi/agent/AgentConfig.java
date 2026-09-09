package com.company.orderapi.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR #39 - exposes the {@link AgentToolSet} to the LLM as Spring AI tool
 * callbacks; PR #40 - provides the agent's chat memory.
 *
 * <p>{@link MethodToolCallbackProvider} introspects the {@code @Tool}-annotated
 * methods and turns each one into a {@link org.springframework.ai.tool.ToolCallback}
 * carrying a JSON schema the model can advertise for function calling. The
 * resulting provider is registered on the {@code ChatClient} built in
 * {@link AgentService}.
 *
 * <p>{@link #agentChatMemory(int)} returns a sliding-window {@link ChatMemory}
 * (default 20 messages, in-memory) keyed per conversation id. It is consumed by
 * the {@code MessageChatMemoryAdvisor} wired in {@link AgentService}, giving the
 * agent multi-turn context scoped to one {@code agentic_ask} conversation.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class AgentConfig {

    @Bean
    public ToolCallbackProvider agentToolCallbacks(AgentToolSet agentToolSet) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(agentToolSet)
                .build();
    }

    @Bean
    public ChatMemory agentChatMemory(
            @Value("${app.agent.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .maxMessages(maxMessages)
                .build();
    }
}