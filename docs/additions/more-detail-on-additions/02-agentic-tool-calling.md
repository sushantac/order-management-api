# 02. Agentic Tool Calling — shared MCP + agent function path (PR #39)

> PR: [#39 — Agentic tool calling — `agentic_ask` + shared `@Tool` surface](https://github.com/anomalyco/order-management-api/pull/39) · Profile: `rag` (`app.rag.enabled=true`) · Stack: Spring AI 1.0.0, DeepSeek Chat (`ChatClient` + `DefaultToolCallingChatOptions`), MCP Java SDK (Streamable HTTP at `/mcp`)

---

## 1. Purpose — what shipped

PR #39 makes the API's own chat model an **agent**: the same four read-only, PII-free capabilities already exposed to external assistants via MCP (`api_health`, `product_search`, `order_status`, `docs_search`) are handed to DeepSeek as **Spring AI `@Tool` functions it can decide to call**, in any order, chaining several calls to complete one task — then wraps the whole loop as a single MCP tool `agentic_ask` (`AgenticAskTool.java:28`) so any MCP caller gets multi-step reasoning with one `tools/call`.

In one line: *one implementation, two protocols — `AbstractMcpReadOnlyTool.execute(Map)` (`AbstractMcpReadOnlyTool.java:124`) is reachable both as MCP `tools/call` and as a `ChatClient` function call, and `agentic_ask` is the server-side loop that connects them.*

This is the foundation every later agentic PR builds on: #40 (chat memory + `conversationId`), #45 (streaming), #48 (audited actor/session), #49 (metrics/traces).

---

## 2. Problem — what was missing/broken without it

**Before PR #39** the two AI surfaces were disconnected:

| Before | After (PR #39) |
|---|---|
| MCP tools required an **external** assistant to orchestrate: caller picks `product_search`, reads result, decides next tool — one tool per `tools/call` round-trip | `agentic_ask` delegates orchestration **server-side**: caller sends one `task` string, the model decides the tool chain |
| No function-calling loop inside the API — `ChatModel` could generate text but could not call any project tool | `AgentService.java:41` builds a `ChatClient` that advertises the four tools; Spring AI runs the function-calling loop (`model → tool → model → answer`) automatically |
| Docs Q&A (`docs_search`) was a single-tool answer (`RagService.java:71`) — a task spanning catalogue + orders + docs needed 3 separate MCP calls from the client | `agentic_ask` can chain `product_search` + `order_status` + `docs_search` in one turn to answer cross-cutting tasks |
| Two copies risk: adding a capability meant writing an MCP tool *and* a separate agent function with drift | `AgentToolSet.java:31` **delegates** to the same `AbstractMcpReadOnlyTool` beans — one code path, two protocols |

Without this PR, PR #40 has no agent to add memory to, PR #45 has no `askStream` to stream, and the "agent" claim is just a chat endpoint.

---

## 3. Solution — architecture with ASCII diagram (one implementation, two protocols)

### Component view — one tool behind two protocols + `agentic_ask` as meta-tool

```
                          MCP clients (Inspector / Claude / curl)
                                     │
                                     │ POST /mcp  JSON-RPC
                                     ▼
                          ┌────────────────────────┐
                          │  McpServerConfiguration │  POST /mcp (WebMvcStatelessServerTransport)
                          │  registers every        │◄─ tools/list + tools/call → execute(Map)
                          │  AbstractMcpReadOnlyTool.specification() │
                          └────────┬───────────────┘
                                   │ delegates to
              ┌────────────────────┼────────────────────────────────┐
              │                    │                                │
     ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐
     │ ApiHealthTool   │  │ProductSearchTool│  │ OrderStatusTool │  │ DocsSearchTool  │  ← ONE impl
     │ api_health      │  │ product_search  │  │ order_status    │  │ docs_search     │    each
     └────────┬────────┘  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘
              │                    │                    │                    │
              │    execute(Map)    │                    │                    │
              └────────────────────┼────────────────────┼────────────────────┘
                                   │                    │
                          ┌────────▼────────────────────▼────────┐
                          │       AgentToolSet (@Tool methods)   │  PR #39 shared surface
                          │  @Tool(name="api_health")      ──────┼──► apiHealthTool.execute(Map.of())
                          │  @Tool(name="product_search")  ──────┼──► productSearchTool.execute(args)
                          │  @Tool(name="order_status")    ──────┼──► orderStatusTool.execute(Map.of("orderId",..))
                          │  @Tool(name="docs_search")     ──────┼──► docsSearchTool.execute(Map.of("question",..))
                          └────────────────┬─────────────────────┘
                                           │ MethodToolCallbackProvider
                                           ▼
                          ┌─────────────────────────────────────┐
                          │ AgentConfig.agentToolCallbacks()    │  ToolCallback[] with JSON Schema
                          │ AgentService.chatClient             │  ChatClient + DefaultToolCallingChatOptions
                          │  + SYSTEM_PROMPT + tools in options │  (DeepSeek)
                          └────────────────┬────────────────────┘
                                           │ ask(task, conversationId)
                          ┌────────────────▼────────────────────┐
                          │ AgenticAskTool (MCP tool)           │  name="agentic_ask"  {task, conversationId?}
                          │  run() → agentService.ask(task, cid)│  single read-only tool, multi-step inside
                          └─────────────────────────────────────┘
```

### Sequence — `agentic_ask` multi-tool loop

```
Caller              AgenticAskTool         ChatClient (DeepSeek)                  AgentToolSet → MCP impl
  │── tools/call agentic_ask {task} ──▶│                │                             │
  │                     │── ask(task,cid) ──────▶│── Prompt(task+SYSTEM+4 tools) ──────▶│
  │                     │                │◀─ call product_search({query:"widget"}) ────│
  │                     │                │── productSearch ──────────────────────────▶│→ ProductSearchTool.run() → DB
  │                     │                │◀─ "3 | Red Widget | 19.99 | stock 42" ──────│
  │                     │                │◀─ call order_status({orderId:7}) ──────────│
  │                     │                │── orderStatus ───────────────────────────▶│→ OrderStatusTool.format()
  │                     │                │◀─ "Order 7 | SHIPPED" ─────────────────────│
  │◀─ CallToolResult("Widget in stock. Order 7 shipped.")│◀─ final answer ──────────│
```

Invariant: the agent loop is owned by Spring AI — no hand-rolled orchestration; every tool is the same `execute(Map)`.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/mcp/AbstractMcpReadOnlyTool.java:31` | Abstract contract for every read-only MCP tool; owns `execute(Map)` (`AbstractMcpReadOnlyTool.java:124`) — the single code path both protocols share |
| `src/main/java/com/company/orderapi/mcp/ApiHealthTool.java:17` | `api_health` — zero-dep probe `OK | version | up Xs` (`ApiHealthTool.java:44`) |
| `src/main/java/com/company/orderapi/mcp/ProductSearchTool.java:20` | `product_search` — case-insensitive substring on `Product.name`, capped `maxResults 1-50` (`ProductSearchTool.java:52`) |
| `src/main/java/com/company/orderapi/mcp/OrderStatusTool.java:20` | `order_status` — `findById` + PII-free `format()` (`OrderStatusTool.java:56`); never touches `customer` association |
| `src/main/java/com/company/orderapi/mcp/DocsSearchTool.java:22` | `docs_search` — thin adapter over `RagService.answer(question)` (`DocsSearchTool.java:56`), gated `@ConditionalOnBean(RagService.class)` |
| `src/main/java/com/company/orderapi/agent/AgentToolSet.java:31` | `@Component` exposing the four MCP tools as Spring AI `@Tool` methods; each delegates to `*.execute(Map)` |
| `src/main/java/com/company/orderapi/agent/AgentConfig.java:28` | `@Configuration` producing `ToolCallbackProvider` via `MethodToolCallbackProvider` (`AgentConfig.java:31`) + `ChatMemory` bean (`AgentConfig.java:38`) |
| `src/main/java/com/company/orderapi/agent/AgentService.java:41` | Agent — `ChatClient` + `SYSTEM_PROMPT` (`AgentService.java:48`) + `DefaultToolCallingChatOptions` (`AgentService.java:69`) + `MessageChatMemoryAdvisor` (`AgentService.java:72`); `ask()` (`AgentService.java:84`) / `askStream()` (`AgentService.java:117`) |
| `src/main/java/com/company/orderapi/mcp/AgenticAskTool.java:28` | MCP tool `agentic_ask` — `{task, conversationId?}` → `agentService.ask(task, conversationId)` (`AgenticAskTool.java:64`); `@ConditionalOnBean(AgentService.class)` so absent without `rag` profile |
| `src/main/java/com/company/orderapi/api/rest/controller/AgentStreamingController.java:41` | SSE streaming endpoint `POST /api/v1/stream/agent/ask` (`AgentStreamingController.java:63`) — virtual-thread bridge for `askStream()` Flux |
| `src/test/java/com/company/orderapi/agent/AgentToolSetTest.java:1` | 5 unit tests — 4 callbacks under MCP names, delegation + PII boundary |
| `src/test/java/com/company/orderapi/agent/AgentServiceTest.java:1` | 4 unit tests — prompt wiring proof (`ToolCallingChatOptions` + callbacks present) |

### Snippet 1 — Single code path: `execute(Map)` (`AbstractMcpReadOnlyTool.java:116`)

```java
// src/main/java/com/company/orderapi/mcp/AbstractMcpReadOnlyTool.java:116
// One implementation, two protocols — MCP handler + @Tool surface share this
public final String execute(Map<String, Object> arguments) {
    return run(arguments); // AbstractMcpReadOnlyTool.java:124
}
protected abstract String run(Map<String, Object> arguments); // AbstractMcpReadOnlyTool.java:48
```

### Snippet 2 — `AgentToolSet` delegates, names match MCP (`AgentToolSet.java:31`)

```java
// src/main/java/com/company/orderapi/agent/AgentToolSet.java:31
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class AgentToolSet {
    // src/main/java/com/company/orderapi/agent/AgentToolSet.java:51
    @Tool(name = "api_health",
          description = "Confirm the Order Management API agent services are up. "
                      + "Returns service name, version and uptime. Takes no arguments.")
    public String apiHealth() {
        return apiHealthTool.execute(Map.of()); // AgentToolSet.java:54
    }
    // src/main/java/com/company/orderapi/agent/AgentToolSet.java:58
    @Tool(name = "product_search",
          description = "Search the product catalogue by name. ...")
    public String productSearch(
            @ToolParam(description = "Substring to match against product names.") String query,
            @ToolParam(description = "Maximum matches to return (1-50).", required = false) Integer maxResults) {
        Map<String, Object> args = new HashMap<>();
        args.put("query", query == null ? "" : query);
        args.put("maxResults", maxResults == null ? 10 : maxResults);
        return productSearchTool.execute(args); // AgentToolSet.java:69
    }
    // src/main/java/com/company/orderapi/agent/AgentToolSet.java:72
    @Tool(name = "order_status",
          description = "Look up a single order by its numeric id ...")
    public String orderStatus(@ToolParam(description = "Numeric order id.") long orderId) {
        return orderStatusTool.execute(Map.of("orderId", orderId)); // AgentToolSet.java:78
    }
    // src/main/java/com/company/orderapi/agent/AgentToolSet.java:82
    @Tool(name = "docs_search",
          description = "Search project documentation and get AI-generated answers ...")
    public String docsSearch(@ToolParam(description = "The question to answer...") String question) {
        return docsSearchTool.execute(Map.of("question", question)); // AgentToolSet.java:90
    }
}
```

### Snippet 3 — `ToolCallbackProvider` from `@Tool` methods (`AgentConfig.java:31`)

```java
// src/main/java/com/company/orderapi/agent/AgentConfig.java:31
@Bean
public ToolCallbackProvider agentToolCallbacks(AgentToolSet agentToolSet) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(agentToolSet)
            .build(); // introspects @Tool, produces ToolCallback[] with ToolDefinition + JSON Schema
}
```

### Snippet 4 — `ChatClient` wiring — the gotcha lives here (`AgentService.java:66`)

```java
// src/main/java/com/company/orderapi/agent/AgentService.java:66
public AgentService(ChatModel chatModel, ToolCallbackProvider toolCallbacks, ChatMemory chatMemory) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT) // AgentService.java:48 — "call a tool, don't guess; no PII"
            .defaultOptions(DefaultToolCallingChatOptions.builder()
                    .toolCallbacks(List.of(toolCallbacks.getToolCallbacks())) // AgentService.java:69
                    .build())
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()) // AgentService.java:72
            .build();
}
// src/main/java/com/company/orderapi/agent/AgentService.java:84
public String ask(String task, String conversationId) {
    String effectiveConversationId = (conversationId == null || conversationId.isBlank())
            ? UUID.randomUUID().toString() : conversationId.trim(); // AgentService.java:88 — stateless by default
    return chatClient.prompt()
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, effectiveConversationId)) // AgentService.java:92
            .user(task)
            .call().content(); // AgentService.java:94 — Spring AI drives the tool loop
}
```

### Snippet 5 — `agentic_ask` as just another read-only MCP tool (`AgenticAskTool.java:28`)

```java
// src/main/java/com/company/orderapi/mcp/AgenticAskTool.java:28
@Component
@ConditionalOnBean(AgentService.class) // only when rag enabled + ChatModel present
public class AgenticAskTool extends AbstractMcpReadOnlyTool {
    @Override public String name() { return "agentic_ask"; } // AgenticAskTool.java:38
    @Override public JsonSchema inputSchema() { // AgenticAskTool.java:52
        return objectSchema(Map.of(
                "task", Map.of("type", "string", "description", "A natural-language task..."),
                "conversationId", Map.of("type", "string", "description", "Optional stable id...")),
                List.of("task"));
    }
    @Override protected String run(Map<String, Object> arguments) { // AgenticAskTool.java:64
        String task = optionalText(arguments, "task");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task must not be blank");
        return agentService.ask(task, optionalText(arguments, "conversationId")); // AgenticAskTool.java:71
    }
}
```

---

## 5. How to use — step-by-step with copy-paste curl

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

Without `rag` profile: `AgenticAskTool` and `AgentService` beans are absent — `tools/list` will not contain `agentic_ask` and the stream endpoint returns 404.

### 1. List tools — confirm `agentic_ask` + 4 delegates are present

```bash
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[] | {name, description}'
# expect 5 entries: api_health, product_search, order_status, docs_search, agentic_ask
```

### 2. Call `agentic_ask` via MCP `tools/call` (single task, multi-step server-side)

```bash
# Simple docs question — agent will call docs_search internally
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"How does order cancellation work in this API?"}}}' \
  | jq -r '.result.content[0].text'

