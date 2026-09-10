# 08. Streaming responses (SSE): token-by-token delivery from LLM to client

> **PR #45** — Every AI call in the codebase today (`RagService.answer()`,
> `AgentService.ask()`) blocks the servlet thread until the full LLM response
> arrives. For a 500-token generation this is 2–5 seconds of dead thread time.
> Streaming eliminates the perceived latency and teaches async I/O in a servlet
> stack using virtual threads + `SseEmitter`.

---

## 1. The one-paragraph mental model

Streaming sends LLM tokens to the client **as they are generated**, instead of
buffering the entire response. The HTTP connection stays open, and each token
arrives as a Server-Sent Event (SSE). The client renders tokens progressively —
the user sees the first word in milliseconds, not seconds.

```
   CLIENT                          SERVER (Spring MVC)
     │                                   │
     │── GET /api/v1/stream/rag/answer ──►│
     │                                   │── retrieve chunks (sync, fast)
     │                                   │── chatModel.stream(prompt) ──► DeepSeek
     │◄── SSE: sources [{...}] ──────────│
     │◄── SSE: token {"text":"The"} ─────│
     │◄── SSE: token {"text":" orders"} ─│
     │◄── SSE: token {"text":" use"} ────│
     │   ... (more tokens) ...           │
     │◄── SSE: done "" ─────────────────│
     │── connection closes ─────────────►│
```

The key insight: **retrieval is synchronous** (it's a local database call, takes
<10ms). Only the LLM generation is streamed — that's where the latency lives.

---

## 2. Why this matters (the problem it solves)

### The latency problem

```
                    Traditional (blocking)       Streaming (SSE)
                    ─────────────────────        ───────────────
Request arrives     t=0ms                        t=0ms
Retrieval complete  t=10ms                       t=10ms
LLM starts          t=15ms                       t=15ms
First token         t=2000ms (buffered)          t=100ms (first event)
All tokens          t=4000ms                     t=4000ms
Response complete   t=4000ms                     t=4000ms

Perceived wait     4000ms                       100ms ← 40x better
```

### The thread problem

