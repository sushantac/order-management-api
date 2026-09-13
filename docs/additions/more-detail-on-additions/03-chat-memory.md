# 03. Chat Memory — multi-turn conversation with MessageChatMemoryAdvisor (PR #40)

> PR: [#40 — Chat memory — multi-turn `agentic_ask`](https://github.com/anomalyco/order-management-api/pull/40) · Builds on [#39](./02-agentic-tool-calling.md) · Stack: Spring AI 1.0.0, `MessageChatMemoryAdvisor`, `MessageWindowChatMemory`, DeepSeek Chat

---

## 1. Purpose — what shipped

PR #40 makes `agentic_ask` **conversational**. Before it, every `agentic_ask({task})` was stateless — turn 2 forgot turn 1 and the caller had to restate everything. After it, pass the same `conversationId` across calls and the agent remembers previous questions and its own answers within a bounded window, enabling natural follow-ups like "and the red ones?" or "what was my first question?" without re-sending history yourself.

Concretely it ships:

- A `ChatMemory` bean (`AgentConfig.java:38` — `MessageWindowChatMemory` with 20-message sliding window, in-memory) keyed per conversation.
- A `MessageChatMemoryAdvisor` wired as a `defaultAdvisor` on the agent's `ChatClient` (`AgentService.java:72`) that prepends stored history before each model call and persists the reply after.
- `AgentService.ask(task, conversationId)` / `askStream(task, conversationId)` (`AgentService.java:84`, `AgentService.java:117`) taking an optional `conversationId` routed via `AdvisorSpec.param(ChatMemory.CONVERSATION_ID, ...)` (`AgentService.java:92`).
- `AgenticAskTool` schema gain: optional `conversationId` (`AgenticAskTool.java:57`) passed straight to `agentService.ask` (`AgenticAskTool.java:71`).
- SYSTEM_PROMPT contract update: "You remember previous questions and answers from the same conversation; refer to them when they help." (`AgentService.java:58`).

Stateless remains the default — omit `conversationId` and each call gets a fresh random UUID (`AgentService.java:88`) so no leakage.

---

## 2. Problem — stateless vs multi-turn

**Before PR #40** the agent loop from PR #39 worked but had no memory:

| Before (PR #39 stateless) | After (PR #40 with memory) |
|---|---|
| `ask("how many widgets are in stock?")` → "3 in stock" then `ask("and the red ones?")` → model has no idea what "the red ones" refers to; caller must restate "how many *red widgets* are in stock?" | `ask("how many widgets are in stock?", "room-42")` → `ask("and the red ones?", "room-42")` — second prompt is `[q1, a1, system, q2]` so DeepSeek resolves the anaphor and answers "2 red" |
| Every `conversationId` blank → `UUID.randomUUID()` already, but that was just to avoid collisions — history was nowhere | Same `UUID.randomUUID()` fallback now *means* stateless by construction (`AgentService.java:88`): empty conversation → no history injected |
| Client had to stitch turns itself (send full history as `task`) — token waste and fragile | Server owns the window: last 20 messages per `conversationId` (`AgentConfig.java:40` `app.agent.memory.max-messages:20`), eviction is automatic |
| "What was my last question?" always failed | Succeeds when same `conversationId` is reused — history contains prior user + assistant turns |

Without this PR, any demo that needs a follow-up ("remember my last order", "summarise what we just discussed") forces the UI to become a state store. PR #40 moves that state server-side, scoped and bounded.

---

## 3. Solution — architecture with ASCII diagram (ChatMemory bean + AdvisorSpec.param routing)

### Component view

```
                    ┌─────────────────────────────────────────┐
                    │ AgentConfig (@Configuration)             │  AgentConfig.java:28
                    │  @ConditionalOnProperty(app.rag.enabled) │
                    │                                         │
                    │  @Bean agentToolCallbacks()  ──────────┼──► MethodToolCallbackProvider → ToolCallback[]
                    │  @Bean agentChatMemory(maxMessages) ───┼──► MessageWindowChatMemory (20, in-memory)
                    │     MessageWindowChatMemory.builder()   │    AgentConfig.java:38 / :41
                    │     .maxMessages(20).build()            │    InMemoryChatMemoryRepository (thread-safe)
                    └──────────────┬──────────────────────────┘
                                   │ ChatMemory bean
                                   ▼
                    ┌─────────────────────────────────────────┐
                    │ AgentService (@Service)                  │  AgentService.java:41
                    │  ChatClient.builder(chatModel)           │  AgentService.java:66
                    │   .defaultSystem(SYSTEM_PROMPT)          │  AgentService.java:48 (memory rule added)
                    │   .defaultOptions(DefaultToolCalling     │  AgentService.java:69
                    │     ChatOptions{toolCallbacks})          │
                    │   .defaultAdvisors(                      │  AgentService.java:72
                    │     MessageChatMemoryAdvisor.builder(    │
                    │       chatMemory).build()) ◄─────────────┼── before(): pull history(id)
                    │                                          │    after():  store reply
                    │  ask(task, conversationId)               │  AgentService.java:84
                    │  askStream(task, conversationId)         │  AgentService.java:117
                    └──────────────┬──────────────────────────┘
                                   │ .advisors(spec -> spec.param(CONVERSATION_ID, id))
                                   │  AgentService.java:92 / :128
                    ┌──────────────▼──────────────────────────┐
                    │ AgenticAskTool (MCP read-only)          │  AgenticAskTool.java:28
                    │  name="agentic_ask"                     │  AgenticAskTool.java:38
                    │  inputSchema {task, conversationId?}    │  AgenticAskTool.java:52
                    │  run() → agentService.ask(task, cid)    │  AgenticAskTool.java:65
                    └──────────────┬──────────────────────────┘
                                   │ POST /mcp  tools/call
                    ┌──────────────▼──────────────────────────┐
                    │ MCP clients (Inspector / Claude / curl) │
                    └─────────────────────────────────────────┘
```

### Sequence — same `conversationId` keeps memory

```
Caller                  AgenticAskTool         AgentService / Advisor              ChatModel (DeepSeek)
  │── tools/call agentic_ask {task:"how many widgets?", conversationId:"room-42"} ──▶│
  │                          │── ask(task,"room-42") ──▶│                              │
  │                          │                  │── AdvisorSpec.param(CONVERSATION_ID,"room-42") ──▶│
  │                          │                  │  MessageChatMemoryAdvisor.before():               │
  │                          │                  │   history("room-42") = [] → prompt=[system,q1] ──▶│
  │                          │                  │◀─ "3 widgets in stock" ───────────────────────────│
  │                          │                  │  after(): store [q1,a1] under "room-42"          │
  │── tools/call agentic_ask {task:"and the red ones?", conversationId:"room-42"} ──▶│              │
  │                          │── ask(task,"room-42") ──▶│── before(): history("room-42")=[q1,a1] ──▶│
  │                          │                  │   prompt=[q1,a1,system,q2] ──────────────────────▶│
  │                          │                  │◀─ "2 red widgets" ────────────────────────────────│
  │                          │                  │  after(): history="room-42"=[q1,a1,q2,a2]        │
  │◀─ "2 red widgets (you asked about widgets before)" ─│                             │
```

Key invariant: history is per-`conversationId`, bounded, and injected *before* the model sees the prompt. The model never manages memory itself.

---

## 4. How it is implemented — file map + annotated code snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/agent/AgentConfig.java:28` | `@Configuration` gated on `app.rag.enabled`; owns `agentToolCallbacks()` (`AgentConfig.java:31`) and `agentChatMemory()` (`AgentConfig.java:38`) |
| `src/main/java/com/company/orderapi/agent/AgentConfig.java:38` | `ChatMemory` bean — `MessageWindowChatMemory.builder().maxMessages(maxMessages).build()` with `@Value("${app.agent.memory.max-messages:20}")` |
| `src/main/java/com/company/orderapi/agent/AgentService.java:41` | Agent service — builds `ChatClient` with `DefaultToolCallingChatOptions` (`AgentService.java:69`) + `MessageChatMemoryAdvisor` (`AgentService.java:72`); exposes `ask()` (`AgentService.java:84`) and `askStream()` (`AgentService.java:117`) |
| `src/main/java/com/company/orderapi/agent/AgentService.java:48` | `SYSTEM_PROMPT` with memory rule: "You remember previous questions and answers from the same conversation" |
| `src/main/java/com/company/orderapi/agent/AgentService.java:88` | Stateless-by-default: `effectiveConversationId = blank ? UUID.randomUUID() : trimmed` |
| `src/main/java/com/company/orderapi/agent/AgentService.java:92` | Routing: `.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, effectiveConversationId))` — the Spring AI 1.0.0 replacement for `.context()` |
| `src/main/java/com/company/orderapi/mcp/AgenticAskTool.java:28` | MCP tool `agentic_ask` — `@ConditionalOnBean(AgentService.class)`, schema `{task, conversationId?}` (`AgenticAskTool.java:52`), delegates to `agentService.ask(task, conversationId)` (`AgenticAskTool.java:71`) |
| `src/main/java/com/company/orderapi/api/rest/controller/AgentStreamingController.java:41` | SSE endpoint `POST /api/v1/stream/agent/ask` (`AgentStreamingController.java:63`) — virtual-thread bridge for `askStream()` with same `conversationId` routing |
| `src/test/java/com/company/orderapi/agent/AgentServiceTest.java:100` | 3 memory tests with real `MessageWindowChatMemory` + mocked `ChatModel`: same-id carries forward, different-id isolated, blank-id stateless |

> Note on naming: the spec calls it `ChatMemoryConfig.java` — in this codebase the `ChatMemory` bean lives in `AgentConfig.java:38` (same `@Configuration` that owns `agentToolCallbacks`). There is no separate `ChatMemoryConfig` file; the bean and advisor wiring are co-located intentionally so one `@ConditionalOnProperty` gates the whole agent surface.

### Snippet 1 — `ChatMemory` bean (`AgentConfig.java:38-44`)

```java
// src/main/java/com/company/orderapi/agent/AgentConfig.java:38
@Bean
public ChatMemory agentChatMemory(
        @Value("${app.agent.memory.max-messages:20}") int maxMessages) {
    return MessageWindowChatMemory.builder()
            .maxMessages(maxMessages) // AgentConfig.java:41 — default 20
            .build();                 // AgentConfig.java:43 — InMemoryChatMemoryRepository, thread-safe
}
```

`MessageWindowChatMemory` keeps the last N messages per conversation id, evicts oldest non-system messages first when over the limit, and keeps `SystemMessage`s when the window overflows. Backed by `InMemoryChatMemoryRepository` — swap the `ChatMemoryRepository` for Redis/JDBC to persist across nodes.

### Snippet 2 — `ChatClient` with `MessageChatMemoryAdvisor` (`AgentService.java:66-73`)

```java
// src/main/java/com/company/orderapi/agent/AgentService.java:66
public AgentService(ChatModel chatModel, ToolCallbackProvider toolCallbacks, ChatMemory chatMemory) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT) // AgentService.java:48
            .defaultOptions(DefaultToolCallingChatOptions.builder()
                    .toolCallbacks(List.of(toolCallbacks.getToolCallbacks())) // AgentService.java:69
                    .build())
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()) // AgentService.java:72
            .build();
}
```
`before` prepends `history(conversationId)`, `after` stores reply (framework stores user message in `before`). Turn 2 order: `[q1, a1, system, q2]`.

### Snippet 3 — `AdvisorSpec.param` routing (`AgentService.java:84-95`)

```java
// src/main/java/com/company/orderapi/agent/AgentService.java:84
public String ask(String task, String conversationId) {
    if (task == null || task.isBlank()) throw new IllegalArgumentException("task must not be blank");
    String effectiveConversationId = (conversationId == null || conversationId.isBlank())
            ? UUID.randomUUID().toString() // AgentService.java:88 — stateless by default
            : conversationId.trim();       // AgentService.java:90
    String answer = chatClient.prompt()
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, effectiveConversationId)) // AgentService.java:92
            .user(task)                    // AgentService.java:93
            .call().content();             // AgentService.java:94
    log.debug("agent: conversation={}, task='{}', answer length={}", // AgentService.java:96
            effectiveConversationId, task, answer == null ? 0 : answer.length());
    return answer;
}
```

`ChatMemory.CONVERSATION_ID` = `"chat_memory_conversation_id"` — the exact key `MessageChatMemoryAdvisor` reads via `getConversationId(context, defaultId)`. Streaming variant is identical (`AgentService.java:117-128`).

### Snippet 4 — MCP surface (`AgenticAskTool.java:52-71`)

```java
// src/main/java/com/company/orderapi/mcp/AgenticAskTool.java:52
@Override public JsonSchema inputSchema() {
    return objectSchema(Map.of(
            "task", Map.of("type","string","description","A natural-language task..."),
            "conversationId", Map.of("type","string", // AgenticAskTool.java:57
                    "description","Optional stable id grouping turns into one conversation with memory. Omit for a stateless question.")),
            List.of("task"));
}
// src/main/java/com/company/orderapi/mcp/AgenticAskTool.java:64
@Override protected String run(Map<String, Object> arguments) {
    String task = optionalText(arguments, "task");
    if (task == null || task.isBlank()) throw new IllegalArgumentException("task must not be blank");
    String conversationId = optionalText(arguments, "conversationId"); // AgenticAskTool.java:70
    return agentService.ask(task, conversationId);                     // AgenticAskTool.java:71
}
```

`@ConditionalOnBean(AgentService.class)` (`AgenticAskTool.java:28`) so the tool vanishes when `rag` profile is off — same gate as the whole agent.

---

## 5. How to use — curl with conversationId, demo multi-turn

### Prerequisites

```bash
brew install ollama
ollama serve &
ollama pull nomic-embed-text          # embeddings for docs_search (PR #38)
export DEEPSEEK_API_KEY="sk-..."      # https://platform.deepseek.com
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# wait for: RAG: indexed 47 docs ... + MCP server on /mcp
```

### 1. Stateless ask (no `conversationId` — each call is fresh)

```bash
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"How many widgets are in stock?"}}}' \
  | jq -r '.result.content[0].text'
```

### 2. Multi-turn demo — same `conversationId` remembers

```bash
# Turn 1 — establishes context under "demo-42"
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"How many widgets are in stock?","conversationId":"demo-42"}}}' \
  | jq -r '.result.content[0].text'
# → "3 widgets in stock ..."

# Turn 2 — follow-up resolves "the red ones" via memory
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"And how many of those are red?","conversationId":"demo-42"}}}' \
  | jq -r '.result.content[0].text'
# → "2 of those are red ..." (model saw turn 1 history)

# Turn 3 — prove recall
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"What was my first question?","conversationId":"demo-42"}}}' \
  | jq -r '.result.content[0].text'
# → recalls "How many widgets are in stock?"
```

### 3. Isolation — different id sees no history

```bash
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"What was my first question?","conversationId":"other-99"}}}' \
  | jq -r '.result.content[0].text'
# → "I don't have that context" — no cross-chat leakage
```

### 4. Streaming with memory (same routing)

```bash
curl -N -X POST "http://localhost:8080/api/v1/stream/agent/ask?task=And+the+red+ones%3F&conversationId=demo-42" \
  -H "Accept: text/event-stream"
# events: event: token / data: {"text":"..."} ... event: done
```

Without `rag` profile, `agentic_ask` is absent — `tools/list` will not contain it and the stream endpoint returns 404.

---

## 6. Key decisions — why MessageChatMemoryAdvisor over .context(), stateless-by-default, InMemory vs persistent

### Why `MessageChatMemoryAdvisor` (and not a hand-rolled `ChatMemory` wrapper)

Spring AI's `MessageChatMemoryAdvisor` is a `CallAdvisor`/`StreamAdvisor` with `before`/`after` hooks that already handle the subtle ordering (history prepend, reply store, system-message preservation). Re-implementing it means re-implementing `MessageWindowChatMemory.process` eviction rules. The advisor is the blessed path in 1.0.0 and is what `AgentService.java:72` wires as a `defaultAdvisor` so every `prompt()` automatically gets history — no per-call boilerplate beyond the `conversationId` param.

### Why `AdvisorSpec.param(ChatMemory.CONVERSATION_ID, id)` over `.context()` — the real gotcha

Older Spring AI docs/blog posts show `ChatClientRequestSpec.context(Map)` or `advisorContext` for passing per-request values. **In Spring AI 1.0.0 `ChatClient.prompt()` returns a `DefaultChatClient.DefaultChatClientRequestSpec` that has no `.context()` method** — those examples don't compile. The actual API is `advisors(Consumer<AdvisorSpec>)` where `AdvisorSpec.param(key, value)` populates `advisorParams` → `ChatClientRequest.context` (`AgentService.java:92`). We verified by decompiling the jar: `MessageChatMemoryAdvisor.getConversationId` reads `context.get(ChatMemory.CONVERSATION_ID)` which is exactly `ChatMemory.CONVERSATION_ID = "chat_memory_conversation_id"`. If you copy a `.context()` example you get a compile error; `AdvisorSpec.param` is the 1.0.0 replacement.

### Why stateless-by-default (random UUID when blank)

`AgentService.java:88` `effectiveConversationId = blank ? UUID.randomUUID() : trimmed` guarantees:

- No accidental statefulness — the PR #39 contract "each ask starts fresh" is preserved unless you deliberately pass a stable id.
- No cross-user leakage — a missing `conversationId` never falls back to a shared default like `"default"` (which would make *every* ask share one history).
- Opt-in memory is explicit — the MCP schema marks `conversationId` as optional (`AgenticAskTool.java:52-60` requires only `task`), so callers that don't need memory pay nothing.

### Why `InMemoryChatMemory` (via `MessageWindowChatMemory`) vs persistent

| In-memory (`MessageWindowChatMemory` default) | Persistent (`JdbcChatMemoryRepository` / Redis) |
|---|---|
| Zero infra — no table, no TTL, no serialization; `AgentConfig.java:38` is one bean | Survives restarts, shared across replicas — required for multi-instance deploys |
| Per-JVM — history dies on restart; per-node isolation | Cross-node — `conversationId` routes to same history regardless of pod |
| Bounded 20 messages (`app.agent.memory.max-messages:20`) prevents OOM | Same bound, but storage must be sized/GC'd (e.g., `DELETE WHERE updated_at < now() - interval`) |
| Good for PR #40 demo/learn path | Next step when you outgrow single-node |

PR #40 ships in-memory to prove the advisor plumbing with minimal infra; the seam is `ChatMemoryRepository` — swap the bean and every `AgentService` call automatically persists.

---

## 7. How to verify — curl / MCP + logs + psql + unit tests

### 1. Tools list contains `agentic_ask` with `conversationId` only on `rag` profile

```bash
# With rag profile
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[] | {name, description}'
# expect agentic_ask with description mentioning "conversationId"

# Without rag profile — agentic_ask absent (restart without profile)
./mvnw spring-boot:run
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
```

### 2. Logs — advisor is active + conversation routing

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag,--logging.level.com.company.orderapi.agent=DEBUG 2>&1 | grep agent
# agent: conversation=demo-42, task='How many widgets...', answer length=...
# agent: conversation=<random-uuid>, task='...', answer length=...   (stateless case)
```

Absence of `conversation=` log or a constant `conversation=default` would mean the `AdvisorSpec.param` routing is broken.

### 3. psql — `chat_memory` table (only if you swapped to persistent)

> Default PR #40 is **in-memory only** — there is no `chat_memory` table and `psql` checks will be empty. This is expected. The check below applies only after you replace `InMemoryChatMemoryRepository` with `JdbcChatMemoryRepository` (or Redis).

```bash
# If you added JdbcChatMemoryRepository with table "chat_memory":
docker compose exec postgres psql -U order -d orderdb -c "\d chat_memory"
#                          Table "public.chat_memory"
#  Column        | Type | Collation | Nullable | Default
#  conversation_id | text |         | not null |
#  messages        | jsonb|         | not null |
#  updated_at      | timestamptz | | not null |

docker compose exec postgres psql -U order -d orderdb -c "SELECT conversation_id, jsonb_array_length(messages) as msgs, updated_at FROM chat_memory ORDER BY updated_at DESC LIMIT 5;"
# demo-42 | 4 | 2026-...  (2 turns = 4 messages: user+assistant ×2)
# other-99| 1 | 2026-...

# Default in-memory path — prove no table is expected:
docker compose exec postgres psql -U order -d orderdb -c "SELECT to_regclass('public.chat_memory');"
# null — confirms in-memory mode (PR #40 default)
```

### 4. Unit tests — real memory + mocked model proves the plumbing

```bash
./mvnw test -Dtest=AgentServiceTest -Dspring.profiles.active=rag
# 7 tests: askReturnsTheGeneratedAnswer, askRejectsBlankTask, promptCarriesSystemInstructions,
# promptOptionsExposeAllFourAgentTools, contextCarriesForwardWithinSameConversationId,
# differentConversationIdsDoNotShareMemory, blankConversationIdKeepsQuestionsStateless
```

What the 3 memory tests assert (`AgentServiceTest.java:100-145`) by capturing the real `Prompt` handed to the mock `ChatModel`: **Same id** (`AgentServiceTest.java:100`): two `ask(..., "conv-1")` → second `Prompt.getInstructions()` size 4 with `q1`+`a1`; **Different id** (`AgentServiceTest.java:124`): `conv-a`→`conv-b` → size 2, no `AssistantMessage`; **Blank id** (`AgentServiceTest.java:136`): `null`→`""` → size 2, stateless.

---

## 8. How this helps you on the job — build / operate / interview

- **Build — add scoped multi-turn UX without a server session.** Seam: `ChatMemory` bean (`AgentConfig.java:38`) + `MessageChatMemoryAdvisor` (`AgentService.java:72`) + `AdvisorSpec.param(ChatMemory.CONVERSATION_ID, id)` (`AgentService.java:92`) + `UUID.randomUUID()` fallback (`AgentService.java:88`). Add advisor, pass stable id; swap `ChatMemoryRepository` for persistence — callers unchanged.

- **Operate — reason about cost and failure.** 20-message sliding window (`app.agent.memory.max-messages:20`) bounds tokens; stateless-by-default prevents leakage. `logging.level.com.company.orderapi.agent=DEBUG` shows per-conversation history; `MessageWindowChatMemory` evicts oldest non-system first — new `SystemMessage` drops old ones.

- **Interview — whiteboard in 90s with receipts.** "PR #40: `AgentConfig.agentChatMemory()` (`AgentConfig.java:38`) → `MessageWindowChatMemory(20)`; `AgentService` wires `MessageChatMemoryAdvisor` as `defaultAdvisors` (`AgentService.java:72`); `ask(task, conversationId)` (`AgentService.java:84`) does `blank ? UUID.randomUUID() : trimmed` (`AgentService.java:88`) and routes via `.advisors(spec -> spec.param(CONVERSATION_ID, id))` (`AgentService.java:92`) — not `.context()` (absent in 1.0.0); `AgenticAskTool` optional `conversationId` (`AgenticAskTool.java:57`) → `ask(task, cid)` (`AgenticAskTool.java:71`). Verified by 3 tests asserting `instructions.size()==4` on second turn same id (`AgentServiceTest.java:100`)."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "How do you give an LLM multi-turn memory without fine-tuning?"**

> "Prompt-frame memory — store the conversation's messages and prepend them to the next prompt. PR #40: `AgentConfig.agentChatMemory()` (`AgentConfig.java:38`) is `MessageWindowChatMemory` (20-message sliding window, `InMemoryChatMemoryRepository`). `AgentService` wires `MessageChatMemoryAdvisor.builder(chatMemory).build()` as a `defaultAdvisor` (`AgentService.java:72`). On `ask(task, conversationId)` (`AgentService.java:84`) we route the id via `AdvisorSpec.param(ChatMemory.CONVERSATION_ID, effectiveId)` (`AgentService.java:92`) — the 1.0.0 replacement for `.context()`. The advisor's `before` pulls `history(id)` and prepends it, `after` stores the assistant reply. The model sees `[q1, a1, system, q2]` on turn 2 and resolves anaphors. `SYSTEM_PROMPT` (`AgentService.java:48`) tells it memory exists."

**Q2: "How do you scope memory so chats don't bleed into each other or leak across users?"**

> "Key every conversation by a caller-supplied `conversationId`; absent id gets `UUID.randomUUID()` per call (`AgentService.java:88`) so stateless is the default and no two unrelated asks share history. Same id = shared history (4 messages on second turn, `AgentServiceTest.java:100`), different id = isolated (2 messages, no `AssistantMessage`, `AgentServiceTest.java:124`), blank id = stateless (`AgentServiceTest.java:136`). The advisor is keyed on `ChatMemory.CONVERSATION_ID = "chat_memory_conversation_id"` — only the exact param key reaches it. In prod you back it with a per-tenant `ChatMemoryRepository` (Redis/JDBC) and add tenant to the key."

**Q3: "Why not just call `.context(Map.of(CONVERSATION_ID, id))` on the ChatClient request?"**

> "Because in Spring AI 1.0.0 that method doesn't exist on `ChatClientRequestSpec` — older docs/blogs show it but it doesn't compile against the 1.0.0 jar. The actual API is `chatClient.prompt().advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, id)).user(task).call()` (`AgentService.java:92`). `AdvisorSpec.param` populates `advisorParams` which becomes `ChatClientRequest.context` that `MessageChatMemoryAdvisor.getConversationId(context, defaultId)` reads. We found this by decompiling `DefaultChatClientUtils` — the docs were stale. Copying `.context()` gives a compile error; `AdvisorSpec.param` is the fix."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #41/#45 pick up

**What PR #40 alone does NOT do (by design):**

- **Not persistent.** `MessageWindowChatMemory` with `InMemoryChatMemoryRepository` (`AgentConfig.java:41`) dies on restart; no `chat_memory` table, no cross-pod sharing. Multi-instance deploys need a shared `ChatMemoryRepository` (Redis, JDBC) — one bean swap.
- **Not bounded by tokens, only by message count.** 20 messages (`AgentConfig.java:40`) could still be large if each turn chains 4 tool results. Token-aware eviction or summarization is the follow-up.
- **No summarization or compression.** Raw messages are lossless but grow linearly. Long conversations need `TokenTextSplitter`-style summarising memory — a classic next PR.
- **No "clear conversation" tool.** At most it evicts after 20 messages. A `memories{action: clear}` guarded write tool is the natural next step (first time this surface would need write-style guards).
- **No per-user/per-tenant scoping.** `conversationId` is caller-chosen; a malicious client could guess another's id. Real prod prefixes `tenantId:conversationId` or ties it to the OAuth `mcp_session_id` (PR #48 does this for audit).
- **In-memory per-node means `psql chat_memory` is empty by default** — the `psql` check in §7 is for the persistent swap, not PR #40 itself.
- **No streaming memory difference.** `askStream()` (`AgentService.java:117`) reuses the same advisor/routing (`AgentService.java:128`), but the Flux path was only fully bridged to SSE in PR #45 (`AgentStreamingController.java:41`).

**Where PR #45 picks up:** PR #45 bridges `askStream()` (`AgentService.java:117`) to `SseEmitter` in `AgentStreamingController` (`AgentStreamingController.java:63`, `POST /api/v1/stream/agent/ask`) with `token`/`done`/`error` — same memory, token-by-token. PR #48 binds `mcp_actor`/`mcp_session_id` via `contextExtractor`; persistent `ChatMemoryRepository` is the one-bean swap for cross-pod survival.

> Next: [`02-agentic-tool-calling.md`](./02-agentic-tool-calling.md) (PR #39) or [`08-streaming-responses-sse.md`](../08-streaming-responses-sse.md) (PR #45).