# Cross-cutting task — agent may chain product_search + order_status + docs_search
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"Find products matching \"widget\" and check whether order 7 exists and what its status is."}}}' \
  | jq -r '.result.content[0].text'

# Multi-turn — same conversationId keeps memory (PR #40)
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"agentic_ask","arguments":{"task":"What was my last question?","conversationId":"demo-42"}}}' \
  | jq -r '.result.content[0].text'
```

### 3. Chat / stream endpoint (same agent, non-MCP surface)

```bash
# Streaming SSE — token-by-token (PR #45, same ChatClient with .stream())
curl -N -X POST "http://localhost:8080/api/v1/stream/agent/ask?task=Find+products+matching+widget&conversationId=demo-42" \
  -H "Accept: text/event-stream"
# events: event: token / data: {"text":"..."} ... event: done
```

---

## 6. Key decisions & tradeoffs

### Why `@Tool` + `MethodToolCallbackProvider` instead of hand-rolled `ToolCallback`s

Spring AI's `@Tool` (`AgentToolSet.java:51`) derives `ToolDefinition` + JSON Schema from `@ToolParam` via reflection. `AgentConfig.java:31` centralizes it as one `ToolCallbackProvider` bean for `ChatClient` and tests — cheaper than hand-rolling JSON Schema.

### Why `DefaultToolCallingChatOptions` on `ChatClient` options, not just `.defaultToolCallbacks(...)` — the real gotcha

**`.defaultToolCallbacks(provider)` alone silently does nothing in Spring AI 1.0.0's `ChatClient`.** We verified by decompiling `DefaultChatClientUtils.toChatClientRequest`: tool callbacks are merged into the `Prompt` **only when `ChatOptions instanceof ToolCallingChatOptions`** — an `instanceof` guard. If `chatOptions` is `null` (which it is when you only call `.defaultToolCallbacks(...)`), the branch is skipped and DeepSeek never sees the tools, so it never emits a function call and just hallucinates.

Correct wiring (`AgentService.java:69`):

```java
.defaultOptions(DefaultToolCallingChatOptions.builder()
        .toolCallbacks(List.of(toolCallbacks.getToolCallbacks()))
        .build())