In a servlet container (Tomcat), each blocking request holds a thread for the
entire duration. With 10 concurrent LLM calls at 3 seconds each, you need 10
threads just for AI. Virtual threads (#30) mitigate this, but streaming is
the *proper* solution — the thread is released as soon as the Flux subscription
is set up.

---

## 3. How it works: the Servlet → Flux → SSE bridge

### The challenge

Spring AI's streaming API returns `Flux<ChatResponse>` (Reactor). But this is a
**servlet-based** Spring MVC app — no WebFlux, no Reactor runtime. We need to
bridge reactive streams to the servlet world.

### The solution: SseEmitter + virtual threads

```
┌─────────────────────────────────────────────────────────┐
│ Spring MVC (Servlet)                                    │
│                                                         │
│  StreamingController                                   │
│    │                                                    │
│    ├─► new SseEmitter(timeout)     ← creates the SSE   │
│    │                                   connection       │
│    ├─► STREAMING_EXECUTOR.submit() ← virtual thread    │
│    │       │                                            │
│    │       ├─► chatModel.stream(prompt)  ← Spring AI   │
│    │       │       .map(response → text)    streaming   │
│    │       │       .doOnNext(token →        API         │
│    │       │           emitter.send(...))               │
│    │       │       .blockLast()  ← blocks the VT,      │
│    │       │                       not a platform       │
│    │       │                       thread               │
│    │       │                                            │
│    │       └─► emitter.complete() ← closes SSE          │
│    │                                                    │
│    └─► return emitter  ← servlet returns immediately    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Why `blockLast()` is fine on a virtual thread:** Virtual threads are cheap
(thin stack, no OS thread). Blocking one while waiting for a Flux to complete
is the intended pattern — it's how you bridge reactive to imperative without
a reactive runtime.

### Why not just add WebFlux?

The project made a deliberate choice (#30): virtual threads over reactive
programming. Adding `spring-boot-starter-webflux` would:
- Introduce a second servlet runtime (Netty) alongside Tomcat
- Require `WebClient` instead of `RestTemplate` for all HTTP calls
- Create confusion about which concurrency model to use
- Double the dependency surface

`SseEmitter` gives us streaming without any of that.

---

## 4. SSE event format

Every event carries a `name` (event type) and a JSON `data` payload:

| Event | Data | When |
|-------|------|------|
| `sources` | `[{"source": "01-orders.md", "text": "..."}]` | Once, before tokens — retrieved chunk metadata |
| `token` | `{"text": "The"}` | Each token as the LLM generates it |
| `done` | `""` (empty) | Stream complete — client closes connection |
| `error` | `{"message": "..."}` | Something went wrong |

### Client-side consumption

```javascript
const eventSource = new EventSource('/api/v1/stream/rag/answer?question=How+does+locking+work?');

eventSource.addEventListener('sources', (e) => {
  const sources = JSON.parse(e.data);
  renderSources(sources);  // show retrieved documents
});

eventSource.addEventListener('token', (e) => {
  const { text } = JSON.parse(e.data);
  appendToAnswer(text);  // progressive rendering
});

eventSource.addEventListener('done', () => {
  eventSource.close();
  finalizeAnswer();
});

eventSource.addEventListener('error', (e) => {
  if (e.data) showError(JSON.parse(e.data).message);
  eventSource.close();
});
```

---

## 5. The code (what changed)

### New files

| File | Purpose |
|------|---------|
| `rag/RagStreamingService.java` | Streaming variant of `RagService` — returns `Flux<String>` |
| `api/rest/controller/StreamingController.java` | SSE endpoints: bridges Flux → SseEmitter |
| `rag/RagStreamingServiceTest.java` | Unit test with StepVerifier |
| `agent/AgentServiceStreamTest.java` | Unit test for agent streaming |

### Modified files

| File | Change |
|------|--------|
| `agent/AgentService.java` | Added `askStream()` returning `Flux<String>` |

### RagStreamingService — the streaming RAG

```java
// Key method: retrieves chunks, streams the LLM answer
public Flux<String> answerStream(String question) {
    List<Document> relevantDocs = retrievalEngine.retrieve(question, ragProperties.topK());

    if (relevantDocs.isEmpty()) {
        return Flux.just("No relevant documentation found...");
    }

    String context = buildContext(relevantDocs);
    Prompt prompt = new Prompt(List.of(
            new SystemMessage(SYSTEM_PROMPT.formatted(context)),
            new UserMessage(question)));

    return chatModel.stream(prompt)              // ← Spring AI streaming API
            .map(response -> response.getResult().getOutput().getText())
            .filter(text -> !text.isEmpty())
            .concatWith(Flux.just(""))           // ← completion signal
            .doOnComplete(() -> log.debug("RAG stream complete"));
}
```

### StreamingController — the SSE bridge

```java
@GetMapping(value = "/rag/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamRagAnswer(@RequestParam String question) {
    SseEmitter emitter = new SseEmitter(300_000L);  // 5-min timeout

    STREAMING_EXECUTOR.submit(() -> {                    // virtual thread
        try {
            // 1. Send source metadata (once)
            emitter.send(SseEmitter.event()
                    .name("sources")
                    .data(objectMapper.writeValueAsString(sources)));

            // 2. Stream tokens
            ragStreamingService.answerStream(question)
                    .doOnNext(token -> emitter.send(
                            SseEmitter.event()
                                    .name("token")
                                    .data(Map.of("text", token))))
                    .doFinally(signal -> {
                        emitter.send(SseEmitter.event().name("done").data(""));
                        emitter.complete();
                    })
                    .blockLast();  // ← blocks the virtual thread, not a platform thread
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;  // ← returns immediately; SSE stays open
}
```

### AgentService — streaming with tool calling

```java
public Flux<String> askStream(String task, String conversationId) {
    return chatClient.prompt()
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, effectiveConversationId))
            .user(task)
            .stream()                              // ← same ChatClient, .stream() instead of .call()
            .map(response -> response.getResult().getOutput().getText())
            .filter(text -> !text.isEmpty())
            .concatWith(Flux.just(""));            // ← completion signal
}
```

Note: tool calls execute synchronously between tokens. The LLM decides to call a
tool, waits for its result, then continues generating text. The client sees a
pause during tool execution, then more tokens.

---

## 6. Key decisions

### Why a completion signal (empty string)?

The Flux emits an empty string `""` as the last event. This is the completion
signal — the controller maps it to an SSE `done` event. Without it, the client
has no clean way to know the stream is finished (vs. a network interruption).

Alternative considered: use `Flux.complete()` and let the SSE connection close
naturally. Rejected because: (a) some SSE clients don't handle connection close
cleanly; (b) an explicit `done` event lets the client run cleanup logic.

### Why a dedicated ExecutorService?

The streaming executor (`Executors.newVirtualThreadPerTaskExecutor()`) is a
static field on the controller. Each streaming request gets its own virtual
thread, avoiding contention with the main servlet thread pool. The executor is
unbounded (virtual threads are cheap) — a production system might add a
Semaphore to limit concurrent LLM calls.

### Why 5-minute timeout?

LLM responses can be slow (especially long generations or multi-step agent
loops). 5 minutes is generous but not infinite. The `SseEmitter` timeout
prevents zombie connections from accumulating.

---

## 7. What to try

### Start with the RAG profile

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
```

### Test the streaming endpoint

```bash
# Watch tokens arrive one by one
curl -N "http://localhost:8080/api/v1/stream/rag/answer?question=How+does+optimistic+locking+work?"
```

### Test the agent streaming endpoint

```bash
curl -N -X POST "http://localhost:8080/api/v1/stream/agent/ask?task=What+products+are+available&conversationId=test-1"
```

### What to look for

1. **Sources arrive first** — the `sources` event shows which docs were retrieved
2. **Tokens arrive incrementally** — each `token` event has one word/chunk
3. **No blocking** — the server doesn't wait for the full response
4. **Clean completion** — the `done` event fires, connection closes

---

## 8. Honest limits

1. **Tool call events not exposed** — the agent's tool calls execute silently.
   A future enhancement could emit `tool_call` and `tool_result` events for
   transparency.

2. **No backpressure** — if the client is slower than the LLM, events queue in
   memory. `SseEmitter` doesn't implement Reactor's backpressure protocol.

3. **No reconnection** — if the connection drops mid-stream, the client must
   restart from the beginning. SSE's built-in `Last-Event-ID` is not
   implemented.

4. **Single-response only** — each request streams one answer. Multi-turn
   streaming (bidirectional) requires WebSocket or a different protocol.

---

*Next: PR #46 — MCP Resources & Prompts (completing the MCP protocol surface).*
