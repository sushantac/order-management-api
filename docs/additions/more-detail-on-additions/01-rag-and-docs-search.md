# 01. RAG and docs_search — Retrieval-Augmented Generation end-to-end (PR #38)

> PR: [#38 — RAG end-to-end (ingest → embed → retrieve → generate) + `docs_search` MCP tool](https://github.com/anomalyco/order-management-api/pull/38) · Profile: `rag` · Stack: Spring AI 1.0.0, DeepSeek Chat, Ollama `nomic-embed-text`, pgvector (from PR #42)

---

## 1. Purpose — what shipped

PR #38 ships **grounded Q&A over the project's own `docs/` corpus** — the first AI feature in the API. On startup the app ingests every `docs/**/*.md` file, chunks it, embeds each chunk with a local embedding model, and stores the vectors in a `VectorStore`. At query time the `docs_search` MCP tool (and the underlying `RagService`) retrieves the top-k most relevant chunks for your question and feeds them — and only them — to DeepSeek for a concise, source-attributed answer. No hallucinated knowledge, no customer data, no external search index.

Think of it as: *"ChatGPT, but it may only answer from our docs, and it must cite the source file."* The feature is fully opt-in (`app.rag.enabled=true` / `--spring.profiles.active=rag`) so every non-RAG context boots with zero AI cost or infrastructure. This is the foundation every later RAG PR builds on (#42 persistence, #43 hybrid retrieval, #44 eval gate).

---

## 2. Problem — what was missing/broken without it

**Before PR #38** the `docs/` folder was rich (architecture, PR walkthroughs, runbooks) but inert at runtime. If you asked the MCP assistant "how does order cancellation work?" or "what does the resilience4j config do?", it had no way to answer from project truth:

| Before | After (PR #38) |
|---|---|
| Docs only readable by humans browsing GitHub / local checkout | Docs indexed into vectors, searchable by meaning (not just `grep`) |
| MCP tools operated on live data (orders, products, health) but had no knowledge surface | `docs_search` gives the assistant a read-only knowledge surface — same MCP transport, same auth, same observability |
| No grounding — any LLM answer would be hallucinated from training data | System prompt forces "answer using ONLY provided context; cite `source` filename; say when context is insufficient" |
| No reusable RAG plumbing for later PRs | Establishes `VectorStore`, `RagService`, `DocumentIngestionService`, `RagProperties`, `RetrievalEngine` seam — everything PRs #42-44 extend |

Without this PR, PR #42 (pgvector persistence), #43 (hybrid retrieval), #44 (eval gate) and #46 (MCP resources/prompts) have nothing to extend.

---

## 3. Solution — architecture with ASCII sequence/component diagram

### Component view

```
                    ┌─────────────────────────────────────────┐
                    │            docs/**/*.md (classpath)     │
                    │   business/, ops/, additions/, etc.    │
                    └────────────────┬────────────────────────┘
                                     │ ApplicationReadyEvent
                                     ▼
                    ┌─────────────────────────────────┐
                    │ DocumentIngestionService          │◄── RagProperties (chunkSize, overlap, docsLocation)
                    │  loadFiles() → TokenTextSplitter  │
                    │  → UUID(name:idx) + sha256 hash   │
                    └────────────────┬──────────────────┘
                                     │ addChunks()
                                     ▼
                    ┌─────────────────────────────────┐
                    │ VectorIndexStore / VectorStore    │ PR #38: SimpleVectorStore (in-memory)
                    │  PgVectorStore (PR #42) on pgvector│ HNSW + COSINE, 768 dims (nomic-embed-text)
                    └────────────────┬──────────────────┘
                                     │ similaritySearch()
                    ┌────────────────┴──────────────────┐
                    │ RetrievalEngine seam (PR #43)     │
                    │  DenseRetrievalEngine  ─┐         │
                    │  HybridRetrievalEngine ─┼─ RRF+MMR│
                    └────────────────┬──────────┘         │
                                     │ retrieve(q, topK)  │
                    ┌────────────────▼──────────────────┐ │
                    │ RagService.answer(question)       │ │
                    │  1. retrieve(topK)                │ │
                    │  2. build SYSTEM_PROMPT(context)  │ │
                    │  3. chatModel.call(Prompt) → ans  │ │
                    └────────────────┬──────────────────┘ │
                                     │                    │
                    ┌────────────────▼──────────────────┐ │
                    │ DocsSearchTool (MCP read-only)    │ │
                    │  name="docs_search"               │ │
                    └────────────────┬──────────────────┘ │
                                     │ MCP Streamable HTTP│
                    ┌────────────────▼──────────────────┐
                    │  MCP Inspector / Claude / client  │
                    └───────────────────────────────────┘
```

### Sequence — `docs_search` call

```
Client (MCP)         DocsSearchTool        RagService         RetrievalEngine       ChatModel (DeepSeek)
   │                      │                    │                      │                     │
   │── tools/call docs_search ──▶│            │                      │                     │
   │  {question}          │── isIndexed()? ──▶│                      │                     │
   │                      │◀─ true ───────────│                      │                     │
   │                      │── answer(q) ─────▶│                      │                     │
   │                      │                    │── retrieve(q, topK) ──▶│                     │
   │                      │                    │  (dense or hybrid)   │── embed(q) ──▶Ollama│
   │                      │                    │◀─ List<Document> ────│                     │
   │                      │                    │  [Source: X] chunks  │                     │
   │                      │                    │── Prompt(sys+user) ──────────────────────▶│
   │                      │                    │◀─ grounded answer ────────────────────────│
   │◀─ answer + sources ──│◀─ answer ─────────│                      │                     │
```

Key invariant: the LLM never sees the full corpus — only the top-k retrieved chunks. That is what makes RAG grounded and cheap.

---

## 4. How it is implemented — file map + annotated code snippets with file:line references

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/rag/RagProperties.java:25` | `@ConfigurationProperties(prefix="app.rag")` record — single source of truth for every RAG knob |
| `src/main/java/com/company/orderapi/rag/RagConfig.java:34` | `@Configuration` wired only when `app.rag.enabled=true`; builds `VectorStore`, `RetrievalEngine`, `VectorIndexStore` |
| `src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:49` | Startup ingestion + incremental `reindex()`; chunking, hashing, id assignment |
| `src/main/java/com/company/orderapi/rag/RagService.java:30` | Orchestrator: `retrieve()` → `SYSTEM_PROMPT` → `ChatModel.call()` → grounded answer |
| `src/main/java/com/company/orderapi/rag/RetrievalEngine.java:17` | Interface seam — `List<Document> retrieve(String query, int topK)` |
| `src/main/java/com/company/orderapi/rag/DenseRetrievalEngine.java:17` | Pure embedding similarity — the PR #38 behaviour preserved |
| `src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java:30` | PR #43 — dense + lexical fused via RRF, optional MMR rerank |
| `src/main/java/com/company/orderapi/mcp/DocsSearchTool.java:22` | MCP read-only tool `docs_search` — thin adapter over `RagService` |
| `src/main/java/com/company/orderapi/mcp/ReindexDocsTool.java:34` | PR #42 — guarded write tool `reindex_docs` triggering `DocumentIngestionService.reindex()` |
| `src/main/resources/application-rag.yml:25` | Profile that enables RAG, selects `nomic-embed-text` + `deepseek-v4-flash`, sets `vector_store` |
| `pom.xml:70` | `spring-ai-bom:1.0.0` + `spring-ai-starter-model-deepseek`, `spring-ai-starter-model-ollama`, `spring-ai-pgvector-store` |

### Snippet 1 — `RagProperties` defaults (`RagProperties.java:25-65`)

```java
// src/main/java/com/company/orderapi/rag/RagProperties.java:25
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean enabled, String docsLocation, int chunkSize, int chunkOverlap, int topK,
        int embeddingDimensions, String vectorTable,
        RetrievalMode retrievalMode, RetrievalSettings retrieval, RagEvalProperties eval) {
    @ConstructorBinding // src/main/java/com/company/orderapi/rag/RagProperties.java:45
    public RagProperties {
        if (docsLocation == null || docsLocation.isBlank()) docsLocation = "classpath:docs/**/*.md";
        if (chunkSize <= 0) chunkSize = 800;
        if (chunkOverlap < 0) chunkOverlap = 200;
        if (topK <= 0) topK = 5;
        if (embeddingDimensions <= 0) embeddingDimensions = 768; // nomic-embed-text
        if (vectorTable == null || vectorTable.isBlank()) vectorTable = "vector_store";
        if (retrievalMode == null) retrievalMode = RetrievalMode.HYBRID;
    }
}
```

Record + compact constructor: unset YAML keys bind as `null`/`0`, so `app.rag.enabled=true` alone boots a working stack.

### Snippet 2 — `RagConfig` wires vector store + retrieval seam (`RagConfig.java:61-72`, `RagConfig.java:50-59`)

```java
// src/main/java/com/company/orderapi/rag/RagConfig.java:61
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel, DataSource dataSource,
                               RagProperties ragProperties) {
    return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
            .vectorTableName(ragProperties.vectorTable())       // "vector_store"
            .dimensions(ragProperties.embeddingDimensions())    // 768
            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
            .indexType(PgVectorStore.PgIndexType.HNSW)
            .initializeSchema(true) // idempotent CREATE EXTENSION + TABLE + INDEX
            .build();
}
// src/main/java/com/company/orderapi/rag/RagConfig.java:50
@Bean
public RetrievalEngine retrievalEngine(DenseRetrievalEngine dense, LexicalRetrievalEngine lexical,
                                       EmbeddingModel embeddingModel, RagProperties props) {
    return switch (props.retrievalMode()) {
        case DENSE -> dense;
        case HYBRID -> new HybridRetrievalEngine(dense, lexical, embeddingModel, props.retrieval());
    };
}
```

Uses `spring-ai-pgvector-store` (plain library) not the starter — the starter's auto-config would eagerly create a `PgVectorStore` in every context including tests. Guarded by `@ConditionalOnProperty(prefix="app.rag", name="enabled", havingValue="true")` at `RagConfig.java:35`.

### Snippet 3 — Incremental, content-addressed ingestion (`DocumentIngestionService.java:120-174`)

```java
// src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:120
public ReindexReport reindex() throws IOException { return reindex(loadFiles()); }

// src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:144
TokenTextSplitter splitter = new TokenTextSplitter(
        ragProperties.chunkSize(), ragProperties.chunkOverlap(), 10, 10000, true);

// src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:150
boolean unchanged = !existing.isEmpty()
        && existing.stream().allMatch(c -> file.contentHash().equals(c.contentHash()));
if (unchanged) { filesUnchanged++; continue; } // skip — no re-embedding cost
indexStore.deleteChunks(existing.stream().map(VectorIndexStore.StoredChunk::id).toList());
List<Document> chunks = chunk(file, splitter);
indexStore.addChunks(chunks);
```

Each chunk gets deterministic id `UUID.nameUUIDFromBytes((source + ":" + chunkIndex))` (`DocumentIngestionService.java:220`) and carries `source` + `content-hash` metadata (`DocumentIngestionService.java:211-223`). Makes `reindex()` idempotent: call twice with no edits and nothing is re-embedded.

### Snippet 4 — Grounded generation (`RagService.java:34-95`)

```java
// src/main/java/com/company/orderapi/rag/RagService.java:34
private static final String SYSTEM_PROMPT = """
        You are a helpful assistant for the Order Management API project.
        Answer the user's question using ONLY the provided documentation context.
        If the context does not contain enough information to answer, say so clearly.
        Be concise and direct. When referencing a specific document, mention its source filename.

        ## Documentation context:
        %s
        """;

// src/main/java/com/company/orderapi/rag/RagService.java:71
public String answer(String question) {
    List<Document> relevantDocs = retrievalEngine.retrieve(question, ragProperties.topK());
    if (relevantDocs.isEmpty()) return "No relevant documentation found ...";
    String context = relevantDocs.stream()
            .map(doc -> "[Source: " + doc.getMetadata().getOrDefault("source", "unknown") + "]\n" + doc.getText())
            .collect(Collectors.joining("\n\n---\n\n"));
    Prompt prompt = new Prompt(List.of(
            new SystemMessage(SYSTEM_PROMPT.formatted(context)), new UserMessage(question)));
    return chatModel.call(prompt).getResult().getOutput().getText();
}
```

`ChatModel` is `@Lazy` at `RagService.java:50` — DeepSeek bean needs `DEEPSEEK_API_KEY` and would fail fast in non-RAG contexts otherwise. Whole service is `@ConditionalOnProperty(app.rag.enabled=true)` at `RagService.java:29`.

### Snippet 5 — MCP adapter (`DocsSearchTool.java:22-68`)

```java
// src/main/java/com/company/orderapi/mcp/DocsSearchTool.java:22
@Component
@ConditionalOnBean(RagService.class) // only when RAG is enabled
public class DocsSearchTool extends AbstractMcpReadOnlyTool {
    @Override public String name() { return "docs_search"; } // src/main/java/com/company/orderapi/mcp/DocsSearchTool.java:36
    @Override protected String run(Map<String, Object> arguments) {
        if (!ingestionService.isIndexed()) return "Documentation index is not loaded yet...";
        return ragService.answer(question); // src/main/java/com/company/orderapi/mcp/DocsSearchTool.java:67
    }
}
```

Read-only tool — no `confirmed` gate, no audit row — consistent with `AbstractMcpReadOnlyTool` vs `AbstractMcpWriteTool` (used by `reindex_docs`/`cancel_order`).

---

## 5. How to use the feature — step-by-step with curl / mcp tools/call docs_search and how to run with rag profile

### Prerequisites

```bash
brew install ollama
ollama serve &                  # keep running
ollama pull nomic-embed-text    # 768-dim embedding model (~270 MB)
export DEEPSEEK_API_KEY="sk-..."   # https://platform.deepseek.com
docker compose up -d postgres
```

### Run with the `rag` profile

```bash
# Preferred — activates application-rag.yml (RAG on, hybrid retrieval, eval harness)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag

# Verify:
curl -s http://localhost:8080/actuator/health | jq .
```

First-boot logs:

```
RAG: found 47 markdown files at 'classpath:docs/**/*.md'
RAG: indexed 47 docs (183 chunks added, 0 deleted, 0 sources removed, 4120ms)
```

### Call `docs_search` via MCP (Streamable HTTP at `/mcp`)

```bash
# 1. List tools — confirm docs_search is present (only with rag profile)
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[] | {name, description}'

# 2. Call docs_search
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"How does the hybrid retrieval with RRF work?"}}}' \
  | jq -r '.result.content[0].text'

