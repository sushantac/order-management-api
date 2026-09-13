# 06. Hybrid Retrieval — dense + lexical + RRF + MMR (PR #43)
> PR: [#43 — Hybrid retrieval: dense + lexical (RRF + MMR)](https://github.com/anomalyco/order-management-api/pull/43) · Profile: `rag` · Stack: `RetrievalEngine` seam, `PgVectorStore` (cosine HNSW) + Postgres `tsvector`/`GIN`/`ts_rank`, RRF (`rrf-k=60`) + MMR (`mmr-lambda`) · Depends on [#42 RAG productionization](./05-rag-productionization.md) · Measured in [#44 eval gate](../07-rag-eval-gate-earned.md)
---
## 1. Purpose — what shipped
PR #43 turns dense-only retrieval into **hybrid retrieval** — the quality fix PR #42 made measurable. Four things land together:
- **`RetrievalEngine` seam.** `RagService` no longer calls `VectorStore.similaritySearch` directly. It depends on `RetrievalEngine.retrieve(query, topK)` (`RetrievalEngine.java:17`), and `RagConfig` wires the strategy per `app.rag.retrieval-mode` (`RagConfig.java:50`). Dense is baseline; hybrid is default.
- **`LexicalRetrievalEngine` — Postgres full-text.** Second retriever over the *same* `vector_store` table using `to_tsvector('english', content) @@ plainto_tsquery` + `ts_rank`, backed by lazy `GIN` index (`LexicalRetrievalEngine.java:31`). No new table.
- **`HybridRetrievalEngine` — RRF fusion.** Merges two ranked lists with **Reciprocal Rank Fusion** (`HybridRetrievalEngine.java:30`, `rrfScores()` at `HybridRetrievalEngine.java:72`). Rank-only, so cosine vs `ts_rank` need no calibration.
- **MMR rerank.** Optionally re-ranks fused list with **Maximal Marginal Relevance** (`HybridRetrievalEngine.java:85`) via `app.rag.retrieval.mmr-enabled` / `mmr-lambda` (`RagProperties.java:96`). Ships **off** by default — earned from PR #44 measurement.
One line: *same prompt, same corpus, better retrieval — embeddings catch paraphrase, lexical catches exact terms, RRF fuses without tuning, MMR de-duplicates when it helps.*
---
## 2. Problem — what was broken without it (embeddings miss exact terms)
PR #38 proved the loop, PR #42 made it persistent and measurable. The eval harness (`RagRetrievalEvaluator.java:31`) then exposed dense-only weakness:
| Signal | Good at | Misses |
|---|---|---|
| **Dense** (`DenseRetrievalEngine.java:17` → `VectorStore.similaritySearch` at `DenseRetrievalEngine.java:26`) | Paraphrase — "how do I cancel an order" finds `cancel_order` | Exact vocabulary — `reindex_docs`, `cancel_order`, `vector_store`, `outbox`, `tsvector` |
| **Lexical** (before #43: absent) | Exact term `tsvector` match — `reindex_docs` only hits chunks with that token | Synonyms — "undo purchase" misses `cancel_order` |
A chunk can be "about" the topic yet rank below an unrelated neighbour. Goldens in `golden-questions.json` hit this.
Before #43 no rescue without re-tuning embeddings. After #43, the miss is rescued by the other signal via RRF.
---
## 3. Solution — architecture with ASCII diagrams
### 3.1 The seam — retrieval is a wiring decision

```
Before (PR #38-42)                  After (PR #43)
┌──────────────┐                    ┌──────────────────────────┐
│  RagService  │                    │        RagService        │
└──────┬───────┘                    └────────────┬─────────────┘
       │ VectorStore.similaritySearch            │ RetrievalEngine.retrieve(q, topK)
       ▼                                         │  RetrievalEngine.java:17
┌──────────────┐                    ┌────────────▼─────────────┐
│ PgVectorStore│                    │ RagConfig.retrievalEngine │ RagConfig.java:50
│  (dense)     │                    │  DENSE->dense  HYBRID->hybrid│ RagProperties.java:33
└──────────────┘                    └──────┬────────┬──────────┘
                                           │        │
                              ┌────────────▼─┐ ┌────▼───────────────┐
                              │DenseRetrieval│ │HybridRetrievalEngine│ :30
                              │Engine :17    │ │ dense+lexical RRF:72│
                              └──────────────┘ │ MMR rerank :85      │
                                               └────┬────────┬──────┘
                                                    │        │
                                            ┌───────▼─┐ ┌────▼──────────┐
                                            │PgVector │ │LexicalRetrieval│ :31
                                            │ cosine  │ │ tsvector/GIN  │
                                            └─────────┘ │ ts_rank :52   │
                                                        └───────────────┘
```

`RagService.java:44` depends on `RetrievalEngine`, not `VectorStore`. `RagConfig.java:39`/`44` build both engines; `:50` selects per `retrievalMode`. Eval harness (`RagRetrievalEvaluator.java:34`) stays `String->List<Document>` — zero changes.

### 3.2 Lexical path — Postgres full-text over the same table

```
vector_store (PgVectorStore + Lexical share it)
┌──────────────────────────────────────────────────────────────┐
│ id UUID | content TEXT | metadata JSONB | embedding vector(768) │
│  HNSW on embedding (cosine)  RagConfig.java:68               │
│  GIN on to_tsvector('english', content)  LexicalRetrievalEngine.java:75 │
│  CREATE INDEX IF NOT EXISTS idx_public_vector_store_content_fts          │
└──────────────────────────────────────────────────────────────┘
          ▲                        ▲
          │ similaritySearch :26   │ SELECT ... WHERE to_tsvector @@ plainto_tsquery
          │ DenseRetrieval         │ ORDER BY ts_rank DESC  :51  ensureFtsIndex :69
```

- `@@` = full-text match, `ts_rank` = BM25-style score, `plainto_tsquery('english',?)` handles stemming.
- GIN created **lazily** on first `retrieve()` behind `AtomicBoolean` (`:40`, `:70`); retry on `DataAccessException` (`:80`).
- Same `metadata` JSON via `jsonToMetadata()` (`:85`) so `source` intact for citations/eval.

### 3.3 Fusion — RRF (rank-only ensemble)

```
Dense top-k (cosine)    Lexical top-k (ts_rank)      RRF fused (k=60)            MMR rerank
[A,B,C,D]               [B,D,A,C]                   [B,A,D,C]                    lambda*sim(q,doc)-(1-lambda)*maxSim(doc,picked)
   └────────┬───────────┘                             │ SUM 1/(k+rank) :72        │ :85 cosine :118 cache :87
            │ rrfScores(dense,lex,60):72              │ byId dedup :56            │ lambda :90 clamped [0,1] :101
            └─────────────▶ ordered :61 ─────────────▶│ CANDIDATE_MULTIPLIER 4    │ mmrEnabled gate :66
                                                      │ MIN_CANDIDATES 20 :33     │
```

Each engine contributes `max(topK*4, 20)` candidates (`:52`, constants `:33`) so MMR/RRF have headroom; exactly `topK` would make reranking degenerate.

### 3.4 RRF arithmetic (from `HybridRetrievalEngineTest.java:36`)

Dense `[A,B,C,D]`, lexical `[B,D,A,C]`, `rrfK=60`:

| doc | dense 1/(60+r) | lexical 1/(60+r) | RRF total | order |
|---|---|---|---|---|
| B | 1/62 | 1/61 | **0.0325225** | 1 |
| A | 1/61 | 1/63 | 0.0322665 | 2 |
| D | 1/64 | 1/62 | 0.0317540 | 3 |
| C | 1/63 | 1/64 | 0.0314980 | 4 |

B wins — top-2 in *both* lists (rescue effect). No score calibration.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/rag/RetrievalEngine.java:17` | Seam — `List<Document> retrieve(String query, int topK)` at `:24`; `RagService` depends only on this |
| `src/main/java/com/company/orderapi/rag/DenseRetrievalEngine.java:17` | Dense-only: `vectorStore.similaritySearch(SearchRequest...topK)` at `:26` — pre-#43 behaviour unchanged |
| `src/main/java/com/company/orderapi/rag/LexicalRetrievalEngine.java:31` | Lexical: `JdbcTemplate` over `vector_store` with `to_tsvector @@ plainto_tsquery` + `ts_rank` at `:51`, `GIN` at `:75`, lazy `ensureFtsIndex()` at `:69` |
| `src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java:30` | Hybrid: fans out `:53`, fuses `rrfScores()` at `:72`/`accumulate()` at `:79`, optionally `mmrRerank()` at `:85` with `cosine()` at `:118`; `CANDIDATE_MULTIPLIER=4`/`MIN_CANDIDATES=20` at `:33` |
| `src/main/java/com/company/orderapi/rag/RagConfig.java:39` | Wiring: `denseRetrievalEngine()` `:39`, `lexicalRetrievalEngine()` `:44`, `retrievalEngine()` switch at `:50` on `retrievalMode` |
| `src/main/java/com/company/orderapi/rag/RagProperties.java:25` | Config: `@ConfigurationProperties(prefix="app.rag")`; `RetrievalMode {DENSE,HYBRID}` at `:71`, `RetrievalSettings(mmrEnabled, mmrLambda, rrfK)` at `:96` clamped at `:101` |
| `src/main/resources/application-rag.yml:40` | Profile: `retrieval-mode: hybrid`, commented `retrieval: {mmr-enabled:false, mmr-lambda:0.5, rrf-k:60}` |

### Snippet 1 — `RetrievalEngine` seam (`RetrievalEngine.java:17-24`)

```java
// src/main/java/com/company/orderapi/rag/RetrievalEngine.java:17
public interface RetrievalEngine {
    List<Document> retrieve(String query, int topK); // :24
}
```

### Snippet 2 — `DenseRetrievalEngine` preserved baseline (`DenseRetrievalEngine.java:17-29`)

```java
// src/main/java/com/company/orderapi/rag/DenseRetrievalEngine.java:17
public class DenseRetrievalEngine implements RetrievalEngine {
    private final VectorStore vectorStore; // :19
    public DenseRetrievalEngine(VectorStore vectorStore) { this.vectorStore = vectorStore; } // :21
    @Override public List<Document> retrieve(String query, int topK) { // :26
        return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build());
    }
}
```

### Snippet 3 — `LexicalRetrievalEngine` (`LexicalRetrievalEngine.java:48-83`)

```java
// src/main/java/com/company/orderapi/rag/LexicalRetrievalEngine.java:48
public List<Document> retrieve(String query, int topK) {
    ensureFtsIndex(); // :49  AtomicBoolean :40, CREATE INDEX IF NOT EXISTS :75
    String tsQuery = "plainto_tsquery('english', ?)"; // :50
    String sql = """
            SELECT id::text AS id, content, metadata,
                   ts_rank(to_tsvector('english', content), %1$s) AS rank
            FROM %2$s WHERE to_tsvector('english', content) @@ %1$s
            ORDER BY rank DESC LIMIT ?""".formatted(tsQuery, table); // :51
    return jdbcTemplate.query(sql, (rs, n) -> Document.builder()
            .id(rs.getString("id")).text(rs.getString("content"))
            .metadata(jsonToMetadata(rs.getString("metadata"))) // :85
            .score(rs.getDouble("rank")).build(), query, query, topK); // :59
}
// src/main/java/com/company/orderapi/rag/LexicalRetrievalEngine.java:69
private void ensureFtsIndex() {
    if (!ftsIndexReady.compareAndSet(false, true)) return; // :70
    String indexName = "idx_" + table.replace('.', '_') + "_content_fts"; // :73
    try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName
            + " ON " + table + " USING GIN (to_tsvector('english', content))"); } // :75
    catch (DataAccessException e) { ftsIndexReady.set(false); // :80 retry
        log.warn("RAG: full-text index not created (will retry): {}", e.getMessage()); }
}
```

### Snippet 4 — `HybridRetrievalEngine` RRF+MMR (`HybridRetrievalEngine.java:50-129`)

```java
// src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java:50
public List<Document> retrieve(String query, int topK) {
    int candidates = Math.max(topK * CANDIDATE_MULTIPLIER, MIN_CANDIDATES); // :52 4x min20
    List<Document> denseDocs = dense.retrieve(query, candidates); // :53
    List<Document> lexDocs = lexical.retrieve(query, candidates); // :54
    Map<String, Document> byId = new LinkedHashMap<>(); // :56 dedup by deterministic id
    denseDocs.forEach(doc -> byId.putIfAbsent(doc.getId(), doc));
    lexDocs.forEach(doc -> byId.putIfAbsent(doc.getId(), doc));
    Map<String, Double> fused = rrfScores(denseDocs, lexDocs, settings.rrfK()); // :60
    List<Document> ordered = byId.keySet().stream()
            .sorted(Comparator.comparingDouble((String id) -> fused.getOrDefault(id, 0.0)).reversed())
            .map(byId::get).toList(); // :61
    List<Document> reranked = settings.mmrEnabled() ? mmrRerank(query, ordered, topK) : ordered; // :66
    return reranked.stream().limit(topK).toList();
}
// src/main/java/com/company/orderapi/rag/HybridRetrievalEngine.java:72
private static Map<String, Double> rrfScores(List<Document> first, List<Document> second, int k) {
    Map<String, Double> scores = new HashMap<>(); accumulate(first, scores, k); accumulate(second, scores, k); return scores; }
