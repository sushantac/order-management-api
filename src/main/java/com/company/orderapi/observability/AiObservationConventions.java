package com.company.orderapi.observability;

/**
 * PR #49 - OpenTelemetry semantic conventions for AI/LLM operations.
 * Simplified constants only - ObservationConvention wiring is done via AiMetrics timers.
 */
public final class AiObservationConventions {

    private AiObservationConventions() {
    }

    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";
    public static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";

    public static final String OP_CHAT = "chat";
    public static final String OP_TOOL_CALL = "tool_call";
    public static final String OP_RETRIEVAL = "retrieval";
    public static final String OP_STREAMING = "streaming";

    public static final String SYSTEM_MCP = "mcp";
    public static final String SYSTEM_RAG = "rag";
    public static final String SYSTEM_AGENT = "agent";

    public record McpToolContext(String toolName, String sessionId, String actor) {}
    public record AgentTurnContext(String agentName, String conversationId, String userId) {}
    public record RagQueryContext(String retrievalMode, String embeddingModel, int topK,
                                  int chunksRetrieved, String queryHash) {}
    public record StreamingContext(String endpoint, String sessionId, long eventsEmitted) {}
    public record RetrievalContext(String searchType, int candidates, boolean fused) {}
    public record IngestionContext(String operation, int filesProcessed, int chunksCreated) {}
}