```

Guard test proves it by capturing the real `Prompt` handed to the mock `ChatModel` and asserting `prompt.getOptions() instanceof ToolCallingChatOptions` and `getToolCallbacks().length == 4`. Lesson: `javap -c` on the jar beats forum answers when a documented pattern no-ops.

### Why names intentionally match MCP (`product_search` not `productSearch`)

One stable identity per capability across protocols. `AgentToolSet.java:58` uses `@Tool(name="product_search")` to match `ProductSearchTool.java:30`. A caller can reason "the `product_search` capability" without caring whether it arrived via `tools/call` or via the agent loop. Renaming would split observability, docs, and eval harnesses.

### Why agent surface stays read-only (no `cancel_order`/`confirm_order`)

Security by construction. `AgentToolSet` only wraps `AbstractMcpReadOnlyTool` beans — the guarded write tools (`AbstractMcpWriteTool` subclasses like `cancel_order`) are structurally unreachable. Adding a write tool to the agent would require a deliberate code change, not a config flip — see `docs/additions/04-guarded-write-tool.md`.

### Why `@ConditionalOnProperty(app.rag.enabled)` gates the whole surface

`agentic_ask` needs a `ChatModel` (DeepSeek, requires `DEEPSEEK_API_KEY`) and the RAG docs tool. Gating on `app.rag.enabled` (`AgentToolSet.java:32`, `AgentConfig.java:28`, `AgentService.java:42`) keeps the default app (no profile) at zero AI cost/infra. `AgenticAskTool.java:28` adds `@ConditionalOnBean(AgentService.class)` so the MCP tool disappears when the agent does.

---

## 7. How to verify — curl / MCP + unit tests

### 1. Tools list contains `agentic_ask` only with `rag` profile

```bash
# With rag profile — 5 tools
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
# "api_health" "product_search" "order_status" "docs_search" "agentic_ask"