private static void accumulate(List<Document> docs, Map<String, Double> scores, int k) { // :79
    for (int i = 0; i < docs.size(); i++) scores.merge(docs.get(i).getId(), 1.0 / (k + i + 1), Double::sum); }
private List<Document> mmrRerank(String query, List<Document> candidates, int topK) { // :85
    float[] queryVector = embeddingModel.embed(query); // :86
    Map<String, float[]> vectors = new ConcurrentHashMap<>(); // :87 cache
    double lambda = settings.mmrLambda(); // :90 clamped [0,1] RagProperties.java:101
}
```

### Snippet 5 — Wiring switch (`RagConfig.java:39-59`, `RagProperties.java:71-106`)

```java
// src/main/java/com/company/orderapi/rag/RagConfig.java:39
@Bean public DenseRetrievalEngine denseRetrievalEngine(VectorStore vs) { return new DenseRetrievalEngine(vs); }
@Bean public LexicalRetrievalEngine lexicalRetrievalEngine(JdbcTemplate j, RagProperties p) { // :44
    return new LexicalRetrievalEngine(j, p); }
@Bean public RetrievalEngine retrievalEngine(DenseRetrievalEngine dense, LexicalRetrievalEngine lex, // :50
        EmbeddingModel em, RagProperties p) {
    return switch (p.retrievalMode()) { // :54 app.rag.retrieval-mode
        case DENSE -> dense;
        case HYBRID -> new HybridRetrievalEngine(dense, lex, em, p.retrieval()); // :56
    };
}
// src/main/java/com/company/orderapi/rag/RagProperties.java:96
public record RetrievalSettings(boolean mmrEnabled, double mmrLambda, int rrfK) { // :96
    public RetrievalSettings { if (mmrLambda < 0) mmrLambda = 0; if (mmrLambda > 1) mmrLambda = 1; if (rrfK <= 0) rrfK = 60; } // :101
}
// defaults RagProperties.java:54: new RetrievalSettings(false, 0.5, 60) — MMR off, rrfK 60
```

---

## 5. How to use — switch `app.rag.retrieval-mode` dense|hybrid, try query with exact term

### Prerequisites

```bash
ollama pull nomic-embed-text
docker compose up -d postgres
export DEEPSEEK_API_KEY="sk-..."  # only for answer generation
```

### Run dense baseline then hybrid — same corpus, same goldens

```bash
# Dense baseline (pre-#43)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.retrieval-mode=dense
# RAG retrieval eval: goldens=12 hitRate@5=66.7% top1Accuracy=41.7%  RagEvalRunner.java:107

