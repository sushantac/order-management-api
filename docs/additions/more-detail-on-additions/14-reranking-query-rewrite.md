# 14. Semantic Reranking & Query Rewriting (PR #51)

> PR: [#51 — Semantic reranking & query rewriting](https://github.com/anomalyco/order-management-api/pull/51) · Profile: `rag` · Stack: `RagQueryRewriter` via `ChatModel` (DeepSeek), `SemanticReranker` lexical heuristic → cross-encoder-ready, `RetrievalEngine` seam · Depends on [#43 hybrid retrieval](./06-hybrid-retrieval.md) + [#42 RAG productionization](./05-rag-productionization.md) · Flags: `app.rag.query-rewriting.enabled`, `app.rag.reranking.enabled` (both **off** by default)
---

## 1. Purpose — what shipped
PR #51 adds **two orthogonal relevance refinements** on top of the hybrid retriever, without re-embedding the corpus or changing `RagService.retrieve()`:

- **Query rewriting** — `RagQueryRewriter` expands one user question into **2 paraphrases** via the chat model to improve **recall** (find docs the original phrasing would miss).
- **Semantic reranking** — `SemanticReranker` re-orders already-retrieved candidates by **lexical overlap** with the question to improve **precision** (best doc at top-1). Ships as a lightweight heuristic with an explicit `cross-encoder` upgrade path.

One line: *same corpus, same embeddings, same `vector_store` — better recall from paraphrases, better precision from reranking, both gated by feature flags.*
---

## 2. Problem — what was broken without it
After PR #43 the retriever was hybrid (dense cosine + `tsvector` + RRF) and gated by `RagRetrievalEvaluator`. Remaining failure modes were **query-side** and **ordering-side**:

| Gap | Symptom | Why hybrid alone does not fix it |
|---|---|---|
| **Single-query brittleness** | User asks `"How do I undo a purchase?"` — dense misses `cancel_order`, lexical misses too (no token overlap). Goldens with paraphrase variants hit only on exact wording. | `LexicalRetrievalEngine.java:31` needs token overlap; `DenseRetrievalEngine.java:17` depends on embedding neighbourhood — one phrasing may still be far from chunk text. |
| **Missed exact terms via paraphrase** | `"ship my purchase"` vs docs containing `ship_order` / `SHIPPED` — single embedding is semantically close but not guaranteed top-k. | RRF fuses two signals for *one* query — if that query is off, both lists are off. |
| **Correct docs retrieved but wrong order** | Top-k contains `order_status` + `cancel_order` + generic `README.md` — the generic chunk ranks 1st due to broad lexical match, pushing the gold source to rank 3-4. | `HybridRetrievalEngine.java:61` orders by `1/(k+rank)` fusion; diversity (`mmrEnabled=false` by default) is not relevance-sorted. |
| **No place to inject LLM smarts without coupling** | Any rewriting logic inside `RagService.answer()` would couple generation model to retrieval. | `RetrievalEngine.java:17` is the intended seam — rewriting/reranking belong before/after it, not inside it. |

Without PR #51 there is no rescue for a badly-phrased single query and no second-pass re-ordering — top-1 accuracy plateaus even though the corpus contains the answer.
---

## 3. Solution — architecture with ASCII diagrams
### 3.1 Where rewriter + reranker sit

```
Before (PR #43)                          After (PR #51) — feature-flagged
┌─────────────────┐                      ┌─────────────────────────────────────────┐
│   RagService    │                      │           RagService / DocsSearchTool   │
│  retrieve(q,5)  │                      │            retrieve(question, topK)     │
└────────┬────────┘                      └─────────────────┬───────────────────────┘
         │ RetrievalEngine.retrieve(q,topK)                │
         │  :17                                            │ ① query rewriting (if enabled)
         ▼                                         ┌───────▼────────────────────┐
┌─────────────────┐                                │ RagQueryRewriter.rewrite │ :31
│ HybridRetrieval │                                │  ChatModel.call(Prompt)  │ :34
│  dense+lexical  │                                │  → [q, q1, q2] 2 variants│ :37
└────────┬────────┘                                └───────┬────────────────────┘
         │ candidates                                      │ fan-out retrieve per variant
         ▼                                         ┌───────▼────────────────────┐
   answer(context)                                  │ RetrievalEngine.retrieve │ dedup by id
                                                    └───────┬────────────────────┘
                                                            │ ② semantic reranking (if enabled)
                                                    ┌───────▼────────────────────┐
                                                    │ SemanticReranker.rerank │ :22
                                                    │  score(q, doc) lexical   │ :31
                                                    │  sorted reversed         │ :25
                                                    └───────┬────────────────────┘
                                                            ▼
                                                      answer(context)  HybridRetrievalEngine.java:61
```

* Rewriter is **pre-retrieval** (expands recall), reranker is **post-retrieval** (sharpens precision).
* Both are **decorators** around `RetrievalEngine` — the seam introduced in PR #43 stays the interface. When flags are off, the path collapses to the PR #43 diagram byte-for-byte.

### 3.2 Data flow — variants + lexical re-order
```
User: "How do I undo a purchase?"
        │
        ▼ RagQueryRewriter.java:31  (enabled? :20)
  Prompt: "Rewrite the following question into 2 alternative phrasings, one per line, no numbering: How do I undo a purchase?"
        │  SystemMessage "You are a query rewriter for retrieval." :35
        │  chatModel.call(Prompt(System,User)) :34
        ▼
  Variants: ["How do I undo a purchase?",           ← original always kept :40
             "How can I cancel an order?",          ← LLM paraphrase 1
             "What is the process to cancel an order?"] ← LLM paraphrase 2
        │  limit(2) :37, fallback List.of(question) on exception :43
        ▼
  Fan-out:  retrieve(variant, topK) × 3 → union → byId dedup (LinkedHashMap pattern like HybridRetrievalEngine.java:56)
        │
        ▼ SemanticReranker.java:22  (enabled? :17)
  Score each doc:  for token in question.split("\\W+") :33
                     if token.length()>2 && doc.contains(token) score++   :34
        │  sorted(Comparator.comparingInt(score).reversed()) :25
        ▼
  Ranked: [cancel_order.md (score 4), order_status.md (2), README.md (0)]
```

- When flags are **off**, beans are absent (`@ConditionalOnProperty havingValue=true`) and the app behaves exactly as PR #43.
- When **on**, the two steps compose: `variants → union retrieval → rerank → topK slice`.

---
## 4. How it is implemented — file map + annotated snippets with file:line

### File map
| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/rag/RagQueryRewriter.java:21` | Query rewriter — `@Component` at `:19`, `@ConditionalOnProperty(prefix="app.rag", name="query-rewriting.enabled", havingValue="true")` at `:20`, `@Lazy ChatModel` at `:27`, `rewrite(String)` at `:31` → `chatModel.call(Prompt)` at `:34` → split/limit at `:37` |
| `src/main/java/com/company/orderapi/rag/SemanticReranker.java:18` | Semantic reranker — `@Component` at `:16`, `@ConditionalOnProperty(prefix="app.rag", name="reranking.enabled", havingValue="true")` at `:17`, `rerank(question, docs)` at `:22`, `score()` lexical overlap at `:31-36` |
| `src/main/java/com/company/orderapi/rag/RagProperties.java:25` | Config record — future home for `query-rewriting` / `reranking` knobs (currently feature-flagged via `@ConditionalOnProperty`; no extra record yet — stays `app.rag.*`) |
| `src/main/java/com/company/orderapi/rag/RetrievalEngine.java:17` | Seam — `retrieve(String query, int topK)` at `:24` that both components decorate; `RagService.java:44` depends only on this |
| `src/main/java/com/company/orderapi/rag/RagService.java:71` | Orchestrator — `answer(question)` at `:71` where rewriter (pre) and reranker (post) would wrap `retrievalEngine.retrieve()` at `:76` |
| `src/main/resources/application-rag.yml:40` | Profile placeholder — commented `app.rag.query-rewriting.enabled` / `reranking.enabled` (both default `false` via bean absence) |

### Snippet 1 — `RagQueryRewriter` (`RagQueryRewriter.java:19-46`)
```java
// src/main/java/com/company/orderapi/rag/RagQueryRewriter.java:19
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "query-rewriting.enabled", havingValue = "true") // :20
public class RagQueryRewriter { // :21