# Via MCP Inspector
npx @modelcontextprotocol/inspector  # Connect to http://localhost:8080/mcp
# Tools → docs_search → question: "Explain the outbox pattern in this project"
```

### Call via `agentic_ask` (PR #39 unified agent)

```bash
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"question":"What docs explain the payment gateway circuit breaker?"}}}' \
  | jq -r '.result.content[0].text'
# Agent internally calls docs_search + product_search/order_status as needed
```

### Toggle RAG off

```bash
./mvnw spring-boot:run  # no profile → app.rag.enabled=false → RagConfig not loaded, docs_search absent
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
```

---

## 6. Key decisions & tradeoffs — why Spring AI 1.0.0, why nomic-embed-text + DeepSeek, why pgvector vs in-memory

### Why Spring AI 1.0.0 (`pom.xml:75` `spring-ai-bom:1.0.0`)

Spring-native abstraction — `VectorStore`, `EmbeddingModel`, `ChatModel` are interfaces, so swapping pgvector ↔ in-memory is a `RagConfig.java:62` bean change. 1.0.0 is first GA with stable `PgVectorStore` builder and `TokenTextSplitter` (`DocumentIngestionService.java:144`).

### Why `nomic-embed-text` (Ollama) + DeepSeek (`application-rag.yml:12`, `application.yml:119`)

- **Embeddings: `nomic-embed-text` via Ollama** (`RagProperties.java:51` 768 dims) — free, local. DeepSeek has no embedding API, so a second provider is required.
- **Chat: DeepSeek `deepseek-v4-flash`** (`application.yml:124`, temp 0.3) — cheap, good instruction-following for `RagService.java:34` prompt.
- **Split** (`application-rag.yml:16`) — without `spring.ai.model.*`, both auto-configs register a `ChatModel` and boot fails (PR #44 fix).

### Why pgvector vs in-memory (`RagConfig.java:61-72`)

|  | In-memory `SimpleVectorStore` (PR #38) | pgvector `PgVectorStore` (PR #42) |
|---|---|---|
| Persistence | Lost on restart — re-embed 47 docs every boot (~4s) | Persistent `vector_store` HNSW — survives restarts, shared across replicas |
| Incremental | Must re-embed everything | `VectorIndexStore` diffs `source` + `content-hash` to skip unchanged files (`DocumentIngestionService.java:150`) |
| Scale | Heap-bound, no `tsvector` | Postgres-native — same DB as orders; enables lexical side (`LexicalRetrievalEngine.java:22`) |

Ship in-memory in PR #38 to prove the loop with minimal infra, then swap the `VectorStore` bean in PR #42 with zero changes to `RagService` (it depends on `RetrievalEngine` at `RagService.java:44`, not the concrete store).

---

## 7. How to verify — curl / mcp + psql vector_store checks + actuator

### 1. Startup log

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag 2>&1 | grep RAG
# RAG: found 47 markdown files at 'classpath:docs/**/*.md'
# RAG: indexed 47 docs (183 chunks added, 0 deleted, 0 sources removed, 4120ms)
# If "No documents found" (DocumentIngestionService.java:99) → check app.rag.docs-location
```

