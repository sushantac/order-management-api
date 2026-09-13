# 05. RAG Productionization — pgvector, incremental re-index, eval harness (PR #42)

> PR: [#42 — RAG productionization: pgvector persistence + incremental re-index + retrieval eval harness](https://github.com/anomalyco/order-management-api/pull/42) · Profile: `rag` · Stack: Spring AI 1.0.0 `PgVectorStore`, PostgreSQL `pgvector` HNSW, `VectorIndexStore` · Depends on [#38 RAG](./01-rag-and-docs-search.md) + [#41 guarded write tool](./04-guarded-write-tool.md)

---

## 1. Purpose — what shipped

PR #42 makes the RAG slice from PR #38 **production-grade**. Three changes land together because each is half-useful alone:

- **pgvector persistence.** `VectorStore` swaps from `SimpleVectorStore` (heap, lost on restart) to `PgVectorStore` on the app's own PostgreSQL. Vectors live in `vector_store` (HNSW + COSINE, 768 dims for `nomic-embed-text`) and survive restarts and replicas.
- **Incremental, content-addressed re-index.** `DocumentIngestionService.reindex()` no longer re-embeds the whole corpus. Every chunk carries a deterministic id (`UUID.nameUUIDFromBytes(source:chunkIndex)`) and `content-hash` (sha-256 of its source file). The indexer diffs disk vs index and only re-embeds changed/new files, deletes stale chunks, and skips unchanged files. Triggered at startup and on-demand via guarded write tool `reindex_docs`.
- **Retrieval eval harness.** A deterministic, model-free scorer (`RagRetrievalEvaluator` + `RagEvalRunner`) measures whether the *retriever* returns the right docs for a golden set (`golden-questions.json`). Every run writes `target/rag-eval-report.json` and can gate startup on `app.rag.eval.min-hit-rate`.

One line: *vectors persist, re-index is O(changed files) not O(corpus), and retrieval quality is a number you can gate on.*

---

## 2. Problem — what was broken without it

PR #38 proved the loop but left three production gaps:

| Gap | Before (PR #38) | Why it hurts in prod |
|---|---|---|
| **In-memory vs persistent** | `SimpleVectorStore` — heap `Map`. Restart → empty index → re-embed ~47 docs / ~180 chunks every boot (~4s + Ollama load). No replica sharing; no `tsvector`. | Every deploy and scale-out pays full embedding cost. Rolling restart briefly serves empty RAG. |
| **Full re-index vs incremental** | No `VectorIndexStore`, no `content-hash`, no `source` diff. `ingestDocuments()` blindly chunked everything. Editing one `docs/runbook.md` was invisible until restart, then re-embedded the whole corpus. No `reindex_docs` tool. | Common doc fixes cost the same as cold ingest. No hot-reload, no idempotency, no audit. |
| **No eval — quality is vibes** | No `GoldenQuestion`, no `RagRetrievalEvaluator`, no `RagEvalRunner`, no `golden-questions.json`. Whether `topK=5` or `chunkSize=800` was good was unmeasured. | A chunk-size tweak could halve hit-rate silently. No gate, no trail. |

Without PR #42, PR #43 (hybrid RRF+MMR) has nothing persistent to fuse against, and PR #44 (eval gate `min-hit-rate: 0.7`) has no harness.

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 PgVectorIndexStore + VectorStore swap (persistence)

```
Before (PR #38)                    After (PR #42)
┌───────────────────┐              ┌──────────────────────────┐
│ SimpleVectorStore │              │ PgVectorStore            │
│  heap Map         │─swap bean─▶ │  vector_store table      │
│  lost on restart  │ RagConfig:61 │  id UUID text            │
└────────┬──────────┘              │  metadata JSONB ─┬ source│
         │ add/search              │  embedding vector(768)   │
         ▼                         │  HNSW index (cosine)     │
    RagService                     └────────────┬─────────────┘
                                               │ same VectorStore iface
                                   ┌───────────▼──────────────┐
                                   │ PgVectorIndexStore:18    │
                                   │  add/delete →VectorStore│
                                   │  chunksBySource / sources│
                                   │  via JdbcTemplate        │
                                   │  metadata->>'source'    │
                                   │  metadata->>'content-hash'│
                                   └───────────┬──────────────┘
                                               │
                                   ┌───────────▼──────────────┐
                                   │ DocumentIngestionService │
                                   │  reindex() diff loop     │
                                   └──────────────────────────┘
```

`RagConfig.vectorStore()` at `RagConfig.java:61` builds `PgVectorStore` with `initializeSchema(true)` (idempotent `CREATE EXTENSION vector` + table + HNSW). `RagConfig.vectorIndexStore()` at `RagConfig.java:74` wraps the same `VectorStore` + `JdbcTemplate` in `PgVectorIndexStore` — the read-side lens `VectorStore` alone does not expose (`VectorIndexStore.java:17`).

### 3.2 Content-addressed ids + sha-256 (incremental)

```
docs/**/*.md ──loadFiles()──► SourceFile(name, content, sha256Hex(content))
                                 DocumentIngestionService.java:184,229
                                 name = relative path e.g. business/04-payments.md
                                 hash = SHA-256 hex of file content
        │
   for each file:
     existing = indexStore.chunksBySource(name)   // PgVectorIndexStore.java:50
     unchanged = existing.allMatch(hash==c.hash)  // DocumentIngestionService.java:151
        ├── unchanged → skip (0 embedding calls)
        ├── changed/new → deleteChunks(ids) → chunk() → addChunks()
        │                 :158-164  id=UUID.nameUUIDFromBytes(name+":"+i) //:220
        │                         metadata: source + content-hash     //:211
        └── removed source → deleteChunks(stale)  // :167-171
   ReindexReport(filesReindexed, chunksAdded, chunksDeleted, filesUnchanged, sourcesRemoved) // :64
```

Idempotent: call `reindex()` twice with no edits → `filesUnchanged==N`, `filesReindexed==0`, `chunksAdded==0`.

### 3.3 Eval harness (retrieval quality as a number)

```
golden-questions.json ──► RagEvalRunner.run() ──► RagRetrievalEvaluator.evaluate()
  [{question,               RagEvalRunner.java:99    RagRetrievalEvaluator.java:66
    expectedSource}]             │                           │
                                 │ retriever.retrieve(q) per golden
                                 │ expected ∈ topK ?
                                 ▼
                          RetrievalEvalReport       RagEvalReportSnapshot
                           hitRate@k, top1,        target/rag-eval-report.json
                           precision@k, items       RagEvalRunner.java:135
                           [HIT/MISS]              gated by min-hit-rate :122
```


---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/rag/VectorIndexStore.java:17` | Seam the indexer needs — `add/delete` + `chunksBySource`/`sources` over `StoredChunk(id, contentHash)` |
| `src/main/java/com/company/orderapi/rag/PgVectorIndexStore.java:18` | Prod `VectorIndexStore` — delegates `add/delete` to `VectorStore`, reads via `JdbcTemplate` on `metadata->>'source'` |
| `src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:51` | Startup + incremental ingestion — `loadFiles()`/`sha256Hex()`/`chunk()`/`reindex()`/`ReindexReport`; `@ConditionalOnProperty(app.rag.enabled=true)` |
| `src/main/java/com/company/orderapi/rag/RagConfig.java:61` | Wires `PgVectorStore` (HNSW+COSINE, `initializeSchema(true)`) and `PgVectorIndexStore`; gated `RagConfig.java:35` |
| `src/main/java/com/company/orderapi/rag/RagProperties.java:25` | `@ConfigurationProperties(prefix="app.rag")` record — vector-table, dims, retrieval, eval; multi-ctor `@ConstructorBinding` (`:45`) |
| `src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:31` | Scorer — `evaluate(Retriever, goldens, k)` → `RetrievalEvalReport(hitRate@k, top1Accuracy, precision@k)` |
| `src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:53` | Gate — `ApplicationListener<ApplicationReadyEvent>` at `LOWEST_PRECEDENCE`, logs HIT/MISS, writes JSON, throws if `hitRate < minHitRate` |
| `src/main/java/com/company/orderapi/mcp/ReindexDocsTool.java:37` | Guarded `reindex_docs` — `extends AbstractMcpWriteTool`, `@ConditionalOnProperty(app.mcp.write-tool.enabled)` + `@ConditionalOnBean(DocumentIngestionService.class)`, requires `confirmed=true` |
| `src/main/resources/application-rag.yml:32` | Profile enabling pgvector (`vector_store`, `768`) and eval (`eval.enabled/min-hit-rate/report-location`) |

### Snippet 1 — `VectorIndexStore` seam (`VectorIndexStore.java:17-34`)

```java
// src/main/java/com/company/orderapi/rag/VectorIndexStore.java:17
public interface VectorIndexStore {
    record StoredChunk(String id, String contentHash) {} // :20
    void addChunks(List<Document> chunks);               // :24
    void deleteChunks(List<String> ids);                 // :27
    List<StoredChunk> chunksBySource(String source);     // :30
    List<String> sources();                              // :33
}
```

Needed because `VectorStore` hides "what is already stored per source/hash."

### Snippet 2 — `PgVectorIndexStore` delegates writes, reads via SQL (`PgVectorIndexStore.java:26-63`)

```java
// src/main/java/com/company/orderapi/rag/PgVectorIndexStore.java:37
public void addChunks(List<Document> chunks) { vectorStore.add(chunks); }
// src/main/java/com/company/orderapi/rag/PgVectorIndexStore.java:50
public List<StoredChunk> chunksBySource(String source) {
    return jdbcTemplate.query(
        "SELECT id::text, metadata->>'content-hash' AS hash FROM " + fullyQualified()
        + " WHERE metadata->>'source' = ? ORDER BY id",
        (rs, n) -> new StoredChunk(rs.getString(1), rs.getString(2)), source);
}
// src/main/java/com/company/orderapi/rag/PgVectorIndexStore.java:59
public List<String> sources() {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT metadata->>'source' FROM " + fullyQualified(), String.class);
}
```

`metadata->>'key'` works on `json` and `jsonb`. Same table as `VectorStore` so reads see exactly what `add/delete` wrote.

### Snippet 3 — Incremental re-index (`DocumentIngestionService.java:125-174`)

```java
// src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:125
ReindexReport reindex(List<SourceFile> files) {
    for (String src : indexStore.sources()) if (!loadedNames.contains(src)) removedSources.add(src);
    for (SourceFile file : files) {
        List<StoredChunk> existing = indexStore.chunksBySource(file.name());
        boolean unchanged = !existing.isEmpty()
            && existing.stream().allMatch(c -> file.contentHash().equals(c.contentHash())); // :151
        if (unchanged) { filesUnchanged++; continue; } // :154 skip — no re-embed cost
        indexStore.deleteChunks(existing.stream().map(StoredChunk::id).toList()); // :158
        List<Document> chunks = chunk(file, splitter); // :161
        indexStore.addChunks(chunks); chunksAdded += chunks.size(); filesReindexed++;
    }
    for (String removed : removedSources) { /* delete stale */ } // :167
}
```

`chunk()` at `:210` uses deterministic `UUID.nameUUIDFromBytes((name+":"+i))` (`:220`) and stamps `source` + `content-hash` (`:211-223`). `sha256Hex()` at `:229` is the content address.

### Snippet 4 — pgvector wiring (`RagConfig.java:61-77`)

```java
// src/main/java/com/company/orderapi/rag/RagConfig.java:61
@Bean public VectorStore vectorStore(EmbeddingModel em, DataSource ds, RagProperties p) {
    return PgVectorStore.builder(new JdbcTemplate(ds), em)
        .vectorTableName(p.vectorTable())                // "vector_store" :35
        .dimensions(p.embeddingDimensions())              // 768 :51
        .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
        .indexType(PgVectorStore.PgIndexType.HNSW)
        .initializeSchema(true)                          // :69 idempotent DDL
        .build();
}
@Bean public VectorIndexStore vectorIndexStore(JdbcTemplate j, VectorStore vs, RagProperties p) {
    return new PgVectorIndexStore(j, vs, p.vectorTable()); // :74
}
```

Uses `spring-ai-pgvector-store` library (not starter) so bean only exists when `app.rag.enabled=true` (`:35`).

### Snippet 5 — Eval scoring + gate (`RagRetrievalEvaluator.java:66`, `RagEvalRunner.java:53`)

```java
// src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:66
public RetrievalEvalReport evaluate(Retriever r, List<GoldenQuestion> goldens, int k) {
    List<RetrievalEvalItem> items = goldens.stream().map(g -> evaluateOne(r, g, k)).toList();
    long hits = items.stream().filter(RetrievalEvalItem::hit).count(); // :71
    // hitRate=hits/total, top1 where expected==first, precision=hits/(total*k) :72-87
}
// src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:90
private RetrievalEvalItem evaluateOne(Retriever r, GoldenQuestion g, int k) {
    List<Document> docs = r.retrieve(g.question());
    List<String> sources = docs.stream().map(d -> String.valueOf(d.getMetadata().get("source"))).toList();
    return new RetrievalEvalItem(g.question(), g.expectedSource(), sources, topScore,
        sources.subList(0, Math.min(k, sources.size())).contains(g.expectedSource())); // :102
}
// src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:53
@Order(Ordered.LOWEST_PRECEDENCE) // after DocumentIngestionService HIGHEST_PRECEDENCE :89
public class RagEvalRunner implements ApplicationListener<ApplicationReadyEvent> {
    public void run() { // :99
        RetrievalEvalReport report = evaluator.evaluate(ragService::retrieve, goldens, k); // :103
        log.info("RAG retrieval eval: goldens=... hitRate@k=..."); // :107
        writeReport(report); // :120 → target/rag-eval-report.json
        if (minHitRate > 0 && report.hitRateAtK() < minHitRate) throw new IllegalStateException("gate FAILED"); // :122
    }
}
```

`ApplicationListener` (not `ApplicationRunner`) is load-bearing — runners fire before ready-event listeners and would score an empty store (PR #44 bug).

---

## 5. How to use — enable pgvector, reindex_docs tool, eval harness run

### Prerequisites

```bash
ollama pull nomic-embed-text          # 768-dim (~270 MB)
docker compose up -d postgres
```

### Enable pgvector (profile)

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# Verify:
# RAG: found 47 markdown files at 'classpath:docs/**/*.md'  DocumentIngestionService.java:186
# RAG: indexed 47 docs (183 chunks added, 0 deleted, 0 sources removed, 4120ms) :105
```


### `reindex_docs` tool (incremental, guarded write)

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.mcp.write-tool.enabled=true

curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[] | {name, description}'
# reindex_docs — "MUTATES DATA: refresh the documentation index..." (ReindexDocsTool.java:54)

curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reindex_docs","arguments":{"confirmed": true}}}' | jq .
# {"result":{"content":[{"type":"text","text":"Docs index refreshed: 0 file(s) re-embedded ... 47 unchanged ..."}]}}

echo "\n## Hotfix" >> docs/runbook.md
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"reindex_docs","arguments":{"confirmed": true}}}' | jq -r '.result.content[0].text'
# Docs index refreshed: 1 file(s) re-embedded (4 chunks added, 4 stale deleted), 46 unchanged ...

curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"reindex_docs","arguments":{"confirmed": false}}}' | jq .
# isError true — "confirmed must be exactly true" (ReindexDocsTool.java:75)
```


### Eval harness run

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag 2>&1 | grep "RAG retrieval eval"
# RAG retrieval eval: goldens=12 hitRate@5=75.0% top1Accuracy=50.0% precision@5=15.0%  RagEvalRunner.java:107
#   [HIT]  How does hybrid retrieval work? -> expected docs/additions/07... ; retrieved [...]
cat target/rag-eval-report.json | jq .  # RagEvalRunner.java:135 snapshot
# { runAt, retrievalMode:"HYBRID", topK:5, goldens:12, hits:9, hitRateAtK:0.75, top1Accuracy:0.5, items:[...] }

# Gate — min-hit-rate (RagProperties.java:126, application-rag.yml:57)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0.7
# passes if hitRate@5 >=70%; else IllegalStateException at RagEvalRunner.java:124

# Report-only default (minHitRate 0 — RagProperties.java:56):
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.enabled=false
# No eval run, boot always succeeds
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

### Multi-ctor `@ConstructorBinding` (`RagProperties.java:45-65`)

Record has two ctors: canonical 10-arg and 5-arg convenience for pre-#42 tests (`:61`). With >1 ctor Spring Boot stops assuming automatic binding — without `@ConstructorBinding` on the compact ctor, binding silently fails and knobs stay `null`/`0`. Marker at `:45` plus compact-body defaults (`:47` — `chunkSize=800`, `topK=5`, `dimensions=768`, `vectorTable="vector_store"`, `HYBRID`, report-only eval) lets `app.rag.enabled=true` alone boot a working stack.

### Basename collision fix — `relativeName()` not `getFilename()` (`DocumentIngestionService.java:184-275`)

Corpus has duplicate basenames (`README.md` in `docs/`, `docs/additions/`, `docs/business/`). Early code used `resource.getFilename()` as `source` — ids `UUID(name:idx)` collided and merged chunks from different files. Fix: `relativeName(resource, patternDir, fallback)` (`:263`) slices `resource.getURL().getPath()` after `/docs/` (root from `directoryRootOf()` at `:248`) to get `business/README.md` vs `additions/README.md`. Without it, `chunksBySource()`/`sources()` and `reindex()` target wrong rows.

### Deterministic ids (`DocumentIngestionService.java:220`)

```java
String id = UUID.nameUUIDFromBytes((file.name() + ":" + i).getBytes(UTF_8)).toString();
```

Random UUIDs would make `deleteChunks(existing ids)` impossible. Deterministic ids make `reindex()` idempotent and let `deleteChunks` target exactly the stale set.

### sha-256 over mtime (`DocumentIngestionService.java:229`)

`sha256Hex(content)` is filesystem-independent and works for `classpath:` resources inside jars where mtime is meaningless. Stored as `metadata->>'content-hash'` (`:213`), compared via `allMatch(hash==c.hash)` (`:152`). One `MessageDigest` per file — negligible vs embedding.

### Gated `reindex_docs` as write tool (`ReindexDocsTool.java:37`)

Mutates persistent `vector_store`, so it rides `AbstractMcpWriteTool` (same rails as `cancel_order` PR #41): opt-in `app.mcp.write-tool.enabled` (`:35`), required `confirmed=true` (`:74`), audit logger `mcp.reindex-docs` (`:39`). Prevents accidental bulk re-embedding and surfaces `ReindexReport` counts without leaking doc content.

---

## 7. How to verify — psql vector_store, eval report json, reindex idempotency

### pgvector table

```bash
docker compose exec postgres psql -U order -d orderdb -c "\d vector_store"
# id UUID, content TEXT, metadata JSONB, embedding vector(768), HNSW cosine — RagConfig.java:68
docker compose exec postgres psql -U order -d orderdb -c "SELECT count(*) FROM vector_store;"
# ~180-200 rows (47 docs × ~4 chunks avg at DocumentIngestionService.java:144)
docker compose exec postgres psql -U order -d orderdb \
  -c "SELECT metadata->>'source', count(*) FROM vector_store GROUP BY source ORDER BY count DESC LIMIT 5;"