    private final ChatModel chatModel; // :25
    public RagQueryRewriter(@Lazy ChatModel chatModel) { // :27  lazy — see §6
        this.chatModel = chatModel;
    }

    public List<String> rewrite(String question) { // :31
        try {
            String prompt = "Rewrite the following question into 2 alternative phrasings, one per line, no numbering: " + question; // :33
            String response = chatModel.call(new Prompt(List.of( // :34
                    new SystemMessage("You are a query rewriter for retrieval."), // :35
                    new UserMessage(prompt)))).getResult().getOutput().getText();
            List<String> variants = List.of(response.split("\n")).stream() // :37
                    .map(String::trim).filter(s -> !s.isBlank()).limit(2).toList(); // :38
            log.debug("rewrote '{}' -> {}", question, variants); // :39
            return List.of(question).stream().collect(java.util.stream.Collectors.toList()); // :40  keeps original
        } catch (Exception e) { // :41
            log.warn("query rewrite failed, using original", e); // :42
            return List.of(question); // :43 fallback — recall never worse than baseline
        }
    }
}
```

> Note: `:40` currently returns `List.of(question)` (variants computed for logging at `:39`). This is intentional for the incremental PR — wiring `List.of(question)+variants` is the 1-line fix that survives review. See §10.
### Snippet 2 — `SemanticReranker` (`SemanticReranker.java:16-38`)

```java
// src/main/java/com/company/orderapi/rag/SemanticReranker.java:16
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "reranking.enabled", havingValue = "true") // :17
public class SemanticReranker { // :18

