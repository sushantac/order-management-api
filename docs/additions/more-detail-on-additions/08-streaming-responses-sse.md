# 08. Streaming Responses — SseEmitter + Virtual Threads (PR #45)

> PR: [#45 — Streaming responses — SseEmitter + virtual threads bridging Flux](https://github.com/anomalyco/order-management-api/pull/45) · Profile: `rag` (`app.rag.enabled=true`) · Stack: Spring MVC `SseEmitter` + `Flux<String>` (Spring AI `ChatModel.stream`) + `Executors.newVirtualThreadPerTaskExecutor()` + `blockLast()` · Depends on [#39 agentic tool calling](./02-agentic-tool-calling.md) + [#40 chat memory](./03-chat-memory.md)

---

## 1. Purpose — what shipped

PR #45 delivers LLM answers **token-by-token**. Before, `RagService.answer()` (`src/main/java/com/company/orderapi/rag/RagService.java:71`) and `AgentService.ask()` (`src/main/java/com/company/orderapi/agent/AgentService.java:84`) block on `call().content()` — 4s generation shows nothing for 4s.

After it, two streaming surfaces ship on the same retrieval + agent stack:

- **`GET /api/v1/stream/rag/answer?question=...`** — `RagStreamingController.streamRagAnswer()` at `src/main/java/com/company/orderapi/api/rest/controller/RagStreamingController.java:81` — RAG-only, synchronous retrieval then token stream with `sources` metadata first.
- **`POST /api/v1/stream/agent/ask?task=...&conversationId=...`** — `AgentStreamingController.streamAgentAsk()` at `src/main/java/com/company/orderapi/api/rest/controller/AgentStreamingController.java:65` — agentic loop (tools sync) then tokens, same `conversationId` memory (PR #40).

Both return `text/event-stream` as `SseEmitter` events `sources`/`token`/`done`/`error`. `RagStreamingService.answerStream()` (`src/main/java/com/company/orderapi/rag/RagStreamingService.java:77`) and `AgentService.askStream()` (`src/main/java/com/company/orderapi/agent/AgentService.java:117`) return `Flux<String>` via `chatModel.stream` / `stream().content()`, bridged on virtual threads so Tomcat never blocks.

---

## 2. Problem — blocking vs non-blocking (why streaming matters)

**Before PR #45** every AI answer was blocking:

| Before (blocking `call()`) | After (streaming `stream()` + SSE) |
|---|---|
| `RagService.java:71` `chatModel.call(prompt)` / `AgentService.java:94` `call().content()` — client waits for full generation (often 2-8s) with zero feedback | `RagStreamingService.java:93` `chatModel.stream(prompt)` / `AgentService.java:130` `stream().content()` — `Flux<String>` emits token strings as DeepSeek generates them |
| UX feels frozen; timeouts look identical to slow generation; user cannot tell if work started | First `token` event arrives in ~100-300ms; `sources` event (RAG path) shows grounding before any token; `done` closes cleanly |
| No incremental rendering — frontend must buffer full answer before display | Frontend appends `token` `data: {"text":"..."}` chunks incrementally — ChatGPT-style typing effect with plain `EventSource` / `fetch` + `text/event-stream` parsing |
| Only error signal is HTTP 500 after full wait | Mid-stream `error` event (`RagStreamingController.java:124`, `AgentStreamingController.java:91`) surfaces failures immediately without killing prior tokens |

A 4-tool chain blocks longest when most useful. Fix is delivery shape: return what you have immediately on the existing servlet stack.

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 Servlet → Flux bridge (the core pattern)

```
Spring MVC (servlet, blocking)              Spring AI (reactive)
─────────────────────────────               ─────────────────────
Client                                    DeepSeek (streaming)
  │  GET /api/v1/stream/rag/answer            │
  │  Accept: text/event-stream                │
  ▼                                           │
┌──────────────────────────┐   Flux<String>   ┌──────────────────┐
│ RagStreamingController   │  chatModel.stream│ RagStreamingService│
│  SseEmitter(300s) :84    │◄─────────────────┤  answerStream() :77│
│  STREAMING_EXECUTOR      │  .map(text) :94  │  retrieve topK :78 │
│  .submit(virtual thread) │  .filter(empty)  │  buildContext :85  │
│  .blockLast() :138       │  .concat(done)   │  Prompt sys+user:87│
└──────────┬───────────────┘                  └──────────────────┘
           │ SseEmitter.event()
           │  name("sources") :102 ─── first event, grounded
           │  name("token")   :112 ─── per-token JSON {"text":"..."}
           │  name("done")    :133 ─── completion signal
           │  name("error")   :124 ─── on failure
           ▼
     EventSource / curl -N
```

### 3.2 SseEmitter + virtual threads (why it scales)

```
Tomcat NIO thread (platform, scarce)        Virtual-thread executor (cheap, per-task)
────────────────────────────────────        ──────────────────────────────────────────
  │  controller returns SseEmitter immediately       │
  │  (no blocking — request thread released)         │
  │───────────────────────────────────────────────▶│  STREAMING_EXECUTOR.submit(() -> {
  │                                                │    ragStreamingService.answerStream(q)
  │                                                │      .doOnNext(token -> emitter.send(token))
  │                                                │      .doOnError(e -> emitter.send(error))
  │                                                │      .doFinally(s -> { emitter.send(done); emitter.complete(); })
  │                                                │      .blockLast(); // blocks VIRTUAL thread, not Tomcat thread
  │                                                │  })
  │◀─ SseEmitter streams events ───────────────────│

Executors.newVirtualThreadPerTaskExecutor()  RagStreamingController.java:56 / AgentStreamingController.java:46
  → one virtual thread per stream, parked on blockLast, costs ~KB not MB
  → Tomcat thread pool (default 200) never holds a 5-minute SSE connection
```

### 3.3 Completion signal — empty-string sentinel + `concatWith`

```
  chatModel.stream(prompt)                chatClient.prompt().stream().content()
       │ Flux<ChatResponse>              │ Flux<String>
       ▼                                 ▼
  .map(r -> r.getResult().getOutput().getText())   // unwrap :94
  .filter(text -> !text.isEmpty())                 // drop nulls/empties :98 / :132
  .concatWith(Flux.just(""))          // ← sentinel: empty string = "stream ended" :99 / :133
       │
  Controller .doOnNext(token -> {
       if (!token.isEmpty()) emitter.send(token :112) // real tokens
       // empty token is swallowed — never sent, but drives doFinally → done event
  })
  .doFinally(signal -> emitter.send(done).complete() :131)
  .blockLast() :138 / :105            // virtual thread blocks until Flux terminates
```

`""` is never a real token (filtered), so it is an unambiguous completion marker without a separate type.

---
## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/rag/RagStreamingService.java:36` | **RAG stream service** — `answerStream(question)` `:77` retrieves `topK` (`:78`), builds context (`:85`), calls `chatModel.stream(prompt)` (`:93`) → `Flux<String>` tokens + `""` sentinel (`:99`) |
| `src/main/java/com/company/orderapi/agent/AgentService.java:117` | **Agent stream** — `askStream(task, conversationId)` `:117` validates blank → `Flux.error` (`:119`), routes `conversationId` via `AdvisorSpec.param` (`:128`), `chatClient.prompt().stream().content()` (`:130`) → filtered + sentinel (`:133`) |
| `src/main/java/com/company/orderapi/api/rest/controller/RagStreamingController.java:51` | **RAG SSE controller** — `GET /api/v1/stream/rag/answer` (`:79`) gated `@ConditionalOnBean(RagStreamingService.class)` (`:49`); virtual executor (`:56`), `sources` first (`:102`), `token` loop (`:112`), `done`/`error` (`:133`/`124`), `blockLast()` (`:138`) |
| `src/main/java/com/company/orderapi/api/rest/controller/AgentStreamingController.java:41` | **Agent SSE controller** — `POST /api/v1/stream/agent/ask` (`:63`) gated `@ConditionalOnBean(AgentService.class)` (`:39`); same executor (`:46`), `token`/`done`/`error`, `blockLast()` (`:105`) |
| `src/main/java/com/company/orderapi/rag/RagService.java:50` | **Non-streaming RAG** — `answer()` still exists for non-SSE callers; streaming service shares `buildContext` logic |
| `src/main/java/com/company/orderapi/agent/AgentConfig.java:28` | **Agent wiring** — `agentChatMemory` + `agentToolCallbacks` beans; `AgentService` consumes both |
| `src/test/java/com/company/orderapi/api/rest/controller/StreamingControllerTest.java:32` | **SSE contract tests** — `MockMvc` standalone + mocked services, `asyncStarted()` + `TEXT_EVENT_STREAM` assertions |
| `pom.xml:330` | **Test dep** — `reactor-test` (`StepVerifier`) for Flux assertions; `reactor-core` transitive from Spring AI |

### Snippet 1 — `RagStreamingService.answerStream()` (`RagStreamingService.java:77-101`)

```java
// src/main/java/com/company/orderapi/rag/RagStreamingService.java:77
public Flux<String> answerStream(String question) {
    List<Document> relevantDocs = retrievalEngine.retrieve(question, ragProperties.topK()); // :78
    if (relevantDocs.isEmpty()) // :80
        return Flux.just("No relevant documentation found..."); // :81 short-circuit
    String context = buildContext(relevantDocs); // :85
    String systemMessageText = SYSTEM_PROMPT.formatted(context); // :86
    Prompt prompt = new Prompt(List.of(new SystemMessage(systemMessageText), new UserMessage(question))); // :87
    return chatModel.stream(prompt) // :93
            .map(response -> { String text = response.getResult().getOutput().getText(); // :95
                               return text != null ? text : ""; }) // :96
            .filter(text -> !text.isEmpty()) // :98  drop nulls
            .concatWith(Flux.just(""))  // :99 completion sentinel
            .doOnComplete(() -> log.debug("RAG stream complete: question='{}'", question)); // :100
}
```

### Snippet 2 — `AgentService.askStream()` (`AgentService.java:117-136`)

```java
// src/main/java/com/company/orderapi/agent/AgentService.java:117
public Flux<String> askStream(String task, String conversationId) {
    if (task == null || task.isBlank()) // :118
        return Flux.error(new IllegalArgumentException("task must not be blank")); // :119 reactive error
    String effectiveConversationId = (conversationId == null || conversationId.isBlank())
            ? UUID.randomUUID().toString() : conversationId.trim(); // :121 stateless-by-default (PR #40)
    log.debug("agent stream: conversation={}, task='{}'", effectiveConversationId, task); // :125
    return chatClient.prompt() // :127
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, effectiveConversationId)) // :128 memory routing
            .user(task).stream().content()  // :130 Flux<String> — Spring AI drives tool loop, then tokens
            .filter(text -> text != null && !text.isEmpty()) // :132
            .concatWith(Flux.just(""))  // :133 sentinel
            .doOnComplete(() -> log.debug("agent stream complete: conversation={}, task='{}'", // :134
                    effectiveConversationId, task));
}
```

Same `AdvisorSpec.param` as `ask()` (`:92`) — blank task → `Flux.error` → controller `error` event.

### Snippet 3 — `RagStreamingController` bridge (`RagStreamingController.java:56-149`)

```java
// src/main/java/com/company/orderapi/api/rest/controller/RagStreamingController.java:56
private static final ExecutorService STREAMING_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor(); // :56
// src/main/java/com/company/orderapi/api/rest/controller/RagStreamingController.java:81
@GetMapping(value = "/rag/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE) // :79
public SseEmitter streamRagAnswer(@RequestParam String question) { // :82
    SseEmitter emitter = new SseEmitter(300_000L); // :84 5-min timeout
    if (!ingestionService.isIndexed()) ingestionService.ingestDocuments(); // :87 ensure indexed
    List<Document> chunks = ragService.retrieve(question); // :92 sync retrieval
    STREAMING_EXECUTOR.submit(() -> { // :94 virtual thread — Tomcat thread returns immediately
        List<Map<String,String>> sources = chunks.stream().map(doc -> Map.of( // :97
            "source", String.valueOf(doc.getMetadata().getOrDefault("source","unknown")),
            "text", truncate(doc.getText(),200))).toList(); // :100
        emitter.send(SseEmitter.event().name("sources").data(objectMapper.writeValueAsString(sources))); // :102
        ragStreamingService.answerStream(question) // :108
            .doOnNext(token -> { if (!token.isEmpty()) // :111 sentinel swallowed
                emitter.send(SseEmitter.event().name("token").data(Map.of("text", token))); }) // :112
            .doOnError(e -> emitter.send(SseEmitter.event().name("error") // :124
                .data(Map.of("message", e.getMessage()!=null?e.getMessage():"Unknown error"))))
            .doFinally(signal -> { emitter.send(SseEmitter.event().name("done").data("")); // :133
                                   emitter.complete(); }) // :134
            .blockLast(); // :138 blocks virtual thread until done
    });
    return emitter; // returned immediately — servlet thread not blocked
}
```

`AgentStreamingController.java:46-105` same shape without `sources`; both map `IOException` → `RuntimeException("Client disconnected")` (`:118`).

### Snippet 4 — Contract test (`StreamingControllerTest.java:58-82`)

```java
// src/test/java/com/company/orderapi/api/rest/controller/StreamingControllerTest.java:58
@Test void ragStreamEndpointReturnsSseContentType() throws Exception {
    when(ragService.retrieve(any())).thenReturn(List.of(new Document("Orders use locking.", Map.of("source","test.md"))));
    when(ragStreamingService.answerStream(any())).thenReturn(Flux.just("Answer"," text","")); // :62 sentinel
    ragMockMvc.perform(get("/api/v1/stream/rag/answer").param("question","How does locking work?")
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(request().asyncStarted()).andExpect(status().isOk()); // :67 SSE is async
}
// src/test/java/com/company/orderapi/api/rest/controller/StreamingControllerTest.java:72
@Test void agentStreamEndpointReturnsSseContentType() throws Exception {
    when(agentService.askStream(any(),any())).thenReturn(Flux.just("Agent ","response","")); // :74
    agentMockMvc.perform(post("/api/v1/stream/agent/ask").param("task","Check order status")
            .param("conversationId","conv-1").accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(request().asyncStarted()).andExpect(status().isOk());
}
```

`MockMvc` standalone proves `TEXT_EVENT_STREAM` + `asyncStarted` without a real model.

---

## 5. How to use — curl and event shapes

### Prerequisites

```bash
ollama pull nomic-embed-text          # 768-dim embeddings (PR #38)
docker compose up -d postgres         # pgvector/pgvector:pg16
export DEEPSEEK_API_KEY="sk-..."      # https://platform.deepseek.com — needed for ChatModel
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# wait for: RAG: indexed 47 docs ... + MCP server on /mcp
```

Without `rag` profile → 404 (`@ConditionalOnBean` `:49`/`:39`).

### RAG stream — `GET /api/v1/stream/rag/answer?question=...`

```bash
# Streaming — -N disables curl buffering so tokens appear as they arrive
curl -N "http://localhost:8080/api/v1/stream/rag/answer?question=How%20does%20hybrid%20retrieval%20work%3F" \
  -H "Accept: text/event-stream"

# events: sources -> token* -> done
```

Parse with `EventSource` in the browser or `fetch` + `ReadableStream` — each `data:` line is JSON.

### Agent stream — `POST /api/v1/stream/agent/ask?task=...&conversationId=...`

```bash
# Stateless
curl -N -X POST "http://localhost:8080/api/v1/stream/agent/ask?task=Find%20products%20matching%20widget" \
  -H "Accept: text/event-stream"
# events: token* -> done (no sources)

curl -N -X POST "http://localhost:8080/api/v1/stream/agent/ask?task=How%20many%20widgets%20are%20in%20stock%3F&conversationId=demo-42" \
  -H "Accept: text/event-stream"
# follow-up with same conversationId resolves memory (PR #40)
```

### Event shapes (contract)

| Event `name` | `data` JSON | When | Example |
|---|---|---|---|
| `sources` | `[{"source":"...","text":"...200c..."}]` | Once, first — RAG only (`RagStreamingController.java:102`) | `data: [{"source":"docs/.../06-hybrid-retrieval.md","text":"Hybrid retrieval..."}]` |
| `token` | `{"text":"..."}` | Per generation chunk (`:112` / `AgentStreamingController.java:80`) | `data: {"text":" retrieval"}` |
| `done` | `""` (empty string) | Once, terminal — `doFinally` (`:133` / `:100`) | `event: done\ndata:` |
| `error` | `{"message":"..."}` | On failure — `doOnError` (`:124` / `:91`) + outer `catch` (`:142` / `:109`) | `data: {"message":"Stream failed: ..."}` |

`token` JSON via `Map.of("text", token)` → Jackson; `sources` truncated 200c (`:154`).

---

## 6. Key decisions — why these choices win (and the traps avoided)

### Why not WebFlux (`Mono`/`Flux` controller return) — stay on Spring MVC

The app is `spring-boot-starter-web` (servlet, Tomcat), not WebFlux. Returning `Flux` directly needs a reactive runtime — either dual stacks or a full migration. `SseEmitter` is the servlet-idiomatic primitive: return it synchronously, write from another thread, let Tomcat NIO hold the connection. `RagStreamingController.java:49` stays plain `@RestController`; the only reactive import is `Flux` from `ChatModel.stream`, contained inside `blockLast()`.

### Why virtual threads (`newVirtualThreadPerTaskExecutor`) not platform pool

`blockLast()` parks the caller until `Flux` terminates. On a platform thread that pins a Tomcat thread (200 cap) for up to 5 minutes (`SseEmitter(300_000L)` `:84`). Virtual threads (`newVirtualThreadPerTaskExecutor()` `:56`, Java 21 `pom.xml:54`) cost KB and release the carrier while parked — thousands of streams without pool tuning. `Schedulers.boundedElastic()` would also work but needs sizing; virtual threads are the Java-21-native choice.

### Why `blockLast()` and not `subscribe()` with callbacks

`subscribe()` needs manual lifecycle — unsubscription, error propagation, `complete()` guarantees. `blockLast()` on a virtual thread is a simple blocking call (`RagStreamingController.java:138-147`) — cheap to park, no callback pyramid. `doFinally` still guarantees `done` + `complete()` on cancellation.

### Why empty-string sentinel (`concatWith(Flux.just(""))`) over a wrapper type

Alternatives like `Flux<StreamEvent>` or `done` flag need a wrapper type. The sentinel appends `""` after filtering (`RagStreamingService.java:99`); controller swallows it (`:111`) and `doFinally` emits `done`. No new type, `Flux<String>` stays plain strings, and `StepVerifier` is trivial: `expectNext("Hello").expectNext("").verifyComplete()`.

### Why `sources` event first (RAG only)

Client renders citations before any token — `RagStreamingController.java:97-104` sends `sources` (200-char truncate) before subscribing, so grounding appears in ms even if the model is slow. Agent skips `sources` (0-N tools, no single set).

### Failures Hit — what broke

During PR #45 the bridge first used `subscribe()` with callbacks — on error the `SseEmitter` leaked (no `complete()` guarantee) and the sentinel was initially `Flux.just("DONE")` which could collide with a real token. Switching to `blockLast()` on virtual threads (`RagStreamingController.java:138`) and an empty-string sentinel (`RagStreamingService.java:99` `concatWith(Flux.just(""))` filtered via `!text.isEmpty()`) fixed both: lifecycle is guaranteed by `doFinally` and completion is unambiguous.

**Payload box — SSE event wire format:**

```json
// RAG: first event is always sources (grounding before tokens)
event: sources
data: [{"source":"docs/business/db.md","text":"Hybrid retrieval uses RRF..."}]

event: token
data: {"text":"Hybrid"}

event: token
data: {"text":" retrieval"}

event: done
data:
```

```bash
curl -N "http://localhost:8080/api/v1/stream/rag/answer?question=How%20does%20hybrid%20retrieval%20work%3F" -H "Accept: text/event-stream"
```

---

## 7. How to verify — curl streaming + StepVerifier + MockMvc

### 1. Curl — watch tokens arrive incrementally

```bash
# RAG — expect sources first, then tokens, then done
curl -N "http://localhost:8080/api/v1/stream/rag/answer?question=What%20is%20hybrid%20retrieval%3F" \
  -H "Accept: text/event-stream" | cat -v
# event: sources -> token* -> done (no sources for agent)
curl -N -X POST "http://localhost:8080/api/v1/stream/agent/ask?task=Check%20order%207%20status" \
  -H "Accept: text/event-stream" | head -20

# Verify 404 without rag profile (restart without --spring.profiles.active=rag):
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/v1/stream/rag/answer?question=test"
# 404 — @ConditionalOnBean(RagStreamingService.class) :49
```

`-N` (no buffer) is required — without it curl buffers and tokens appear to arrive all at once.

### 2. MockMvc — HTTP contract without a real model (`StreamingControllerTest.java:32`)

```bash
./mvnw test -Dtest=StreamingControllerTest
# 4 tests: ragStreamEndpointReturnsSseContentType, agentStreamEndpointReturnsSseContentType,
#          ragStreamEndpointHandlesEmptyQuestion, agentStreamEndpointHandlesBlankTask
#
# Each asserts: request().asyncStarted() + status().isOk() + TEXT_EVENT_STREAM
# Mocked Flux.just("Answer"," text","") proves sentinel and async dispatch
```

What it proves: the controller returns `SseEmitter` correctly (async dispatch), `produces = TEXT_EVENT_STREAM_VALUE` (`RagStreamingController.java:79` / `AgentStreamingController.java:63`), and blank-input still yields async SSE (error path, not 400).

### 3. StepVerifier — assert on `Flux<String>` directly (`pom.xml:330` `reactor-test`)

```java
// Unit test on RagStreamingService / AgentService (no HTTP):
Flux<String> flux = ragStreamingService.answerStream("What is RAG?");
// Mock ChatModel to return Flux.just(response("Hello"), response(" world"))
StepVerifier.create(flux)
    .expectNext("Hello")
    .expectNext(" world")
    .expectNext("")              // sentinel — completion signal :99 / :133
    .verifyComplete();

// Agent blank-task → Flux.error path:
StepVerifier.create(agentService.askStream("   ", null))
    .expectError(IllegalArgumentException.class)
    .verify();

```
`reactor-test` (`pom.xml:330`) is test scope; `reactor-core` is transitive from Spring AI.

### 4. Logs — streaming lifecycle

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag,--logging.level.com.company.orderapi.rag=DEBUG 2>&1 | grep -E "RAG stream|Agent stream"
# RAG stream: question='How does hybrid...', chunks=5          RagStreamingService.java:91
# RAG stream complete: question='How does hybrid...'            RagStreamingService.java:100
# Client disconnected during RAG stream                         RagStreamingController.java:118 (IOException on emitter.send)
# agent stream: conversation=demo-42, task='...'                AgentService.java:125
# agent stream complete: conversation=demo-42, task='...'       AgentService.java:134
```

No `stream complete` + `error` event means `doOnError` fired — check `:122` / `:89`.

---

## 8. How this helps you on the job — build / operate / interview

- **Build — add streaming without WebFlux.** `ChatModel.stream` (`:93`) / `stream().content()` (`:130`) → `Flux`+sentinel (`:99`) → `SseEmitter` on virtual threads (`:56`) → `blockLast()` (`:138`). Keep `spring-boot-starter-web`; copy `SseEmitter(300_000L)` + `submit` + `doOnNext` + `doFinally` + `blockLast()`.

- **Operate — cost/failure.** One virtual thread per stream (KB, 5m max) — Tomcat pool free. `Client disconnected` (`:118`) is expected, not an alert. `error` event (`:124`) preserves prior tokens vs 500. 404 → profile/key.

- **Interview — whiteboard in 90s.** "PR #45: `RagStreamingService.answerStream()` (`:77`) sync retrieve (`:78`) then `chatModel.stream` (`:93`) → `Flux` + sentinel (`:99`); `AgentService.askStream()` (`:117`) via `stream().content()` (`:130`) with `CONVERSATION_ID` (`:128`). Controllers (`:51`/`:41`) bridge `Flux` to `SseEmitter` on virtual threads (`:56`) — `sources`→`token`→`done`/`error`, `blockLast()` (`:138`) parks virtual not Tomcat. Verified by `StreamingControllerTest` + `StepVerifier` (`:330`)."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "Blocking vs streaming — when does token-by-token actually matter and how do you implement it without WebFlux?"**

> "Blocking `call().content()` (`:94`) shows nothing for 4s; streaming first token ~200ms feels live. Without WebFlux: keep `spring-boot-starter-web`, return `SseEmitter` sync (Tomcat thread released), on virtual threads (`:56`) `chatModel.stream` (`:93`) → `Flux` → `emitter.send(token)` (`:112`), `doFinally`→`done` (`:133`), `blockLast()` (`:138`) parks virtual not Tomcat. Verified `StreamingControllerTest.java:58` `asyncStarted()` + `curl -N`."

**Q2: "Why virtual threads for SSE and what happens on client disconnect or model failure?"**

> "5-minute SSE (`SseEmitter(300_000L)` `:84`) on a platform thread pins a Tomcat thread (200 cap) — 200 streams exhaust the pool. Virtual threads (`:56`, Java 21 `pom.xml:54`) cost KB and release the carrier while `blockLast()` (`:138`) parks. On disconnect: `emitter.send` → `IOException` (`:117`), logged `:118`, rethrown as `RuntimeException` cancels `Flux`, `doFinally` still sends `done`. On failure: `doOnError` (`:122`) sends `error` event before `completeWithError` (`:147`) — prior tokens preserved."

**Q3: "How do you signal stream completion without a wrapper type, and why does RAG send `sources` first?"**

> "Sentinel `concatWith(Flux.just(\"\"))` (`:99`/`:133`) — `\"\"` never a token, controller swallows (`:111`), `doFinally` sends `done` (`:131`). No wrapper type; `StepVerifier` `expectNext(\"\").verifyComplete()`. RAG `sources` first (`:102`) before subscribe — grounding in ms; truncated 200c (`:154`). Agent skips (no single set)."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #46+ picks up

**What PR #45 alone does NOT do (by design):**

- **Not back-pressured.** `blockLast()` blocks the virtual thread (fine — cheap) but there is no `onBackpressureDrop`. True back-pressure needs WebFlux.
- **Not cancellable mid-tool-call.** Agent tools run synchronously inside `stream()` — disconnect during a tool still completes the tool; cancel only between tokens.
- **No per-token metrics.** `AiMetrics` records call-level latency but not token throughput or time-to-first-token. PR #49 could add `streamEventsTotal` + `streamFirstTokenLatency` histogram.
- **No heartbeat.** `SseEmitter(300_000L)` times out after 5m idle — a slow tool chain can hit it without a `:` keep-alive.
- **No resume / single-node sources.** `Last-Event-ID` not read (re-run on drop); `sources` from `retrieve()` (`:92`) delays first event if slow.
- **No auth scoping on streams.** Like PR #40's `conversationId`, the stream endpoints have no per-user binding — any caller with `conversationId` can read another's stream. PR #48's `contextExtractor` → `mcp_session_id` pattern is the model for fixing this.

**Where it picks up:** PR #46 `stream_docs_search` (same bridge); PR #48 `mcp_session_id` scoping for `conversationId`; hardening: heartbeat, `Last-Event-ID` resume, token latency metric, persistent memory.

> Next: [`02-agentic-tool-calling.md`](./02-agentic-tool-calling.md) (PR #39) · [`03-chat-memory.md`](./03-chat-memory.md) (PR #40) — or back to [`README.md`](./README.md).

