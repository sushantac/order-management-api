# 12. AI-Feature Observability: metrics, traces and structured logs for every AI call

> **PR #49** — AI features (MCP tools, RAG, agent turns, streaming) are now observable by default: Prometheus counters/timers per tool/mode, Micrometer tracing spans via OTel, and JSON logs in prod with correlation IDs. No separate sidecar needed — the same Prometheus + OTLP collector (Jaeger) that serves the rest of the app now sees AI.

---

## 1. The one-paragraph mental model

Every AI operation is measured on three planes:

- **Metrics** (counts + latency) — *how many, how fast, how often it fails*.
- **Traces** (spans) — *where time went* across retrieval → rerank → LLM.
- **Logs** (structured JSON) — *what happened* with correlationId + actor + tool.

`AiMetrics` is the single meter registry facade. Tool handlers call `recordToolCall()` with tool name + status + duration; `RagService` calls `recordRagQuery()` with retrieval mode + chunks; streaming adds `recordStreamEvent()`. All meters are registered lazily per tag combination so cardinality stays low.

---

## 2. Why this matters

Before this PR AI calls were invisible to ops:

- A slow `docs_search` looked like a slow HTTP request — no separate SLI.
- A failing MCP tool left only an audit row, not a metric or trace.
- Streaming TTFT and tokens/sec were not measured.

Now each AI surface has its own SLI:

| Surface | Metric | Trace |
|---------|--------|-------|
| MCP `tools/call` | `mcp.tool.call.duration` (tool,status) + `mcp.tool.call.total` | transport context span |
| Agent turn | `agent.turn.duration` (agent,status) | `agent.turn` observation |
| RAG query | `rag.query.duration` (mode,status) + `rag.chunks_retrieved` | `rag.query` span |
| Retrieval | `retrieval.search.duration` (type,fused) | retrieval span |
| Streaming | `streaming.events.total` + connection duration | streaming span |

---

## 3. The code (what changed)

| File | Change |
|------|--------|
| `observability/AiMetrics.java` | Central meter facades: timers per tool/mode, counters, DistributionSummary, active-session Gauge |
| `observability/AiObservationConventions.java` | OTel GenAI semantic convention constants |
| `mcp/AbstractMcpReadOnlyTool.java` + `AbstractMcpWriteTool.java` | `specification(AuditService, AiMetrics)` overload — records `mcp.tool.call.duration` + `total` on success/error, with `System.nanoTime()` |
| `mcp/McpServerConfiguration.java` | Injects `AiMetrics` into tool specifications |
| `rag/RagService.java` | Injects `AiMetrics`, wraps `answer()` with `recordRagQuery()` (status success/error, chunks, duration) |

### Wiring example (MCP)

```java
long start = System.nanoTime();
try {
    String result = execute(arguments);
    aiMetrics.recordToolCall(name(), STATUS_SUCCESS, System.nanoTime() - start);
    return new CallToolResult(result, false);
} catch (IllegalArgumentException e) {
    aiMetrics.recordToolCall(name(), STATUS_ERROR, System.nanoTime() - start);
    return new CallToolResult(msg, true);
}
```

### Wiring example (RAG)

```java
long start = System.nanoTime();
try {
    List<Document> docs = retrievalEngine.retrieve(question, topK);
    String answer = chatModel.call(prompt).getResult().getOutput().getText();
    return answer;
} finally {
    aiMetrics.recordRagQuery(retrievalMode, status, duration, docs.size());
}
```

---

## 4. How to try

```bash
# Prometheus scrape
curl -s http://localhost:8080/actuator/prometheus | grep -E "mcp_tool|rag_query|agent_turn"

# OTLP traces (Jaeger in docker-compose)
# Set OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318, then query Jaeger UI at http://localhost:16686
```

---

## 5. Honest limits

1. **No per-token LLM metrics yet** — input/output token counts are not yet extracted from `ChatResponse.metadata` (provider-specific).
2. **Stateless MCP = no session duration histogram** — `mcp.sessions.active` Gauge exists but session lifetime is not timed.
3. **Sampling 10%** — prod traces sample 10%; adjust `management.tracing.sampling.probability` for load.

*Next: PR #50 — expanding guarded write tools beyond `cancel_order`.*