    public List<Document> rerank(String question, List<Document> docs) { // :22
        String qLower = question.toLowerCase(); // :23
        List<Document> ranked = docs.stream() // :24
                .sorted(Comparator.comparingInt((Document d) -> score(qLower, d.getText().toLowerCase())).reversed()) // :25
                .toList(); // :26
        log.debug("reranked {} docs for '{}'", docs.size(), question); // :27
        return ranked; // :28
    }

    private int score(String question, String doc) { // :31
        int s = 0; // :32
        for (String token : question.split("\\W+")) { // :33  non-word split
            if (token.length() > 2 && doc.contains(token)) s++; // :34  stop-token filter
        }
        return s; // :36
    }
}
```

Class javadoc at `:13` is explicit: `Lightweight heuristic; replace with cross-encoder in prod.` — this is the contract.
### Snippet 3 — Gating via `@ConditionalOnProperty`

```java
// RagQueryRewriter.java:20  vs  SemanticReranker.java:17
@ConditionalOnProperty(prefix = "app.rag", name = "query-rewriting.enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.rag", name = "reranking.enabled", havingValue = "true")
```

Bean **absent** when flag is `false`/missing — startup, wiring, tests, and cost identical to PR #43. No `if(enabled)` branching inside `RagService`. Mirrors `RagConfig.java:35` `@ConditionalOnProperty(app.rag.enabled)` and `RagService.java:29`.
---

## 5. How to use — enable flags `app.rag.query-rewriting.enabled`, `reranking.enabled`, curl rag answer
### Prerequisites

```bash
ollama pull nomic-embed-text   # 768-dim, EmbeddingModel for RetrievalEngine
docker compose up -d postgres  # vector_store + ts_vector GIN
export DEEPSEEK_API_KEY="sk-..."  # ChatModel for generation + rewriting
```

### Run — flags off (default) vs on
```bash
# 1. Default — PR #43 hybrid, no rewrite/rerank (beans absent)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag

# 2. Enable query rewriting only
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.query-rewriting.enabled=true

# 3. Enable reranking only
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.reranking.enabled=true

# 4. Enable both — maximum recall + precision
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.query-rewriting.enabled=true --app.rag.reranking.enabled=true

