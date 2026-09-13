# 18. Observability Runbook — Prometheus, Jaeger, Logs

> PR: [#49 — AI-feature observability](https://github.com/anomalyco/order-management-api/pull/49) + [#32 baseline] · Stack: `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + `logstash-logback-encoder` · Depends on [12-ai-observability](./12-ai-observability.md)

---

## 1. Purpose — what to watch

On-call cheat sheet for the three pillars shipped in PR #32 + #49. When `POST /mcp tools/call` is slow, RAG returns 0 chunks, or `agent.turn.duration` spikes — open this file first.

| Pillar | Signal | Where it lands | File:line |
|---|---|---|---|
| **Metrics** | 7 families + 1 gauge (`mcp.tool.call.*`, `agent.turn.*`, `rag.query.*`, `retrieval.search.*`, `streaming.events.*`, `ingestion.*`, `mcp.sessions.active`) | `/actuator/prometheus` | `AiMetrics.java:18` `pom.xml:291` |
| **Traces** | OTel `gen_ai.*` spans, 10% sampling | Jaeger OTLP `http://localhost:4318` | `AiObservationConventions.java:12` `application.yml:158` `pom.xml:295` |
| **Logs** | JSON in prod, `correlationId` in every line | stdout (prod JSON / dev console) | `CorrelationIdFilter.java:28` `logback-spring.xml:12` `pom.xml:302` |

**4 dashboards to watch:**

1. **MCP health** — `mcp.tool.call.duration` p95 per `tool × status` + error rate. Spike on `cancel_order{status="error"}` → audit table + trace.
2. **RAG quality** — `rag.query.duration` p95 per `retrieval_mode` + `rag.chunks_retrieved` avg. Avg < 2.0 = recall collapse.
3. **Agent / retrieval** — `agent.turn.duration` per `agent`, `retrieval.search.duration` per `search_type × fused`.
4. **Capacity** — `mcp.sessions.active` gauge (`AiMetrics.java:51`) + `ingestion.documents.total`. Gauge near limit → scale.

```
alert? ──► /actuator/prometheus ──► grep mcp_tool / rag_query ──► PromQL histogram_quantile 0.95
           application.yml:144       AiMetrics.java:82
              ├─► Jaeger :16686 ──► gen_ai.tool.name ──► traceId = MDC
              │     AiObservationConventions.java:12
              └─► logs JSON ──► jq .mdc.correlationId ──► grep correlationId
                    logback-spring.xml:18          CorrelationIdFilter.java:31
```

---

## 2. Metrics — Prometheus scrape `/actuator/prometheus`

### 2.1 Scrape config

`spring-boot-starter-actuator` (`pom.xml:279`) + `micrometer-registry-prometheus` (`pom.xml:291`):

```yaml
# src/main/resources/application.yml:139
management:
  endpoints.web.exposure.include: health, info, metrics, prometheus # :144
  tracing.enabled: false          # :156 — prod enables at application-prod.yml:48
  tracing.sampling.probability: 0.1  # :158 — 10% export
  otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318} # :161
```

```bash
curl -s http://localhost:8080/actuator/prometheus | head -20
curl -s http://localhost:8080/actuator/metrics | jq '.names | sort | .[] | select(contains("mcp") or contains("rag") or contains("agent"))'
```

**Metric inventory** — all in `AiMetrics.java:18` (`MeterRegistry` `:48`, lazy `ConcurrentHashMap.computeIfAbsent` `:81` `:100` `:116` `:133`):

| Prometheus name | Micrometer name | Type | Tags | File:line |
|---|---|---|---|---|
| `mcp_tool_call_duration_seconds` | `mcp.tool.call.duration` | `Timer` histogram | `tool`, `status` | `AiMetrics.java:82` |
| `mcp_tool_call_total` | `mcp.tool.call.total` | `Counter` | `tool`, `status` | `AiMetrics.java:90` |
| `mcp_sessions_active` | `mcp.sessions.active` | `Gauge` (`AtomicLong`) | — | `AiMetrics.java:51` |
| `agent_turn_duration_seconds` | `agent.turn.duration` | `Timer` | `agent`, `status` | `AiMetrics.java:101` |
| `agent_turns_total` | `agent.turns.total` | `Counter` | — | `AiMetrics.java:55` |
| `agent_tool_calls_total` | `agent.tool_calls.total` | `Counter` | — | `AiMetrics.java:58` |
| `rag_query_duration_seconds` | `rag.query.duration` | `Timer` | `retrieval_mode`, `status` | `AiMetrics.java:117` |
| `rag_queries_total` | `rag.queries.total` | `Counter` | — | `AiMetrics.java:62` |
| `rag_chunks_retrieved_*` | `rag.chunks_retrieved` | `DistributionSummary` | — | `AiMetrics.java:65` |
| `retrieval_search_duration_seconds` | `retrieval.search.duration` | `Timer` | `search_type`, `fused` | `AiMetrics.java:134` |
| `streaming_events_total` | `streaming.events.total` | `Counter` | — | `AiMetrics.java:70` |
| `ingestion_documents_total` | `ingestion.documents.total` | `Counter` | — | `AiMetrics.java:74` |

### 2.2 PromQL — copy-paste for Grafana

All use `rate(...[5m])`. The primary SLO chart is the first query.

```promql
# MCP p95 per tool — alert if > 0.5s (the main SLO)
histogram_quantile(0.95, sum by (tool, le) (rate(mcp_tool_call_duration_seconds_bucket{status="success"}[5m])))

# MCP p95 incl. errors + p50/p99 for api_health
histogram_quantile(0.95, sum by (tool, status, le) (rate(mcp_tool_call_duration_seconds_bucket[5m])))
histogram_quantile(0.50, sum by (le) (rate(mcp_tool_call_duration_seconds_bucket{tool="api_health"}[5m])))
histogram_quantile(0.99, sum by (le) (rate(mcp_tool_call_duration_seconds_bucket{tool="api_health"}[5m])))

# MCP error rate per tool + global — alert if > 1%
sum by (tool) (rate(mcp_tool_call_total{status="error"}[5m])) / sum by (tool) (rate(mcp_tool_call_total[5m]))
sum(rate(mcp_tool_call_total{status="error"}[5m])) / sum(rate(mcp_tool_call_total[5m]))

# RAG p95 per retrieval_mode + avg chunks (alert if avg < 2.0)
histogram_quantile(0.95, sum by (retrieval_mode, le) (rate(rag_query_duration_seconds_bucket{status="success"}[5m])))
rate(rag_chunks_retrieved_sum[5m]) / rate(rag_chunks_retrieved_count[5m])

# Agent p95 + fan-out (tool calls per turn)
histogram_quantile(0.95, sum by (agent, status, le) (rate(agent_turn_duration_seconds_bucket[5m])))
rate(agent_tool_calls_total[5m]) / rate(agent_turns_total[5m])

# Retrieval p95 per search_type×fused, ingestion, sessions, streaming
histogram_quantile(0.95, sum by (search_type, fused, le) (rate(retrieval_search_duration_seconds_bucket[5m])))
rate(ingestion_documents_total[5m])
mcp_sessions_active
rate(streaming_events_total[5m])
```

Raw scrape without PromQL:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "mcp_tool_call_duration_seconds_bucket|rag_query_duration|agent_turn_duration|retrieval_search_duration|mcp_sessions_active"
# mcp_tool_call_duration_seconds_bucket{tool="api_health",status="success",le="0.005"} 12.0
# rag_query_duration_seconds_bucket{retrieval_mode="dense",status="success",le="0.1"} 7.0
# mcp_sessions_active 0.0
curl -s http://localhost:8080/actuator/prometheus | grep -E "mcp_tool_call_total|rag_chunks_retrieved|ingestion_documents_total"
```

---

## 3. Traces — OTel Jaeger at `localhost:4318`

### 3.1 Wiring

```yaml
# docker-compose.yml:83 — Jaeger all-in-one
jaeger:
  image: jaegertracing/all-in-one:1.57
  ports: ["16686:16686", "4317:4317", "4318:4318"] # :88-89 OTLP HTTP matches application.yml:161
  environment: { COLLECTOR_OTLP_ENABLED: "true" }
```

Dependencies: `micrometer-tracing-bridge-otel` (`pom.xml:295`) + `opentelemetry-exporter-otlp` (`pom.xml:298`). Sampling `0.1` (`application.yml:158`) — only 1 in 10 requests exports. Metrics are always 100% (use for alerts, traces for tail).

Enable locally (disabled by default `application.yml:156`):

```bash
SPRING_PROFILES_ACTIVE=prod OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 ./mvnw spring-boot:run
# or 100% sampling for debugging:
./mvnw spring-boot:run -Dspring-boot.run.arguments="--management.tracing.enabled=true --management.tracing.sampling.probability=1.0 --management.otlp.tracing.endpoint=http://localhost:4318"
```

### 3.2 GenAI tags — `AiObservationConventions.java:7`

```java
// src/main/java/com/company/orderapi/observability/AiObservationConventions.java:12
GEN_AI_OPERATION_NAME = "gen_ai.operation.name"; // :12
GEN_AI_SYSTEM         = "gen_ai.system";         // :13
GEN_AI_REQUEST_MODEL  = "gen_ai.request.model";  // :14
GEN_AI_TOOL_NAME      = "gen_ai.tool.name";      // :15
GEN_AI_AGENT_NAME     = "gen_ai.agent.name";     // :16
OP_TOOL_CALL="tool_call" OP_RETRIEVAL="retrieval" OP_CHAT="chat" // :18-20
SYSTEM_MCP="mcp" SYSTEM_RAG="rag" SYSTEM_AGENT="agent"           // :23-25
// records McpToolContext, RagQueryContext, etc. :27-33
```

Span tree for `POST /mcp tools/call → docs_search`:

```
traceId=abc123  correlationId=abc123 (MDC CorrelationIdFilter.java:42)
 └─ POST /mcp  gen_ai.operation.name=tool_call gen_ai.tool.name=docs_search gen_ai.system=mcp
     ├─ retrieval  gen_ai.operation.name=retrieval gen_ai.system=rag retrieval_mode=hybrid
     └─ chat       gen_ai.operation.name=chat      gen_ai.system=rag gen_ai.request.model=deepseek-v4-flash
```

### 3.3 How to find rag.query spans

Jaeger UI `http://localhost:16686` → Service `order-management-api`:

- `gen_ai.operation.name=tool_call` + `gen_ai.tool.name=docs_search` (`AiObservationConventions.java:15` `:19`)
- `gen_ai.operation.name=retrieval` (`:20`) or `gen_ai.system=rag` (`:24`)
- `gen_ai.request.model=deepseek-v4-flash` (`:14`) for LLM calls

Drill-down: open a `rag.query` trace → compare `retrieval` child vs `chat` child. If `retrieval >> chat`, pgvector index or reranking is bottleneck (confirm via `retrieval.search.duration` `AiMetrics.java:134`).

```bash
open "http://localhost:16686/search?service=order-management-api&tags=%7B%22gen_ai.operation.name%22%3A%22tool_call%22%7D"
curl -s http://localhost:8080/actuator/metrics | jq '.names | map(select(contains("tracing") or contains("otel")))'
curl -s "http://localhost:16686/api/traces?service=order-management-api&limit=5" | jq '.data[0] | {traceID, spans: (.spans|length)}'
```

---

## 4. Logs — `logback-spring.xml` JSON in prod, `correlationId` MDC

### 4.1 Appender wiring

Two appenders by profile (`logback-spring.xml:1`, encoder `logstash-logback-encoder:7.4` `pom.xml:302`):

```xml
<!-- :9 CONSOLE for dev/tests -->
<pattern>%d{HH:mm:ss.SSS} %-5level [%thread] [%X{correlationId:-}] %logger{36} - %msg%n</pattern> <!-- :12 -->
<!-- :16 prod JSON — one JSON object per line, mdc includes correlationId+traceId -->
<encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder"> <!-- :18 -->
  <providers><timestamp/><logLevel/><threadName/><mdc/><!-- :23 --><loggerName/><message/><stackTrace/></providers>
</encoder>
```

### 4.2 CorrelationId — `CorrelationIdFilter.java:28`

```java
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 10) // :27
public class CorrelationIdFilter extends OncePerRequestFilter {
    String HEADER="X-Correlation-Id";  // :30 — client may supply
    String MDC_KEY="correlationId";    // :31 — rendered by logback :12 :23
    // :38 honour header, :40 UUID fallback, :42 MDC.put, :43 echo on response, :47 MDC.remove
}
```

Prod JSON includes both ids (`micrometer-tracing-bridge-otel` `pom.xml:295` adds `traceId`/`spanId` to MDC):

```json
{"timestamp":"...","level":"INFO","mdc":{"correlationId":"demo-123","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7"},"logger":"c.c.orderapi.mcp.ApiHealthTool","message":"MCP tool api_health called"}
```

### 4.3 How to grep

```bash
# dev console — brackets
./mvnw spring-boot:run 2>&1 | grep "demo-123"
# 14:22:01.123 INFO  [http-nio-8080-exec-1] [demo-123] c.c.orderapi.mcp.ApiHealthTool - MCP tool api_health called

# prod JSON — jq filter
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run 2>&1 | jq 'select(.mdc.correlationId=="demo-123")'
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run 2>&1 | jq -c 'select(.mdc.traceId!=null) | {correlationId: .mdc.correlationId, traceId: .mdc.traceId, message}'

# docker / k8s
docker logs order-management-api 2>&1 | grep "demo-123"
docker logs order-management-api 2>&1 | jq -R 'fromjson? | select(.mdc.correlationId=="demo-123")'
kubectl logs -f deploy/order-management-api | jq 'select(.level=="ERROR") | {timestamp, mdc, message, stackTrace}'

# join logs → Jaeger: extract traceId then search Jaeger by traceId
TRACE_ID=$(docker logs order-management-api 2>&1 | jq -r 'fromjson | select(.mdc.correlationId=="demo-123") | .mdc.traceId' | head -1)
echo $TRACE_ID  # paste at http://localhost:16686

# header echo — client supplies or server generates UUID
curl -s -H "X-Correlation-Id: demo-123" http://localhost:8080/actuator/health -D - | grep -i correlation  # CorrelationIdFilter.java:43
curl -s -H "X-Correlation-Id: mcp-trace-42" -H "Authorization: Bearer $TOKEN" -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"api_health","arguments":{}}}' -D - | grep -i correlation
```

---

## 5. Alerts / SLOs — example thresholds

Paste into `prometheus/alerts.yml`. All series from `AiMetrics.java` at `/actuator/prometheus` (`application.yml:144`).

```yaml
groups:
  - name: ai-latency
    interval: 30s
    rules:
      - alert: McpToolP95High   # p95 < 500ms per tool
        expr: histogram_quantile(0.95, sum by (tool, le) (rate(mcp_tool_call_duration_seconds_bucket{status="success"}[5m]))) > 0.5
        for: 5m
        labels: { severity: warning }
        annotations: { summary: "MCP tool {{ $labels.tool }} p95 {{ $value }}s > 0.5s" }
      - alert: RagQueryP95High  # p95 < 800ms
        expr: histogram_quantile(0.95, sum by (retrieval_mode, le) (rate(rag_query_duration_seconds_bucket{status="success"}[5m]))) > 0.8
        for: 5m
        labels: { severity: warning }
      - alert: AgentTurnP95High # p95 < 2s
        expr: histogram_quantile(0.95, sum by (agent, le) (rate(agent_turn_duration_seconds_bucket{status="success"}[5m]))) > 2.0
        for: 5m
        labels: { severity: warning }
      - alert: RetrievalSearchP95High # p95 < 300ms
        expr: histogram_quantile(0.95, sum by (search_type, le) (rate(retrieval_search_duration_seconds_bucket[5m]))) > 0.3
        for: 5m
        labels: { severity: warning }

  - name: ai-errors              # error rate < 1%
    interval: 30s
    rules:
      - alert: McpToolErrorRateHigh
        expr: sum by (tool) (rate(mcp_tool_call_total{status="error"}[5m])) / sum by (tool) (rate(mcp_tool_call_total[5m])) > 0.01
        for: 5m
        labels: { severity: critical }
      - alert: McpGlobalErrorRateHigh
        expr: sum(rate(mcp_tool_call_total{status="error"}[5m])) / sum(rate(mcp_tool_call_total[5m])) > 0.01
        for: 5m
        labels: { severity: critical }
      - alert: RagErrorRateHigh
        expr: sum by (retrieval_mode) (rate(rag_query_duration_seconds_count{status="error"}[5m])) / sum by (retrieval_mode) (rate(rag_query_duration_seconds_count[5m])) > 0.01
        for: 5m
        labels: { severity: warning }

  - name: ai-quality
    interval: 1m
    rules:
      - alert: RagChunksLow      # avg chunks < 2.0 → recall collapse
        expr: rate(rag_chunks_retrieved_sum[5m]) / rate(rag_chunks_retrieved_count[5m]) < 2.0
        for: 10m
        labels: { severity: warning }
      - alert: McpSessionsHigh
        expr: mcp_sessions_active > 80
        for: 5m
        labels: { severity: warning }
```

| Signal | Threshold | Window | Severity |
|---|---|---|---|
| `mcp.tool.call.duration` p95 | **< 500 ms** | 5m | warning → page if > 1s |
| `rag.query.duration` p95 | **< 800 ms** | 5m | warning |
| `agent.turn.duration` p95 | **< 2 s** | 5m | warning |
| MCP error rate | **< 1%** | 5m | critical |
| Avg RAG chunks | **≥ 2.0** | 10m | warning |

Retune after 1 week of prod histograms.

---

## 6. How to use / verify — curl Prometheus, open Jaeger UI, tail logs

### 6.1 Prerequisites

```bash
docker compose up -d postgres redis kafka jaeger && docker compose ps
./mvnw spring-boot:run  # dev: metrics+logs, tracing off application.yml:156
SPRING_PROFILES_ACTIVE=prod OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 ./mvnw spring-boot:run  # with traces application-prod.yml:48
curl -s http://localhost:8080/actuator/health | jq .status
```

### 6.2 Verify metrics — curl Prometheus

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
for i in 1 2 3; do curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":'$i',"method":"tools/call","params":{"name":"api_health","arguments":{}}}' >/dev/null; done

curl -s http://localhost:8080/actuator/prometheus | grep 'mcp_tool_call_total{tool="api_health",status="success"}'
# mcp_tool_call_total{tool="api_health",status="success"} 3.0
curl -s http://localhost:8080/actuator/prometheus | grep mcp_tool_call_duration_seconds_bucket | head -1
# mcp_tool_call_duration_seconds_bucket{tool="api_health",status="success",le="0.005"} 3.0
curl -s http://localhost:8080/actuator/prometheus | grep -E "rag_query_duration|rag_chunks_retrieved|mcp_sessions_active"
curl -s "http://localhost:8080/actuator/metrics/mcp.tool.call.duration?tag=tool:api_health&tag=status:success" | jq .
curl -s http://localhost:8080/actuator/metrics/rag.query.duration | jq .
```

### 6.3 Verify traces — Jaeger UI

```bash
open http://localhost:16686  # Service: order-management-api, Tags: gen_ai.operation.name=tool_call
# expect ~10% of requests (application.yml:158); for 100% set sampling.probability=1.0
curl -s "http://localhost:16686/api/services" | jq .
curl -s "http://localhost:16686/api/traces?service=order-management-api&limit=5" | jq '.data[0] | {traceID, spans: (.spans|length)}'
curl -s http://localhost:8080/actuator/env | jq '.propertySources[] | select(.name | contains("applicationConfig")) | .properties | with_entries(select(.key | contains("tracing") or contains("otlp")))'
```

### 6.4 Verify logs — tail + grep correlationId

```bash
curl -s -D - http://localhost:8080/actuator/health | grep -i X-Correlation-Id
# X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000  (generated CorrelationIdFilter.java:40)
curl -s -D - -H "X-Correlation-Id: verify-999" http://localhost:8080/actuator/health | grep -i X-Correlation-Id
# X-Correlation-Id: verify-999  (echoed :43)
./mvnw spring-boot:run 2>&1 | grep "verify-999"  # dev console [verify-999] logback-spring.xml:12
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run 2>&1 | grep verify-999 | jq .  # prod JSON :18 :23
TRACE_ID=$(docker logs order-management-api 2>&1 | jq -r 'fromjson | select(.mdc.correlationId=="verify-999") | .mdc.traceId' | head -1)
open "http://localhost:16686/trace/$TRACE_ID"
./mvnw test -Dtest=ObservabilityIntegrationTest
```

### 6.5 60s smoke test

```bash
#!/bin/bash
set -e
curl -s -H "X-Correlation-Id: smoke-001" http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep -q mcp_tool_call_duration_seconds && echo "metrics OK" || echo "metrics MISSING"
curl -s http://localhost:16686/api/services | grep -q order-management-api && echo "jaeger OK" || echo "jaeger no traces (check sampling application.yml:158)"
curl -s -H "X-Correlation-Id: smoke-001" http://localhost:8080/actuator/health >/dev/null && echo "correlationId OK"
echo "done — Prometheus http://localhost:9090  Jaeger http://localhost:16686"
```

---

## References

| File | Key lines |
|---|---|
| `observability/AiMetrics.java:18` | `MeterRegistry` `:48`, `Gauge` `:51`, `Timer` `:82` `:101` `:117` `:134`, lazy `:81` `:116`, `recordToolCall` `:79` |
| `observability/AiObservationConventions.java:7` | `GEN_AI_*` `:12-16`, ops `:18-21`, systems `:23-25`, records `:27-33` |
| `observability/CorrelationIdFilter.java:16` | `OncePerRequestFilter` `:28`, `HEADER` `:30`, `MDC_KEY` `:31`, `MDC.put` `:42`, echo `:43` |
| `resources/logback-spring.xml:1` | console `:12`, prod JSON `:18`, `mdc` `:23` |
| `resources/application.yml:139` | exposure `:144`, `tracing.enabled` `:156`, `sampling` `:158`, `otlp.endpoint` `:161` |
| `resources/application-prod.yml:45` | prod tracing `:48` |
| `pom.xml:276` | `actuator` `:279`, `micrometer-registry-prometheus` `:291`, `tracing-bridge-otel` `:295`, `opentelemetry-exporter-otlp` `:298`, `logstash-logback-encoder` `:302` |
| `docker-compose.yml:83` | Jaeger UI `:86`, OTLP `:88-89` |

> Next: [`17-configuration-and-environments.md`](./17-configuration-and-environments.md) · [`README.md`](./README.md) · [`docs/additions/12-ai-observability.md`](../../12-ai-observability.md)
