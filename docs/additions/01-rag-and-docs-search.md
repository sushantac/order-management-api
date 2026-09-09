# 01. RAG: make the API answer questions about itself (`docs_search`)

> **Bonus PR #38** — follow-up to the MCP work in
> [`../business/11-mcp-ai-integration.md`](../business/11-mcp-ai-integration.md).
> With MCP we let an assistant *call into* the API (read-only tools). RAG turns
> the direction around: the API can now *answer questions* about its own
> documentation using Retrieval-Augmented Generation. This document is the
> complete teaching guide: the concepts, the exact code in this repo, the
> config, how to try it, and the failures we hit so you don't.

---

## 1. The one-paragraph mental model

RAG = **R**etrieval-**A**ugmented **G**eneration. Instead of asking an LLM a
question out of thin air (where it may hallucinate), you first **retrieve the
most relevant pieces of a trusted corpus** (here: this project's own `docs/`
markdown), stuff them into the prompt as ground truth, and only then ask the
LLM to answer. The answer is *grounded*: it cites the actual source file, and
if the corpus has no answer the model says so instead of inventing one.

```
                      INGEST (once, on ApplicationReadyEvent)
                                                     
  docs/*.md ──► read ──► chunk (TokenTextSplitter) ──► embed (Ollama) ──► SimpleVectorStore
                                                                                ▲
                                                                                │ similarity search (top-k)
                                                       ASK                     │
                                                       ────────────────────────┘
  question ──► RagService.answer ──► top-k chunks ──► prompt([Source: x.md] + context)
                                                          │
                                                          ▼ DeepSeek
                                                   grounded answer
                                                          │
                          exposed to AI assistants as read-only MCP tool  docs_search
```

Two models with two jobs:

| Job | Model | Why |
|---|---|---|
| **Embeddings** (turn text into vectors) | **Ollama** `nomic-embed-text` (local, http://localhost:11434) | Free, runs on your machine, no API key |
| **Chat** (write the final answer) | **DeepSeek** `deepseek-v4-flash` (hosted) | Better reasoning/writing than a raw embedding model |

DeepSeek does **not** provide a first-class embeddings API for this use-case,
and Ollama embeddings + a hosted chat model is a cheap, fast split. A local
LLM (llama3, qwen) would work for chat too — swap the DeepSeek starter for an
Ollama chat starter and nothing else changes.

---

## 2. Why RAG here (and what it is not)

The `docs/` folder is the project's brain: 66 markdown files covering every
decision, every concept, every gotcha. Searching it with `grep` works only if
you know the exact phrase. RAG makes it a *question-answering* layer:

- "How does optimistic locking protect stock?" → returns the chunk from
  `business/01-orders.md` and a grounded answer that names the file.
- "What is the GitHub Actions pipeline?" → the GitOps docs chunk.

It is **not** a replacement for the MCP *data* tools:

| Concern | `product_search` / `order_status` (previous PR) | `docs_search` (this PR) |
|---|---|---|
| Data source | live database | static markdown corpus |
| Result | structured, exact facts | a quoted, generated answer |
| PII | none by design | none — the corpus is public docs |
| Wrong-if-stale risk | real-time | ever so slightly — only until re-indexed |

Both are **read-only** and go through the same /mcp endpoint + auth.

---

## 3. What was added (file by file)

### 3.1 Dependencies — `pom.xml`

```xml
<dependencyManagement>                                  <!-- one Spring AI version for all -->
    <artifactId>spring-ai-bom</artifactId><version>1.0.0</version> <type>pom</type> <scope>import</scope>
</dependencyManagement>
<dependency> spring-ai-starter-model-deepseek </dependency>   <!-- chat model -->
<dependency> spring-ai-starter-model-ollama   </dependency>   <!-- embeddings, local -->
<dependency> spring-ai-vector-store          </dependency>   <!-- SimpleVectorStore -->
```

Three learnings baked into the build:

1. **BOM import, no versions.** Like every Spring project here, third-party
   versions are managed centrally; never hard-code a Spring AI version on an
   individual dependency.
2. **`docs/` must become a classpath resource.** The RAG service loads
   `classpath:docs/**/*.md`, but `docs/` is at the project root, not inside
   `src/main/resources`. A `<resources>` entry copies it into the jar under
   `docs/` so tests *and* a packaged run can resolve it:
   ```xml
   <resources>
       <resource><directory>src/main/resources</directory></resource>
       <resource><directory>docs</directory><targetPath>docs</targetPath></resource>
   </resources>
   ```
3. **Mockito cannot mock on Java 25 out of the box.** Unless you add
   `-Dnet.bytebuddy.experimental=true` for the test JVM, every `mock(...)`
   throws *"Java 25 is not supported by the current version of Byte Buddy"*:
   ```xml
   <maven-surefire-plugin>
       <configuration><argLine>-Dnet.bytebuddy.experimental=true</argLine></configuration>
   </maven-surefire-plugin>
   ```
   The repo builds with `maven.compiler.release=21` (bytecode stays 21), but
   the local JDK is 25 — the JVM runs the mocks, so the *test JVM* needs the flag.

### 3.2 Configuration

**`src/main/resources/application.yml`** — the *base* Spring AI config
(activated only when RAG is on):

```yaml
spring.ai.deepseek.base-url: https://api.deepseek.com
spring.ai.deepseek.api-key:  ${DEEPSEEK_API_KEY:}     # empty by default → safe to boot without it
spring.ai.deepseek.chat.model: deepseek-v4-flash
spring.ai.deepseek.chat.temperature: 0.3
spring.ai.ollama.base-url: http://localhost:11434
spring.ai.ollama.embedding.model: nomic-embed-text
spring.ai.ollama.embedding.enabled: true
```

**`src/main/resources/application-rag.yml`** — the `rag` **profile** that turns
everything on. Activating the profile is the explicit "I know what I'm doing,
start the AI stack" step, so the default `no-profile` run needs no Ollama, no
API key, and no vector store:

```yaml
spring.ai.deepseek.api-key: ${DEEPSEEK_API_KEY:?Set DEEPSEEK_API_KEY env var}  # profile = hard require it
app.rag.enabled: true
app.rag.docs-location: classpath:docs/**/*.md
app.rag.chunk-size: 800
app.rag.chunk-overlap: 200
app.rag.top-k: 5
```

### 3.3 `rag/RagProperties.java` — typed config

A plain `@ConfigurationProperties(prefix = "app.rag")` record. Records are the
idiomatic Spring Boot 3 way for config carriers — immutable, valid constructor
defaults, zero boilerplate:

```java
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean enabled, String docsLocation,
        int chunkSize,    int chunkOverlap, int topK) {
    public RagProperties {
        if (chunkSize  <= 0) chunkSize  = 800;
        if (chunkOverlap < 0) chunkOverlap = 200;
        if (topK       <= 0) topK       = 5;
    }
}
```

### 3.4 `rag/RagConfig.java` — the vector store bean

`@ConditionalOnProperty("app.rag.enabled=true")` means **none of these beans
exist unless the profile/flag is on** — the rest of the app is untouched.

```java
@Bean
VectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

`SimpleVectorStore` is Spring AI's **in-memory** vector store — a list of
`{text, vector, metadata}` rows with cosine similarity. Perfect for a
documentation corpus. In production with a huge corpus you'd swap to
Postgres-PGVector / Milvus / Redis — the `VectorStore` interface is the same.

### 3.5 `rag/DocumentIngestionService.java` — the ingest half

Runs on `ApplicationReadyEvent` (once the HTTP server is up), guarded by a
`volatile boolean indexed` so it only ever fires once:

1. `resourceResolver.getResources("classpath:docs/**/*.md")` → every markdown file.
2. Each file → a Spring AI `Document` with metadata (`source` = filename,
   `resource-path`). The **source filename is the attribution** that later
   appears in prompts and answers.
3. `TokenTextSplitter(800, 200, 10, 10000, true)` chunks each file into
   overlapping ~800-token windows. Overlap keeps a concept that straddles a
   chunk boundary from being lost when retrieval picks just one window.
4. `vectorStore.add(allChunks)` — the `EmbeddingModel` is called internally
   for every chunk; this is why a ready Ollama is a prerequisite.
5. `isIndexed()` flips true; the MCP tool refuses to answer before that.

The splitter's 4th/5th args (min token count / max tokens) are Spring AI's
progress+guards; the practical knobs you tune are `chunk-size` and
`chunk-overlap`.

### 3.6 `rag/RagService.java` — the ask half

`answer(question)`:

1. `vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(k).build())`
   — cosine-similarity over the corpus, return the top-k chunks.
2. If nothing matches → honest "No relevant documentation found" (
   **no LLM call at all** — saves money and never hallucinates).
3. Otherwise join the chunks as `[Source: <file>]` blocks, wrap them in a
   system prompt:
   ```
   You are a helpful assistant for the Order Management API project.
   Answer the user's question using ONLY the provided documentation context.
   If the context does not contain enough information to answer, say so clearly.
   ...
   [Source: business/01-orders.md]
   ...chunk text...
   ```
4. `chatModel.call(new Prompt(List.of(system, user)))` → DeepSeek writes the
   answer, *grounded in the provided chunks* because the prompt says the chunks
   are the only allowed knowledge source.

**Why `SearchRequest.builder()` and not `SearchRequest.query()`?** Spring AI
1.0.0's fluent builder exposes `.query(...)` on the **`Builder`**, not as a
static factory. The current-API incantation is
`builder().query(...).topK(...).build()` (see §5, gotcha #3).

### 3.7 `mcp/DocsSearchTool.java` — the MCP tool

A `@Component @ConditionalOnBean(RagService.class)` extending
`AbstractMcpReadOnlyTool` — the same base class as the other tools, so it is
**auto-discovered**: `McpServerConfiguration` collects every
`List<AbstractMcpReadOnlyTool>` bean, sorts them by name, and registers each
one on the `/mcp` server. No routing code, no new endpoint.

- `name()` → `docs_search`
- `inputSchema()` → one required `question` string
- `run()` → blank-check the question → wait for `isIndexed()` → `ragService.answer(...)`

Because the base class is *read-only*, the tool is structurally unable to write
anything, exactly like `product_search`/`order_status`.

---

## 4. Running it

```bash
# 0. Prerequisites
brew install ollama && ollama serve &          # local embeddings
ollama pull nomic-embed-text                   # the embedding model
export DEEPSEEK_API_KEY=sk-...                 # hosted chat model