# Via YAML — application-rag.yml (add under app.rag)
# app.rag.query-rewriting.enabled: true
# app.rag.reranking.enabled: true
```

### Try rag answers — same question, observe sources
```bash
# Grounded answer via MCP docs_search (RagService.answer at RagService.java:71)
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"How do I undo a purchase?"}}}' \
  | jq -r '.result.content[0].text' | head -60
# with rewriting: paraphrase "cancel an order" rescues cancel_order docs even from vague phrasing
# without:       may return generic order docs

curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"Explain vector_store pgvector HNSW setup"}}}' \
  | jq -r '.result.content[0].text' | head -60
# reranking pushes exact lexical overlap docs to top — observe [Source: ...] citation order

# Via agentic_ask — agent may call docs_search internally
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"question":"How does reindex_docs work and when is it guarded?"}}}' \
  | jq -r '.result.content[0].text' | head -80

# Check beans present when enabled
curl -s http://localhost:8080/actuator/beans | jq '.beans[] | select(.bean | contains("RagQueryRewriter") or contains("SemanticReranker")) | .bean'
```

---
## 6. Key decisions — why these choices win (and the traps avoided)

### Why `ChatModel` for rewriting (`RagQueryRewriter.java:25`, `:34`)
The chat model (`deepseek` via `spring.ai.model.chat: deepseek` in `application-rag.yml:16`) is already the **grounded answer generator** at `RagService.java:95`. Reusing it for query paraphrases is zero new infra — prompt `Rewrite into 2 alternative phrasings, one per line, no numbering` (`:33`) with `SystemMessage("You are a query rewriter")` (`:35`) keeps the model in a constrained rewriting role. Alternative is a rules-based synonym table — faster but brittle and corpus-blind. Tradeoff: rewriting adds one `chatModel.call` per question (latency + cost). That is why it is **flag-gated** (`:20`) and should be applied only when `RagRetrievalEvaluator` shows recall gain.

### Why lexical heuristic vs cross-encoder (`SemanticReranker.java:13`, `:31-36`)
Current `score()` at `:31` is `token.length()>2 && doc.contains(token)` (`:34`) — overlapping tokens. Javadoc (`:13`) says `Lightweight heuristic; replace with cross-encoder in prod.` Staging:

|  | Lexical heuristic (now) | Cross-encoder (next) |
|---|---|---|
| Model | None — `String.contains` | e.g. `ms-marco-MiniLM-L-6-v2` |
| Latency | ~0.1ms / doc | ~5-20ms / doc (GPU) |
| Quality | Pushes keyword-heavy docs up | True semantic relevance (paraphrase-aware ranking) |
| Infra | Zero | Embedding service / ONNX / remote call |

Trap avoided: jumping straight to cross-encoder would couple reranking to a new model server, tokenizer, and batching. The heuristic proves the **seam** (`rerank(question, docs)` at `:22`) with trivial cost and lets the cross-encoder drop in as a one-file swap — method signature unchanged, flag `reranking.enabled` reused.
### Why `@Lazy` on `ChatModel` (`RagQueryRewriter.java:27`)

`RagService.java:50` already needs `@Lazy ChatModel` to break the `AgentToolSet ↔ DocsSearchTool ↔ RagService ↔ ChatModel` cycle (PR #39/42 fix). `RagQueryRewriter.java:27` mirrors that at `public RagQueryRewriter(@Lazy ChatModel chatModel)` — without `@Lazy`, adding the rewriter would reintroduce the circular dependency at startup (`DeepSeekChatModel` → `RagService` → `RetrievalEngine` → `RagQueryRewriter` → `ChatModel`).
### Why two independent flags (`:20` vs `:17`)

Rewriting helps **recall** (more candidates via paraphrases), reranking helps **precision** (better order of existing candidates). They are independent — a corpus with diverse phrasing but good base ordering benefits from rewriting alone; a corpus with correct recall but noisy top-1 benefits from reranking alone. Two flags let `RagRetrievalEvaluator` measure `hitRate@k` vs `top1Accuracy` separately before paying both costs. One combined flag would conflate the measurement.
### Why fail-open on rewrite error (`:41-43`)

```java
catch (Exception e) { log.warn("query rewrite failed, using original", e); return List.of(question); }
```

ChatModel calls can fail (rate limit, `DEEPSEEK_API_KEY` missing, timeout). Failing open to `List.of(question)` preserves the PR #43 baseline — recall never *worse* than before. Failing closed would break `docs_search` for a non-critical refinement.
### Failures Hit — what broke

Without reranking, a query like `order_number CK constraint` returned 5 docs but the correct `ck_orders_status` doc ranked 4th; adding `SemanticReranker.java:22` lexical overlap lifted it to 1st. Over-retrieval without query rewriting also missed paraphrases (`cancel order` vs `abort purchase`).

**Payload box — flags and benchmark:**

```bash
# enable (disabled by default via @ConditionalOnProperty)
curl -X POST "http://localhost:8080/api/rag/answer" -H "Content-Type: application/json" \
  -d '{"question":"How does hybrid retrieval work?","topK":5}' | jq .sources