### 2. MCP `docs_search` returns grounded answer

```bash
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"What is the order state machine?"}}}' | jq .
# .result.content[0].text contains chunk text + [Source: ...] citations, .isError false

# Blank question is rejected (DocsSearchTool.java:59)
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":""}}}' | jq .
# error: "question must not be blank"
```

### 3. psql — `vector_store` populated (pgvector path)

```bash
docker compose exec postgres psql -U order -d orderdb -c "SELECT count(*) FROM vector_store;"
# ~180-200 rows (47 docs × ~4 chunks avg)
docker compose exec postgres psql -U order -d orderdb -c "SELECT metadata->>'source' as source, count(*) FROM vector_store GROUP BY source ORDER BY count DESC LIMIT 10;"
docker compose exec postgres psql -U order -d orderdb -c "\d vector_store"
# vector column vector(768), metadata jsonb, HNSW index on embedding
docker compose exec postgres psql -U order -d orderdb -c "SELECT metadata->>'content-hash', left(content, 80) FROM vector_store LIMIT 3;"
# If empty → check RagConfig.java:69 initializeSchema and curl http://localhost:11434/api/tags
```

### 4. Actuator + eval harness

```bash
curl -s http://localhost:8080/actuator/health | jq .
curl -s http://localhost:8080/actuator/metrics | jq '.names | map(select(contains("rag") or contains("ai")))'
# AiMetrics records rag_query latency/counters after a docs_search call (RagService.java:104)
./mvnw verify -Dspring.profiles.active=rag  # RagRetrievalEvaluator vs golden-questions.json
cat target/rag-eval-report.json | jq .
# { timestamp, retrievalMode: "hybrid", perQuestion: [{question, expected, hit}], hitRate }
```

