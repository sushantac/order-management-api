# docs/additions — Bonus-request follow-ups

*Teach-alongs and deep-dives written after the main journey, indexed here so
each bonus PR is as easy to learn from as the original 35.*

| Document | PR | Covers |
|---|---|---|
| [`01-rag-and-docs-search.md`](01-rag-and-docs-search.md) | #38 | RAG end-to-end — ingest/chunk/embed/retrieve/generate, the exact code, how to run `docs_search`, the failures hit (Spring AI 1.0.0 API, eager auto-config, Java 25/ByteBuddy, shadowed test config) |
| [`02-agentic-tool-calling.md`](02-agentic-tool-calling.md) | #39 | Function calling — the `@Tool` surface, MCP + agent sharing one code path, the `agentic_ask` MCP tool, and the real gotcha (`.defaultToolCallbacks` alone never reaches the model; options must carry the callbacks) |
| [`03-chat-memory.md`](03-chat-memory.md) | #40 | Multi-turn memory — the `ChatMemory` bean + `MessageChatMemoryAdvisor`, how a conversation id is routed via `AdvisorSpec.param(...)` (the 1.0.0 replacement for `.context()`), and the stateless-by-default `conversationId` design |