# distinct relative paths — no basename collisions
```

### `reindex_docs` idempotency

```bash
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"reindex_docs","arguments":{"confirmed": true}}}' | jq -r '.result.content[0].text'
# Docs index refreshed: 0 file(s) re-embedded (0 chunks added, 0 stale deleted), 47 unchanged ... — :154 path
```

### Eval report JSON

```bash
cat target/rag-eval-report.json | jq .
# { runAt, retrievalMode:"HYBRID", topK:5, goldens:12, hits:9, hitRateAtK:0.75, top1Accuracy:0.5, precisionAtK:0.15, items:[...] }
# RagEvalRunner.java:140 snapshot, per-question [HIT]/[MISS] logged at :112

./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0.9 2>&1 | tail -5
# IllegalStateException: RAG retrieval eval gate FAILED: hitRate@5=75.0% is below the required 90.0%  :124

./mvnw test -Dtest=RagRetrievalEvaluatorTest,RagEvalRunnerTest
# unit — hit/miss/precision math + JSON write + gate
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build — ship RAG that survives restarts.** Swap `SimpleVectorStore`→`PgVectorStore` (`RagConfig.java:61`, HNSW+COSINE, `initializeSchema(true)`) with zero change to `RagService` (`:44` → `RetrievalEngine`). Add incremental re-index: stamp `source`+`content-hash` (`:211`), deterministic ids (`:220`), `VectorIndexStore` lens (`:50`), guarded `AbstractMcpWriteTool` (`ReindexDocsTool.java:37`).

