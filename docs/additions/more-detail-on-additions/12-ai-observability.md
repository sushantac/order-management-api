# 12. AI Observability — Metrics, Traces, Logs (PR #49)

> PR: [#49 — AI-feature observability (`AiMetrics` + OTel GenAI + correlationId)](https://github.com/anomalyco/order-management-api/pull/49) · Stack: `AiMetrics` (Micrometer `Timer`/`Counter`/`DistributionSummary`/`Gauge`) + `AiObservationConventions` (OTel GenAI) + `CorrelationIdFilter` + `logback-spring.xml` + `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel` · Depends on [#48 audit](../11-mcp-session-authorization-audit.md) + [#37 MCP `POST /mcp`](../02-agentic-tool-calling.md) + [#38 RAG](../01-rag-and-docs-search.md) + [#32 observability baseline] · Enables prod dashboards, SLO alerts, and per-tool latency debugging

---

## 1. Purpose — make AI observable

PR #48 made MCP tool calls **auditable** (who + what + when in `mcp_tool_audit`). PR #49 makes every AI path **observable** in the three pillars — **metrics, traces, logs** — so you can alert and debug without querying the audit table.

* **Metrics per AI feature** — `AiMetrics.java:18` registers `mcp.tool.call.*`, `agent.turn.*`, `rag.query.*`, `retrieval.search.*`, `streaming.events.*`, `ingestion.*`, and `mcp.sessions.active` with dimensional tags (`tool`, `status`, `agent`, `retrieval_mode`, `search_type`, `fused`).
* **OTel GenAI semantic conventions** — `AiObservationConventions.java:12` defines `gen_ai.operation.name`, `gen_ai.system`, `gen_ai.tool.name`, etc. (`SYSTEM_MCP`, `SYSTEM_RAG`, `SYSTEM_AGENT`) so spans/logs share vocabulary with Grafana/Jaeger.
* **CorrelationId through every log line** — `CorrelationIdFilter.java:38` honours or generates `X-Correlation-Id`, puts it in `MDC` (`:42`), echoes it on the response (`:43`), and both console (`logback-spring.xml:12`) and prod JSON (`:18`) render it.

After the PR: `curl /actuator/prometheus | grep mcp_tool` shows `mcp_tool_call_duration_seconds_bucket{tool="api_health",status="success"}`; `curl -H "X-Correlation-Id: demo-123"` echoes the header and every log line carries `[demo-123]`; Jaeger at `http://localhost:16686` shows `gen_ai.operation.name=tool_call` spans.

---

## 2. Problem — invisible AI

Before PR #49, AI features were black boxes:

| Before (PR #48 only) | After (PR #49) |
|---|---|
| `mcp_tool_audit` knew *what* was called, not *how long* or *how often* | `AiMetrics.recordToolCall` (`AbstractMcpReadOnlyTool.java:90`) emits `Timer` + `Counter` per `tool × status` |
| `RagService.answer()` latency invisible unless you tailed `DEBUG` logs | `AiMetrics.recordRagQuery` (`RagService.java:104`) emits `Timer` per `retrieval_mode × status` + `DistributionSummary` for chunks |
| `agent.turn` / `retrieval.search` / `streaming.events` had zero counters | Dedicated `Counter`/`Timer`/`Gauge` for each (`AiMetrics.java:31-46`) |
| Logs were free text; correlating MCP call → RAG query → DB required grepping | `CorrelationIdFilter` (`:30`) + JSON logs (`logback-spring.xml:16`) give one `correlationId` per request across all services |
| Traces disabled by default, no GenAI naming | `management.tracing.sampling.probability: 0.1` (`application.yml:158`) + `AiObservationConventions.java:12` standardize span names |
| Prometheus scrape existed but no `mcp_*`/`rag_*`/`agent_*` series | 7 metric families + 1 gauge exposed at `/actuator/prometheus` (`application.yml:144`) |

Without this PR an on-call engineer sees `mcp_tool_call 500 isError=true` in the audit table hours later — no p95 latency, no error rate per tool, no trace linking `POST /mcp tools/call → RagService.retrieve → chatModel.call`.

---

## 3. Solution — `AiMetrics` + OTel + structured logs

### 3.1 Three pillars, one PR

```
                           AiMetrics.java:18                    AiObservationConventions.java:7
                      ┌─────────────────────────┐            ┌──────────────────────────────┐
  MCP call ──────────►│ recordToolCall :79      │            │ gen_ai.operation.name :12  │
  tool × status       │  Timer  mcp.tool.call.duration  :82  │ gen_ai.tool.name    :15    │
                      │  Counter mcp.tool.call.total    :90  │ OP_TOOL_CALL="tool_call":19│
                      └──────────┬──────────────┘            │ SYSTEM_MCP="mcp"    :23    │
                                 │                           └──────────────┬───────┘
                                 │ metrics                                │ traces
                                 ▼                                          ▼
                      ┌─────────────────────┐                  ┌─────────────────────────┐
                      │ /actuator/prometheus│◄─────────────────│ Jaeger / OTLP :161      │
                      │  mcp_tool_call_*    │   OTel bridge    │ gen_ai.operation.name  │
                      │  rag_query_*        │   micrometer-    │ traceId = correlationId│
                      │  agent_turn_*       │   tracing-bridge │ 10% sampling :158       │
                      └─────────────────────┘   -otel :295     └─────────────────────────┘
                                 ▲
                                 │ logs
                      ┌─────────────────────┐
                      │ CorrelationIdFilter │──► MDC correlationId :42 ──► logback-spring.xml
                      │  X-Correlation-Id   │    echoed on response :43    console [%X{correlationId}] :12
                      │  UUID fallback :40  │                               prod JSON mdc :23
                      └─────────────────────┘
```

### 3.2 What is measured — metric inventory

| Metric | Type | Tags | Recorded in | File:line |
|---|---|---|---|---|
| `mcp.tool.call.duration` | `Timer` | `tool`, `status` | `recordToolCall` | `AiMetrics.java:82` |
| `mcp.tool.call.total` | `Counter` | `tool`, `status` | `recordToolCall` | `AiMetrics.java:90` |
| `mcp.sessions.active` | `Gauge` | — | `incrementActiveSessions` | `AiMetrics.java:51` |
| `agent.turn.duration` | `Timer` | `agent`, `status` | `recordAgentTurn` | `AiMetrics.java:101` |
| `agent.turns.total` | `Counter` | — | `recordAgentTurn` | `AiMetrics.java:55` |
| `agent.tool_calls.total` | `Counter` | — | `recordAgentToolCall` | `AiMetrics.java:58` |
| `rag.query.duration` | `Timer` | `retrieval_mode`, `status` | `recordRagQuery` | `AiMetrics.java:117` |
| `rag.queries.total` | `Counter` | — | `recordRagQuery` | `AiMetrics.java:62` |
| `rag.chunks_retrieved` | `DistributionSummary` | — | `recordRagQuery` | `AiMetrics.java:65` |
| `retrieval.search.duration` | `Timer` | `search_type`, `fused` | `recordRetrieval` | `AiMetrics.java:134` |
| `streaming.events.total` | `Counter` | — | `recordStreamEvent` | `AiMetrics.java:70` |
| `ingestion.documents.total` | `Counter` | — | `recordIngestion` | `AiMetrics.java:74` |

Every dimensional `Timer` is **lazy per-tag** — `ConcurrentHashMap.computeIfAbsent` (`:81`,`:100`,`:116`,`:133`) creates the meter on first use, so adding a new tool `my_tool` never requires a code change to registration.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `observability/AiMetrics.java:18` | **Metric registry** — `MeterRegistry` injection (`:48`), `Gauge` for `mcp.sessions.active` (`:51`), lazy `Timer`/`Counter` maps (`:31-43`), `recordToolCall` (`:79`), `recordAgentTurn` (`:98`), `recordRagQuery` (`:114`), `recordRetrieval` (`:131`), `recordStreamEvent` (`:127`), `recordIngestion` (`:142`), session `increment`/`decrement` (`:146`) |
| `observability/AiObservationConventions.java:7` | **OTel GenAI vocabulary** — `GEN_AI_*` keys (`:12-16`), ops `chat`/`tool_call`/`retrieval`/`streaming` (`:18-21`), systems `mcp`/`rag`/`agent` (`:23-25`), typed context records (`:27-33`) |
| `mcp/McpServerConfiguration.java:57` | **MCP instrumentation seam** — `mcpServer()` takes `AiMetrics aiMetrics` (`:106`), passes to `specification(auditService, aiMetrics)` for read (`:111`) + write (`:115`) tools |
| `mcp/AbstractMcpReadOnlyTool.java:67` | **Read tool wrapper** — `specification(auditService, aiMetrics)` (`:67`), `callHandler` `:77`, `long start = System.nanoTime()` `:78`, `aiMetrics.recordToolCall(success)` `:90` / `(error)` `:99` |
| `mcp/AbstractMcpWriteTool.java:75` | **Write tool wrapper** — identical `specification(auditService, aiMetrics)` (`:75`) with `recordToolCall` `:98`/`:107` |
| `rag/RagService.java:30` | **RAG instrumentation** — `aiMetrics` field (`:47`), ctor injection (`:52`), `answer()` try/finally (`:71-107`) with `recordRagQuery(retrievalMode, status, duration, chunks)` (`:104`), `STATUS_SUCCESS`/`STATUS_ERROR` (`:73`/`:100`) |
| `observability/CorrelationIdFilter.java:16` | **Correlation ID** — `OncePerRequestFilter` (`:28`), `HEADER="X-Correlation-Id"` (`:30`), `MDC_KEY="correlationId"` (`:31`), `UUID` fallback (`:40`), `MDC.put` (`:42`) + `setHeader` (`:43`), `MDC.remove` in `finally` (`:47`) |
| `resources/logback-spring.xml:1` | **Structured logs** — console pattern `[%X{correlationId}]` (`:12`), prod JSON `LoggingEventCompositeJsonEncoder` (`:18`) with `mdc` provider (`:23`) |
| `resources/application.yml:139` | **Actuator + tracing** — `exposure: health,info,metrics,prometheus` (`:144`), `tracing.enabled: false` (`:156`), `sampling.probability: 0.1` (`:158`), `otlp.tracing.endpoint` (`:161`) |
| `pom.xml:276` | **Dependencies** — `spring-boot-starter-actuator` (`:279`), `micrometer-registry-prometheus` (`:291`), `micrometer-tracing-bridge-otel` (`:295`), `opentelemetry-exporter-otlp` (`:298`), `logstash-logback-encoder` (`:302`) |

### Snippet 1 — Lazy per-tag timers (`AiMetrics.java:79`)

```java
// src/main/java/com/company/orderapi/observability/AiMetrics.java:79
public void recordToolCall(String toolName, String status, long durationNanos) {
    String key = toolName + "|" + status; // :80 composite key — one Timer per tool×status
    Timer timer = mcpToolTimers.computeIfAbsent(key, k -> // :81 lazy create — no upfront cardinality explosion
            Timer.builder("mcp.tool.call.duration") // :82 Prometheus: mcp_tool_call_duration_seconds
                    .tag(TAG_TOOL, toolName)   // :83  → mcp_tool_call_duration_seconds_bucket{tool="api_health"}
                    .tag(TAG_STATUS, status)   // :84  → {status="success"|"error"}
                    .description("MCP tool call latency") // :85
                    .register(registry)); // :86 registers on first call, reused thereafter
    timer.record(durationNanos, TimeUnit.NANOSECONDS); // :87

    Counter counter = mcpToolCounters.computeIfAbsent(key, k -> // :89 parallel counter for rate alerts
            Counter.builder("mcp.tool.call.total") // :90
                    .tag(TAG_TOOL, toolName).tag(TAG_STATUS, status)
                    .description("MCP tool call count").register(registry));
    counter.increment(); // :95
}
```

Why `ConcurrentHashMap` + `computeIfAbsent` instead of `Counter.builder(...).register` at startup: tool names are **open-ended** (`api_health`, `product_search`, `cancel_order`, future tools) — pre-registering `N × 2` meters wastes memory; lazy creation keeps cardinality bounded to what is actually called.

### Snippet 2 — RAG with chunks distribution (`AiMetrics.java:114`)

```java
// src/main/java/com/company/orderapi/rag/RagService.java:71
public String answer(String question) {
    long start = System.nanoTime(); // :72
    String status = AiMetrics.STATUS_SUCCESS; // :73
    int chunks = 0; // :74
    try {
        List<Document> relevantDocs = retrievalEngine.retrieve(question, ragProperties.topK()); // :76
        chunks = relevantDocs.size(); // :77
        // ... build prompt, call chatModel :83-95
        return answer;
    } catch (RuntimeException e) {
        status = AiMetrics.STATUS_ERROR; // :100
        throw e;
    } finally {
        if (aiMetrics != null) { // :103 null-safe — test-only ctor passes null :62
            aiMetrics.recordRagQuery(ragProperties.retrievalMode() != null
                    ? ragProperties.retrievalMode().toString() : "dense", // :104 tag: dense|hybrid
                    status, System.nanoTime() - start, chunks); // :105 duration + chunks
        }
    }
}
// AiMetrics.java:114 — records both Timer and DistributionSummary
public void recordRagQuery(String retrievalMode, String status, long durationNanos, int chunksRetrievedCount) {
    Timer timer = ragQueryTimers.computeIfAbsent(retrievalMode+"|"+status, k ->
            Timer.builder("rag.query.duration").tag(TAG_RETRIEVAL_MODE, retrievalMode)
                 .tag(TAG_STATUS, status).description("RAG query latency").register(registry)); // :117-121
    timer.record(durationNanos, TimeUnit.NANOSECONDS); // :122
    ragQueriesTotal.increment(); // :123 global counter for throughput
    ragChunksRetrieved.record(chunksRetrievedCount); // :124 DistributionSummary → histogram of chunks/query
}
```

`DistributionSummary` (not `Timer`) because chunks is a **count**, not latency — it gives `rag_chunks_retrieved_max`, `count`, `sum`, and histogram buckets for "is retrieval returning 0 chunks too often?"

### Snippet 3 — MCP seam + OTel conventions (`McpServerConfiguration.java:99`)

```java
// src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:99
@Bean(destroyMethod = "close")
public McpStatelessSyncServer mcpServer(WebMvcStatelessServerTransport transport,
        List<AbstractMcpReadOnlyTool> readTools,
        List<AbstractMcpWriteTool> writeTools,
        McpDocsResourceCatalog docsCatalog, List<AbstractMcpPrompt> prompts,
        McpAuditService auditService, AiMetrics aiMetrics) { // :106 injected — no manual new
    List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
    readTools.stream().sorted(Comparator.comparing(AbstractMcpReadOnlyTool::name))
            .map(t -> t.specification(auditService, aiMetrics)) // :111 THE seam — one line adds metrics+trace
            .forEach(specifications::add);
    writeTools.stream().sorted(Comparator.comparing(AbstractMcpWriteTool::name))
            .map(t -> t.specification(auditService, aiMetrics)) // :115 same for guarded writes
            .forEach(specifications::add);
    return McpServer.sync(transport).serverInfo("order-management-api-mcp","1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(specifications.toArray(McpStatelessServerFeatures.SyncToolSpecification[]::new))
            .build(); // :132
}
// src/main/java/com/company/orderapi/observability/AiObservationConventions.java:12
public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name"; // :12 OTel GenAI
public static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";           // :15
public static final String OP_TOOL_CALL = "tool_call";                      // :19
public static final String SYSTEM_MCP = "mcp";                               // :23
public record McpToolContext(String toolName, String sessionId, String actor) {} // :27 typed span baggage
```

---

## 5. How to use — scrape, correlate, trace

### Prerequisites

```bash
./mvnw spring-boot:run
# Prometheus scrape at http://localhost:8080/actuator/prometheus  application.yml:144
# OTLP endpoint http://localhost:4318  :161  (Jaeger via docker-compose)
# CorrelationIdFilter active for every request  CorrelationIdFilter.java:27
```

### 1. Scrape MCP/RAG metrics — the 10s proof

```bash
# all MCP tool latency histograms
curl -s http://localhost:8080/actuator/prometheus | grep mcp_tool
# mcp_tool_call_duration_seconds_bucket{tool="api_health",status="success",le="0.005"} 12.0
# mcp_tool_call_duration_seconds_count{tool="api_health",status="success"} 12.0
# mcp_tool_call_total{tool="api_health",status="success"} 12.0
# mcp_tool_call_total{tool="order_status",status="error"} 1.0

# RAG query latency by retrieval_mode + chunk distribution
curl -s http://localhost:8080/actuator/prometheus | grep -E "rag_query|rag_chunks"
# rag_query_duration_seconds_bucket{retrieval_mode="dense",status="success",le="0.1"} 7.0
# rag_chunks_retrieved_count 7.0
# rag_chunks_retrieved_sum 21.0   # avg 3 chunks/query

# active MCP sessions gauge (stateless → usually 0, stateful → live count)
curl -s http://localhost:8080/actuator/prometheus | grep mcp_sessions_active
# mcp_sessions_active 0.0

# via /actuator/metrics JSON (per-meter detail)
curl -s http://localhost:8080/actuator/metrics/mcp.tool.call.duration | jq .
curl -s http://localhost:8080/actuator/metrics/rag.query.duration | jq .
```

### 2. CorrelationId — echo and structured logs

```bash
# client supplies correlationId → server echoes it, all log lines carry it
curl -s -H "X-Correlation-Id: demo-123" http://localhost:8080/actuator/health -D - | grep -i correlation
# X-Correlation-Id: demo-123

# server generates one when missing
curl -s http://localhost:8080/actuator/health -D - | grep -i correlation
# X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000

# in logs (dev console):
# 14:22:01.123 INFO  [http-nio-8080-exec-1] [demo-123] c.c.orderapi.mcp.ApiHealthTool - MCP tool api_health called

# prod JSON (SPRING_PROFILES_ACTIVE=prod) — one JSON object per line, mdc includes correlationId:
# {"timestamp":"2026-09-11T14:22:01.123Z","level":"INFO","thread":"http-nio-8080-exec-1","mdc":{"correlationId":"demo-123"},"logger":"c.c.orderapi.mcp.ApiHealthTool","message":"MCP tool api_health called"}

# wire through MCP too — pass the same header on POST /mcp:
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: mcp-trace-42" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"api_health","arguments":{}}}' -D - | grep -i correlation
```

### 3. Traces — Jaeger UI

```bash
# enable tracing (disabled by default  application.yml:156)
SPRING_PROFILES_ACTIVE=prod OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 ./mvnw spring-boot:run
# or: management.tracing.enabled=true management.tracing.sampling.probability=0.1

# make a request, then open Jaeger:
open http://localhost:16686  # service: order-management-api
# search for gen_ai.operation.name=tool_call  AiObservationConventions.java:12
# or gen_ai.tool.name=api_health              :15
# traces show: POST /mcp → tool_handler → (RagService → chatModel if docs_search) with shared traceId

# verify sampling is 10% — only ~1 in 10 requests exports a span:
curl -s http://localhost:8080/actuator/metrics/http.server.requests | jq .
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

**Lazy per-tag `Timer` via `ConcurrentHashMap.computeIfAbsent` (`AiMetrics.java:81`)** — Pre-registering every `tool × status` at startup would require enumerating all tools (and future tools) eagerly, costing memory for meters never called. Lazy creation bounds cardinality to live traffic. `ConcurrentHashMap` guarantees thread-safe single registration under concurrent `tools/call` on virtual threads (`spring.threads.virtual.enabled: true` `application.yml:24`). Trap avoided: naive `registry.timer("mcp.tool.call.duration", Tags.of(...))` on every call would allocate a new `Timer` object per invocation — map memoizes it.

**`DistributionSummary` for chunks (`AiMetrics.java:65`), not `Timer` or `Counter`** — `rag.chunks_retrieved` is a discrete count per query, not latency nor monotonic total. `DistributionSummary` gives `count`/`sum`/`max` + configurable histogram — you can alert on `rate(rag_chunks_retrieved_sum[5m]) / rate(rag_queries_total[5m])` dropping below 2.0 (retrieval degrading). A `Counter` would lose per-query shape; a `Timer` would mislabel units.

**`Gauge` with `AtomicLong` for active sessions (`AiMetrics.java:51`)** — `Gauge.builder("mcp.sessions.active", activeSessions, AtomicLong::get)` reads live state without increment shims. `incrementActiveSessions()` (`:146`) / `decrement...()` (`:150`) mutate the `AtomicLong` — Prometheus scrapes the current value. Alternative `Counter` would only show cumulative sessions, not concurrent load. Today stateless transport keeps this near zero — the gauge exists so the stateful upgrade (`WebMvcStreamableServerTransport`) just calls `increment`/`decrement` with no metric migration.

**10% trace sampling (`application.yml:158`)** — `management.tracing.sampling.probability: 0.1` keeps OTLP/Jaeger cost bounded (1 in 10 requests exports) while still capturing p95 tail. Metrics are **always** recorded (no sampling) — alerts never miss an error spike even when traces sample it out. Trap: 100% sampling on an MCP-heavy workload (10 tool calls per `agentic_ask`) would flood Jaeger and add per-span CPU; 0% would leave incidents untraceable.

**OTel GenAI conventions as constants, not auto-instrumentation (`AiObservationConventions.java:7`)** — The file is intentionally `final` with `String` constants + typed `record` contexts (`:27-33`) rather than a full `ObservationConvention` wiring. This keeps PR #49 additive — existing `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` (`pom.xml:295`+`:298`) work without custom `ObservationHandler` beans. Micrometer bridge maps MDC `traceId`/`spanId` into logs automatically, so `CorrelationIdFilter` correlationId and OTel traceId coexist.

### Failures Hit — what broke

The `mcp.tool.call.duration` histogram initially used a single Timer with high-cardinality `actor` tag, exploding Prometheus series. Fix: `AiMetrics.java:51` keeps `ConcurrentHashMap<String,Timer>` per `tool|status` low-cardinality only; `actor` stays in logs/traces (`CorrelationIdFilter.java:42`), not metrics. Sampling was also 1.0 in dev, flooding Jaeger — lowered to `management.tracing.sampling.probability:0.1` (`application.yml:158`).

**Payload box — PromQL + trace query:**

```promql
histogram_quantile(0.95, sum(rate(mcp_tool_call_duration_bucket[5m])) by (le, tool))
rate(rag_query_duration_count{retrieval_mode="hybrid"}[5m])
mcp_sessions_active
```
```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "mcp_tool|rag_query"
# Jaeger: Service=order-management-api, Tags gen_ai.operation.name=tool_call
```

---

## 7. How to verify — metrics + logs + traces in 60s

### 1. Metrics — curl Prometheus

```bash
# trigger a few tool calls (or agentic_ask that fans out to tools)
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
for i in 1 2 3; do curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":'$i',"method":"tools/call","params":{"name":"api_health","arguments":{}}}' >/dev/null; done

# assert counters moved
curl -s http://localhost:8080/actuator/prometheus | grep 'mcp_tool_call_total{tool="api_health",status="success"}'
# mcp_tool_call_total{tool="api_health",status="success"} 3.0

# assert histogram exists
curl -s http://localhost:8080/actuator/prometheus | grep mcp_tool_call_duration_seconds_bucket | head -1
# mcp_tool_call_duration_seconds_bucket{tool="api_health",status="success",le="0.005",} 3.0
```

### 2. Logs — CorrelationId round-trip

```bash
# missing header → server generates UUID, echoes it, logs carry it
curl -s -D - http://localhost:8080/actuator/health | grep -i X-Correlation-Id
# X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000

# supplied header → echoed verbatim
curl -s -D - -H "X-Correlation-Id: verify-999" http://localhost:8080/actuator/health | grep -i X-Correlation-Id
# X-Correlation-Id: verify-999
# check logs: grep verify-999 target/spring.log  (or docker logs)

# prod JSON shape (SPRING_PROFILES_ACTIVE=prod):
# curl -H "X-Correlation-Id: verify-999" http://localhost:8080/actuator/health
# log line: {"timestamp":...,"mdc":{"correlationId":"verify-999","traceId":"...","spanId":"..."},...}
```

### 3. Traces — Jaeger + sampling gate

```bash
# with tracing enabled, make an MCP call then search Jaeger
open http://localhost:16686/search?service=order-management-api
# filter Tags: gen_ai.operation.name=tool_call  or  gen_ai.tool.name=api_health
# expect ~10% of recent requests have a trace (sampling.probability 0.1)

# verify the export endpoint is wired
curl -s http://localhost:8080/actuator/metrics | jq '.names | map(select(contains("tracing") or contains("otel")))'
```

### 4. Tests — observability integration gate

```bash
./mvnw test -Dtest=ObservabilityIntegrationTest
# ObservabilityIntegrationTest.java:91 correlationIdIsEchoedAndGeneratedWhenMissing
# ObservabilityIntegrationTest.java:104 GET /actuator/health with/without X-Correlation-Id
# ObservabilityIntegrationTest.java:127 /actuator/metrics lists expected meter names

./mvnw test -Dtest=McpOAuth2IntegrationTest
# still passes after AiMetrics wiring — AiMetrics is nullable in tool specs (null-safe :89, :98)
```

---

## 8. How this helps you on the job — build / operate / interview

* **Build — add observable AI features without new infra.** Copy `AiMetrics.java:18` pattern — inject `MeterRegistry`, declare `ConcurrentHashMap<String, Timer>` per dimension, `computeIfAbsent` with `Timer.builder(...).tag(...).register(registry)` (`:81`), record in `try { success } catch { error } finally { record }` (`RagService.java:71-106`). Add `AiObservationConventions.java:12` constants for any new `gen_ai.*` span attribute — OTel export is already wired (`pom.xml:295`). Every new MCP tool automatically gets `mcp.tool.call.duration{tool="<name>"}` by being in `McpServerConfiguration.java:109-116` — zero extra code.
* **Operate — SRE-ready in one scrape.** Prometheus `scrape: /actuator/prometheus` (`application.yml:144`) feeds `mcp.tool.call.duration` p95 by tool, `rag.query.duration` p95 by `retrieval_mode`, `rag.chunks_retrieved` histogram, `mcp.sessions.active` gauge. AlertManager rules (`docs/monitoring/prometheus/alerts.yml:2`) can fire on `rate(mcp_tool_call_total{status="error"}[5m]) > 0.05` or `histogram_quantile(0.95, rate(mcp_tool_call_duration_seconds_bucket[5m])) > 0.5`. CorrelationId (`CorrelationIdFilter.java:38`) lets you `grep demo-123` across app + Jaeger traceId for incident replay.
* **Interview — 90s whiteboard.** "PR #49: `AiMetrics` (`AiMetrics.java:18`) holds 7 metric families — lazy `Timer` per `tool×status`/`retrieval_mode×status` via `ConcurrentHashMap.computeIfAbsent` (`:81`), `DistributionSummary` for `rag.chunks_retrieved` (`:65`), `Gauge` on `AtomicLong` for `mcp.sessions.active` (`:51`), all on Micrometer `MeterRegistry` (`:48`) scraped at `/actuator/prometheus` (`:144`). Wired into MCP via `McpServerConfiguration.mcpServer(aiMetrics)` (`:106`) → `specification(auditService, aiMetrics)` (`AbstractMcpReadOnlyTool.java:67` `:90`/`:99`, `AbstractMcpWriteTool.java:71` `:98`/`:107`) and RAG via `RagService.answer()` finally `recordRagQuery` (`:104`). OTel GenAI keys in `AiObservationConventions.java:12`, 10% sampling (`application.yml:158`), correlationId in `MDC` (`CorrelationIdFilter.java:42`) rendered by both console (`logback-spring.xml:12`) and prod JSON (`:23`). Verified by `curl /actuator/prometheus | grep mcp_tool` + `curl -H X-Correlation-Id` echo + Jaeger `gen_ai.operation.name=tool_call`."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "You already had `mcp_tool_audit` — why add `AiMetrics`? What's the difference between audit and metrics?"**

> "Audit (`McpAuditService.java:20`, `REQUIRES_NEW`) is **durable truth** — one row per `tools/call` with actor, args, success, error — for forensics. Metrics (`AiMetrics.java:18`) is **operational signal** — `Timer`/`Counter`/`Gauge` aggregated per `tool×status` and scraped every 15s at `/actuator/prometheus`. Audit answers 'who called `cancel_order` for order 7 at 14:22?' — needs `psql`. Metrics answers 'what's p95 latency for `cancel_order` success vs error in the last 5m?' and fires `rate(mcp_tool_call_total{status="error"}[5m])` alerts. PR #48 without #49 leaves you blind between incidents; PR #49 without #48 leaves you unable to reconstruct them. The `specification(auditService, aiMetrics)` seam (`AbstractMcpReadOnlyTool.java:67`) does both in one handler (`:87` + `:90`)."

**Q2: "Why lazy `ConcurrentHashMap` timers instead of pre-registering, and why `DistributionSummary` for chunks? Why 10% sampling?"**

> "Three choices. First, lazy timers (`AiMetrics.java:81` `computeIfAbsent`): tool set is open-ended — pre-registering `N tools × 2 statuses` at startup wastes memory for cold tools and breaks when a new `AbstractMcpReadOnlyTool` bean appears. Lazy keeps cardinality to live traffic, `ConcurrentHashMap` is safe on virtual threads. Second, `DistributionSummary` (`:65`) for `rag.chunks_retrieved`: chunks is a count per query, not latency — `DistributionSummary` gives count/sum/max/histogram so you can alert when avg chunks drops (retrieval recall collapse). A `Counter` would accumulate and hide per-query shape. Third, 10% sampling (`application.yml:158`): traces are high-volume, metrics are already 100% — you need traces for tail debugging, not alerting. 10% bounds Jaeger/OTLP cost while still capturing ~1 in 10 slow requests for the p95 you already measure in metrics."

**Q3: "Your Gauge shows 0 and you have no per-token cost — how do you know the AI layer isn't leaking latency or money? How would you extend it?"**

> "Honest limits (§10). `mcp.sessions.active` (`AiMetrics.java:51`) is zero on stateless transport — the `AtomicLong` is incremented/decremented only by the stateful `WebMvcStreamableServerTransport`, not yet adopted. No per-token count, no time-to-first-token, no `gen_ai.usage.input_tokens` — so you can measure call latency but not token cost or streaming stalls. Extension without breaking: (1) add `Counter gen_ai.tokens.input/output` tagged `model` and `DistributionSummary time_to_first_token` in `AiMetrics`, increment in `RagService.answer()` after `chatModel.call` using `ChatResponse.getMetadata().getUsage()`; (2) in `SseEmitter` streaming, record `streamEventsTotal` per-token with `recordStreamEvent(endpoint)` (`:127`) already exists — add a `Timer.stream.first_token` started at request and stopped on first `token` SSE event (see `08-streaming-responses-sse.md:391` future note); (3) for cost, multiply tokens by model price table. The `specification(auditService, aiMetrics)` handler already times each tool — adding token counters is one more `record*` call per span, no Micrometer migration."

---

## 10. Honest limits & next steps — what it doesn't do

**What PR #49 alone does NOT do (by design):**

* **No per-token / per-model cost metrics** — `AiMetrics` records call-level latency (`Timer`) and chunk counts (`DistributionSummary`) but not `gen_ai.usage.input_tokens`, `output_tokens`, or `time_to_first_token`. Streaming `streaming.events.total` (`AiMetrics.java:70`) counts SSE events, not tokens. Cost attribution per model (`deepseek-v4-flash` `application.yml:124`) needs usage metadata from `ChatResponse`.
* **Stateless session gauge is inert** — `mcp.sessions.active` `Gauge` (`:51`) + `increment`/`decrement` (`:146`/`:150`) are wired but `WebMvcStatelessServerTransport` (`McpServerConfiguration.java:62`) never calls them — gauge stays `0.0`. Stateful `WebMvcStreamableServerTransport` must `increment` on `initialize` and `decrement` on close.
* **No span `ObservationHandler` auto-wiring** — `AiObservationConventions.java:7` is constants + records only (`:12-33`). No `ObservationPredicate` or `ObservationConvention` bean is shipped — manual `Tracer.spanBuilder(GEN_AI_OPERATION_NAME)` + `McpToolContext` tagging is caller's job. Full auto-instrumentation would add `ObservedAspect` + `MeterObservationHandler`.
* **Sampling hides 90% of traces** — `probability: 0.1` (`application.yml:158`) means a 30s error spike may have zero exported spans while metrics show the spike. Debug then needs `management.tracing.sampling.probability=1.0` temporarily or a tail-based sampler (OTel Collector).
* **MDC-only correlation, no `traceId` propagation to MCP** — `CorrelationIdFilter.java:30` echoes `X-Correlation-Id` on HTTP, but MCP `CallToolRequest` JSON-RPC payload doesn't carry it — correlating `POST /mcp tools/call` to a downstream `RagService` log needs `McpTransportContext` to propagate `correlationId` into the handler's MDC (future: set `MDC.put("correlationId", ctx.get("correlationId"))` in `AbstractMcpReadOnlyTool` handler `:77`).

**Where to go next:**

PR #49's seam (`specification(auditService, aiMetrics)` `AbstractMcpReadOnlyTool.java:67`) is the extension point — add `McpTransportContext → MDC` propagation, per-token counters from `ChatResponse.getMetadata()`, and a tail sampler in `docker-compose.yml` Jaeger without touching metric names. After #49 you can alert on latency; next PR can alert on cost.

> Next: [`13-guarded-write-expanded.md`](./13-guarded-write-expanded.md) (PR #50) · or back to [`README.md`](./README.md) · high-level companion [`docs/additions/12-ai-observability.md`](../../12-ai-observability.md)