# Hybrid default (dense+lexical RRF)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.retrieval-mode=hybrid
# RAG retrieval eval: goldens=12 hitRate@5=75.0% top1Accuracy=50.0%  (PR #44 measured)

# Via YAML — application-rag.yml:40
# app.rag.retrieval-mode: hybrid   # or "dense"
```

### Try a query with an exact term — the hybrid rescue

```bash
# Tool name / identifier that dense tends to miss
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"How does reindex_docs work and when is it guarded?"}}}' \
  | jq -r '.result.content[0].text' | head -40
# hybrid: cites ReindexDocsTool.java:37 + DocumentIngestionService.java:125 (lexical hit on "reindex_docs")
# dense:  may surface generic "re-index" paraphrases without exact tool doc

curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"Explain vector_store pgvector HNSW setup"}}}' \
  | jq -r '.result.content[0].text' | head -40
# "vector_store" exact table name — lexical rescues it

# Multi-part question — enable MMR for diverse chunks
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.retrieval.mmr-enabled=true --app.rag.retrieval.mmr-lambda=0.5
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

### `rrf-k = 60` — rarely touch (`RagProperties.java:96`, `HybridRetrievalEngine.java:72`)

### `mmr-lambda` in `[0,1]` (`RagProperties.java:96`, `HybridRetrievalEngine.java:90`)

