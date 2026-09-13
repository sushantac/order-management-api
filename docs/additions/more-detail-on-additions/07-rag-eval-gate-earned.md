# 07. Rag Eval Gate — Earned Deploy Gate with Real-Model Measurement (PR #44)

> PR: [#44 — RAG eval gate: earned threshold + real-model measurement + startup ordering fix](https://github.com/anomalyco/order-management-api/pull/44) · Profile: `rag` · Stack: `RagEvalRunner` + `RagRetrievalEvaluator` + `nomic-embed-text` (Ollama) + `pgvector/pgvector:pg16` + `DeepSeek` · Depends on [#42 RAG productionization](./05-rag-productionization.md) + [#43 hybrid retrieval](./06-hybrid-retrieval.md)

---

## 1. Purpose — what shipped

PR #44 turns the **report-only** harness from PR #42 into an **earned deploy gate** backed by a real measurement. Four fixes land together because the gate cannot be earned until the harness actually runs end-to-end:

- **Real-model measurement over real corpus.** `RagRetrievalEvaluator` is run with `nomic-embed-text` via Ollama against the persisted `vector_store` corpus (`docs/**/*.md` ~47 files / ~180 chunks). The headline numbers are no longer hypothetical — they are `hit-rate@5`, `top-1 accuracy`, `precision@5` on 12 goldens in `golden-questions.json`.
- **Earned threshold.** `application-rag.yml:57` sets `app.rag.eval.min-hit-rate: 0.7` — one notch under the measured hybrid `75.0%` hit-rate@5. Below that, startup fails loudly. At `0` the gate is vibes; at `0.7` it is a contract.
- **Persistent audit trail.** `RagEvalRunner.writeReport()` at `RagEvalRunner.java:135` writes `target/rag-eval-report.json` — timestamp, `retrievalMode`, `topK`, per-question `[HIT]/[MISS]` + `retrievedSources`. Logs rotate; the JSON is the artifact that survives and lets dense vs hybrid compare on disk.
- **Startup that actually boots.** Two P0 wiring bugs that made `rag` profile unbootable with real models are fixed: `ChatModel` cycle + missing `vector` extension + `ApplicationRunner` vs `ApplicationReadyEvent` ordering.

---

## 2. Problem — what was broken without it (unevaluated gate)

PR #42 shipped the scorer and runner but left the threshold unearned and the runner unproven:

| Gap | Before (PR #42-43) | Why it hurts |
|---|---|---|
| **Unevaluated gate — threshold at 0** | `RagProperties.java:56` default `minHitRate=0.0` → report-only forever. `application-rag.yml:57` had no `0.7`; no measured table existed (dense vs hybrid) to justify any number. | A threshold set before measurement is either cargo-cult or dishonest. Any `chunkSize`/`topK`/`rrfK` change could halve hit-rate and still boot. |
| **Empty-store eval — the harness measured nothing** | `RagEvalRunner` was an `ApplicationRunner` (`run()` before ready). `DocumentIngestionService.ingestDocuments()` at `DocumentIngestionService.java:88` listens on `ApplicationReadyEvent` at `Ordered.HIGHEST_PRECEDENCE`. Runners fire **before** ready-event listeners → eval saw `retrieved []` for all 12 goldens, every question `[MISS]`. | The harness existed on paper but literally could not score. First real-model run returned `0%` hit-rate — obvious proof it never ran end-to-end before. |
| **`rag` profile never booted with real models** | `spring.ai.model.chat/embedding` unpinned → both `DeepSeekChatAutoConfiguration` and `OllamaChatAutoConfiguration` register a `ChatModel` → `NoUniqueBeanDefinitionException: required a single bean, but 2 were found`. Plus `agentToolSet → docsSearchTool → ragService → deepSeekChatModel → agentToolSet` circular ref. Plus stock `postgres:16` with no `vector` extension → `PgVectorStore` DDL fails. | You cannot earn a number if `spring.profiles.active=rag` crashes before ingestion. PRs #38-43 shipped config nobody had run with Ollama+DeepSeek+pgvector together. |
| **Mismatched default — MMR shipped without measurement** | `RagProperties.java:54` could have defaulted `mmrEnabled=true`. Unit tests prove MMR mechanics but no corpus measurement justified it. | On single-topic goldens, MMR re-ranking is diversity at the cost of relevance — it needs a measured default, not a guessed one. |

Without PR #44, the eval harness is a log line with no teeth and no proof.

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 Harness with REAL model over REAL corpus (the earned number)
```
golden-questions.json (12)                    Real corpus: docs/**/*.md (47 files)
  [{question, expectedSource}]                  → DocumentIngestionService.java:184 loadFiles()
         │                                      → chunk() :210 UUID(name:idx) :220 + sha256Hex :229
         │                                      → PgVectorStore.java:64 HNSW COSINE 768 :66 on pgvector/pg16
         │  RagEvalRunner.java:99 run()         │   docker-compose.yml:16 image: pgvector/pgvector:pg16
         └──────────────┬───────────────────────┘
                        │ ragService::retrieve (RetrievalEngine.retrieve :113)
                        │   per golden: topK=5 (RagProperties.java:50)
                        ▼
              RagRetrievalEvaluator.java:66 evaluate(retriever, goldens, k)
                        │
                        ├─► evaluateOne :90 → retriever.retrieve(q) → sources.contains(expected) ? HIT : MISS
                        ├─► hits / total = hitRate@k  :84
                        ├─► expected==first ? top1Accuracy :72
                        └─► hits/(total*k) = precision@k :76
                        │
                        ▼
              RetrievalEvalReport(total=12, hits=9, hitRate@k, top1, precision@k, items)
                        │
            ┌───────────┴───────────┐
            │ log HIT/MISS :112     │ writeReport :135 → target/rag-eval-report.json :135
            │ gate minHitRate :122  │ RagEvalReportSnapshot :79
            └───────────┬───────────┘
                        │ if minHitRate>0 && hitRate < minHitRate → IllegalStateException :124
                        ▼
                 boot proceeds or fails fast (deploy gate)
```

Measured table (PR #44, `nomic-embed-text`, pgvector-persisted, `topK=5`):

| retrieval-mode | hit-rate@5 | top-1 accuracy | precision@5 | verdict |
|---|---|---|---|---|
| `DENSE` | **75.0%** | 41.7% | 15.0% | baseline — ties hybrid on hit-rate |
| `HYBRID` RRF only (`mmr-enabled:false`) | **75.0%** | **50.0%** | 15.0% | **earned default** — same hit-rate, +8.3pp top-1 |
| `HYBRID` RRF+MMR (`lambda=0.5`) | **50.0%** | 41.7% | 10.0% | MMR harmful here — diversity penalizes single-topic goldens |

`0.7` is earned: just under `75%` so regression fails, noise does not.

### 3.2 pgvector in compose — local DB matches prod
```
Before (PR #42 wishful)                After (PR #44 runnable)
┌─────────────────────┐                ┌──────────────────────────────┐
│ postgres:16         │                │ pgvector/pgvector:pg16       │ docker-compose.yml:16
│ no vector extension │─DDL fails─────▶│ CREATE EXTENSION vector      │ RagConfig.java:69 initializeSchema(true)
│ vector_store table  │  HNSW fails    │ vector_store (vector 768)    │ HNSW cosine :68, GIN fts :75
└─────────────────────┘                │ lex + dense share same table │
                                       └──────────────────────────────┘
```

### 3.3 Cycle break — `@Lazy` on ChatModel
```
Before: agentToolSet ─► docsSearchTool ─► ragService ─► deepSeekChatModel ─► agentToolSet  ↺ cycle
After:  RagService.java:50  @Lazy ChatModel chatModel  — proxy defers resolution, cycle settles

spring.ai.model.chat: deepseek          application-rag.yml:17
spring.ai.model.embedding: ollama       application-rag.yml:18  → disambiguates 2 ChatModel beans
```

### 3.4 Eval-before-ingest fix — explicit `@Order`
```
Before (bug):  ApplicationRunner.run() ──► eval on empty store (0 chunks) ──► ApplicationReadyEvent ──► ingest
After  (fix):  ApplicationReadyEvent @Order(HIGHEST_PRECEDENCE) DocumentIngestionService.java:89 ingest
               ApplicationReadyEvent @Order(LOWEST_PRECEDENCE)  RagEvalRunner.java:52        eval
               Guarantees ingest → eval deterministically, not "bean registration order if you're lucky"
```

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:31` | **Scorer** — `evaluate(Retriever, goldens, k)` at `:66` → `RetrievalEvalReport(hitRate@k, top1Accuracy, precision@k, items)` at `:49`; per-question `evaluateOne()` at `:90` |
| `src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:53` | **Gate** — `ApplicationListener<ApplicationReadyEvent>` at `LOWEST_PRECEDENCE` `:52`; `run()` at `:99` logs `[HIT]/[MISS]` at `:112`, `writeReport()` at `:135` to `target/rag-eval-report.json`, throws at `:124` if `hitRate < minHitRate` |
| `src/main/java/com/company/orderapi/rag/eval/GoldenQuestion.java` | **Golden** — `record GoldenQuestion(String question, String expectedSource)` deserialized at `RagEvalRunner.java:170` from `goldensLocation` |
| `src/main/java/com/company/orderapi/rag/RagProperties.java:25` | **Config** — `RagEvalProperties(enabled, goldensLocation, minHitRate, reportLocation)` at `:123`; defaults `RagProperties.java:55` (report-only `0.0`, `classpath:rag/eval/golden-questions.json`, `target/rag-eval-report.json`); `RetrievalSettings` at `:96` defaults `mmrEnabled=false` at `:54` |
| `src/main/java/com/company/orderapi/rag/DocumentIngestionService.java:51` | **Ingest** — `ingestDocuments()` at `:88` `@EventListener(ApplicationReadyEvent)` `@Order(HIGHEST_PRECEDENCE)` `:89`; `reindex()` `:125` with `sha256Hex()` `:229` + `UUID.nameUUIDFromBytes(name:idx)` `:220` |
| `src/main/java/com/company/orderapi/rag/RagConfig.java:80` | **Wiring** — `ragRetrievalEvaluator()` bean `:80`; `vectorStore()` `:62` `PgVectorStore` HNSW+COSINE `initializeSchema(true)` `:69`; explicit `spring.ai.model.chat/embedding` selection `application-rag.yml:17` fixes 2-bean conflict |
| `src/main/java/com/company/orderapi/rag/RagService.java:50` | **Cycle fix** — `RagService(@Lazy ChatModel chatModel, RetrievalEngine, RagProperties)` at `:50`; `retrieve()` at `:113` is `RagRetrievalEvaluator.Retriever` |
| `src/main/resources/application-rag.yml:54` | **Profile** — `eval.enabled:true :55`, `goldens-location :56`, `min-hit-rate: 0.7 :57` (earned), `report-location: target/rag-eval-report.json :58` |
| `docker-compose.yml:16` | **Infra** — `image: pgvector/pgvector:pg16` so `vector` extension exists locally |
| `src/main/resources/rag/eval/golden-questions.json` | **Goldens** — 12 `{question, expectedSource}` pairs; exact `source` match against `metadata->>'source'` |
| `src/test/java/com/company/orderapi/rag/eval/RagRetrievalEvaluatorTest.java` | **Unit** — hit/miss/precision math with real cosine scoring |
| `src/test/java/com/company/orderapi/rag/eval/RagEvalRunnerTest.java` | **Gate unit** — report-only vs gate failure, JSON snapshot shape |

### Snippet 1 — Scorer (`RagRetrievalEvaluator.java:66-102`)
```java
// src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:66
public RetrievalEvalReport evaluate(Retriever retriever, List<GoldenQuestion> goldens, int k) {
    List<RetrievalEvalItem> items = goldens.stream().map(g -> evaluateOne(retriever, g, k)).toList(); // :67
    long hits = items.stream().filter(RetrievalEvalItem::hit).count(); // :71
    long top1 = items.stream().filter(i -> !i.retrievedSources().isEmpty())
        .filter(i -> i.expectedSource().equals(i.retrievedSources().get(0))).count(); // :72
    double precisionAtK = items.stream().mapToDouble(i -> i.hit()?1:0).sum()
        / ((double) Math.max(items.size(),1) * Math.max(k,1)); // :77
    return new RetrievalEvalReport(items.size(), (int)hits,
        items.isEmpty()?0:(double)hits/items.size(), // hitRate@k :84
        items.isEmpty()?0:(double)top1/items.size(), precisionAtK, items); // :85
}
// src/main/java/com/company/orderapi/rag/eval/RagRetrievalEvaluator.java:90
private RetrievalEvalItem evaluateOne(Retriever r, GoldenQuestion g, int k) {
    List<Document> docs = r.retrieve(g.question()); // real retriever over pgvector
    List<String> sources = docs.stream().map(d -> String.valueOf(d.getMetadata().get("source"))).toList(); // :92
    return new RetrievalEvalItem(g.question(), g.expectedSource(), sources, topScore,
        !sources.isEmpty() && sources.subList(0, Math.min(k, sources.size())).contains(g.expectedSource())); // :102
}
```

Model-independent — needs only `Retriever` (`String → List<Document>` at `RagRetrievalEvaluator.java:34`), not the chat model.

### Snippet 2 — Gate, ordering, and audit trail (`RagEvalRunner.java:52-162`)
```java
// src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:52
@Order(Ordered.LOWEST_PRECEDENCE) // after DocumentIngestionService HIGHEST_PRECEDENCE :89
public class RagEvalRunner implements ApplicationListener<ApplicationReadyEvent> { // :53
    public void run() { // :99
        List<GoldenQuestion> goldens = loadGoldens(); // :101  classpath:rag/eval/golden-questions.json
        RetrievalEvalReport report = evaluator.evaluate(ragService::retrieve, goldens, k); // :103
        log.info("RAG retrieval eval: goldens={} hitRate@5={} top1={} ...", ...); // :107
        for (RetrievalEvalItem item : report.items())
            logLine.append("  [").append(item.hit()?"HIT":"MISS").append("] ") // :113
                   .append(item.question()).append(" -> expected ").append(item.expectedSource());
        writeReport(report); // :120
        if (minHitRate > 0 && report.hitRateAtK() < minHitRate) // :122 min-hit-rate gate
            throw new IllegalStateException("RAG retrieval eval gate FAILED: hitRate@"+k+"=" // :124
                + percent(report.hitRateAtK()) + " is below the required " + percent(minHitRate));
    }
    // src/main/java/com/company/orderapi/rag/eval/RagEvalRunner.java:135
    private void writeReport(RetrievalEvalReport report) {
        RagEvalReportSnapshot snapshot = new RagEvalReportSnapshot( // :140  Instant.now(), retrievalMode, topK, ...
            report.items().stream().map(i -> new Item(i.question(), i.expectedSource(), i.hit(), i.retrievedSources())).toList());
        Files.createDirectories(Path.of(location).getParent()); // :155
        objectMapper.writeValue(Path.of(location).toFile(), snapshot); // :156 target/rag-eval-report.json
    }
    private List<GoldenQuestion> loadGoldens() throws IOException { // :164
        Resource r = resourceLoader.getResource(ragProperties.eval().goldensLocation()); // :165
        return List.of(objectMapper.readValue(r.getInputStream(), GoldenQuestion[].class)); // :170
    }
}
```

`LOWEST_PRECEDENCE` is load-bearing — `ApplicationRunner` would fire before `ApplicationReadyEvent` and score an empty store (the PR #44 bug).

### Snippet 3 — Earned defaults (`RagProperties.java:54`, `application-rag.yml:54`)
```java
// src/main/java/com/company/orderapi/rag/RagProperties.java:54
if (retrieval == null) retrieval = new RetrievalSettings(false, 0.5, 60); // MMR OFF — earned at :91-94
if (eval == null) eval = new RagEvalProperties(false, "classpath:rag/eval/golden-questions.json", 0.0, null); // report-only default
// src/main/java/com/company/orderapi/rag/RagProperties.java:129 compact body clamps
if (reportLocation == null || reportLocation.isBlank()) reportLocation = "target/rag-eval-report.json"; // :135
```
```yaml
# src/main/resources/application-rag.yml:54  (rag profile overrides the 0.0 default with the earned value)
eval:
  enabled: true              # :55
  goldens-location: classpath:rag/eval/golden-questions.json  # :56
  min-hit-rate: 0.7          # :57  earned — one notch under measured 75% (PR #44)
  report-location: target/rag-eval-report.json                # :58
```

### Snippet 4 — Startup fixes (`RagService.java:50`, `docker-compose.yml:16`)
```java
// src/main/java/com/company/orderapi/rag/RagService.java:50
public RagService(RetrievalEngine retrievalEngine, @Lazy ChatModel chatModel, RagProperties ragProperties, AiMetrics aiMetrics)
// @Lazy defers ChatModel proxy — breaks agentToolSet ↺ docsSearchTool ↺ ragService ↺ chatModel cycle
```
```yaml
# docker-compose.yml:16  was postgres:16 → pgvector/pgvector:pg16 for CREATE EXTENSION vector (RagConfig.java:69)
services: { postgres: { image: pgvector/pgvector:pg16 } }
```

---

## 5. How to use — run eval, read report, gate 0.7

### Prerequisites
```bash
ollama pull nomic-embed-text          # 768-dim (~270 MB), RagProperties.java:51
docker compose up -d postgres         # pgvector/pgvector:pg16 — check: docker compose exec postgres psql -U order -d orderdb -c "CREATE EXTENSION IF NOT EXISTS vector;"
export DEEPSEEK_API_KEY="sk-..."      # only for answer generation; eval itself needs only embeddings
```

### Run the earned eval (hybrid is default)
```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag 2>&1 | grep "RAG retrieval eval"
# RAG retrieval eval: goldens=12 hitRate@5=75.0% top1Accuracy=50.0% precision@5=15.0%  RagEvalRunner.java:107
#   [HIT]  How does hybrid retrieval work? -> expected docs/additions/07-rag-eval-gate-earned.md ; retrieved [docs/additions/07-..., ...]
#   ... 12 lines, HIT vs MISS per golden

# Dense baseline for comparison — same corpus, same goldens:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.retrieval-mode=dense 2>&1 | grep "RAG retrieval eval"
# RAG retrieval eval: goldens=12 hitRate@5=75.0% top1Accuracy=41.7% precision@5=15.0%
```

### Read the audit report
```bash
cat target/rag-eval-report.json | jq .
# {
#   "runAt": "2026-03-15T10:42:00Z",          # RagEvalReportSnapshot.java:80
#   "retrievalMode": "HYBRID",               # :81  vs "DENSE"
#   "topK": 5,                               # :82  RagProperties.java:50
#   "goldens": 12, "hits": 9,
#   "hitRateAtK": 0.75, "top1Accuracy": 0.5, "precisionAtK": 0.15,
#   "items": [
#     {"question":"How does hybrid retrieval work?","expectedSource":"docs/...","hit":true,"retrievedSources":["docs/...", ...]},
#     {"question":"...","hit":false,"retrievedSources":[...]},  # twin-file misses visible here
#     ...
#   ]
# }
```

### Gate at 0.7 — regressions fail startup
```bash
# Default rag profile — passes (75% >= 70%):
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# boot succeeds, report written

# Drag threshold above measured — fails fast:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0.9 2>&1 | tail -5
# java.lang.IllegalStateException: RAG retrieval eval gate FAILED: hitRate@5=75.0% is below the required 90.0%
#   (app.rag.eval.min-hit-rate=0.9). Fix the corpus, the chunking, or the goldens before shipping.  RagEvalRunner.java:124

# Report-only (default without profile — RagProperties.java:56 minHitRate 0):
./mvnw spring-boot:run  # no rag profile → RAG off, no eval
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0
# logs score, never blocks — safe before you have earned a number
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

### MMR off by default — earned from 75%→50% drop (`RagProperties.java:54`, `RagProperties.java:91`)
```java
// RagProperties.java:91 — PR #44 measured-default comment
// PR #44 measured-default decision: mmr-enabled defaults to false. Against the real
// nomic-embed-text corpus, MMR at 0.5 took hit-rate@5 from 75% to 50% (single-topic
// goldens; top-5 already has diversity) while RRF alone held 75% and lifted top-1
// accuracy from 41.7% to 50%.
if (retrieval == null) retrieval = new RetrievalSettings(false, 0.5, 60);
```

MMR `lambda*sim(query,doc) - (1-lambda)*maxSim(doc, picked)` trades relevance for diversity. On single-topic goldens (the current 12), diversity re-orders the right doc out of top-5 — hit-rate can only drop. MMR's unit tests at `lambda=0` (max diversity) and `1` (pure relevance) still prove the mechanism (`HybridRetrievalEngineTest.java:65/85`); the default stays `false` until a multi-topic golden set makes it earn screen time again. Do not ship a knob because it looks clever.

### pgvector in compose — make local runnable (`docker-compose.yml:16`)

Without `pgvector/pgvector:pg16`, `RagConfig.java:69 initializeSchema(true)` tries `CREATE EXTENSION vector` on stock `postgres:16` and fails — the rag profile was unrunnable locally before PR #44. Same-major-version image (`pg16`) means existing named-volume data is untouched. The comment at `docker-compose.yml:12` is explicit: pgvector so full-text lexical path and vector path share one local DB instead of a dev-only special case.

### Startup cycle — `@Lazy` on ChatModel (`RagService.java:50`, `application-rag.yml:17`)
```
spring.ai.model.chat: deepseek / spring.ai.model.embedding: ollama  (application-rag.yml:17-18)
```

Without explicit `spring.ai.model.chat/embedding`, DeepSeek and Ollama autoconfigs both register a `ChatModel` → `NoUniqueBeanDefinitionException`. Even pinned, `agentToolSet → docsSearchTool → ragService → chatModel → agentToolSet` is a circular ref via tool-callback resolution. `@Lazy` on the `ChatModel` parameter defers the proxy until the cycle settles. The fix is one annotation; the alternative is restructuring tool-callback wiring for no benefit.

### Ordering bug — `LOWEST` vs `HIGHEST` (`RagEvalRunner.java:52`, `DocumentIngestionService.java:89`)
```java
// DocumentIngestionService.java:88
@EventListener(ApplicationReadyEvent.class) @Order(Ordered.HIGHEST_PRECEDENCE)
public void ingestDocuments() { ... } // re-index first

// RagEvalRunner.java:52
@Order(Ordered.LOWEST_PRECEDENCE)
public class RagEvalRunner implements ApplicationListener<ApplicationReadyEvent> { ... } // eval last
```

`ApplicationRunner` fires before `ApplicationReadyEvent` listeners — that is Spring Boot contract, not a bug in Spring. Calling the old runner "the eval harness" while it measured an empty store was the bug in us. Explicit `@Order` on two `ApplicationReadyEvent` listeners is deterministic; relying on "bean registration order if you're lucky" is not.

---

## 7. How to verify — report json, gate threshold

### Report JSON exists and is self-describing
```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
cat target/rag-eval-report.json | jq '{runAt, retrievalMode, topK, goldens, hits, hitRateAtK, top1Accuracy, precisionAtK}'
# {"runAt":"...","retrievalMode":"HYBRID","topK":5,"goldens":12,"hits":9,"hitRateAtK":0.75,"top1Accuracy":0.5,"precisionAtK":0.15}
# spot-check: RagEvalRunner.java:135 writeReport, :140 RagEvalReportSnapshot, :155 createDirectories

jq '.items[] | select(.hit==false) | {question, expectedSource, retrievedSources}' target/rag-eval-report.json
# 3 misses — inspect twins: business/07-... vs interview-cheat-sheets/07-... carry identical content
# Retrieved list contains the twin, not the golden's exact folder — hit is false but answer would be correct
```

### Gate threshold — earned 0.7 passes, 0.9 fails
```bash
# Pass — measured 75% >= 70%:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0.7
# exit 0, report written, log at :107

# Fail — 75% < 90%:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag --app.rag.eval.min-hit-rate=0.9 2>&1 | grep "gate FAILED"
# IllegalStateException: RAG retrieval eval gate FAILED: hitRate@5=75.0% is below the required 90.0%  RagEvalRunner.java:124
# app exits non-zero — deploy blocked, not degraded

# Toggle retrieval-mode and re-measure without code change:
```

### Unit — scoring math without a live DB
```bash
./mvnw test -Dtest=RagRetrievalEvaluatorTest,RagEvalRunnerTest
# RagRetrievalEvaluatorTest: hit true when expected in topK, top1 when first, precision denominator total*k
# RagEvalRunnerTest: report-only (minHitRate 0) never blocks; gate 1.0 blocks; JSON shape matches RagEvalReportSnapshot :79
```

### Infra — pgvector is actually there
```bash
docker compose exec postgres psql -U order -d orderdb -c "\dx" | grep vector
# vector | 0.7.0 | ...

docker compose exec postgres psql -U order -d orderdb -c "\d vector_store"
# id UUID, content TEXT, metadata JSONB, embedding vector(768) — HNSW cosine RagConfig.java:68, ~180 rows (47 docs × chunkSize 800 :48)
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build — ship a gate you can defend.** Do not set `min-hit-rate` until `RagRetrievalEvaluator.java:66` has run with real `nomic-embed-text` over persisted `vector_store`. Run `dense` then `hybrid`, write `target/rag-eval-report.json` (`RagEvalRunner.java:135`), pick threshold one notch under measured (`0.7` under `75%`). Future `chunkSize`/`topK` regressions fail at `:124` not silently. Twins (`business/07-` vs `interview-…/07-`) stay honest — record twin in `retrievedSources`.

- **Operate — own the boot.** `rag` profile now boots: `docker-compose.yml:16 pgvector` + `application-rag.yml:17 chat: deepseek` / `:18 embedding: ollama` + `RagService.java:50 @Lazy` + `@Order` ingest (`DocumentIngestionService.java:89` HIGHEST) before eval (`RagEvalRunner.java:52` LOWEST). `NoUniqueBeanDefinitionException` → pin model; `gate FAILED` → check `target/rag-eval-report.json`; `retrieved []` → check ordering.

- **Interview — whiteboard the earned gate in 90s with receipts.** "PR #44: `RagRetrievalEvaluator.evaluate(retriever,goldens,k)` (`:66`) scores `hitRate@k`/`top1`/`precision@k` against 12 goldens over real `nomic-embed-text` + `pgvector` corpus (`vector_store` HNSW `:68`). `RagEvalRunner` (`:53`) is `ApplicationListener<ApplicationReadyEvent>` at `LOWEST_PRECEDENCE` (`:52`) after `DocumentIngestionService` `HIGHEST` (`:89`) — `ApplicationRunner` was the bug that scored empty store. Logs `[HIT]/[MISS]` (`:113`), writes `RagEvalReportSnapshot` to `target/rag-eval-report.json` (`:135` via `RagProperties.java:135`). `application-rag.yml:57` `min-hit-rate: 0.7` earned from measured hybrid `75%` (dense also `75%`, hybrid+MMR `50%`), so MMR defaults OFF (`RagProperties.java:54`). Fails at `:124` if `hitRate < minHitRate`. Infra `docker-compose.yml:16 pgvector/pg16` + `RagService.java:50 @Lazy` breaks `agentToolSet` cycle."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "You set a quality gate at 0.7 — how do you know 0.7 is not made up?"**

> "It is earned. PR #42's harness was report-only (`RagProperties.java:56` `minHitRate 0.0`) — the only honest default before measurement. PR #44 ran it end-to-end with `nomic-embed-text` over the real persisted `vector_store` (not mocks). `RagRetrievalEvaluator.evaluate(Retriever,goldens,k)` at `RagRetrievalEvaluator.java:66` checks `expectedSource in topK` per golden (`:102`), aggregates `hitRate@k=hits/total` (`:84`), `top1Accuracy` (`:72`), `precision@k=hits/(total*k)` (`:77`). Dense/hybrid `75.0%`, hybrid+MMR `50.0%`; top-1 `41.7%→50.0%`, so `RagProperties.java:54` keeps `mmrEnabled=false`. `min-hit-rate: 0.7` in `application-rag.yml:57` sits one notch under measured `75%` — tight enough to catch regressions, loose enough for embedding noise. Proof is `target/rag-eval-report.json` (`RagEvalRunner.java:135`)."

**Q2: "The eval ran but every question MISSed — what happened?"**

> "Eval before ingest. `RagEvalRunner` was an `ApplicationRunner`; `DocumentIngestionService.ingestDocuments()` at `DocumentIngestionService.java:88` re-indexes on `ApplicationReadyEvent`. Runners fire before ready-event listeners, so the gate scored an empty `vector_store` — `retrieved []` for all 12, `0%` hit-rate. Fix: `RagEvalRunner.java:52` `LOWEST_PRECEDENCE` (`:53`) paired with `DocumentIngestionService.java:89` `HIGHEST`. Verify `grep HIT :112` 9 vs 0 and `jq .hitRateAtK` on `target/rag-eval-report.json` (`:155`)."

**Q3: "RAG won't start — 'required a single bean, but 2 were found' and 'gate FAILED'?"**

> "Two distinct fixes in PR #44. The `2 beans` is `spring.ai.model.chat` unpinned — `DeepSeekChatAutoConfiguration` and `OllamaChatAutoConfiguration` both register a `ChatModel`. `application-rag.yml:17` pins `spring.ai.model.chat: deepseek` and `:18` `spring.ai.model.embedding: ollama`. Even pinned, `agentToolSet→chatModel` cycles, so `RagService.java:50` `@Lazy ChatModel` defers proxy. `gate FAILED` at `RagEvalRunner.java:124` means `min-hit-rate` (`RagProperties.java:126`) above measured — check `target/rag-eval-report.json` (`:135`) `retrievedSources` before editing goldens; `retrieved []` → ordering, missing `vector` → `docker-compose.yml:16`."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #45+ picks up

**What PR #44 alone does NOT do (by design):**

- **Not a hybrid quality change.** Retrieval still `dense` vs `hybrid` (`RagProperties.java:53` / `RagConfig.java:54`) — PR #44 only measures it; advantage here is top-1 not hit-rate.
- **Not a generation eval.** Scores retrieval (`expectedSource in topK`), not `DeepSeek` faithfulness — needs LLM-as-judge harness.
- **Not golden curation.** 12 goldens are smoke set; twins (`business/07-` vs `interview-cheat-sheets/07-`) give strict `MISS` while content present — keep strict match, twin visible in `retrievedSources`.
- **Not multi-topic MMR.** MMR `false` (`:54`) until multi-part goldens earn it (`75%→50%` otherwise).
- **Not a dashboard.** One JSON per boot (`:58` git-ignored) — not a time-series; promote to CI artifact retention.
- **No sub-corpus filtering / chunk-level hash / re-ranker.** File-granular `sha256Hex()` (`:229`) re-embeds whole file; `resource-path` (`:57`) exists but `PgVectorIndexStore.java:52` filters `source` only; no `SemanticReranker`.

**Where it picks up:**

- **PR #45-46** add `stream_docs_search` and MCP `resources/prompts` on the same corpus — eval harness covers them free.
- **PR #43 seam** means `RetrievalEngine.java:17` can take `SemanticReranker`/`QueryRewriter` without `RagService.java:113` changes.
- **Next eval step** — grow goldens to 30-50, add multi-topic queries to re-measure MMR, and fail CI (not just startup) on `hitRate@k < 0.7` via `RagEvalRunnerTest` in the pipeline.

> Next: [`05-rag-productionization.md`](./05-rag-productionization.md) (PR #42) · [`06-hybrid-retrieval.md`](./06-hybrid-retrieval.md) (PR #43).