# 1. Start with the RAG profile
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# (or: SPRING_PROFILES_ACTIVE=rag ./mvnw spring-boot:run)

# 2. Ask it something, the MCP way
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"docs_search","arguments":{"question":"How does optimistic locking work?"}}}'
```

On startup you should see `RAG: ingested N chunks from documentation (Xms)`
(≈118 chunks from the current corpus). Without `app.security.enabled=false`,
the `/mcp` calls need the usual bearer JWT / `X-API-Key`.

---

## 5. Failures we hit (the actual learning)

1. **Every `@SpringBootTest` started failing with *"DeepSeek API key must be
   set"*.** Adding the DeepSeek starter makes its auto-configuration eager: the
   `deepSeekChatModel` bean is created during bootstrap, and with no API key
   it throws `IllegalArgumentException` — so **no test context could load at
   all**, not just the AI tests. Fix: opt out of AI auto-configuration in every
   test with `spring.ai.model.chat=none` + `spring.ai.model.embedding=none` in
   `@TestPropertySource` (all 36 `@SpringBootTest` classes). This is why the
   *profile* keeps RAG opt-in — the default app never instantiates the model
   beans, but the *auto-configuration* still would unless told not to.
2. **A `src/test/resources/application.yml` silently broke Liquibase.** The
   test classpath shadows the main resources, so a "just add one file" test
   config replaced the *entire* main `application.yml` — losing
   `spring.liquibase.change-log: .../db.changelog-master.xml` and causing
   *"classpath:/db/changelog/db.changelog-master.yaml does not exist"* in every
   test. Cheap lesson: **never shadow main config with a test config unless you
   replicate it fully**; use `@TestPropertySource` per class instead.
3. **Spring AI 1.0.0 API differs from most tutorials.** The methods we
   discovered with `javap`:
   - `SearchRequest.builder().query(q).topK(k).build()` (no static `.query()`),
   - `SimpleVectorStore.builder(embeddingModel).build()` (constructor is gone),
   - `Generation` lives in `org.springframework.ai.chat.model`, not
     `org.springframework.ai.chatGeneration`.
   Always verify the actual jar (`javap -public <class>`) before writing code
   against a new library version.
4. **Mockito/ByteBuddy vs Java 25** — see §3.1 #3. Symptom is a *test* failure
   even though main code compiles; fix is JVM-wide `net.bytebuddy.experimental=true`.
5. **`ResourcePatternResolver` is not injectable.** Spring only registers
   `ResourceLoader` as a resolvable dependency. Inject `ResourceLoader` and
   build your own `new PathMatchingResourcePatternResolver(resourceLoader)`.
6. **`docs/` is not on the classpath** — see §3.1 #2. `classpath:` patterns
   only work for resources that reach the jar.

---

## 6. Tests

Both test classes are **pure unit tests** (no Spring context, no Ollama, no
network): `VectorStore` and `ChatModel` are Mockito mocks, but
`RagService`/`DocumentIngestionService` are real and read the *real* corpus
from the classpath (which the pom `<resources>` change guarantees).

`DocumentIngestionServiceTest` (4 tests) — `rag/DocumentIngestionServiceTest.java`
- loads markdown from `classpath:docs/**/*.md` and chunks it,
- every chunk carries a `source` metadata key ending in `.md`,
- `ingestDocuments()` calls `vectorStore.add(...)` once and flips `isIndexed()`,
- ingest is idempotent (the `volatile boolean` guards a second add — verified
  with `verify(vectorStore, times(1)).add(...)`).

`RagServiceTest` (4 tests) — `rag/RagServiceTest.java`
- `answer()` retrieves chunks and returns the LLM response,
- no chunks found → helpful message and the LLM is **never** called,
- `retrieve()` returns the vector-store documents,
- the generated prompt includes source attribution
  (`contains("Source: 11-mcp-ai-integration.md")`) — this proves grounding.

Full suite: **135 tests, all green** (127 pre-existing + 8 new).

---

## 7. Decisions and honest limits

- **In-memory vector store.** 66 files / ≈118 chunks is tiny; `SimpleVectorStore`
  is right-sized. Scale path: PGVector or Redis with the identical
  `VectorStore` interface + a re-index job.
- **Docs indexed once at startup.** No watcher yet. After editing `docs/`,
  re-run (or call `ingestionService.reindex()`). A `FileSystemWatcher` or a
  tiny admin trigger is the obvious next increment.
- **Embeddings are local, chat is hosted.** Best balance of free/fast/quality
  today; DeepSeek has no embeddings API, Ollama has a perfectly good chat model
  if you want 100% local.
- **No write tools, as before.** `docs_search` answers; it cannot change docs,
  orders, or anything else.
- **The answer is only as good as the retrieval.** If `top-k` is too small or
  the embedding model is weak, the right chunk never reaches the prompt. That
  is why `searchRequest.topK(...)` is config-driven (`app.rag.top-k`).
- **Cost/shape of the corpus matters.** The system prompt says "use ONLY the
  context"; for facts not in the corpus the tool should say "not found", and
  `answerReturnsHelpfulMessageWhenNoChunksFound` locks that behaviour in.

---

## 8. Interview highlights

- "Explain RAG in one breath." → *Retrieve the most relevant chunks of a
  trusted corpus, paste them into the prompt as context, then generate a
  grounded answer.*
- "Why not just feed the whole corpus to the model?" → context-window cost and
  confusion; retrieval makes the prompt small, cheap and precise.
- "What are embeddings?" → numbers representing meaning, positioned so similar
  text is nearby; similarity search = nearest neighbours.
- "Why chunk with overlap?" → a concept straddling a boundary stays recoverable
  from either side; chunk size ≈ retrieval granularity.
- "Why a vector store at all?" → lexical `WHERE text LIKE '%locking%'` misses
  "concurrency guard"; embeddings answer the *meaning*, not the exact words.
- "Grounding / hallucination control." → the prompt forbids using knowledge
  outside the cited chunks and `[Source: ...]` attribution makes answers
  checkable; "no chunks → no LLM call" stops invented answers at the cheapest stage.
- "Opt-in profile + conditionals." → default context is unchanged; only
  `app.rag.enabled=true` (or the `rag` profile) instantiates AI beans. But
  auto-configuration itself must be disabled in tests via
  `spring.ai.model.*=none` — a subtle but very common Spring AI pitfall.
- "Are the model beans safe to have on the classpath silently?" → No: that's
  gotcha #1. Always gate tests.

## 9. Apply this at work

1. Gate AI features behind a flag/profile AND disable Spring AI autoconfig in
   tests; don't assume tests "just don't use it" — the beans are eager.
2. Never shadow main `application.yml` with a minimal test copy; prefer
   `@TestPropertySource`.
3. For a docs-QA feature, index-on-startup is fine; add a re-index trigger
   early and make retrieval configurable (`top-k`, chunk size).
4. Attribute sources in every answer — `[Source: file]` turns "AI magic" into
   a link you can verify, and unit-test that the prompt actually contains it.
5. Verify library APIs against the jar before writing code; Spring AI moved to
   builder-based `VectorStore`/`SearchRequest` in 1.0.0.

---

## 10. Related reading

- [`../business/11-mcp-ai-integration.md`](../business/11-mcp-ai-integration.md) — MCP, the transport, the other read-only tools.
- [`../design/04-security-and-privacy-design.md`](../design/04-security-and-privacy-design.md) — why every surface here stays read-only and PII-free.
- [`../learnings/README.md`](../learnings/README.md) — the 35-PR syllabus this builds on.