```
mmrScore = lambda*sim(query,doc) - (1-lambda)*maxSim(doc, alreadySelected)
```

### MMR defaults OFF — earned from measurement (`RagProperties.java:54`)

PR #44 measured: RRF alone `75%` hitRate@5; RRF+MMR `0.5` -> `50%`. So `mmrEnabled=false` by default.

### `CANDIDATE_MULTIPLIER=4`, `MIN_CANDIDATES=20` (`HybridRetrievalEngine.java:33`)

### GIN lazy + idempotent (`LexicalRetrievalEngine.java:69-83`)

### Rank fusion over score fusion

Averaging `cosine [-1,1]` with `ts_rank [0,1]` needs per-corpus weights/normalization. Ranks are unit-free — whole point of `HybridRetrievalEngine.java:18`.

---

## 7. How to verify — eval harness dense vs hybrid comparison

### Eval harness — the ruler (`RagRetrievalEvaluator.java:66`, `RagEvalRunner.java:53`)

```
golden-questions.json ──► RagEvalRunner.run() ──► RagRetrievalEvaluator.evaluate()
  [{question,               :99                    :66
    expectedSource}]             │ retriever.retrieve(q) per golden
                                 ▼
                          RetrievalEvalReport     target/rag-eval-report.json :135
                           hitRate@k, top1        gated by min-hit-rate :122
                           [HIT/MISS]
```