### 5. Re-index idempotency (PR #42)

```bash
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"reindex_docs","arguments":{"confirmed": true}}}' | jq -r '.result.content[0].text'
# Docs index refreshed: 0 file(s) re-embedded (0 chunks added ...), 47 unchanged file(s) skipped ...
# Second immediate call also 0/0 — proves idempotency
```

---

## 8. How this helps you on the job — 3 bullets: build / operate / interview

- **Build — add grounded Q&A over any private corpus.** You know the full loop: `docs/**/*.md` → `TokenTextSplitter` (`DocumentIngestionService.java:144`) → `EmbeddingModel.embed` (Ollama) → `VectorStore.add` → `RetrievalEngine.retrieve` → `SYSTEM_PROMPT` with `[Source]` citations → `ChatModel.call`. Copy `RagProperties`/`RagConfig`/`RagService`/`DocsSearchTool`, point `docsLocation` at the new corpus, and you have `legal_search` or `runbook_search`. The `RetrievalEngine` seam lets you start dense-only and add hybrid later without touching `RagService`.

- **Operate — run RAG cheaply and explain its cost model.** Embeddings are local (Ollama, free); only answer generation hits the paid API (DeepSeek). Startup indexing is one-time; pgvector persistence (`RagConfig.java:64` HNSW/COSINE) means restarts are free, and `DocumentIngestionService.reindex()` is incremental via `content-hash`. Profile gating (`application-rag.yml:25`) and `@ConditionalOnProperty`/`@ConditionalOnBean` mean non-RAG deployments pay nothing.

