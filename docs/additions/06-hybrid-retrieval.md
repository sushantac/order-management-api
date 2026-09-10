# docs/additions/06 — Hybrid retrieval: dense + Postgres full-text (RRF + MMR)

**Bonus PR #43.**

> **Measured update (PR #44):** run against the real corpus with nomic-embed-text,
> hybrid/RRF ties dense on hit-rate@5 (75%) and beats it on top-1 accuracy
> (50% vs 41.7%), but MMR at λ=0.5 *hurt* (75% → 50%) — single-topic goldens
> already have diversity in top-5. `mmr-enabled` now defaults to `false`.
> Full table + reasoning: [07-rag-eval-gate-earned.md](07-rag-eval-gate-earned.md).

PR #42 made retrieval *measurable* (golden questions → hit-rate@k), and that
measurement exposed the honest weakness of a dense-only retriever: **embeddings
are bad at exact vocabulary**. Method names, tool ids, error codes and
identifiers (`reindex_docs`, `cancel_order`, `vector_store`) are terms a fuzzy
semantic match struggles with — a chunk can be semantically "about" the topic
and still never surface for the query that names it exactly. Postgres has a
native answer already sitting in the database: full-text `tsvector`.

This PR makes retrieval hybrid — cosine *and* BM25-style full-text fused with
**Reciprocal Rank Fusion**, re-ranked for topical diversity with **Maximum
Marginal Relevance** — and turns PR #42's eval harness into the before/after
ruler for it.

| # | Deliverable | Problem it solves |
|---|---|---|
| 1 | **`RetrievalEngine` seam** (`app.rag.retrieval-mode` = `DENSE` \| `HYBRID`) | the retrieval strategy is a wiring decision, not a code change |
| 2 | **`LexicalRetrievalEngine`** (Postgres full-text, GIN index) | dense misses exact terms/ids; plain SQL catches them |
| 3 | **`HybridRetrievalEngine`** (RRF fusion + MMR diversity) | two incomparable scores need a rank-based ensemble; one dominant topic must not monopolize top-k |
| 4 | **comparability** | with #42's harness you can measure dense vs hybrid hit-rate@k on the same goldens and only *then* raise the gate |

---

## 1. The seam: `RetrievalEngine` replaces direct `VectorStore` use

`RagService` (and #42's eval harness through it) previously called
`vectorStore.similaritySearch(...)` directly. Now all retrieval goes through one
interface:

```java
public interface RetrievalEngine {
    List<Document> retrieve(String query, int topK);
}
```

`RagService` keeps the same constructor shape — `(RetrievalEngine, ChatModel,
RagProperties)` — and `RagConfig` wires the wanted strategy:

```java
@Bean
public RetrievalEngine retrievalEngine(DenseRetrievalEngine dense, LexicalRetrievalEngine lexical,
                                       EmbeddingModel embeddingModel, RagProperties props) {
    return switch (props.retrievalMode()) {
        case DENSE -> dense;                       // pre-#43 behaviour, unchanged
        case HYBRID -> new HybridRetrievalEngine(dense, lexical, embeddingModel, props.retrieval());
    };
}
```

The eval harness from #42 needed zero changes — it still gets a `String →
List<Document>` `Retriever`. That is the point of a seam.

## 2. Lexical: Postgres full-text over the *same* table

`LexicalRetrievalEngine` queries the `vector_store` table directly with
`JdbcTemplate` — no new index storage, no new service:

```sql
SELECT id::text AS id, content, metadata,
       ts_rank(to_tsvector('english', content), plainto_tsquery('english', ?)) AS rank
FROM public.vector_store
WHERE to_tsvector('english', content) @@ plainto_tsquery('english', ?)
ORDER BY rank DESC
LIMIT ?
```

- the `vector` extension table already holds the chunks; this reads the same
  rows and returns the same `metadata` json (so `source` stays intact)
- `@@` is the full-text match operator; `ts_rank` gives the BM25-flavoured score
- the GIN index is created lazily and idempotently on first query, behind an
  `AtomicBoolean`, so bean wiring order never matters and `CREATE INDEX` never
  blocks a restart. A `DataAccessException` (table not created yet) just defers
  to the next call instead of failing startup.

The integration test proves the *point* of the path: `retrieve("outbox", 5)`
returns chunks that provably contain the word "outbox".

## 3. Hybrid: fuse with RRF, diversify with MMR

The two relevance signals are incomparable units (cosine similarity vs
`ts_rank`). **Reciprocal Rank Fusion** sidesteps calibration entirely — it only
looks at *positions*:

```
RRF(doc) = Σ  over each engine list that contains doc of  1 / (rrfK + rank)
```

With `rrfK = 60` (the canonical constant), for dense `[A,B,C,D]` and lexical
`[B,D,A,C]` the unit test's fused order is exact arithmetic:

| doc | dense | lexical | RRF total | order |
|---|---|---|---|---|
| B | 1/62 | 1/61 | 0.0325225 | 1 |
| A | 1/61 | 1/63 | 0.0322665 | 2 |
| D | 1/64 | 1/62 | 0.0317540 | 3 |
| C | 1/63 | 1/64 | 0.0314980 | 4 |

A document is *rescued* by appearing in a different list than the one that
ranked it low — the whole point of fusion.

**MMR** then trades a little relevance for coverage when a multi-part question
touches several documents:

```
MMR(doc) = λ · sim(query, doc)  −  (1 − λ) · max sim(doc, alreadySelected)
```

- `λ = 1` → pure relevance (identical to the fused order)
- `λ = 0` → pure diversity (towards a near-duplicate of what's picked, a
  disparate doc wins)
- `0.5` is the typical starting point

The unit tests exercise the two *extremes* deliberately — intermediate lambdas
would need a truly semantic embedding, and the test `HashEmbeddingModel` is
plumbing-only.

## 4. The ruler: dense vs hybrid on the #42 harness

Setup (from the `rag` profile):

```yaml
app.rag.retrieval-mode: hybrid   # or "dense" for the baseline run
app.rag.eval.enabled: true       # logs HIT/MISS per golden at startup
app.rag.eval.min-hit-rate: 0     # report-only until a number is earned
```

Run the app in each mode, read the hit-rate@k lines, and *only then* raise
`min-hit-rate`. The fix-first-raise-later ordering is the #42 discipline: the
gate never gets set ahead of a measured baseline.

## 5. Real gotchas hit

1. **RRF beats score-blending because there is nothing to calibrate.** Averaging
   cosine and `ts_rank` needs magic weights per corpus; ranks are
   unit-free and transferable.
2. **MMR needs headroom.** Each engine is asked for `4 × topK` (min 20)
   candidates so reranking has material to re-order; asking for exactly `topK`
   first makes MMR degenerate. The `HybridRetrievalEngineTest` verifies the
   widened candidate pool via Mockito.
3. **The same `Document` instance flows through both lists.** Fusion dedups by
   `Document#id` (deterministic content hash ids from #42) across the two
   engine results — shared lists must reference the same objects or ids won't
   line up.
4. **`λ` must be clamped.** MMR with negative or `>1` lambda produces nonsense;
   `RetrievalSettings.mmrLambda` is clamped into `[0,1]` in the record's compact
   constructor.
5. **Fishing for exact numbers on the non-semantic hash embedder is a trap.**
   With it, the tests prove *ordering semantics* (RRF math, MMR extremes), never
   real retrieval *quality* — quality is the integration test's term-containment
   proof and the eval harness's job, not the unit tests'.