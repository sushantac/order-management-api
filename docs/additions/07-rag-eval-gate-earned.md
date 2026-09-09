# docs/additions/07 — Earned retriev-benchmarks: the eval gate measured, then set

**Bonus PR #44.**

PRs #42/#43 built the *mechanism* (goldens → hit-rate@k, a report-only gate)
and even a *second* retriever (hybrid dense + full-text). But nobody had ever
actually run the harness with a real embedding model against the real corpus —
the gate stayed at `0` because the number was never earned. Worse, first run
revealed the harness literally could not measure: it evaluated an *empty store*.

The whole point of this PR is honesty-by-measurement. Set the gate to a number
you measured — never ahead of it. What follows is the actual measured table for
this corpus (`docs/**`, nomic-embed-text via Ollama, pgvector-persisted chunks).

## 1. First run: the gate measuers... nothing

Running the rag profile with eval enabled and a REAL model produced `retrieved []`
for all 12 goldens. The app "worked", the gate "ran", every question MISSed.

**Cause:** `RagEvalRunner` was an `ApplicationRunner`; `DocumentIngestionService`
re-indexes on `ApplicationReadyEvent`. Runners fire **before** ready-event
listeners — so the gate evaluated a vector store that still had zero chunks.

**Fix (the one that makes the harness exist at all):** both are now
`ApplicationReadyEvent` listeners with explicit order —
`@Order(HIGHEST_PRECEDENCE)` on the re-index, `@Order(LOWEST_PRECEDENCE)` on
the evaluator. Ordering is now deterministic, not "bean registration order, if
you're lucky".

## 2. The measured table: dense vs hybrid (real model, real corpus)

Retrieval-mode | hit-rate@5 | top-1 accuracy | precision@5
---|---|---|---
`DENSE` (baseline) | **75.0%** | 41.7% | 15.0%
`HYBRID`, RRF only (`mmr-enabled: false`) | **75.0%** | **50.0%** | 15.0%
`HYBRID`, RRF + MMR (`mmr-lambda: 0.5`) | **50.0%** | 41.7% | 10.0%

Three honest conclusions:

1. **Hybrid (RRF) ties dense on hit-rate and beats it on top-1** (50.0% vs
   41.7%): the exact-term rescue works — the fused list surfaces the right doc
   first more often. #43's intuition survives contact with measurement.
2. **MMR at λ=0.5 was actively harmful.** The diversity penalty re-orders
   single-topic goldens' answers out of top-5 (75 → 50%). Top-5 *already*
   contains diversity; MMR's job is multi-topic questions, not these. The
   measured default is therefore **`mmr-enabled: false`** — an opt-in knob
   again, not a shipped default. (Its unit tests at λ=0/1 still prove the
   mechanism.)
3. **The corpus has same-content twin files** (`business/07-…` vs
   `interview-cheat-sheets/07-…`, `business/11-…` vs `interview-…/11-…`). Two
   "misses" surface the *identical content* under the other folder name. The
   goldens stay exact (§4); real *answers* would still be correct because the
   content twin was retrieved.

## 3. "Chrome" — what was needed to run it at all (four real finds)

| Find | Fix |
|---|---|
| Two `ChatModel` beans (DeepSeek + Ollama autoconfig) → "required a single bean, but 2 were found" | explicit `spring.ai.model.chat: deepseek` / `spring.ai.model.embedding: ollama` in the rag profile |
| `agentToolSet ↺ docsSearchTool ↺ ragService ↺ deepSeekChatModel ↺ … ↺ agentToolSet` tool-callback cycle at startup | `@Lazy` on the `ChatModel` consumed by `RagService` — the tool-callback resolution only needs the proxy until the cycle settles |
| Eval before ingest (must measuure nothing) | explicit `@Order` on the two ready-event listeners (§1) |
| Local Postgres had no `vector` extension — RAG wasn't runnable locally at all | `docker-compose.yml` postgres image → `pgvector/pgvector:pg16` |

Let me be blunt about what these four add up to: **the rag profile had never
booted end-to-end before this PR.** #38–#43 shipped config that nobody had run.
That is exactly the class of bug "edit the gate until it's a gate" misses and
"run the gate" catches.

## 4. The earned gate + a persisted audit trail

- `app.rag.eval.min-hit-rate: 0.7` — the shipped rag-profile default now, one
  notch under the measured 75%, so a real regression fails startup loudly while
  day-to-day embedding noise doesn't. The goldens are untouched; gaming a
  threshold by editing goldens until they pass is the anti-pattern blocked here.
- `app.rag.eval.report-location: target/rag-eval-report.json` — every run also
  writes a self-describing snapshot: timestamp, retrieval-mode, hit-rate@k,
  top-1, precision@k, and the full per-question HIT/MISS + retrieved-sources
  trail. Logs rotate; the report is the audit artifact — and dense-vs-hybrid
  runs compare on disk, not on memory. It's a build artifact (git-ignored).
- Verified both directions with the real model: default profile passes (75% ≥
  70%); dragging `min-hit-rate` to 0.9 kills startup with
  `RAG retrieval eval gate FAILED: hitRate@5=75.0% is below the required 90.0%`.

## 5. Run it yourself

```bash
docker compose up -d postgres      # now pgvector-capable (PR #44)
ollama pull nomic-embed-text
export DEEPSEEK_API_KEY=...
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# startup logs the HIT/MISS table; target/rag-eval-report.json is written.
# Toggle app.rag.retrieval-mode=dense|hybrid to reproduce the table above.
```

## 6. Real gotchas hit (this time with a real model)

1. **An eval that runs before ingest measures an empty store.** Event order is
   not "Spring sorts it out" — it's `@Order` or nothing.
2. **A gate threshold and a retriever both need a runway.** Setting `0.7` was
   only defensible after §2's table existed; before that the only honest value
   was `0`.
3. **MMR's diversity is an asset *after* recall is fixed.** On recall-tier
   metrics (hit-rate@k) it can only cost points; its unit tests still teach the
   mechanism, which stays until a multi-topic golden set makes it earn screen
   time again.
4. **Twin documents are corpus reality.** Same content, two folders, two
   "sources". Fixing goldens to whichever folder the embedder happens to rank
   would be teaching to the test; recording the twins in the report trail is the
   honest version.