Every boot with `app.rag.eval.enabled=true` (`application-rag.yml:55`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.retrieval-mode=dense 2>&1 | grep "RAG retrieval eval"
# RAG retrieval eval: goldens=12 hitRate@5=66.7% top1Accuracy=41.7% precision@5=13.3%

./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.retrieval-mode=hybrid 2>&1 | grep "RAG retrieval eval"
# RAG retrieval eval: goldens=12 hitRate@5=75.0% top1Accuracy=50.0% precision@5=15.0%

cat target/rag-eval-report.json | jq .
# { runAt, retrievalMode:"HYBRID", topK:5, goldens:12, hits:9, hitRateAtK:0.75, items:[...] }

# MMR regression on single-topic goldens:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.retrieval.mmr-enabled=true --app.rag.retrieval.mmr-lambda=0.5 2>&1 | grep "RAG retrieval eval"
# hitRate@5=50.0%  <- worse on this corpus

# Gate (PR #44):
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0.7 2>&1 | tail
# IllegalStateException: gate FAILED: hitRate@5=50.0% below 70.0%  RagEvalRunner.java:124
```

Measured table (PR #44, 12 goldens, `nomic-embed-text`):

| retrieval-mode | hit-rate@5 | top-1 | precision@5 |
|---|---|---|---|
| `dense` | 66.7% | 41.7% | 13.3% |
| `hybrid` (RRF, MMR off) | **75.0%** | **50.0%** | 15.0% |
| `hybrid` (RRF + MMR lambda=0.5) | 50.0% | — | — |

### Unit — RRF math + MMR extremes

```bash
./mvnw test -Dtest=HybridRetrievalEngineTest
# rrfFusionOrdersByReciprocalRankWhenMmrDisabled :36
# mmrAtLambdaZeroPrefersTheMostDiverseDocument :65
# mmrAtLambdaOneKeepsRelevanceOrder :85
```

### psql — lexical index exists

```bash
docker compose exec postgres psql -U order -d orderdb -c "\d vector_store"

docker compose exec postgres psql -U order -d orderdb -c "SELECT indexname FROM pg_indexes WHERE tablename='vector_store';"
# idx_public_vector_store_content_fts (GIN)  <- :75

docker compose exec postgres psql -U order -d orderdb -c \
  "SELECT left(content,60) FROM vector_store WHERE to_tsvector @@ plainto_tsquery('english','reindex_docs') LIMIT 3;"
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build — ship retrieval that handles identifiers.** Dense misses `cancel_order`/`reindex_docs`; lexical catches them; RRF rescues without tuning. Point `RagProperties.docsLocation` at new corpus — both engines share `vector_store`, no infra. Start `DENSE`, measure, flip `retrieval-mode: hybrid` (`RagConfig.java:54`) with zero `RagService` changes. Enable MMR (`RagProperties.java:96`) only when multi-part queries monopolize top-k.

- **Operate — explain cost/quality.** One table + one `GIN`; lazy `ensureFtsIndex()` (`:69`) never blocks boot. `hitRate@k` logs (`:107`) + JSON (`:135`); `min-hit-rate` (`:122`) fails startup on regression.

- **Interview — whiteboard in 90s.** "PR #43: `RetrievalEngine` (`:17`), `RagConfig` switch (`:50`), `Lexical` (`:31`) `ts_rank` (`:51`) `GIN` (`:75`), `Hybrid` (`:30`) `max(topK*4,20)` (`:52`) `1/(rrfK+rank)` (`:81`), `MMR` (`:106`), `cosine` (`:118`). Hybrid 75% vs dense 66%."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "Embeddings are fuzzy — how do you handle exact term queries like tool names?"**

> "Dense `DenseRetrievalEngine` (`:26` `similaritySearch`) misses `reindex_docs`/`vector_store`. `LexicalRetrievalEngine` (`:31`) queries same `vector_store` via `JdbcTemplate` with `to_tsvector('english',content) @@ plainto_tsquery('english', ?)` and `ts_rank` (`:51`), `GIN` (`:75`) lazy via `AtomicBoolean` (`:70`). `Hybrid` (`:30`) asks each for `max(topK*4,20)` (`:52`), fuses `rrfScores()` (`:60`) where `accumulate()` (`:79`) does `1/(rrfK+rank)` (`:81`, `60`), sorts (`:61`), optionally `mmrRerank()` (`:66`). Try `reindex_docs` in `docs_search` — dense paraphrases miss it, lexical rescues it."

**Q2: "How do you fuse two scores in different units without tuning weights?"**

> "Fuse ranks, not scores. RRF `SUM 1/(rrfK+rank)` (`HybridRetrievalEngine.java:72`). Cosine `[-1,1]` and `ts_rank` need per-corpus normalization; ranks are unit-free. With `rrfK=60` expected order `B>A>D>C` (`HybridRetrievalEngineTest.java:56`) is exact: `B 1/61+1/62`, `A 1/61+1/63`. MMR `lambda*sim-(1-lambda)*maxSim` (`:106`) trades relevance/diversity; `lambda` clamped `[0,1]` (`RagProperties.java:102`). Ships `mmrEnabled=false` because MMR 0.5 dropped hitRate 75%->50%."

**Q3: "How do you know hybrid helps and stop regressions?"**

> "`RagRetrievalEvaluator.evaluate(retriever,goldens,k)` (`:66`) checks `expectedSource in topK` — `hitRate@k`/`top1`/`precision@k`. `RagEvalRunner` (`:53`) runs at `ApplicationReadyEvent`+`LOWEST_PRECEDENCE` after ingestion, logs `[HIT]/[MISS]` (`:113`), writes `target/rag-eval-report.json` (`:135`), and if `hitRate<minHitRate` (`:126`, `application-rag.yml:57` `0.7` earned from 75% hybrid) throws at `:124`. Flip `app.rag.retrieval-mode dense|hybrid` — dense 66.7%/41.7%, hybrid 75%/50%, hybrid+MMR 50%."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #44-46 pick up

**What PR #43 alone does NOT do (by design):**

- **Not a quality gate yet.** Harness reports but `min-hit-rate` `0` until PR #44 earns `0.7` from 75% hybrid (`application-rag.yml:57`, `RagProperties.java:126`).
- **No query rewriting/reranking.** Raw query embedded as-is; no `RagQueryRewriter`/`SemanticReranker` (PR #51).
- **No streaming/memory/resources.** Single `chatModel.call()` in `RagService.answer()` (`:71`); no SSE, no `MessageChatMemoryAdvisor` (PR #40/45), no MCP resources/prompts (PR #46).
- **English-only full-text.** `to_tsvector('english',...)` (`:53`) — stemming/stopwords English. Non-English needs matching config + separate `GIN`.
- **Same-table coupling.** Lexical reads `vector_store` directly — rename `vectorTable` (`:52`) changes GIN name (`:73`) but external `SELECT` must follow.

**Where PR #44-46 pick up:**

- **PR #44** earns gate — measures dense vs hybrid (table above), sets `min-hit-rate: 0.7` (`:57`), persists `target/rag-eval-report.json` (`:135`), fixes `ApplicationRunner`->`ApplicationListener` ordering.
- **PR #45-46** add `stream_docs_search` and MCP `resources/list` — hybrid quality flows through unchanged.
- **PR #51** adds `RagQueryRewriter` + `SemanticReranker` on seam — `RetrievalEngine` stays interface.

> Next: [`05-rag-productionization.md`](./05-rag-productionization.md) (PR #42) · [`07-rag-eval-gate-earned.md`](../07-rag-eval-gate-earned.md) (PR #44) · or [`03-chat-memory.md`](./03-chat-memory.md) (PR #40).