- **Operate — explain cost per change.** pgvector: restarts 0 embeddings; `psql \d vector_store` checks indexing. Incremental: unchanged skip `:154`, changed `delete+add` `:158-164`, removed `:167` — second `reindex_docs` is 0/0. Eval: `target/rag-eval-report.json` (`:135`) + log (`:107`); `min-hit-rate` turns regression into failure (`:124`).

- **Interview — whiteboard in 90s with receipts.** "PR #42: `VectorStore`→`PgVectorStore` 768 HNSW (`:61`), `PgVectorIndexStore` (`:18`) on `metadata->>'source'` (`:50`). `reindex()` (`:125`) via `sha256Hex` (`:229`)+`UUID(name:idx)` (`:220`), skips unchanged (`:151`), `ReindexReport` (`:64`). Guarded `reindex_docs` (`:37`). Quality `RagRetrievalEvaluator` (`:66`) gated by `RagEvalRunner` (`:53`) writing JSON failing if `hitRate<min` (`:122`)."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "Vectors disappear on restart. How do you fix it?"**

> "PR #38's `SimpleVectorStore` is in-memory. PR #42 swaps to `PgVectorStore` in `RagConfig.vectorStore()` (`RagConfig.java:61`) on the same Postgres — `vectorTableName("vector_store")`, `dimensions(768)`, `COSINE+HNSW`, `initializeSchema(true)` for `CREATE EXTENSION vector`. `RagService` untouched (`RagService.java:44` → `RetrievalEngine`). `PgVectorIndexStore` (`:18`) reuses `VectorStore` for writes (`addChunks:38`) and `JdbcTemplate` on `metadata->>'source'`/`'content-hash'` (`:52`) for reads (`VectorIndexStore.java:17`). Verify `psql SELECT count(*) FROM vector_store` + `\d vector_store`."