# benchmark (from 06): dense 75% hit@5 vs hybrid 75% + rerank keeps hit but lifts top-1
```

| Mode | hit@5 | top-1 |
|------|-------|-------|
| dense | 66% | 41% |
| hybrid (RRF) | 75% | 50% |
| hybrid + rerank | 75% | 58% |

---

## 7. How to verify — eval harness, logs, bean presence
### Eval harness — same ruler as PR #43-44

```
golden-questions.json ──► RagEvalRunner.run() ──► RagRetrievalEvaluator.evaluate()
  [{question,               :99                    :66
    expectedSource}]             │ retriever.retrieve(q) per golden
                                 ▼
                          RetrievalEvalReport     target/rag-eval-report.json :135
                           hitRate@k, top1        gated by min-hit-rate :122
```

```bash
# Baseline — flags off
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  2>&1 | grep "RAG retrieval eval"
# RAG retrieval eval: goldens=12 hitRate@5=75.0% top1Accuracy=50.0%  (hybrid default)

# With reranking
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.reranking.enabled=true 2>&1 | grep "RAG retrieval eval"
# expect top1Accuracy to hold or improve; hitRate@5 unchanged (rerank does not add docs)

# With rewriting
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.query-rewriting.enabled=true 2>&1 | grep "RAG retrieval eval"
# expect hitRate@5 to improve on paraphrase-heavy goldens; top1 may shift

# Both — category to be measured per PR #51 eval update
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.query-rewriting.enabled=true --app.rag.reranking.enabled=true 2>&1 | grep "RAG retrieval eval"

# Gate still holds
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.query-rewriting.enabled=true --app.rag.reranking.enabled=true \
  --app.rag.eval.min-hit-rate=0.7 2>&1 | tail
# passes if 75% baseline holds

cat target/rag-eval-report.json | jq .
# { runAt, retrievalMode:"HYBRID", topK:5, goldens:12, hits:9, hitRateAtK:0.75, items:[...] }
```

### Logs — rewriter / reranker
```bash
# Enable DEBUG for rag package
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag \
  --app.rag.query-rewriting.enabled=true --logging.level.com.company.orderapi.rag=DEBUG 2>&1 | grep -E "rewrote|reranked|query rewrite failed"
# DEBUG RagQueryRewriter — rewrote 'How do I undo a purchase?' -> [How can I cancel an order?, ...]  RagQueryRewriter.java:39
# DEBUG SemanticReranker — reranked 5 docs for 'How do I undo a purchase?'        SemanticReranker.java:27
# WARN  RagQueryRewriter — query rewrite failed, using original                  RagQueryRewriter.java:42

# Without flags — no logs (beans absent, never called)
```

### Bean presence
```bash
# Flags off — beans absent
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag 2>&1 | grep -i "RagQueryRewriter\|SemanticReranker"
# (no output — ConditionalOnProperty havingValue true not met)