- **Interview — whiteboard RAG in 90 seconds with receipts.** "PR #38 ingests `docs/**/*.md` on `ApplicationReadyEvent` (`DocumentIngestionService.java:88`), chunks with `TokenTextSplitter(800, 200)` (`DocumentIngestionService.java:144`), embeds via `nomic-embed-text` (768-dim), stores in `PgVectorStore` HNSW (`RagConfig.java:64`), retrieves topK=5 via `RetrievalEngine` (`RagService.java:76`), and generates with a `SYSTEM_PROMPT` forcing source citation (`RagService.java:34`). MCP tool is `docs_search` (`DocsSearchTool.java:36`)." Then flip to key decisions: pgvector over in-memory, why separate chat/embedding models.

---

## 9. Interview lens — 2-3 Q&A you can now answer

**Q1: "Walk me through a RAG system you'd ship — how do you prevent hallucination?"**

> "PR #38: ingestion on `ApplicationReadyEvent` (`DocumentIngestionService.java:88`) chunks `docs/**/*.md` with `TokenTextSplitter(800, 200)` (`DocumentIngestionService.java:144`), embeds via Ollama `nomic-embed-text` (768 dims, `RagProperties.java:51`), stores in `PgVectorStore` HNSW+cosine (`RagConfig.java:64-69`), and at query time `RagService.answer()` (`RagService.java:71`) retrieves topK=5 via `RetrievalEngine.retrieve()` (`RetrievalEngine.java:24`) and builds a `SYSTEM_PROMPT` saying `Answer using ONLY the provided context. If insufficient, say so. Cite source filename.` (`RagService.java:34-42`). The LLM only sees retrieved chunks tagged `[Source: filename]` (`RagService.java:85`). MCP surface is `DocsSearchTool` (`DocsSearchTool.java:36`, read-only). If `relevantDocs.isEmpty()` we return 'no relevant documentation' (`RagService.java:78`) instead of generating."

