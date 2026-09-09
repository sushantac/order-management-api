# docs/additions/05 — RAG productionization: pgvector + re-index trigger + retrieval eval

**Bonus PR #42.**

PR #38 built RAG end-to-end and its doc honestly left two open boxes:
*"in-memory vector store … Scale path: PGVector or Redis with the identical
`VectorStore` interface + a re-index job"* and *"Docs indexed once at startup …
A `FileSystemWatcher` or a tiny admin trigger is the obvious next increment."*
This PR closes both and adds the part almost nobody learns — **measuring
retrieval quality, not just hoping it works**.

Three deliverables:

| # | Deliverable | Problem it solves |
|---|---|---|
| 1 | **pgvector persistence** | vectors died with the JVM; live embeddings needed them on the app's own Postgres |
| 2 | **incremental re-index trigger** (guarded `reindex_docs` MCP write tool) | docs edited after startup were invisible until a restart |
| 3 | **retrieval-eval harness** (goldens → hit-rate@k, deploy gate) | nobody *knows* whether retrieval is actually finding the right docs |

---

## 1. Swap in pgvector — the `VectorStore` seam pays off

PR #38 stored vectors with `SimpleVectorStore` (in-memory) and pre-defined the
solution in its own "Decisions" section: keep the `VectorStore` interface, swap
the backend. That's exactly what happened — `RagConfig` now returns
`PgVectorStore`:

```java
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel, DataSource dataSource,
                               RagProperties ragProperties) {
    return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
            .vectorTableName(ragProperties.vectorTable())          // vector_store
            .dimensions(ragProperties.embeddingDimensions())      // 768 (nomic-embed-text)
            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
            .indexType(PgVectorStore.PgIndexType.HNSW)
            .initializeSchema(true)
            .build();
}
```

Everything that touched vectors — `RagService.retrieve()`, `docs_search`,
`DocumentIngestionService` — did not change by a single line, because they all
program against `VectorStore`. `initializeSchema(true)` makes the migration
self-healing: on first use it runs `CREATE EXTENSION IF NOT EXISTS vector`
(+ `uuid-ossp`, `hstore`), creates `vector_store (id uuid, content text,
metadata json, embedding vector(768))` and an HNSW index over the cosine
distance. The integration test asserts all of it against a real
`pgvector/pgvector:pg16` container.

**Dependency choice matters.** `spring-ai-pgvector-store` (the library) is used,
not `spring-ai-starter-vector-store-pgvector`. The starter eagerly
auto-configures a `PgVectorStore` bean on any healthy `DataSource` — which would
appear in every test context whether RAG is enabled or not. The plain library
keeps the bean creation opt-in behind `app.rag.enabled=true`, so the entire RAG
stack stays invisible to the rest of the suite.

**The `@ConfigurationProperties` record gotcha.** A Spring Boot property record
is constructor-bound automatically *only when it has exactly one constructor*.
Adding the secondary convenience constructor (kept so the pre-#42 unit tests
still compile) silently downgraded the record to JavaBean binding and the
context failed with *"No default constructor found"*. Fix: mark the compact
constructor `@ConstructorBinding`. The compact body also gives every unset field
a sane default (`docs-location`, `chunk-size`, `top-k`, `embedding-dimensions`,
`vector-table`, and the whole nested `eval` block) — binding supplies `null` for
keys absent from the environment, and the constructor fills them in.

---

## 2. Re-index: content-addressed, incremental, guarded

PR #38 re-indexed everything on every startup ("docs indexed once at startup").
For a 118-chunk corpus that's fine; the moment re-indexing is a *first-class
action* (an admin trigger) it must be cheap and idempotent. So re-indexing
became **content-addressed and incremental**:

- every chunk gets a **deterministic id**: `UUID.nameUUIDFromBytes(source + ":" + chunkIndex)`,
- every chunk carries its file's **sha-256** as metadata `content-hash`,
- `reindex()` compares on-disk files against what the index already holds (via
  `VectorIndexStore`, a small read-side lens over the table):
  - unchanged file (same hash) → **skipped** — zero embedding cost,
  - edited file → its stale chunks are deleted and re-embedded **once each**,
  - source gone from disk → all its chunks are dropped.