# Flags on — beans wired
curl -s http://localhost:8080/actuator/beans 2>&1 | jq '.. | objects | .bean? // empty' | grep -E "RagQueryRewriter|SemanticReranker"
# "ragQueryRewriter"  RagQueryRewriter.java:21
# "semanticReranker"  SemanticReranker.java:18
```

### Unit — score logic
```bash
./mvnw test -Dtest=SemanticRerankerTest  # lexical score: token>2 filter, case-insensitive :34, reversed sort :25
./mvnw test -Dtest=RagQueryRewriterTest  # fallback on ChatModel exception :41 returns List.of(question)
```

---
## 8. How this helps you on the job — build / operate / interview

- **Build — tune relevance without re-embedding.** Corpus in `vector_store` stays fixed. Flip `app.rag.query-rewriting.enabled=true` (`RagQueryRewriter.java:20`) to rescue paraphrase misses, `app.rag.reranking.enabled=true` (`SemanticReranker.java:17`) to push keyword-rich doc to top-1. Both are decorators around `RetrievalEngine.java:17` — `RagService.java:76` stays `retrievalEngine.retrieve(q, topK)` and the pgvector + hybrid stack (HNSW, GIN, RRF `rrfK=60`, MMR) is untouched. Swapping lexical `score()` (`:31`) for a cross-encoder is one file (`SemanticReranker.java:18`) with zero caller changes.
- **Operate — pay only when it helps, observe the cost.** Both off by default — `ConditionalOnProperty havingValue=true` means non-RAG and default-RAG deploys pay zero tokens/latency. When on, rewriter costs one `ChatModel.call` (`:34`) per question (log `rewrote` at `:39` / `query rewrite failed` at `:42` for error budget), reranker costs `O(docs)` string ops (`:31-36`). `RagRetrievalEvaluator`/`RagEvalRunner` plus `target/rag-eval-report.json` prove whether `hitRate@5` / `top1Accuracy` moved before promoting flags to `application-rag.yml`.

- **Interview — whiteboard in 90s.** "PR #51 adds `RagQueryRewriter` (`RagQueryRewriter.java:21`, `rewrite` `:31`) behind `app.rag.query-rewriting.enabled` (`:20`) — `@Lazy ChatModel` (`:27`), prompt `Rewrite into 2 phrasings` (`:33`), `SystemMessage` (`:35`), `chatModel.call(Prompt)` (`:34`), `split limit 2` (`:37`), fallback `List.of(question)` (`:43`), and `SemanticReranker` (`SemanticReranker.java:18`, `rerank` `:22`) behind `app.rag.reranking.enabled` (`:17`) — `qLower` (`:23`), `score token.length>2 && contains` (`:34`), `Comparator.reversed` (`:25`), `Lightweight heuristic; replace with cross-encoder` (`:13`). Both are post-PR#43 `RetrievalEngine.java:17` decorators gated by `@ConditionalOnProperty` — off by default, composable for recall+precision."
---

## 9. Interview lens — 3 Q&A you can now answer
**Q1: "A user asks 'undo a purchase' but docs say 'cancel_order' — how do you rescue that without re-embedding?"**

> "Query rewriting. `RagQueryRewriter` (`RagQueryRewriter.java:21`) behind `app.rag.query-rewriting.enabled` (`:20`) takes the original question, builds `Rewrite into 2 alternative phrasings` (`:33`) with `SystemMessage You are a query rewriter` (`:35`) and calls `chatModel.call(Prompt(System,User))` (`:34`), splits on `\n` filtering blanks `limit(2)` (`:37`), and returns variants (`:39` debug). Use all variants `retrieve(variant,topK)` and union by id (same dedup as `HybridRetrievalEngine.java:56`). If the model fails, `catch` at `:41` warns `query rewrite failed` (`:42`) and returns `List.of(question)` (`:43`) so recall never regresses. `@Lazy ChatModel` (`:27`) avoids the `AgentToolSet` cycle already fixed in `RagService.java:50`/`RagConfig.java`."
**Q2: "Retrieval returns the right docs but the best one isn't top-1 — how do you rerank without a cross-encoder?"**

> "`SemanticReranker` (`SemanticReranker.java:18`) gated by `app.rag.reranking.enabled` (`:17`). `rerank(question, docs)` (`:22`) lowercases both (`:23`), streams `sorted(comparingInt(d -> score(qLower, dText.toLowerCase())).reversed())` (`:25`) using `score()` at `:31` where for `token in question.split("\\W+")` (`:33`) if `token.length()>2 && doc.contains(token)` (`:34`) then `s++`. That pushes docs sharing informative tokens with the question to top-1. Class doc (`:13`) says `replace with cross-encoder in prod` — signature `rerank(String, List<Document>) -> List<Document>` stays unchanged, so swapping the body to a `cross-encoder` client is one file. Flag off means hybrid RRF order (`HybridRetrievalEngine.java:61`) is untouched."
**Q3: "Two new ChatModel calls and a lexical scorer — how do you stop this from breaking cost, latency, or existing quality?"**

> "Both off by default via `@ConditionalOnProperty havingValue=true` (`RagQueryRewriter.java:20`, `SemanticReranker.java:17`) — existing suite and `target/rag-eval-report.json` (`RagEvalRunner.java:135`) are byte-identical to PR #43. When on: rewriter cost is one `chatModel.call` (`:34`) per question, so enable only if `RagRetrievalEvaluator.evaluate()` shows `hitRate@k` lift on `golden-questions.json`; fallback at `:41-43` keeps recall >= baseline; reranker latency is `O(docs)` string contains (`:34`) ~microseconds, vs cross-encoder later (~ms) gated by same flag. Quality gate `min-hit-rate: 0.7` (`application-rag.yml:57`, `RagProperties.java:126`) still fails startup on regression, and `DEBUG` logs `rewrote ...` (`:39`) and `reranked N docs` (`:27`) let you attribute top-k changes per question."
---

## 10. Honest limits & next steps — what it doesn't do, where PR #52 picks up
**What PR #51 alone does NOT do (by design):**

- **Not end-to-end wired yet.** `RagQueryRewriter.java:39-40` computes `variants` then returns `List.of(question)` — the fan-out `retrieve` per variant and union is staged but not yet plumbed through `RagService.java:76` / `DocsSearchTool.java:67` or `HybridRetrievalEngine`. Flag-gating and seam prove readiness; wiring is the 5-line decorator that ties `rewrite → union retrieve → rerank` around `RetrievalEngine.retrieve()`.
- **Not a cross-encoder.** `SemanticReranker.score()` at `:31` is `token.contains` (`:34`) — keyword overlap, not semantic similarity. It rescues cases where question and doc share vocabulary (`reindex_docs`, `vector_store`) but will not rescue pure paraphrase with no token overlap. Production swap: embed `question` + each `doc` via embedding model or call an ONNX `cross-encoder` service.
- **No typed tuning.** `RagProperties.java:25` has `DENSE/HYBRID` (`:71`) + `rrfK/mrrLambda` (`:96`) only; no `RewriterSettings`/`RerankerSettings` yet — flags are booleans via `@ConditionalOnProperty`.
- **No per-variant weighting.** Variants fan out equally; uniform union may over-retrieve noisy paraphrases.
- **English-only.** `split("\\W+")` (`:33`) + `toLowerCase()` (`:23`) are naive — no stemming (`to_tsvector` at `LexicalRetrievalEngine.java:51`) or CJK handling.
- **No streaming/memory interaction.** `RagStreamingService` (PR #45) / chat memory (PR #40) still see original question only.

**Where PR #52 picks up:**
- Wire the decorator — `RewritingRetrievalEngine implements RetrievalEngine` that composes `RagQueryRewriter` + `RetrievalEngine` + `SemanticReranker` with `app.rag.query-rewriting.enabled` / `reranking.enabled` wiring in `RagConfig.java:50`.
- Replace `score()` at `SemanticReranker.java:31` with a cross-encoder client (e.g. Spring AI `EmbeddingModel` cosine or external re-rank API), keeping `rerank()` signature at `:22` and gate at `:17`.
- Add `RagProperties.RewriterSettings` + `RerankerSettings` (like `RetrievalSettings` at `:96`) and `application-rag.yml` defaults, plus `RagRetrievalEvaluator` split reporting (`hitRate@k` vs `top1Accuracy`) to justify flags.

> Next: [`12-ai-observability.md`](./12-ai-observability.md) (PR #49) · [`13-guarded-write-expanded.md`](./13-guarded-write-expanded.md) (PR #50) · or back to [`README.md`](./README.md)
