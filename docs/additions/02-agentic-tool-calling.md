# 02. Agentic tool-calling: let the model decide which tools to call (`agentic_ask`)

> **Bonus PR #39** — builds directly on
> [`01-rag-and-docs-search.md`](01-rag-and-docs-search.md) and the MCP work in
> [`../business/11-mcp-ai-integration.md`](../business/11-mcp-ai-integration.md).
> With MCP (PR #36/#37) an EXTERNAL assistant calls the API one tool at a time.
> With RAG (PR #38) the API answers questions about itself. This PR is the
> step that makes the API's own chat model an **agent**: it is handed the same
> read-only tools as *functions it can decide to call*, in any order, chaining
> several calls to complete one task.

---

## 1. One paragraph + one picture

**MCP = the assistant calls YOUR tools.** **Function calling / tool calling =
YOUR model calls YOUR tools as part of answering.** An external caller gives the
agent a single task ("find the red widget and check whether order 7 was
placed"), the chat model replies *"I want to call `product_search`"* with JSON
arguments, Spring AI executes the real method, appends the result to the
conversation, and asks the model again — looped until the model produces a
normal text answer. The chain is decided by the model, not hard-coded.

```
   caller ── task ──► AgenticAskTool ──► AgentService.ask(task)
                                              │
                                              ▼
                                     ChatClient (DeepSeek)
                                     prompts are pre-seeded with 4 tools
                                     (api_health, product_search,
                                      order_status, docs_search) + system rules
                                              │
        ┌───────────── function-calling loop ─┤
        │  model:  call product_search({query:"widget"})        │
        │  Spring AI runs the real tool → result appended       │
        │  model:  call order_status({orderId:7})               │
        │  Spring AI runs the real tool → result appended       │
        │  model:  "The red widget has 3 units and order 7 was shipped." │
        └──────────────── start decision ──► final answer ▼
                                                                   answer
```

Two protocols, ONE code path: the tools the agent can call are the **same**
`AbstractMcpReadOnlyTool` beans the MCP server exposes — delegated through one
`execute(map)` method, so a capability is identical whether reached via
`/mcp` `tools/call` or via DeepSeek function calling.

---

## 2. The files (what each one teaches)

### 2.1 `mcp/AbstractMcpReadOnlyTool` — the shared invocation contract

Added one public method next to the (protected) `run`:

```java
public final String execute(Map<String, Object> arguments) { return run(arguments); }
```

The MCP call handler and the agent now both call `execute(...)`. This is the
"one implementation, many surfaces" trick — adding a protocol never duplicates
business logic.

### 2.2 `agent/AgentToolSet` — the tools, as `@Tool` methods

```java
@Tool(name = "product_search",
      description = "Search the product catalogue by name. Returns product id, "
                    + "name, price and available stock for up to maxResults matches.")
public String productSearch(
        @ToolParam(description = "Substring to match against product names.") String query,
        @ToolParam(description = "Maximum matches to return (1-50).", required = false) Integer maxResults) {
    Map<String, Object> args = new HashMap<>();
    args.put("query", query == null ? "" : query);
    args.put("maxResults", maxResults == null ? 10 : maxResults);
    return productSearchTool.execute(args);
}
```

Three teaching points:

1. `@Tool` (Spring AI) turns a plain Java method into a callable function: the
   framework derives a JSON Schema from the parameters (`@ToolParam`).
2. **Names deliberately match the MCP tools** (`product_search`, not
   `productSearch`): whatever protocol the caller uses, the capability has one
   stable identity.
3. Reuse over duplication: each method just builds the argument `Map` and
   delegates to `execute(...)`. `Map.of` can't hold `null`, hence the
   `HashMap` for the optional query.

### 2.3 `agent/AgentConfig` — the `ToolCallbackProvider` bean

```java
@Bean
public ToolCallbackProvider agentToolCallbacks(AgentToolSet agentToolSet) {
    return MethodToolCallbackProvider.builder().toolObjects(agentToolSet).build();
}
```

`MethodToolCallbackProvider` introspects the `@Tool` methods and produces a
`ToolCallback[]` (one per tool, each carrying `ToolDefinition` with name +
description + JSON schema). This provider is the wiring point your tests can
grab and assert on.

### 2.4 `agent/AgentService` — the agent itself

```java
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem(SYSTEM_PROMPT)
        .defaultOptions(DefaultToolCallingChatOptions.builder()
                .toolCallbacks(List.of(toolCallbacks.getToolCallbacks()))
                .build())
        .build();
```

`ask(task)` is two lines: blank-check the task, then
`chatClient.prompt().user(task).call().content()`.

Two design decisions worth your attention:

- **`defaultSystem`**: the prompt instructs the model to *call a tool instead
  of guessing*, that it may chain calls, that it has NO customer PII, and that
  it must say so when the tools can't answer. The system prompt is the agent's
  behavioural contract.
- **Tools go into the `ChatOptions`, not just "registered"** — see §4, the
  one real gotcha of this PR.

### 2.5 `mcp/AgenticAskTool` — the agent as just another MCP tool

```java
@Component
@ConditionalOnBean(AgentService.class)
public class AgenticAskTool extends AbstractMcpReadOnlyTool { ... }
```

`agentic_ask` takes one `task` string and returns the agent's final answer.
From the protocol's point of view it is still a single read-only tool — the
multi-step reasoning is entirely server-side. Because it extends the same
abstract tool, `McpServerConfiguration` auto-registers it with zero new wiring,
sorted into `tools/list` with the others.

---

## 3. Why it is still safe (security by construction)

- The agent can only ever call the four read-only, PII-free tools — there is no
  write tool to reach and the `OrderStatusTool` surface physically excludes the
  customer association.
- One more protocol, same auth: `/mcp` sits behind Spring Security exactly as
  before; `agentic_ask` inherits that.
- No unbounded loops: the model asks for a tool, the result returns, the model
  must answer. If it produces malformed JSON or stops, the request just ends
  with whatever was generated — nothing is written anywhere.

---

## 4. The gotcha this PR is really about

**`.defaultToolCallbacks(provider)` on its own does NOT reach the model in
Spring AI 1.0.0's `ChatClient`.**

We verified it by decompiling `DefaultChatClientUtils.toChatClientRequest`:
tool callbacks are merged into the generated `Prompt` **only when the request's
`ChatOptions` is already a `ToolCallingChatOptions`** (`instanceof` guard). If
`chatOptions` is `null` — which it is when you only call
`.defaultToolCallbacks(...)` — the branch is skipped and the model never sees
the tools.

The fix is to make the default options a tool-calling options carrying the
callbacks:

```java
.defaultOptions(DefaultToolCallingChatOptions.builder()
        .toolCallbacks(List.of(toolCallbacks.getToolCallbacks()))
        .build())
```

and the unit test proves it by capturing the real `Prompt` handed to the model
and asserting `prompt.getOptions() instanceof ToolCallingChatOptions` with
`getToolCallbacks()` = the four tools. (Lesson: read the jar — `javap -c`
beat a week of forum answers.)

---

## 5. Running it

```bash
# everything from PR #38 still applies (Ollama + DeepSeek + rag profile)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag

# agentic_ask is now a 5th tool on the same /mcp endpoint
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"agentic_ask",
                 "arguments":{"task":"Find products matching \"widget\" and check order 7"}}}'
```

With debug logging on the AI tool layer
(`--logging.level.org.springframework.ai.tool=debug`) you can watch each tool
call the model decides to make and the result fed back — a free tour of the
function-calling loop.

---

## 6. Tests (9 new, all "pure unit")

`AgentToolSetTest` (5) — constructs the REAL tools with mocked repositories.
- the provider resolves exactly 4 callbacks under the MCP names
  (`api_health`, `product_search`, `order_status`, `docs_search`),
- `api_health` returns `OK | ...`,
- `productSearch` delegates to the catalogue tool (mock `ProductRepository`
  returns fixtures; result contains the matching product name, price, stock),
- `orderStatus` returns `PLACED` but **never the customer's email** — the PII
  boundary survives the adaptation,
- `docsSearch` delegates to `RagService.answer(...)` (verified via Mockito).

`AgentServiceTest` (4) — mocked `ChatModel`, real tool set.
- `ask` returns the generated answer,
- blank task → `IllegalArgumentException` and the model is never called,
- the built prompt carries the system instructions (agent role + tool names)
  and the user's task,
- **the model's options expose all four tools** (the §4 wiring proof).

Full suite: **144 tests, 0 failures** (127 pre-existing + 8 RAG + 9 agent).

---

## 7. Decisions and honest limits

- **Single agent in a loop, not a multi-agent system.** The model manages its
  own tool sequence. True sub-agents / planning with a second model are a
  different, heavier architecture — deliberately out of scope.
- **No memory.** Originally each `ask` started fresh; conversation history
  between tasks was not retained. **Addressed in PR #40** — see
  [`03-chat-memory.md`](03-chat-memory.md): optional `conversationId` gives
  scoped, opt-in multi-turn memory while keeping the stateless-by-default
  behaviour.
- **Gated with RAG.** `agentic_ask` needs the DeepSeek model and the docs tool,
  so it inherits the `app.rag.enabled` gate — the default app still needs
  nothing but the DB.
- **Tools are the same 4, forever (for now).** The moment a write tool exists,
  this design will happily let the agent call it — that must be a *guarded*,
  confirmation-wrapped, side-effect-disciplined decision in its own PR.
- **Retrieval quality gates answer quality** (from PR #38) still applies inside
  `docs_search`; the agent adds no new hallucination surface beyond what those
  tools already control.

---

## 8. Interview highlights

- "What is function calling?" → The model asks for a tool by name + JSON args;
  your code runs the real method and feeds the result back; repeat until the
  model answers. Replaces brittle hard-coded intent detection.
- "Agent vs MCP?" → MCP is the protocol by which a caller reaches your tools;
  function calling is how a model *inside* a chat loop uses them. Complementary:
  here MCP is the transport and function calling is the loop.
- "How do you stop an agent doing damage?" → Limit the tool set (read-only, no
  write tools), make PII structurally unreachable, gate behind auth, and prompt
  a behavioural contract. Safety is mostly *surface area you never create*.
- "Why put tools in ChatOptions?" → Spring AI 1.0.0 merges callbacks into the
  `Prompt` only when `ChatOptions` is a `ToolCallingChatOptions`; the test
  asserting `getToolCallbacks()` is how you guard the wiring.
- "Why did we reverse-engineer the framework?" → Because the documented
  pattern silently no-ops in this build. `javap -c` on `DefaultChatClientUtils`
  is faster and more truthful than blog examples.

---

## 9. Apply this at work

1. Size the tool surface first. Read-only + PII-free is not an accident; it is
   the security boundary. Add write tools only with guard rails.
2. Prove wiring with a mock that captures the real `Prompt` to the model —
   "it compiles" is far from "the model sees the tools".
3. Keep the same capability on MCP and function calling via one delegated
   implementation; two copies of business logic will drift.
4. If a framework pattern visibly does nothing, decompile the actual jar before
   patching around it; you learn the real contract and avoid cargo-cult fixes.
5. Log tool calls in dev: watching the model's JSON decisions is the best
   feedback loop for prompt + tool-description quality.

---

## 10. Related reading

- [`01-rag-and-docs-search.md`](01-rag-and-docs-search.md) — RAG, the DeepSeek
  + Ollama split, the `app.rag.enabled` gate, the docs tool.
- [`../business/11-mcp-ai-integration.md`](../business/11-mcp-ai-integration.md)
  — MCP, the read-only tool contract, the PII boundary.
- [`../design/04-security-and-privacy-design.md`](../design/04-security-and-privacy-design.md)
  — why every AI surface here stays read-only and PII-free.