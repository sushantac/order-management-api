# 00. Overview — Data, Agent, Control, Lifecycle (Bonus PRs #38-#52)

> Companion index for [`docs/additions/README.md`](../README.md) · 15 bonus PRs that turn the order API from a CRUD service into an **AI-augmented, MCP-native, production-ready** platform. Read this first, then jump to any deep dive in `01-15`.

---

## Elevator — 15 PRs in 4 planes (one paragraph)

In 15 bonus PRs (#38-#52) this project adds a full **Retrieval-Augmented Generation** stack over its own `docs/` corpus, wraps it in a **Model Context Protocol (MCP) server + agentic loop**, hardens it with a **control plane** (OAuth2, session-scoped audit, and AI observability), and closes the **order lifecycle** with guarded write tools — all behind feature flags so `main` still boots with zero AI cost. The **data plane** (#38 retrieval, #42 productionization, #43 hybrid RRF+MMR, #44 eval gate, #45 streaming, #51 reranking+rewrite) proves that answers are grounded, measurable, and fast. The **agent plane** (#37 SDK, #39 agentic tool-calling, #40 memory, #46 resources/prompts, #52 A2A) proves that one `@Tool` code path serves both MCP and the autonomous agent, with memory and inter-agent delegation. The **control plane** (#47 OAuth2 AS+RS, #48 session audit, #49 observability) proves that every tool call is authenticated, attributed, and metered. The **lifecycle plane** (#41 cancel, #50 confirm/ship) proves that LLM-triggered mutations are safe by construction (flag + `confirmed` token + `@PreAuthorize` + state-machine + audit). Together they are the story you tell when an interviewer asks "how would you ship AI features without burning trust?"

---

## The four planes

### Data plane — RAG you can trust (#38, #42, #43, #44, #45, #51)

| PR | Ships | Why it matters |
|---|---|---|
| **#38** retrieval | `DocumentIngestionService` + `RagService` + `docs_search` | First grounded Q&A: ingest `docs/**/*.md` → chunk → embed (Ollama `nomic-embed-text:768d`) → `VectorStore` → `SYSTEM_PROMPT` forcing `[Source]` citations. Lost on restart (in-memory). |
| **#42** productionization | `PgVectorStore` (`HNSW`, `COSINE`, `vector_store`) + `VectorIndexStore` + `reindex_docs` | Persistence + incremental re-index: deterministic `UUID(name:source:idx)` + `sha256` `content-hash` diff skips unchanged files. Guarded `AbstractMcpWriteTool`. |
| **#43** hybrid | `RetrievalEngine` seam → `DenseRetrievalEngine` + `LexicalRetrievalEngine` + `HybridRetrievalEngine` (RRF + MMR) | Dense misses keywords (`docs_search`), lexical misses paraphrases — fuse via `rrf-k` then diversify via `mmr-lambda`. Toggle `app.rag.retrieval-mode=dense/hybrid`. |
| **#44** eval gate | `RagRetrievalEvaluator` + `RagEvalRunner` + `golden-questions.json` → `hit-rate@k` | Measured 75% `hit-rate@5` on real corpus/model → earned `app.rag.eval.min-hit-rate:0.7` deploy gate + `target/rag-eval-report.json` audit artifact. Fixed eval-before-ingest ordering bug. |
| **#45** streaming | `RagStreamingService` + `RagStreamingController` + `AgentStreamingController` (SSE, `SseEmitter` + virtual threads) | Token-by-token `sources`/`token`/`done`/`error` events; client distinguishes completion from interruption. No WebFlux. |
| **#51** reranking | `RagQueryRewriter` + `SemanticReranker` (feature-flagged) | LLM paraphrases broaden recall; lexical heuristic (cross-encoder-ready) sharpens `top-1` precision without re-embedding. |

Entry points: `src/main/java/com/company/orderapi/rag/RagService.java:30`, `src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:51`, `src/main/java/com/company/orderapi/rag/RetrievalEngine.java:17`, `src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java:30`, `src/main/java/com/company/orderapi/rag/RagStreamingService.java:36`, `src/main/java/com/company/orderapi/rag/SemanticReranker.java:18`, `src/main/java/com/company/orderapi/rag/RagQueryRewriter.java:21`, `src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:53`

### Agent plane — one tool surface, many surfaces (#37, #39, #40, #46, #52)

| PR | Ships | Why it matters |
|---|---|---|
| **#37** SDK | `McpServerConfiguration` (`McpSyncServer`, `Streamable HTTP /mcp`) | App is its own MCP server; Inspector/Claude connect to `http://localhost:8080/mcp`. Foundation for every `tools/list` + `tools/call`. |
| **#39** agentic | `AgentService` + `AgentToolSet` + `AgenticAskTool` (`agentic_ask`) | `ChatClient` loop: model → `@Tool` → model. `AgentToolSet` (`AgentToolSet.java:33`) is the **single `@Tool` surface** reused by MCP and agent. `.defaultToolCallbacks(options)` carries callbacks to the model (the `.defaultToolCallbacks` alone never reaches it). |
| **#40** memory | `ChatMemory` bean + `MessageChatMemoryAdvisor` | Stateless-by-default multi-turn: caller passes `conversationId` via `AdvisorSpec.param(...)` (Spring AI 1.0.0); same id on two `agentic_ask` calls = follow-up context ("remember my last order"). No server session. |
| **#46** resources/prompts | `McpDocsResourceCatalog` + `McpServerConfiguration.resources/prompts` + `SummarizeOrderPrompt`/`DocsQuestionPrompt` | Beyond tools: `resources/list`+`read` exposes `docs/**/*.md` lazily; prompts `summarize_order`/`ask_docs` are parameterized templates (SYSTEM+USER) — they suggest workflows but never widen tool surface. Missing SYSTEM-role gotcha fixed (USER-only invariant). |
| **#52** A2A | `A2aController` (`/.well-known/agent-card` + `/a2a/message`) | Minimal Agent-to-Agent: discovery card + delegation endpoint, extensible to task state. Proves multi-agent interop beyond one MCP server. |

Entry points: `src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:57`, `src/main/java/com/company/orderapi/agent/AgentService.java:44`, `src/main/java/com/company/orderapi/agent/AgentToolSet.java:33`, `src/main/java/com/company/orderapi/agent/AgentConfig.java:29`, `src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java:35`, `src/main/java/com/company/orderapi/a2a/A2aController.java:10`

### Control plane — auth, audit, observability (#47, #48, #49)

| PR | Ships | Why it matters |
|---|---|---|
| **#47** OAuth2 | `OAuth2AuthorizationServerConfig` (AS + RS for `/mcp`), PKCE public client, `client_secret_basic` confidential, rotating refresh, `SCOPE_mcp` gate, RFC 8414 discovery | `/mcp` is `SCOPE_mcp`-gated; JWT RS256 verification. Debugging story: `JwtGenerator` hard-codes RS256 but encoder defaults changed — override in `tokenCustomizer` fixed 401 Bearer disguise. |
| **#48** session audit | `McpToolAudit` + `McpAuditService` + `McpServerConfiguration.contextExtractor` + `mcp_tool_audit` table | Every `tools/call` binds OAuth actor+session via custom `contextExtractor`; immutable row on success/failure via `REQUIRES_NEW` tx; same path covers agent function-calling. |
| **#49** observability | `AiMetrics` (Micrometer timers/counters per tool/mode) + OTel GenAI conventions + correlationId prod JSON logs + Prometheus/OTLP | `rag_query`, `agentic_ask`, `docs_search` latencies/counters by tool/mode; scrape `/actuator/prometheus`, traces show model cost/latency/error. |

Entry points: `src/main/java/com/company/orderapi/authorization/OAuth2AuthorizationServerConfig.java:76`, `src/main/java/com/company/orderapi/mcp/McpToolAudit.java:19`, `src/main/java/com/company/orderapi/mcp/McpAuditService.java:10`, `src/main/java/com/company/orderapi/observability/AiMetrics.java:18`

### Lifecycle plane — writes that earn trust (#41, #50)

| PR | Ships | Why it matters |
|---|---|---|
| **#41** cancel | `CancelOrderTool` (`cancel_order`) — PLACED→CANCELLED | Four-layer guard: `app.mcp.write-tool.enabled` flag + `confirmed=true` per-call token + `@PreAuthorize(order_write)` + domain state-machine + `AuditLog` row. `AgentToolSet` stays read-only — agent never sees it. |
| **#50** confirm/ship | `ConfirmOrderTool` (PLACED→CONFIRMED) + `ShipOrderTool` (CONFIRMED→SHIPPED) + outbox events | Same four layers; extends state machine to full lifecycle with `OutboxEntry` events. Validates that guarded-write pattern scales beyond one mutation. |

Entry points: `src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:37`, `src/main/java/com/company/orderapi/mcp/ConfirmOrderTool.java:16`, `src/main/java/com/company/orderapi/mcp/ShipOrderTool.java:16`, `src/main/java/com/company/orderapi/domain/service/OrderService.java:1`, `src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java:40` vs `src/main/java/com/company/orderapi/mcp/AbstractMcpReadOnlyTool.java:31`

---

## ASCII architecture — docs → ingestion → vector_store → RetrievalEngine → RagService → MCP tools → Agent → streaming

```
                            docs/**/*.md (classpath: docsLocation)
                                       │
                                       │ ApplicationReadyEvent
                                       ▼
                     ┌──────────────────────────────────────┐
                     │ DocumentIngestionService :51          │
                     │  loadFiles() → TokenTextSplitter      │
                     │  (chunkSize 800 / overlap 200)        │
                     │  → UUID(name:source:chunkIdx)         │
                     │  + sha256 content-hash metadata       │
                     └──────────────────┬───────────────────┘
                                        │ addChunks() / deleteChunks()
                                        ▼
                     ┌──────────────────────────────────────┐
                     │ VectorIndexStore / VectorStore        │
                     │  PgVectorIndexStore : PgVectorStore   │
                     │  table=vector_store  dims=768         │
                     │  index=HNSW  distance=COSINE          │
                     │  (PR #38: SimpleVectorStore in-mem)   │
                     └──────────────────┬───────────────────┘
                                        │ similaritySearch()
                     ┌──────────────────┴──────────────────┐
                     │ RetrievalEngine :17  (seam)          │
                     │  DenseRetrievalEngine :17            │
                     │  LexicalRetrievalEngine :31 (tsvector/GIN, ts_rank)
                     │  HybridRetrievalEngine :30           │
                     │    RRF (rrf-k) + MMR (mmr-lambda)    │
                     │  + SemanticReranker :18              │
                     │  + RagQueryRewriter :21 (paraphrases)│
                     └──────────────────┬──────────────────┘
                                        │ retrieve(query, topK)
                     ┌──────────────────▼──────────────────┐
                     │ RagService :30                       │
                     │  1. retrieve(topK)                   │
                     │  2. build SYSTEM_PROMPT(context)     │
                     │     "[Source: file] chunk" joins     │
                     │  3. chatModel.call(Prompt) → answer  │
                     │  @Lazy ChatModel, @ConditionalOn     │
                     └──────────────────┬──────────────────┘
                                        │
                     ┌──────────────────┼──────────────────┐
                     │                  │                   │
          ┌──────────▼────────┐ ┌──────▼────────┐ ┌───────▼────────────┐
          │ DocsSearchTool :24│ │ReindexDocsTool│ │ RagStreamingService│
          │ name=docs_search  │ │:37 reindex_docs│ │ :36  Flux→SseEmitter│
          │ read-only         │ │write (guarded)│ │  sources/token/done│
          └──────────┬────────┘ └──────┬────────┘ └───────┬────────────┘
                     │                 │                   │
                     └─────────┬───────┴──────────┬────────┘
                               ▼                  ▼
                     ┌──────────────────────────────────┐
                     │ McpServerConfiguration :57        │
                     │  McpSyncServer  Streamable HTTP   │
                     │  /mcp  tools/list + tools/call    │
                     │  + resources/list+read (PR #46)   │
                     │  + prompts/list+get               │
                     │  contextExtractor (PR #48 audit)  │
                     │  SCOPE_mcp JWT gate (PR #47)      │
                     └──────────────────┬───────────────┘
                                        │ MCP Streamable HTTP
                     ┌──────────────────┴───────────────┐
                     │ AgentService :44  AgentToolSet :33│
                     │  ChatClient + @Tool callbacks     │
                     │  MessageChatMemoryAdvisor :40     │
                     │  exposes agentic_ask tool :29     │
                     └──────────────────┬───────────────┘
                                        │ SseEmitter (virtual threads)
                     ┌──────────────────▼───────────────┐
                     │ AgentStreamingController :41      │
                     │ RagStreamingController :51        │
                     │  SSE: sources → token* → done/error│
                     │  + A2aController :10              │
                     │    /.well-known/agent-card        │
                     │    POST /a2a/message              │
                     └──────────────────┬───────────────┘
                                        │ text/event-stream
                     ┌──────────────────▼───────────────┐
                     │ Client: MCP Inspector / Claude / │
                     │ curl -N  /  browser EventSource  │
                     └──────────────────────────────────┘

     Guarded writes (out-of-band): CancelOrderTool :37 ─┐
                                   ConfirmOrderTool :16 ─┼─► OrderService ─► OutboxEntry + AuditLog
                                   ShipOrderTool :16 ────┘   (4-layer guard)

     Observability (cross-cutting): AiMetrics :18 ──► Micrometer + OTel GenAI + Prometheus
                                    AppInfoHealthIndicator, correlationId logs (PR #49)
```

Flow in words: `docs` glob → `DocumentIngestionService:88` on `ApplicationReadyEvent` → `VectorStore` (`PgVectorStore.builder` at `RagConfig.java:61`) → `RetrievalEngine.retrieve()` (dense/hybrid at `RagConfig.java:50`, reranked at `RagService.java:71` when flags enabled) → `RagService.answer()` (`RagService.java:71`, SYSTEM_PROMPT at `RagService.java:34`) → MCP tools (`DocsSearchTool:24`, `ReindexDocsTool:37`) registered in `McpServerConfiguration:57` → `AgentService:44` reuses same `AgentToolSet:33` via `AgenticAskTool:29` → streaming controllers (`RagStreamingService:36`, `AgentStreamingController:41`) bridge `Flux` to `SseEmitter` on virtual threads → client.

---

## PR → file → how-to-use table (copy-paste one-liners)

| PR | What | Key file(s) | How to use (one-liner) |
|---|---|---|---|
| **#37** | MCP SDK + server | `src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:57` | `curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \| jq '.result.tools[].name'` |
| **#38** | RAG retrieval | `src/main/java/com/company/orderapi/rag/RagService.java:30`, `src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:51` | `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"How does hybrid retrieval work?"}}}' -H "Content-Type: application/json" \| jq -r '.result.content[0].text'` |
| **#39** | Agentic tool-calling | `src/main/java/com/company/orderapi/agent/AgentService.java:44`, `src/main/java/com/company/orderapi/agent/AgentToolSet.java:33` | `curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"question":"What docs explain the outbox pattern?"}}}' \| jq -r '.result.content[0].text'` |
| **#40** | Chat memory | `src/main/java/com/company/orderapi/agent/AgentConfig.java:29` (`ChatMemory` + `MessageChatMemoryAdvisor`) | `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"question":"follow-up","conversationId":"demo-123"}}}' -H "Content-Type: application/json" \| jq .` — send two calls with same `conversationId` to see context retained |
| **#41** | Guarded write (cancel) | `src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:37`, `src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java:40` | `curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"cancel_order","arguments":{"orderId":1,"confirmed":false}}}' \| jq .` → blocked; retry with `"confirmed":true` + `order_write` authority |
| **#42** | RAG productionization | `src/main/java/com/company/orderapi/rag/RagConfig.java:61` (`PgVectorStore`), `src/main/java/com/company/orderapi/mcp/ReindexDocsTool.java:37` | `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"reindex_docs","arguments":{"confirmed":true}}}' -H "Content-Type: application/json" \| jq -r '.result.content[0].text'` — second call is no-op (incremental `content-hash` diff) |
| **#43** | Hybrid retrieval | `src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java:30`, `src/main/java/com/company/orderapi/rag/LexicalRetrievalEngine.java:31` | Toggle `app.rag.retrieval-mode: hybrid` vs `dense` in `application-rag.yml:25` and re-run `./mvnw verify -Dspring.profiles.active=rag` to compare `hit-rate@k` — tune `rrf-k` and `mmr-lambda` |
| **#44** | Eval gate (earned) | `src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:53`, `src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:31` | `./mvnw verify -Dspring.profiles.active=rag -Dapp.rag.eval.enabled=true && cat target/rag-eval-report.json \| jq .` — build fails if `hitRate < 0.7` (`RagProperties.java:126`) |
| **#45** | Streaming SSE | `src/main/java/com/company/orderapi/rag/RagStreamingService.java:36`, `src/main/java/com/company/orderapi/api/rest/controller/RagStreamingController.java:51` | `curl -N http://localhost:8080/api/rag/stream?question="Explain outbox" -H "Accept: text/event-stream"` — watch `event: sources` then `event: token` then `event: done` |
| **#46** | Resources + prompts | `src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java:35`, `src/main/java/com/company/orderapi/mcp/SummarizeOrderPrompt.java:20` | `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":7,"method":"resources/list","params":{}}' -H "Content-Type: application/json" \| jq .` then `resources/read` with a `uri`; `prompts/list` + `prompts/get` for `summarize_order` |
| **#47** | OAuth2 AS+RS | `src/main/java/com/company/orderapi/authorization/OAuth2AuthorizationServerConfig.java:76` | `curl -s http://localhost:8080/.well-known/oauth-authorization-server \| jq .` then PKCE token flow → `curl -s http://localhost:8080/mcp -H "Authorization: Bearer $JWT" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \| jq .` |
| **#48** | Session audit | `src/main/java/com/company/orderapi/mcp/McpToolAudit.java:19`, `src/main/java/com/company/orderapi/mcp/McpAuditService.java:10` | Call any MCP tool with a JWT, then `docker compose exec postgres psql -U order -d orderdb -c "SELECT tool_name, actor, success, session_id FROM mcp_tool_audit ORDER BY created_at DESC LIMIT 5;"` |
| **#49** | Observability | `src/main/java/com/company/orderapi/observability/AiMetrics.java:18` | `curl -s http://localhost:8080/actuator/prometheus \| grep -E "rag|ai|mcp"` and `curl -s http://localhost:8080/actuator/metrics \| jq '.names\|map(select(contains("rag") or contains("ai")))'` |
| **#50** | Guarded writes expanded | `src/main/java/com/company/orderapi/mcp/ConfirmOrderTool.java:16`, `src/main/java/com/company/orderapi/mcp/ShipOrderTool.java:16` | `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"confirm_order","arguments":{"orderId":1,"confirmed":true}}}' -H "Content-Type: application/json" \| jq .` then `ship_order` — verify `OrderStatus` progression + outbox row |
| **#51** | Reranking + query rewrite | `src/main/java/com/company/orderapi/rag/RagQueryRewriter.java:21`, `src/main/java/com/company/orderapi/rag/SemanticReranker.java:18` | Enable `app.rag.query-rewrite.enabled=true` and `app.rag.reranker.enabled=true` in `application-rag.yml:25`, restart with `--spring.profiles.active=rag`, compare `top-1` hit before/after via eval harness |
| **#52** | A2A multi-agent | `src/main/java/com/company/orderapi/a2a/A2aController.java:10` | `curl -s http://localhost:8080/.well-known/agent-card \| jq .` then `curl -s http://localhost:8080/a2a/message -H "Content-Type: application/json" -d '{"message":"hello from peer"}' \| jq .` |

> Tip: without `--spring.profiles.active=rag` most data-plane tools are absent — that is intentional. Gate is `app.rag.enabled` at `RagConfig.java:35` + `@ConditionalOnBean(RagService.class)` at `DocsSearchTool.java:23`.

---

## Reading order (suggested paths)

- **First time (15 min):** this overview → `01-rag-and-docs-search.md` (#38) → `02-agentic-tool-calling.md` (#39) → `12-ai-observability.md` (#49). Gives data → agent → control arc.
- **RAG deep dive:** `01` (#38) → `05` (#42) → `06` (#43) → `07` (#44) → `14` (#51) → `08` (#45 streaming).
- **Agent deep dive:** `02` (#39) → `03` (#40 memory) → `09` (#46 resources/prompts) → `15` (#52 A2A).
- **Hardening deep dive:** `04` (#41 cancel) → `13` (#50 confirm/ship) → `10` (#47 OAuth2) → `11` (#48 audit) → `12` (#49 observability).
- **Interview cram (pick one per plane):** `01` or `06` (data), `02` (agent), `10` or `11` (control), `04` or `13` (lifecycle) — then rehearse the STAR story below.

All deep dives live in `docs/additions/more-detail-on-additions/` and are indexed in [`docs/additions/README.md`](../README.md) and [`docs/additions/more-detail-on-additions/README.md`](./README.md).

---

## Job lens — how to tell the story in an interview (STAR)

Interviewers rarely ask "list 15 PRs." They ask "tell me about a time you shipped an AI feature safely" or "how would you add grounded Q&A over private docs?" Use **STAR** with one PR per plane — 90 seconds, with file:line receipts.

### STAR template (fill with any row from the table)

**S — Situation (10s):** *"Our order API had rich `docs/` but no runtime knowledge surface — support questions, onboarding, and runbooks were grep-only. Separately, order mutations via AI were unsafe by default."*

**T — Task (10s):** *"Ship grounded Q&A over private docs, expose it via MCP, make mutations auditable, and prove quality with data — all behind flags so non-AI deployments pay nothing."*

**A — Action (45s) — pick one per plane and name the files:**

- *Data:* "I built RAG end-to-end (PR #38): `DocumentIngestionService.java:51` chunks `docs/**/*.md` with `TokenTextSplitter(800,200)` at `DocumentIngestionService.java:144`, embeds via Ollama `nomic-embed-text` (768d), stores in `PgVectorStore` (`RagConfig.java:61`, HNSW+COSINE), retrieves via `RetrievalEngine.java:17` seam, and `RagService.java:30` enforces a `SYSTEM_PROMPT` at `RagService.java:34` — `Answer using ONLY context, cite [Source]` — before `chatModel.call()` at `RagService.java:95`. Then productionized it (PR #42 hybrid RRF+MMR at `HybridRetrievalEngine.java:30` + incremental `content-hash` reindex) and earned the deploy gate (PR #44 `RagEvalRunner.java:53`, `hit-rate@5 75%` → `min-hit-rate:0.7`)."
- *Agent:* "One `@Tool` surface (`AgentToolSet.java:33`) serves both MCP (`McpServerConfiguration.java:57`) and the autonomous agent (`AgentService.java:44`). The gotcha was `ChatClient.defaultToolCallbacks` never reaching the model — callbacks must be carried in `ChatOptions` (`AgentConfig.java:29`). Memory (PR #40) is stateless-by-default via `MessageChatMemoryAdvisor` + `conversationId` param."
- *Control:* "The app is its own OAuth2 AS+RS for `/mcp` (PR #47 `OAuth2AuthorizationServerConfig.java:76`, RFC 8414 discovery, `SCOPE_mcp`). Every `tools/call` is attributed via a custom `contextExtractor` at `McpServerConfiguration.java:93` and written immutably to `mcp_tool_audit` (`McpToolAudit.java:19`, `McpAuditService.java:10` with `REQUIRES_NEW`). `AiMetrics.java:18` emits per-tool timers/counters to Prometheus/OTLP (PR #49)."
- *Lifecycle:* "Writes are read-only by default. Mutations (`cancel_order:37`, `confirm_order:16`, `ship_order:16`) pass four layers at `AbstractMcpWriteTool.java:40`: `app.mcp.write-tool.enabled` flag + `confirmed=true` token + `@PreAuthorize(order_write)` + domain state-machine (`OrderService.java`) + immutable audit row. The agent's tool set stays read-only."

**R — Result (15s) + honest limit (10s):** *"Result: `docs_search` and `agentic_ask` answer from project truth with citations, streaming (`RagStreamingService.java:36` + `AgentStreamingController.java:41`) delivers tokens via SSE with `done` vs interruption distinction, every tool call is scoped + audited, and a bad chunking change fails the build instead of prod (`RagEvalRunner.java:53`). Limit: RAG is only as good as the corpus — which is why PR #44 gates on measured `hit-rate@5`, not an arbitrary threshold — and embeddings are lexical-blind without hybrid RRF, which is why PR #43 exists."*

### Two follow-up Q&A you can now answer verbatim

**Q: "How do you prevent an LLM from hallucinating over private docs?"**
> "Only the top-k retrieved chunks reach the model. `RagService.answer()` at `RagService.java:71` calls `retrievalEngine.retrieve()` at `RetrievalEngine.java:17`, joins them as `[Source: filename]\nchunk`, and injects them into a `SYSTEM_PROMPT` at `RagService.java:34` that says `Answer using ONLY the provided documentation context... mention its source filename. If insufficient, say so.` If `relevantDocs.isEmpty()` we return `No relevant documentation` without calling the model at all. The MCP tool `DocsSearchTool.java:24` is `@ConditionalOnBean(RagService.class)` and read-only (`AbstractMcpReadOnlyTool.java:31`), so it cannot mutate."

**Q: "How do you make LLM-triggered writes safe?"**
> "Four layers, same for `cancel_order:37`, `confirm_order:16`, `ship_order:16`: (1) `app.mcp.write-tool.enabled` feature flag at `AbstractMcpWriteTool.java:40`, (2) per-call `confirmed=true` token the client must explicitly set, (3) service-level `@PreAuthorize("hasAuthority('order_write')")` at `OrderService.java`, (4) domain state-machine (e.g. only `PLACED→CANCELLED`, `PLACED→CONFIRMED→SHIPPED`) plus immutable `AuditLog`/`OutboxEntry` row. The agent's `AgentToolSet.java:33` is deliberately read-only — it never imports a write tool — so autonomy cannot escalate."

### What to demo live (pick one, 30s)

1. `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"How does hybrid retrieval with RRF work?"}}}' -H "Content-Type: application/json" | jq -r '.result.content[0].text'` — show `[Source: ...]` citations.
2. `curl -N http://localhost:8080/api/agentic/stream?question="Explain the outbox pattern"` — watch `sources` → `token`* → `done`.
3. `curl -s http://localhost:8080/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cancel_order","arguments":{"orderId":1,"confirmed":false}}}' ... | jq .` → blocked, then `confirmed:true` → succeeds (with JWT).
4. `docker compose exec postgres psql -U order -d orderdb -c "SELECT tool_name, success, actor FROM mcp_tool_audit LIMIT 5;"` — prove audit.
5. `./mvnw verify -Dapp.rag.eval.enabled=true && cat target/rag-eval-report.json | jq .hitRate` — prove gate.

---

## Failure modes that shaped the design (why each plane looks the way it does)

These are the bugs and gotchas that forced the final shape — cite them when an interviewer asks "what went wrong?"

| Plane | Failure | Fix (file:line) |
|---|---|---|
| Data | Spring AI 1.0.0 eager auto-config created `PgVectorStore` in every test context, even with `app.rag.enabled=false` | Switched to plain `spring-ai-pgvector-store` library + `@ConditionalOnProperty` at `RagConfig.java:35`, manual `PgVectorStore.builder(...).initializeSchema(true)` at `RagConfig.java:61` |
| Data | `SimpleVectorStore` lost everything on restart; re-embedding 47 docs every boot cost ~4s + API calls | `PgVectorStore` HNSW at `RagConfig.java:64` + `VectorIndexStore` diff via `content-hash` at `DocumentIngestionService.java:150` — `reindex()` is now `O(changed)` |
| Data | `README.md` basename collision: two `README.md` in different folders hashed to same id, one overwrote the other | Deterministic id now `UUID.nameUUIDFromBytes(source + ":" + chunkIdx)` at `DocumentIngestionService.java:220` with full relative `source` in metadata `DocumentIngestionService.java:211` |
| Data | Eval measured empty store (`hit-rate 0%`) because `RagEvalRunner` ran before `DocumentIngestionService` `ApplicationReadyEvent` | Fixed ordering: `RagEvalRunner.java:53` implements `ApplicationListener<ApplicationReadyEvent>` and runs *after* `DocumentIngestionService.java:88`; added `@DependsOn` guard |
| Data | Dense retrieval missed exact keywords (`docs_search`, `reindex_docs`); embeddings are semantic, not lexical | `HybridRetrievalEngine.java:30` fuses `DenseRetrievalEngine.java:17` + `LexicalRetrievalEngine.java:31` (Postgres `tsvector`/GIN, `ts_rank`) via RRF `k=60` at `HybridRetrievalEngine.java:72`, optional MMR rerank at `HybridRetrievalEngine.java:85` |
| Agent | `AgentService` `.defaultToolCallbacks(...)` alone never reached the model — tool calls were silently ignored | `AgentConfig.java:29` carries callbacks via `ChatOptions` (`DefaultChatOptions.builder().toolCallbacks(...)`) — the actual field the model reads |
| Agent | `@Tool` methods duplicated between MCP and agent — drift risk | Single `AgentToolSet.java:33` with `@Tool(name="api_health")` at `AgentToolSet.java:51` etc. reused by `McpServerConfiguration.java:57` via `MethodToolCallbackProvider` at `AgentConfig.java:16` |
| Agent | Spring AI 1.0.0 removed `.context()` for memory routing; `.param()` is the replacement | `MessageChatMemoryAdvisor` wired with `AdvisorSpec.param("conversationId", id)` — stateless-by-default, caller-supplied |
| Control | JWT verification failed with opaque `401 Bearer` despite valid token — `JwtGenerator` hard-codes RS256 while encoder defaulted differently | Overrode algorithm in `OAuth2AuthorizationServerConfig.java:76` `tokenCustomizer` to pin RS256 for both generator + encoder |
| Control | WebFlux streaming pulled in reactive stack for a servlet app; blocked `Flux` on MVC thread | `RagStreamingService.java:36` + `AgentStreamingController.java:41` use `SseEmitter` + virtual threads; `blockLast()` is safe on a virtual thread, no WebFlux dependency |
| Lifecycle | Agent could call `cancel_order` autonomously — unsafe | `AgentToolSet.java:33` stays read-only; write tools (`CancelOrderTool.java:37`, `ConfirmOrderTool.java:16`, `ShipOrderTool.java:16`) extend `AbstractMcpWriteTool.java:40` which is never added to the agent's callback set |

---

## Configuration surface (what to toggle, where it lives)

```yaml
# src/main/resources/application-rag.yml:25
app:
  rag:
    enabled: true
    docs-location: "classpath:docs/**/*.md"   # DocumentIngestionService.java:53
    chunk-size: 800                            # TokenTextSplitter at DocumentIngestionService.java:144
    chunk-overlap: 200
    top-k: 5                                   # RagService.java:71 retrieve(topK)
    embedding-dimensions: 768                  # nomic-embed-text, RagProperties.java:51
    vector-table: vector_store                 # RagConfig.java:61
    retrieval-mode: hybrid                     # RagConfig.java:50  dense | hybrid
    retrieval:                                 # HybridRetrievalEngine.java:30
      rrf-k: 60                                # HybridRetrievalEngine.java:72
      mmr-enabled: false                       # HybridRetrievalEngine.java:85 (flipped off after #44 measurement)
      mmr-lambda: 0.5
    query-rewrite-enabled: false               # RagQueryRewriter.java:21  PR #51
    reranker-enabled: false                    # SemanticReranker.java:18 PR #51
    eval:                                      # RagEvalRunner.java:53  PR #44
      enabled: false                           # report-only by default
      goldens-location: classpath:rag/golden-questions.json
      min-hit-rate: 0.7                        # RagProperties.java:126 earned gate
  mcp:
    write-tool-enabled: false                  # AbstractMcpWriteTool.java:40  PR #41/#50

# src/main/resources/application.yml:119  (chat) + application-rag.yml:12 (embedding)
spring.ai.model.chat: deepseek-v4-flash (temp 0.3)
spring.ai.model.embedding: nomic-embed-text via Ollama http://localhost:11434
```

Profile gating: `./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag` activates `application-rag.yml:25`; without it `RagConfig.java:35` and `DocsSearchTool.java:23` never load — zero AI beans, zero cost.

---

## Key entry points — quick reference

| Layer | File | Line | Role |
|---|---|---|---|
| Data | `src/main/java/com/company/orderapi/rag/RagService.java` | `:30` | Grounded generation orchestrator |
| Data | `src/main/java/com/company/orderapi/rag/DocumentIngestionService.java` | `:51` | Ingestion + incremental reindex |
| Data | `src/main/java/com/company/orderapi/rag/RagConfig.java` | `:61` | `PgVectorStore` bean (HNSW/COSINE) |
| Data | `src/main/java/com/company/orderapi/rag/RetrievalEngine.java` | `:17` | Seam (`dense`/`hybrid`) |
| Data | `src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java` | `:30` | RRF + MMR fusion |
| Data | `src/main/java/com/company/orderapi/rag/RagStreamingService.java` | `:36` | Flux→SSE bridge |
| Data | `src/main/java/com/company/orderapi/rag/SemanticReranker.java` | `:18` | Reranking heuristic |
| Data | `src/main/java/com/company/orderapi/rag/RagQueryRewriter.java` | `:21` | Query paraphrasing |
| Data | `src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java` | `:53` | Eval harness + deploy gate |
| Agent | `src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java` | `:57` | MCP server (tools/resources/prompts) |
| Agent | `src/main/java/com/company/orderapi/agent/AgentService.java` | `:44` | Agentic loop |
| Agent | `src/main/java/com/company/orderapi/agent/AgentToolSet.java` | `:33` | Single `@Tool` surface |
| Agent | `src/main/java/com/company/orderapi/agent/AgentConfig.java` | `:29` | `ChatClient` + tool callbacks |
| Agent | `src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java` | `:35` | Resources over `docs/` |
| Agent | `src/main/java/com/company/orderapi/a2a/A2aController.java` | `:10` | A2A discovery + delegation |
| Control | `src/main/java/com/company/orderapi/authorization/OAuth2AuthorizationServerConfig.java` | `:76` | OAuth2 AS+RS |
| Control | `src/main/java/com/company/orderapi/mcp/McpToolAudit.java` | `:19` | Audit row |
| Control | `src/main/java/com/company/orderapi/observability/AiMetrics.java` | `:18` | Timers/counters |
| Lifecycle | `src/main/java/com/company/orderapi/mcp/CancelOrderTool.java` | `:37` | Guarded cancel |
| Lifecycle | `src/main/java/com/company/orderapi/mcp/ConfirmOrderTool.java` | `:16` | Guarded confirm |
| Lifecycle | `src/main/java/com/company/orderapi/mcp/ShipOrderTool.java` | `:16` | Guarded ship |
| Lifecycle | `src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java` | `:40` | Write-tool guard contract |

---

## Verify in 60 seconds (smoke checklist)

```bash
# 0. prereqs (once)
ollama serve &; ollama pull nomic-embed-text; export DEEPSEEK_API_KEY=sk-...
docker compose up -d postgres

# 1. boot with RAG
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag 2>&1 | grep RAG
# → RAG: indexed 47 docs (183 chunks added, 0 deleted, ...) at DocumentIngestionService.java:88

# 2. MCP present
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
# → ["docs_search","agentic_ask","api_health","product_search","order_status", ... reindex_docs when enabled]

# 3. grounded answer + audit
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"What is the order state machine?"}}}' | jq -r '.result.content[0].text'
# → [Source: ...] citations from RagService.java:34 prompt

# 4. streaming
curl -N http://localhost:8080/api/rag/stream?question="Explain hybrid retrieval" -H "Accept: text/event-stream" | head -n 20
# → event: sources, event: token, event: done  (RagStreamingService.java:36)

# 5. metrics + eval gate
curl -s http://localhost:8080/actuator/prometheus | grep -E "ai_rag|ai_mcp"
./mvnw verify -Dspring.profiles.active=rag -Dapp.rag.eval.enabled=true && cat target/rag-eval-report.json | jq '.hitRate, .retrievalMode'
```

---

> **Next:** pick a plane — [`01-rag-and-docs-search.md`](./01-rag-and-docs-search.md) (data), [`02-agentic-tool-calling.md`](./02-agentic-tool-calling.md) (agent), [`10-mcp-authorization-oauth2.md`](./10-mcp-authorization-oauth2.md) (control), or [`04-guarded-write-tool.md`](./04-guarded-write-tool.md) (lifecycle). Or skim all 15 via [`../README.md`](../README.md).