**Q2: "Why not use an in-memory vector store? When does pgvector win?"**

> "PR #38 started `SimpleVectorStore` to prove the loop with zero infra. PR #42 swaps to `PgVectorStore` (`RagConfig.java:61`) with no change to `RagService` because it depends on `RetrievalEngine` (`RagService.java:44`). pgvector wins on: (1) persistence — HNSW in `vector_store` survives restarts, (2) incremental re-index — `DocumentIngestionService.reindex()` (`DocumentIngestionService.java:120`) diffs `source`+`content-hash` and skips unchanged files (`DocumentIngestionService.java:150`), so cost is O(changed files) not O(corpus), (3) lexical side for hybrid — `LexicalRetrievalEngine` (`LexicalRetrievalEngine.java:22`) needs Postgres `tsvector`/GIN. Tradeoff is `CREATE EXTENSION vector` handled idempotently by `initializeSchema(true)` (`RagConfig.java:69`). For 50 docs in-memory is fine; beyond that or with replicas, pgvector is the choice."

**Q3: "How do you test and gate retrieval quality?"**

> "Unit: `@MockBean VectorStore`/`EmbeddingModel` for `DenseRetrievalEngine`. Integration: Testcontainers Postgres + real pgvector for `DocumentIngestionService` round-trips. Then PR #44 adds the eval harness: `RagProperties.RagEvalProperties` (`RagProperties.java:123`) holds `goldensLocation` (`golden-questions.json` with `{question, expectedSource}`), `RagRetrievalEvaluator` scores hit-rate@k, and `RagEvalRunner` fails startup if `hitRate < minHitRate` (`RagProperties.java:126`). `application-rag.yml:57` sets `min-hit-rate: 0.7` — earned from measured 75% hit-rate@5 on the real corpus (hybrid RRF). Every run writes `target/rag-eval-report.json` with per-question HIT/MISS (`RagProperties.java:135`). A bad chunking change breaks the build, not prod."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #42 picks up