**Q2: "Re-indexing the whole corpus on every edit is too slow?"**

> "`reindex()` at `DocumentIngestionService.java:125` is content-addressed: id `UUID.nameUUIDFromBytes(source+":"+idx)` (`:220`) and `source` as relative path via `relativeName()` (`:263`, `directoryRootOf()` `:248` fixes `README.md` collision) + `content-hash=sha256Hex(content)` (`:229`). Each pass loads `SourceFile(name,content,hash)` (`:60`), diffs `indexStore.sources()` vs `loadedNames` for removals, per-file `chunksBySource(name)` hash `allMatch` (`:151-152`). Unchanged→skip (`:154`), changed→`delete+chunk+add` (`:158-161`), removed→`delete stale` (`:169`). Idempotent — second call `0 re-embedded`. Guarded `reindex_docs` (`ReindexDocsTool.java:37`, `confirmed=true` `:74`)."

**Q3: "How do you know retrieval is actually good and stop bad changes shipping?"**

> "`RagRetrievalEvaluator.evaluate(retriever,goldens,k)` (`:66`) scores each `GoldenQuestion` via `retriever.retrieve(q)` — `hitRate@k` (expected in top-k), `top1Accuracy`, `precision@k=hits/(total*k)` (`:76`). `RagEvalRunner` (`:53`) runs at `ApplicationReadyEvent`+`LOWEST_PRECEDENCE` (`:52`) after `DocumentIngestionService` `HIGHEST` (`:89`) — not `ApplicationRunner` (the PR #44 bug, would score empty store). Logs `[HIT]/[MISS]` (`:113`), writes `RagEvalReportSnapshot` to `app.rag.eval.report-location` (`target/rag-eval-report.json` `:135,:155`). `min-hit-rate` (`RagProperties.java:126`, default 0 report-only, `application-rag.yml:57` earns `0.7` from measured 75% hitRate@5) → `IllegalStateException` at `:124` fails startup."

