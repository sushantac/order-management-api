package com.company.orderapi.agent;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR #39 - exposes the {@link AgentToolSet} to the LLM as Spring AI tool
 * callbacks.
 *
 * <p>{@link MethodToolCallbackProvider} introspects the {@code @Tool}-annotated
 * methods and turns each one into a {@link org.springframework.ai.tool.ToolCallback}
 * carrying a JSON schema the model can advertise for function calling. The
 * resulting provider is registered on the {@code ChatClient} built in
 * {@link AgentService}.
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
}