```java
boolean unchanged = !existing.isEmpty()
        && existing.stream().allMatch(c -> file.contentHash().equals(c.contentHash()));
```

`DocumentIngestionService.ReindexReport` exposes exactly what a run did
(`filesReindexed`, `chunksAdded`, `chunksDeleted`, `filesUnchanged`,
`sourcesRemoved`) — which is what the trigger replies with, and what the tests
assert on.

### The bug the tests actually caught: basenames aren't unique

This corpus contains eleven `README.md` files. The first version keyed chunks by
*basename* (`metadata.source = README.md`) and derived deterministic ids from it
— so chunks from eleven different files collided on the same id, and the
"unchanged" check could never pass (the hash was alternately those of different
READMEs). The incremental contract silently broke.

Fix: the source key is the **relative path within the docs root**
(`interview-cheat-sheets/README.md`), and the unit test
`reindexIsIdempotentAndSkipsUnchangedFiles` now proves the contract:
a second `reindex()` adds/deletes zero chunks and reports every file unchanged.
Idempotency is *tested*, not assumed.

### The trigger is a guarded write tool: `reindex_docs`

Exactly the PR #41 rails, re-used — this is the payoff of the
`AbstractMcpWriteTool` type:

- exists only when `app.mcp.write-tool.enabled=true` **and** RAG is on
  (`@ConditionalOnBean(DocumentIngestionService.class)`),
- requires `confirmed=true`, anything else refuses — the agent still never sees
  it (`AgentToolSet` collects only `AbstractMcpReadOnlyTool`),
- all side effects run through the domain service (`DocumentIngestionService.reindex()`),
- replies with **counts only, never doc content**, and writes a structured
  `mcp.reindex-docs` audit line.

> The user-facing reopen of the "tiny admin trigger" idea: an MCP write tool is
> the admin trigger — callable by an operator chat, still impossible for the
> agent to leak into.

---

## 3. Retrieval eval: scoring top-k hits against golden questions

RAG quality is **a retrieval problem before it is a generation problem**: if the
right chunk never reaches the prompt, DeepSeek cannot answer. This harness turns
*"is our retrieval good?"* into a measured number.

The golden set is a JSON file (`rag/eval/golden-questions.json`, 12 questions)
mapping natural-language questions to the doc each should retrieve:

```json
{ "question": "How does the outbox pattern make order event publishing reliable?",
  "expected-source": "interview-cheat-sheets/06-event-driven-and-kafka.md" }
```

`RagRetrievalEvaluator` runs every question through the top-k retriever
(`RagService::retrieve` — no chat model needed) and scores:

- **hit-rate@k** — fraction of goldens whose expected source appears in the
  top-k (the headline number),
- **top-1 accuracy** — fraction where the expected source is the *first* hit,
- **precision@k** — how many of the retrieved slots were actually relevant.

`RagEvalRunner` (an `ApplicationRunner`, only when `app.rag.eval.enabled=true`)
logs the full per-question HIT/MISS report at startup and optionally **gates the
deploy**:

- `app.rag.eval.min-hit-rate=0` → **report only** (the only sane default before
  retrieval quality has been measured once),
- raising it (e.g. `0.7`) turns the harness into a deploy gate: startup fails
  loudly on a regression below the agreed standard.

### Why an honest test uses a fake embedding model

`RagRetrievalEvaluatorTest` drives the scoring through a **real cosine-similarity
retriever** over a tiny synthetic corpus using `HashEmbeddingModel` — a
deterministic, all-Java embedder that maps each token to one dimension with a
fixed sign and normalizes, so anything non-semantic crashes it:

- all three goldens retrieve their expected source → `hitRateAtK == 1.0`,
- a golden pointing at a non-existent source is counted as a HIT=false and drags
  the rate to `0.5` — the scoring is *sensitive*, not a rubber stamp.

The pgvector integration test also runs the eval over the *real* corpus + real
goldens, but asserts only shape (counts, list sizes) — never gates. The hash
embedder proves **plumbing**; a real semantic threshold must be measured once
with the real embedding model before anyone raises `min-hit-rate`.

---

## Tests

- `DocumentIngestionServiceTest` (rewritten, 6) — real corpus + an in-memory
  `VectorIndexStore` twin: chunk metadata, **idempotency**, re-embedding only
  the changed file, removed-source cleanup, `isIndexed()` after successful ingest
- `RagRetrievalEvaluatorTest` (3) — hit-rate/top-1/precision scoring semantics
  over SimpleVectorStore + HashEmbeddingModel, incl. the miss case
- `RagEvalRunnerTest` (4) — report-only never blocks; a `min-hit-rate=1.0` gate
  with an empty retriever fails startup loudly; goldens load from the real
  classpath file; `RagService.retrieve` feeds the evaluator directly
- `ReindexDocsToolTest` (4) — refuses without `confirmed=true` and never
  touches the indexer; success delegates + reports count-only; reindex failure
  → tool error; write-tool schema requires `confirmed`
- `PgVectorRagIntegrationTest` (6, `pgvector/pgvector:pg16`) — rows land in the
  `vector_store` table with 768-dim vectors + HNSW index; re-index is idempotent
  on row count; a simulated edit (flip `content-hash` via `jsonb_set`) deletes
  stale rows and re-adds exactly the same count; `retrieve()` returns
  source-tagged chunks from Postgres; eval over the shipped goldens is
  well-formed; the `vectorStore` bean is really `PgVectorStore` (not the
  in-memory default)

Full suite: **183 tests, all green** (164 pre-existing + 19 new). Note the
integration test caught a real bug the unit tests couldn't: `Document(id, text,
metadata)` is the constructor order — swapping them made the *text* the id and
pgvector rejected it ("UUID string too large"). As in every PR here, the fix
came from reading the Spring AI 1.0.0 sources, not a blog.

---

## 4. Decisions and honest limits

- **pgvector on the app's own Postgres.** No new infra component. Redis
  (via `RedisVectorStore`) was equally viable — staying on Postgres keeps the
  env and the ops story unchanged; the swap is a bean either way.
- **Deterministic chunk ids.** Re-indexing a *changed* file must not duplicate
  or orphan rows; hashing (`source:index`) gives stable upserts and makes
  `DELETE`-then-`INSERT` precise.
- **The trigger is manual, not a watcher.** A file watcher re-deploys / live
  changes as you type; the guarded tool makes re-indexing an explicit,
  auditable, agent-proof action. That's the "tiny admin trigger" the PR #38 doc
  flagged — just wired onto the MCP rails instead of a cron.
- **Eval gates must be earned.** `min-hit-rate` is `0` by default; the shipped
  golden set is measured and reported (logged) every startup, and raising it is
  a *decision with a number attached*, not a guess.
- **Hash embeddings are for plumbing tests only.** They prove the harness and
  the pgvector round-trip offline; they are NOT a semantic stand-in, which is
  exactly why the quality threshold lives out of reach of the hash-based tests.

---

## 5. Interview highlights

- *"When is RAG's retrieval 'good'?"* → When the expected source shows up in the
  top-k for a fixed golden set — and now that's a startup log line
  (`hitRate@5=…`), not a vibe.
- *"Why content-addressed ids?"* → Deterministic ids make re-indexing
  idempotent: unchanged files are skipped by hash, changed files are deleted and
  re-added under the same ids, so the index converges to the on-disk truth
  without drift.
- *"Why pgvector instead of a dedicated vector DB?"* → Because the swap was a
  bean, not a rewrite — the whole app (and its `VectorStore` interface) is
  unchanged, and one database answers both SQL and similarity queries.