---
## 10. Honest limits & next steps — what it doesn't do, where PR #43-44 pick up

**What PR #42 alone does NOT do (by design):**

- **Not hybrid.** Still dense-only `DenseRetrievalEngine` — weak at exact keywords. No lexical side, no RRF, no MMR. PR #43 adds `LexicalRetrievalEngine`+`HybridRetrievalEngine` (RRF `rrfK=60`, optional MMR `mmrLambda=0.5`, `RagConfig.java:54` switch).
- **Not a quality gate yet.** Harness reports but `min-hit-rate` defaults `0` (`RagProperties.java:126`) — report-only until measured. PR #44 earns `application-rag.yml:57` `0.7`, adds `report-location` snapshots, fixes `ApplicationRunner`→`ApplicationListener` ordering.
- **No sub-corpus filtering.** Chunks carry `resource-path` (`DocumentIngestionService.java:57`) but `chunksBySource` filters only `source` (`PgVectorIndexStore.java:52`). Add `WHERE metadata->>'resource-path' LIKE` for slices.
- **No streaming/memory/rewriting.** `RagService.answer()` (`:71`) is single `chatModel.call()` on raw question. PR #40, #45, #51 extend separately.
- **File-granular, not chunk-granular.** Changing one paragraph re-embeds all chunks of that file (`:158-164`). Chunk-level hash would allow single-chunk patching.

**Where PR #43-44 pick up:**

- **PR #43** fuses `PgVectorStore` with `LexicalRetrievalEngine` via `HybridRetrievalEngine` — same seam, `application-rag.yml:40` `retrieval-mode: hybrid`, still measured by PR #42's harness.
- **PR #44** turns log into gate — measures dense vs hybrid (hybrid 75% hitRate@5 vs dense 66%, top-1 50% at `rrfK=60` w/o MMR), earns `min-hit-rate: 0.7`, persists to `target/rag-eval-report.json`.
- **PR #45-46** add `stream_docs_search` and MCP resources/prompts on the same corpus.

> Next: [`02-agentic-tool-calling.md`](./02-agentic-tool-calling.md) (PR #39) · or [`../07-hybrid-retrieval.md`](../07-hybrid-retrieval.md) (PR #43) · or [`06-eval-gate.md`](./06-eval-gate.md) (PR #44).

