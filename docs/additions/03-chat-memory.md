# 03. Chat memory: let the agent remember (multi-turn `agentic_ask`)

> **Bonus PR #40** — builds directly on
> [`02-agentic-tool-calling.md`](02-agentic-tool-calling.md). PR #39 made the
> API's DeepSeek model an **agent** that decides which read-only tools to call.
> Each `agentic_ask` was still stateless: turn 2 forgot turn 1. This PR gives
> the agent **scoped multi-turn memory** — call the tool with the same
> `conversationId` and previous turns are fed back into the model's context.

---

## 1. The problem in one sentence

Without memory, "how many widgets are in stock?" followed by "and the red
ones?" forces the caller to restate everything — the agent has no idea what
"the red ones" refers to. With memory, turn 2's prompt automatically includes
turn 1's question *and* answer, keyed by a conversation id you pass in.

```
   caller ── agentic_ask({task, conversationId:"room-42"})
              │
              ▼
         AgentService.ask(task, conversationId)
              │  .advisors(spec -> spec.param(CONVERSATION_ID, id))
              ▼
    MessageChatMemoryAdvisor  ◄———— real plumbing (not a mock)
      before():  pull history("room-42") ──► [q1, a1] + [system, q2] → model
      after():   push assistant reply ──►   history("room-42") = [q1, a1, q2, a2]
              │
              ▼
           DeepSeek sees: "widgets = 3" from earlier, answers "the red ones: 2"
```

---

## 2. The four pieces (in the order they wire together)

### 2.1 `ChatMemory` bean — the store (`AgentConfig`)

```java
@Bean
public ChatMemory agentChatMemory(
        @Value("${app.agent.memory.max-messages:20}") int maxMessages) {
    return MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
}
```

`MessageWindowChatMemory` keeps the last N messages per conversation id and
evicts the oldest beyond the window (20 by default). `SystemMessage`s are
treated specially: kept when the window overflows. Backed by an in-memory,
thread-safe `InMemoryChatMemoryRepository`. A different `ChatMemoryRepository`
(Redis, DB, ...) is the drop-in swap for production persistence.

### 2.2 The advisor — where history is injected (`AgentService`)

```java
ChatClient.builder(chatModel)
        .defaultSystem(SYSTEM_PROMPT)
        .defaultOptions(DefaultToolCallingChatOptions.builder()
                .toolCallbacks(List.of(toolCallbacks.getToolCallbacks()))
                .build())
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();
```

`MessageChatMemoryAdvisor` is a **before/after advisor**: *before* the model
call it prepends the conversation's stored messages to the prompt; *after* the
call it stores the assistant's reply (the framework stores the user message for
you in `before`). Order of messages for turn 2:
`[q1, a1, system, q2]` — coherent history, current system instructions in the
middle, current question last.

### 2.3 Carrying the conversation id — the routing trick

The advisor needs to know *which* conversation, per request. Spring AI 1.0.0's
`ChatClientRequestSpec` has no `.context(...)` — the way to pass per-call
values to advisors is `advisorParams`, set via the `AdvisorSpec`:

```java
chatClient.prompt()
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, id))
        .user(task)
        .call();
```

`ChatMemory.CONVERSATION_ID` = `"chat_memory_conversation_id"` — the exact key
`MessageChatMemoryAdvisor` reads from the request context
(`getConversationId(context, defaultId)`).

### 2.4 `ask(task, conversationId)` — preserving the stateless default

```java
String effectiveId = (conversationId == null || conversationId.isBlank())
        ? UUID.randomUUID().toString()
        : conversationId.trim();
```

Blank/missing id → a **fresh random id per call**: each ask looks up an empty
conversation, so the PR #39 guarantee ("each ask starts fresh") is preserved.
Passing a stable id opts into memory deliberately. The MCP tool `agentic_ask`
gains the optional `conversationId` JSON-schema property and passes it straight
through.

---

## 3. The system prompt changed too

Rule added: *"You remember previous questions and answers from the same
conversation; refer to them when they help."* Memory is useless if the model
isn't told it exists — the prompt is the contract that turns stored messages
into coherent multi-turn answers.

---

## 4. Anatomical detail: the window eviction semantics

`MessageWindowChatMemory.process`:
- if a *new* `SystemMessage` arrives, all previously stored `SystemMessage`s
  are dropped (the latest system instruction wins);
- when over the limit, non-system messages are evicted first (oldest first),
  system messages preserved.

Here we never store system messages in memory (the advisor only stores the
user + assistant turns), but the behaviour matters the moment you persist
system content — worth knowing so a "system went stale" bug never surprises you.

---

## 5. Tests (3 new — real memory, mocked model)

All use the **real** `MessageWindowChatMemory` + real `MessageChatMemoryAdvisor`
and a mocked `ChatModel` — so they prove the actual Spring AI plumbing, not a
copy:

- `contextCarriesForwardWithinSameConversationId` — two asks with `"conv-1"`:
  the second prompt is 4 messages and contains turn 1's user text AND the
  assistant answer. *That's memory, captured at the real prompt.*
- `differentConversationIdsDoNotShareMemory` — second prompt is just
  `[system, user]`: no leaked assistant message from the other conversation.
- `blankConversationIdKeepsQuestionsStateless` — `null`/`""` ids never reuse
  history (fresh per-call id), preserving PR #39's stateless default.

Plus the 9 PR #39 tests updated (`ask(task)` → `ask(task, conversationId)`).
Full suite: **147 tests, 0 failures** (127 + 8 RAG + 9 agent + 3 memory).

---

## 6. Decisions and honest limits

- **Memory is per-conversation-id, in-memory, bounded (20), opt-in.** No global
  state, no unbounded growth, no cross-chat leakage, no persisted secrets —
  PII rules from PR #39 still hold: the agent has no customer data to leak.
- **No "clear conversation" tool yet.** At most it evicts itself after 20
  messages. A `memories{action: clear}` write tool is the natural next step
  (and the first time this surface would need a *write*-style guard).
- **No summarisation.** History as raw messages is simple and lossless; long
  conversations would grow — `TokenTextSplitter`-style summarising memory is a
  classic follow-up.
- **In-memory store is per-node.** Multi-instance deployments need a shared
  `ChatMemoryRepository` (Redis etc.) — the replacement point is one bean.

---

## 7. Interview highlights

- "How do you give an LLM memory?" → An advisor that prepends stored history to
  the prompt before each call and appends the reply after; storage is a
  keyed, bounded message window. (Prompt-frame memory, not fine-tuning.)
- "How do you scope memory so chats don't bleed?" → Key every conversation by a
  caller-supplied id; absent id → fresh random id per call = stateless.
- "What did you learn decompiling ChatClient?" → In Spring AI 1.0.0 there is no
  `.context()` on the request spec; per-request values reach advisors via
  `AdvisorSpec.param(...)` → `advisorParams` → request context. The docs
  examples for older versions don't compile against it.
- "Why keep the stateless default?" → Because a memory advisor with a shared
  default id silently makes *every* ask stateful — the safe default is opt-in.

---

## 8. Related reading

- [`02-agentic-tool-calling.md`](02-agentic-tool-calling.md) — the agent, the
  function-calling loop, the `.defaultToolCallbacks` gotcha.
- [`01-rag-and-docs-search.md`](01-rag-and-docs-search.md) — RAG grounding.
- [`../business/11-mcp-ai-integration.md`](../business/11-mcp-ai-integration.md)
  — the read-only tool contract this feature stays inside of.