# Without rag profile — agentic_ask absent (restart without profile)
./mvnw spring-boot:run
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
```

### 2. Observe delegation — run with `--logging.level.org.springframework.ai.tool=DEBUG` then `tools/call agentic_ask {task:"Find widgets and check api health"}`; logs show `Calling tool api_health` / `Calling tool product_search`.

### 3. Unit tests — wiring proof without a real model

```bash
./mvnw test -Dtest=AgentToolSetTest,AgentServiceTest -Dspring.profiles.active=rag
# AgentToolSetTest (5): 4 callbacks under MCP names + api_health/product_search/order_status/docs_search delegation + PII boundary
# AgentServiceTest (4): ask returns answer, blank task → IllegalArgumentException, prompt carries SYSTEM_PROMPT + user task, options instanceof ToolCallingChatOptions with 4 callbacks
```

Key assertion (`AgentServiceTest`): `capturedPrompt.getOptions() instanceof ToolCallingChatOptions` with `getToolCallbacks().length == 4`.

### 4. PII boundary — `agentic_ask` with "show order 1 with customer email" returns `Order 1 | number ... | status ...` but never an email — `OrderStatusTool.format()` (`OrderStatusTool.java:56`) excludes `customer`.

---

## 8. How this helps you on the job — build / operate / interview

- **Build — add an agentic loop without duplicating business logic.** You know the pattern: keep one `execute(Map)` implementation (`AbstractMcpReadOnlyTool.java:124`), expose it twice — as `specification().callHandler` for MCP and as `@Tool` methods in an `AgentToolSet` (`AgentToolSet.java:51`) turned into `ToolCallback[]` by `MethodToolCallbackProvider` (`AgentConfig.java:31`) and carried in `DefaultToolCallingChatOptions` (`AgentService.java:69`). Need a new capability? Add one `AbstractMcpReadOnlyTool` subclass and one `@Tool` delegate — both protocols get it, one test covers it.

- **Operate — reason about cost/failure.** You can explain the `instanceof` gotcha (`.defaultToolCallbacks` silently no-ops) and the `DefaultToolCallingChatOptions` fix (`AgentService.java:69`), that `ask(task, conversationId)` (`AgentService.java:84`) is stateless by default (random UUID) with opt-in memory via `conversationId`, and that `org.springframework.ai.tool=DEBUG` shows each JSON tool decision live.

- **Interview — whiteboard agentic tool calling in 90 seconds with receipts.** "PR #39: four `AbstractMcpReadOnlyTool` beans expose `execute(Map)` (`AbstractMcpReadOnlyTool.java:124`); `AgentToolSet` wraps them as `@Tool(name="product_search")` (`AgentToolSet.java:58`) delegating to `execute`; `AgentConfig` builds `ToolCallbackProvider` (`AgentConfig.java:31`); `AgentService` builds `ChatClient` with `DefaultToolCallingChatOptions{toolCallbacks}` (`AgentService.java:69`) — not just `defaultToolCallbacks` — so the model sees the tools; `AgenticAskTool` (`AgenticAskTool.java:28`) exposes the loop as `agentic_ask {task, conversationId?}`. Spring AI drives `model → tool → model → answer`; `SYSTEM_PROMPT` (`AgentService.java:48`) enforces 'call a tool, no PII, say when stuck'. Verified by capturing `Prompt.getOptions() instanceof ToolCallingChatOptions`."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "What's the difference between MCP `tools/call` and Spring AI `@Tool` function calling? Why have both?"**

> "MCP is the **transport** — an external assistant reaches your tools via `POST /mcp` `tools/call`. Function calling is the **loop** — your own `ChatClient` advertises tools to DeepSeek, the model emits `call product_search({query:"widget"})` as JSON, Spring AI executes `AgentToolSet.productSearch()` (`AgentToolSet.java:58`), appends the result, and re-prompts until a final answer. PR #39 has both because `AbstractMcpReadOnlyTool.execute(Map)` (`AbstractMcpReadOnlyTool.java:124`) is one implementation behind two protocols: `specification()` for MCP and `@Tool` delegates for the agent. `AgenticAskTool` (`AgenticAskTool.java:28`) is the bridge — it is itself an MCP tool whose `run()` is `agentService.ask(task)` (`AgentService.java:84`), so one `tools/call` yields multi-step reasoning."

**Q2: "The model never calls my tool — it just hallucinates. How do you debug Spring AI 1.0.0 wiring?"**

> "First `javap -c DefaultChatClientUtils` — tool callbacks are merged into the `Prompt` only when `ChatOptions instanceof ToolCallingChatOptions`. If you only did `.defaultToolCallbacks(provider)` the options stay `null` and the branch is skipped — the model never sees the tools, so it never emits a function call. Fix is `.defaultOptions(DefaultToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).build())` (`AgentService.java:69`). Prove it with a unit test that captures the `Prompt` passed to a mock `ChatModel` and asserts `getOptions() instanceof ToolCallingChatOptions` with 4 callbacks — `AgentServiceTest` does exactly that. Then turn on `--logging.level.org.springframework.ai.tool=DEBUG` and watch each tool decision live."

**Q3: "How do you keep an agent safe when it can chain tools?"**

> "Limit the surface it can ever reach. `AgentToolSet` (`AgentToolSet.java:31`) only wraps `AbstractMcpReadOnlyTool` beans — `api_health` (`ApiHealthTool.java:17`), `product_search` (`ProductSearchTool.java:20`), `order_status` (`OrderStatusTool.java:20`), `docs_search` (`DocsSearchTool.java:22`). The guarded writes (`cancel_order` etc.) extend `AbstractMcpWriteTool` and are structurally absent from the agent. `OrderStatusTool.format()` (`OrderStatusTool.java:56`) physically excludes the `customer` association, so even a prompt-injected 'show emails' can't leak PII — the data isn't in the tool's return. `SYSTEM_PROMPT` adds the behavioural contract ('no PII, say when stuck'). PR #40 keeps memory opt-in via `conversationId` and stateless by default (random UUID per call, `AgentService.java:88`), and `/mcp` inherits the app's auth. Safety is mostly surface you never create."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #40 picks up

**What PR #39 alone does NOT do (by design):**

- **No chat memory.** Every `ask(task, conversationId)` with a blank `conversationId` gets a fresh `UUID.randomUUID()` (`AgentService.java:88`) — turn 2 forgets turn 1. Ask "what was my last question?" and it has no answer. **PR #40 fixes this** — `AgentConfig.agentChatMemory()` (`AgentConfig.java:38`) + `MessageChatMemoryAdvisor` (`AgentService.java:72`) keep a sliding window (default 20 messages) per stable `conversationId`; `AgenticAskTool` gains `conversationId` in its schema (`AgenticAskTool.java:52`).

- **No streaming.** `ask()` (`AgentService.java:84`) is `prompt().call().content()` — the client waits for the full answer after all tool calls finish. For long chains this feels slow. **PR #45 adds** `askStream()` (`AgentService.java:117`) → `prompt().stream().content()` (Flux) bridged to `SseEmitter` in `AgentStreamingController` (`AgentStreamingController.java:41`, `POST /api/v1/stream/agent/ask`).

- **No audit / session binding.** The MCP `specification()` handler (`AbstractMcpReadOnlyTool.java:67`) accepts optional `McpAuditService` + `AiMetrics`, but PR #39 calls the no-arg overload — no actor/session recorded, no per-tool latency. **PR #48/49 add** session-scoped auth (`contextExtractor` → `mcp_actor`/`mcp_session_id`) and `AiMetrics.recordToolCall` + OTel GenAI traces.

- **Read-only only.** Deliberate — but it means the agent can't complete workflows that need a write (cancel/confirm/ship). **PR #41/50 keep writes off the agent** and gate them on MCP with 4-layer guards (flag + `confirmed=true` + `@PreAuthorize` + state machine + audit). The agent can *explain* cancellation via `docs_search` but never *perform* it.

**Where PR #40 picks up:**

PR #40 is "make `agentic_ask` conversational" — it keeps the exact `ChatClient` wiring from #39, adds `ChatMemory` bean (`AgentConfig.java:38`) and advisor (`AgentService.java:72`), and makes `conversationId` (`AgenticAskTool.java:57`, `AgentService.java:88`) the opt-in key for multi-turn context with `ChatMemory.CONVERSATION_ID` scoping. After #40, `agentic_ask({task:"...widgets...", conversationId:"room-42"})` followed by `agentic_ask({task:"what was my first question?", conversationId:"room-42"})` remembers — same model, same tools, now with memory.

> Next: [`03-chat-memory.md`](./03-chat-memory.md) (PR #40) — or for the RAG productionization path — [`05-rag-productionization.md`](./05-rag-productionization.md) (PR #42).