**What PR #38 alone does NOT do (by design):**

- **Not persistent.** PR #38 `SimpleVectorStore` is in-memory — restart and every chunk is re-embedded. No `vector_store` table, no HNSW, no `VectorIndexStore`. If Ollama is down at boot, indexing fails and `docs_search` returns "No relevant documentation" (`RagService.java:78`) until restart.
- **Not incremental.** No `content-hash` diff, no `reindex_docs` tool. Editing docs is invisible until restart. PR #42 fixes with deterministic ids (`DocumentIngestionService.java:220`), `sha256Hex` (`DocumentIngestionService.java:229`), and guarded `reindex_docs` (`ReindexDocsTool.java:49`, requires `confirmed=true` + `app.mcp.write-tool.enabled`).
- **Dense-only retrieval.** Pure `similaritySearch` (`DenseRetrievalEngine.java:26`) — great at paraphrases, terrible at exact keywords/tool names (`docs_search`, `reindex_docs`). PR #43 adds `HybridRetrievalEngine.java:30` fusing dense + `LexicalRetrievalEngine` via RRF (`HybridRetrievalEngine.java:72`) + optional MMR (`HybridRetrievalEngine.java:85`), switched by `retrieval-mode` (`RagConfig.java:54`).
- **No quality gate.** No `golden-questions.json`, no hit-rate. PR #44 earns `min-hit-rate: 0.7` in `application-rag.yml:57` from measured 75% hit-rate@5 and writes `target/rag-eval-report.json` (`RagProperties.java:135`).
- **No streaming, no memory.** Single `chatModel.call()` (`RagService.java:95`) — no SSE, no conversation. PR #45 adds `RagStreamingService` + virtual-thread Flux; PR #40 adds `MessageChatMemoryAdvisor`.
- **No query rewriting/reranking.** Raw question embedded as-is. PR #51 adds `RagQueryRewriter` + `SemanticReranker`.
- **No MCP resources/prompts.** Only `docs_search` tool, not `resources/list`/`read`. PR #46 exposes corpus as MCP resources and adds `summarize_order`/`ask_docs` prompts.

**Where PR #42 picks up:**

PR #42 is "productionize RAG" — swap `SimpleVectorStore`→`PgVectorStore` (`RagConfig.java:62`), introduce `PgVectorIndexStore`/`VectorIndexStore`, make `reindex()` incremental via ids + `content-hash` (`DocumentIngestionService.java:120`), and expose `reindex_docs` as guarded `AbstractMcpWriteTool` (`ReindexDocsTool.java:34`, same rails as `cancel_order` from PR #41). After #42, answers survive restarts and docs edits are hot-reloadable without a deploy.

> Next: [`02-agentic-tool-calling.md`](./02-agentic-tool-calling.md) (PR #39) or — for the RAG productionization path — [`05-rag-productionization.md`](./05-rag-productionization.md) (PR #42).

