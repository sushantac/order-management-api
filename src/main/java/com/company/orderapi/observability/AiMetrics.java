package com.company.orderapi.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PR #49 - AI-specific metrics for MCP, Agent, RAG, Streaming, Retrieval, Ingestion.
 */
@Component
public class AiMetrics {

    public static final String TAG_TOOL = "tool";
    public static final String TAG_STATUS = "status";
    public static final String TAG_AGENT = "agent";
    public static final String TAG_RETRIEVAL_MODE = "retrieval_mode";
    public static final String TAG_ENDPOINT = "endpoint";

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";

    private final MeterRegistry registry;

    private final ConcurrentHashMap<String, Timer> mcpToolTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> mcpToolCounters = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Timer> agentTurnTimers = new ConcurrentHashMap<>();
    private final Counter agentTurnsTotal;
    private final Counter agentToolCallsTotal;

    private final ConcurrentHashMap<String, Timer> ragQueryTimers = new ConcurrentHashMap<>();
    private final Counter ragQueriesTotal;
    private final DistributionSummary ragChunksRetrieved;

    private final Counter streamEventsTotal;
    private final ConcurrentHashMap<String, Timer> retrievalTimers = new ConcurrentHashMap<>();

    private final Counter ingestionDocumentsTotal;
    private final AtomicLong activeSessions = new AtomicLong(0);

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("mcp.sessions.active", activeSessions, AtomicLong::get)
                .description("Number of active MCP sessions")
                .register(registry);

        agentTurnsTotal = Counter.builder("agent.turns.total")
                .description("Total agent conversation turns")
                .register(registry);
        agentToolCallsTotal = Counter.builder("agent.tool_calls.total")
                .description("Total tool calls made by agent")
                .register(registry);

        ragQueriesTotal = Counter.builder("rag.queries.total")
                .description("Total RAG queries")
                .register(registry);
        ragChunksRetrieved = DistributionSummary.builder("rag.chunks_retrieved")
                .description("Number of chunks retrieved per query")
                .baseUnit("chunks")
                .register(registry);

        streamEventsTotal = Counter.builder("streaming.events.total")
                .description("Total SSE events emitted")
                .register(registry);

        ingestionDocumentsTotal = Counter.builder("ingestion.documents.total")
                .description("Total documents ingested")
                .register(registry);
    }

    public void recordToolCall(String toolName, String status, long durationNanos) {
        String key = toolName + "|" + status;
        Timer timer = mcpToolTimers.computeIfAbsent(key, k ->
                Timer.builder("mcp.tool.call.duration")
                        .tag(TAG_TOOL, toolName)
                        .tag(TAG_STATUS, status)
                        .description("MCP tool call latency")
                        .register(registry));
        timer.record(durationNanos, TimeUnit.NANOSECONDS);

        Counter counter = mcpToolCounters.computeIfAbsent(key, k ->
                Counter.builder("mcp.tool.call.total")
                        .tag(TAG_TOOL, toolName)
                        .tag(TAG_STATUS, status)
                        .description("MCP tool call count")
                        .register(registry));
        counter.increment();
    }

    public void recordAgentTurn(String agentName, String status, long durationNanos) {
        String key = agentName + "|" + status;
        Timer timer = agentTurnTimers.computeIfAbsent(key, k ->
                Timer.builder("agent.turn.duration")
                        .tag(TAG_AGENT, agentName)
                        .tag(TAG_STATUS, status)
                        .description("Agent turn latency")
                        .register(registry));
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
        agentTurnsTotal.increment();
    }

    public void recordAgentToolCall() {
        agentToolCallsTotal.increment();
    }

    public void recordRagQuery(String retrievalMode, String status, long durationNanos, int chunksRetrievedCount) {
        String key = retrievalMode + "|" + status;
        Timer timer = ragQueryTimers.computeIfAbsent(key, k ->
                Timer.builder("rag.query.duration")
                        .tag(TAG_RETRIEVAL_MODE, retrievalMode)
                        .tag(TAG_STATUS, status)
                        .description("RAG query latency")
                        .register(registry));
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
        ragQueriesTotal.increment();
        ragChunksRetrieved.record(chunksRetrievedCount);
    }

    public void recordStreamEvent(String endpoint) {
        streamEventsTotal.increment();
    }

    public void recordRetrieval(String searchType, boolean fused, long durationNanos) {
        String key = searchType + "|" + fused;
        Timer timer = retrievalTimers.computeIfAbsent(key, k ->
                Timer.builder("retrieval.search.duration")
                        .tag("search_type", searchType)
                        .tag("fused", String.valueOf(fused))
                        .description("Retrieval search latency")
                        .register(registry));
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordIngestion(int filesProcessed) {
        ingestionDocumentsTotal.increment(filesProcessed);
    }

    public void incrementActiveSessions() {
        activeSessions.incrementAndGet();
    }

    public void decrementActiveSessions() {
        activeSessions.decrementAndGet();
    }
}
