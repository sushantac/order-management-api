# docs/additions — Bonus-request follow-ups

*Teach-alongs and deep-dives written after the main journey, indexed here so
each bonus PR is as easy to learn from as the original 35.*

| Document | PR | Covers |
|---|---|---|
| [`01-rag-and-docs-search.md`](01-rag-and-docs-search.md) | #38 | RAG end-to-end — ingest/chunk/embed/retrieve/generate, the exact code, how to run `docs_search`, the failures hit (Spring AI 1.0.0 API, eager auto-config, Java 25/ByteBuddy, shadowed test config) |
| [`02-agentic-tool-calling.md`](02-agentic-tool-calling.md) | #39 | Function calling — the `@Tool` surface, MCP + agent sharing one code path, the `agentic_ask` MCP tool, and the real gotcha (`.defaultToolCallbacks` alone never reaches the model; options must carry the callbacks) |
| [`03-chat-memory.md`](03-chat-memory.md) | #40 | Multi-turn memory — the `ChatMemory` bean + `MessageChatMemoryAdvisor`, how a conversation id is routed via `AdvisorSpec.param(...)` (the 1.0.0 replacement for `.context()`), and the stateless-by-default `conversationId` design |
| [`04-guarded-write-tool.md`](04-guarded-write-tool.md) | #41 | Guarded write — the `cancel_order` MCP tool stage-gated by `app.mcp.write-tool.enabled`, `confirmed=true` per call, service-level `@PreAuthorize(order_write)`, domain state-machine + audit log, and the hard rule that the *agent* never sees it (`AgentToolSet` stays read-only) |
| [`05-rag-productionization.md`](05-rag-productionization.md) | #42 | RAG productionization — persistent pgvector storage (the `VectorStore` swap, the multi-ctor `@ConstructorBinding` gotcha), content-addressed *incremental* re-indexing (deterministic ids, sha-256 `content-hash`, the README-basename collision bug), the guarded `reindex_docs` write trigger, and a retrieval-eval harness (golden questions → hit-rate@k, report-only by default, deploy-gate when earned) |
| [`06-hybrid-retrieval.md`](06-hybrid-retrieval.md) | #43 | Hybrid retrieval — the `RetrievalEngine` seam (`dense` \| `hybrid`), Postgres full-text as the lexical path (GIN index, `ts_rank`, why embeddings miss exact terms), Reciprocal Rank Fusion (worked ranking math behind `rrf-k`), and MMR diversity re-ranking (`mmr-lambda` at its two extremes) — with the #42 harness as the dense-vs-hybrid ruler |