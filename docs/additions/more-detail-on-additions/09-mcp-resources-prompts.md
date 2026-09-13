# 09. MCP Resources & Prompts — The Full Protocol Surface (PR #46)

> PR: [#46 — MCP resources & prompts completing the protocol surface](https://github.com/anomalyco/order-management-api/pull/46) · Profile: `rag` not required · Stack: Spring Boot 3.x + MCP Java SDK `McpStatelessServerFeatures` + `WebMvcStatelessServerTransport` at `POST /mcp` · Depends on [#37 MCP server + tools](./02-agentic-tool-calling.md) · Complements [#38 RAG docs_search](./01-rag-and-docs-search.md)

---

## 1. Purpose — what shipped

PR #46 completes the MCP server beyond **tools** (`tools/list` + `tools/call` from PR #37). The MCP spec defines three surfaces — tools (do), resources (read), prompts (follow) — and we shipped only one. This PR adds the other two.

After it, a client can **enumerate and read** the project documentation verbatim without executing any tool:

- **`resources/list` + `resources/read`** via `McpDocsResourceCatalog.java:35` — every `docs/**/*.md` (`:39`) plus `docs/api/openapi.yaml` (`:40`) exposed as a `SyncResourceSpecification` (`AbstractMcpResource.java:44`). Discovery scans the same classpath glob RAG ingestion uses, so a new markdown file appears automatically on next boot (`McpDocsResourceCatalog.java:51`).
- **`prompts/list` + `prompts/get`** via `AbstractMcpPrompt.java:22` — two reusable instruction templates: `summarize_order` (`SummarizeOrderPrompt.java:20`) and `ask_docs` (`DocsQuestionPrompt.java:20`). Each is a `SyncPromptSpecification` (`AbstractMcpPrompt.java:41`) registered alongside resources in `McpServerConfiguration.java:99`.

Tools answer *"what can you do?"*; resources answer *"what do you have?"*; prompts answer *"how should you do it?"*. Together they make the server a **read-the-world surface** (`doc://…` is the document itself; `docs_search` is the model's claim about it).

---

## 2. Problem — only tools, missing resources/prompts

**Before PR #46** the MCP server advertised a single capability:

| Before (tools only) | After (PR #46 — full surface) |
|---|---|
| `McpServerConfiguration.java:118-125` built only `tools(...)` — `resources` and `prompts` absent; `ServerCapabilities` had `tools=true` only | `McpServerConfiguration.java:120-131` advertises `resources(false,false)` + `prompts(false)` and registers `.resources(docsCatalog.specifications())` (`:126`) + `.prompts(prompts.stream()…)` (`:128`) |
| To brief an engineer on locking, assistant could only call `docs_search` and trust the generated summary — opaque, uncitable, possibly hallucinated | Lists `doc://` corpus (`resources/list`), finds `doc://docs/business/…`, reads markdown source via `resources/read` (`AbstractMcpResource.java:41`) — citable ground truth |
| Support workflow ("summarise order 7, hide PII, cite total/status") reinvented every session as ad-hoc system prompt | `summarize_order` (`SummarizeOrderPrompt.java:23`) packages persona + rules + `orderId` param once; any client renders via `prompts/get` (`AbstractMcpPrompt.java:45`) |
| Client cannot discover what docs exist without asking the model | `McpDocsResourceCatalog.java:62` enumerates all `docs/**/*.md` at boot into `AbstractMcpResource` descriptors (`:176`) — listable without generation cost |
| Prompts were invisible — each consumer hard-coded its own instructions | `DocsQuestionPrompt.java:23` standardizes "call `docs_search`, answer from context, name sources" (`:33`) — one versioned, inspectable template |

In short: the project had a rich `docs/` corpus reachable only through inference. The protocol's **non-execution read path** was missing.

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 The three MCP verbs and what they map to

```
  MCP client                    MCP server (POST /mcp)               Backing store
  ──────────                    ────────────────────────               ─────────────
  tools/list  ───────────────►  McpServerConfiguration:99            Tool beans
  tools/call  ───────────────►  tools[]:109-116                      RAG / DB
  resources/list ────────────►  McpDocsResourceCatalog:35            docs/**/*.md
  resources/read ────────────►  AbstractMcpResource:44               file.read()
  prompts/list ──────────────►  AbstractMcpPrompt:41                 Prompt beans
  prompts/get  ──────────────►  messages(args):38                    template rendering

  list  = enumerate what exists        (descriptors)
  read/get = fetch one thing verbatim (contents / messages)
  call  = execute side-effect          (only tools)
```

### 3.2 `resources/list` → `resources/read`

```
CLIENT                              SERVER (McpDocsResourceCatalog)
  │── resources/list ───────────────►│
  │  jsonrpc 2.0 id:1                │  descriptors() :176 builds
  │◄── { resources: [                │    McpSchema.Resource{ uri, name, description, mimeType }
  │       { uri:"openapi://spec",    │    from each AbstractMcpResource :45
  │         name:"OpenAPI specification",
  │         mimeType:"application/yaml" },
  │       { uri:"doc://docs/business/01-orders.md",
  │         name:"docs/docs/business/01-orders.md",
  │         mimeType:"text/markdown" }, … ] }
  │── resources/read ───────────────►│  specification() read handler :52
  │  { uri:"openapi://spec" }        │  → new TextResourceContents(uri, mimeType, read()) :53-54
  │◄── { contents:[                  │    read() is LAZY — file.getContentAsString :114 / :149
  │        { uri, mimeType,          │
  │          text:"openapi: 3.1.0\n…"} ] }
```

### 3.3 `prompts/list` → `prompts/get`

```
CLIENT                              SERVER (AbstractMcpPrompt beans)
  │── prompts/list ─────────────────►│  specification() :41 builds McpSchema.Prompt
  │◄── { prompts:[                   │    { name, description, arguments[] } :42
  │       { name:"summarize_order",  │    from SummarizeOrderPrompt :23 / DocsQuestionPrompt :23
  │         arguments:[{name:orderId, required:true}] },
  │       { name:"ask_docs",         │
  │         arguments:[{name:question, required:true}] } ] }
  │── prompts/get ──────────────────►│  messages(args) :38 renders List<PromptMessage>
  │  { name:"summarize_order",       │  SummarizeOrderPrompt:40 parses orderId (Number or String)
  │    arguments:{orderId:7} }       │  → List.of(userMessage(system), userMessage(task)) :57
  │◄── { description:"…",            │
  │       messages:[                 │  userMessage() :49 / assistantMessage() :57 — role is USER/ASSISTANT only
  │         { role:USER, content:{ text:"You are a support agent…" } },
  │         { role:USER, content:{ text:"Please summarise order 7." } } ] }
  │     // client injects messages into its own model conversation
```


---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java:35` | **Discovery catalog** — `DOCS_GLOB="docs/**/*.md"` (`:39`) + `OPENAPI_PATH="docs/api/openapi.yaml"` (`:40`); `discover()` (`:51`) scans via `PathMatchingResourcePatternResolver` (`:42`), `MarkdownResource` inner class (`:78`) + anonymous `openapi://spec` resource (`:124`), sorts (`:71`), exposes `specifications()` (`:167`) and `descriptors()` (`:176`) |
| `src/main/java/com/company/orderapi/mcp/AbstractMcpResource.java:22` | **Resource contract** — `uri()` (`:25`), `name()` (`:28`), `description()` (`:31`), `mimeType()` (`:34`), `read()` (`:41`); `specification()` (`:44`) builds `McpSchema.Resource` + `SyncResourceSpecification` with `TextResourceContents` (`:53-55`) |
| `src/main/java/com/company/orderapi/mcp/AbstractMcpPrompt.java:22` | **Prompt contract** — `name()` (`:25`), `description()` (`:28`), `arguments()` (`:31`), `messages(args)` (`:38`); `specification()` (`:41`) builds `McpSchema.Prompt` + `SyncPromptSpecification`; helpers `userMessage()` (`:49`) / `assistantMessage()` (`:55`) |
| `src/main/java/com/company/orderapi/mcp/SummarizeOrderPrompt.java:20` / `DocsQuestionPrompt.java:20` | **Prompt templates** — `summarize_order` (`:23`, `orderId` `:35`, dual `USER` `:40-57`) + `ask_docs` (`:23`, `question` `:34`, `docs_search` `:42-47`) — both `@Component` `AbstractMcpPrompt` :22 |
| `src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:57` | **Server wiring** — `mcpTransport()` (`:62`) + `mcpRouterFunction()` (`:94`) + `mcpServer()` (`:99`) registers `readTools`/`writeTools` (`:108-116`), `docsCatalog` (`:126`), `prompts` (`:128`) and advertises `ServerCapabilities` (`:120`) |
| `src/test/java/com/company/orderapi/mcp/McpServerSdkIntegrationTest.java:32` | **Integration tests** — 4 E2E cases: `resources/list`, `resources/read` (spec + markdown file), `prompts/list`, `prompts/get` over real `WebMvcStatelessServerTransport` |

### Snippet 1 — `McpDocsResourceCatalog` discovery + lazy reads (`McpDocsResourceCatalog.java:51-119`)

```java
// src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java:51
private void discover() {
    try { scan(DOCS_GLOB); } // "docs/**/*.md" :39 — same glob as RAG ingestion
    catch (IOException e) { log.warn("MCP resources: could not scan '{}': {}", DOCS_GLOB, e.getMessage()); }
    registerOpenApiSpec(); // always adds openapi://spec :57
    log.info("MCP resources: exposed {} documentation resources", resources.size());
}
// src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java:62
private void scan(String pattern) throws IOException {
    Resource[] files = resolver.getResources(pattern); // :63 classpath scan
    for (Resource file : files) {
        if (!file.isReadable()) continue; // :65
        resources.add(MarkdownResource.of(filePath(file), file)); // :69
    }
    resources.sort((a, b) -> a.uri().compareTo(b.uri())); // :71 deterministic order
}
// src/main/java/com/company/orderapi/mcp/McpDocsResourceCatalog.java:78
static final class MarkdownResource extends AbstractMcpResource {
    public String uri() { return "doc://" + relativePath; } // :93
    public String name() { return "docs/" + relativePath; } // :98
    public String mimeType() { return "text/markdown"; } // :108
    public String read() { // :112 LAZY — every resources/read hits the file
        return file.getContentAsString(StandardCharsets.UTF_8); // :114
    }
}
```


### Snippet 2 — `AbstractMcpResource.specification()` (`AbstractMcpResource.java:44-57`)

```java
// src/main/java/com/company/orderapi/mcp/AbstractMcpResource.java:44
public final McpStatelessServerFeatures.SyncResourceSpecification specification() {
    McpSchema.Resource resource = McpSchema.Resource.builder()
            .uri(uri()).name(name()).description(description()).mimeType(mimeType()).build(); // :45-50
    return new McpStatelessServerFeatures.SyncResourceSpecification(
            resource, (transportContext, request) -> { // :52 read handler
                McpSchema.TextResourceContents contents =
                        new McpSchema.TextResourceContents(uri(), mimeType(), read()); // :53-54
                return new McpSchema.ReadResourceResult(List.of(contents)); // :55
            });
}
```

One method yields both shapes: descriptor (`resources/list`) and handler (`resources/read`).

### Snippet 3 — `AbstractMcpPrompt.specification()` (`AbstractMcpPrompt.java:41-53`)

```java
// src/main/java/com/company/orderapi/mcp/AbstractMcpPrompt.java:41
public final McpStatelessServerFeatures.SyncPromptSpecification specification() {
    McpSchema.Prompt prompt = new McpSchema.Prompt(name(), description(), arguments()); // :42
    return new McpStatelessServerFeatures.SyncPromptSpecification(
            prompt, (transportContext, request) ->
                    new McpSchema.GetPromptResult(description(), messages(request.arguments()))); // :44-45
}
// src/main/java/com/company/orderapi/mcp/AbstractMcpPrompt.java:49
protected static McpSchema.PromptMessage userMessage(String text) {
    return new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text)); // :50-52
}
```


### Snippet 4 — Wiring in `McpServerConfiguration` (`McpServerConfiguration.java:99-132`)

```java
// src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:99
@Bean(destroyMethod = "close")
public McpStatelessSyncServer mcpServer(WebMvcStatelessServerTransport transport,
        List<AbstractMcpReadOnlyTool> readTools, List<AbstractMcpWriteTool> writeTools,
        McpDocsResourceCatalog docsCatalog, List<AbstractMcpPrompt> prompts, ...) {
    return McpServer.sync(transport)
            .serverInfo("order-management-api-mcp", "1.0.0") // :119
            .capabilities(McpSchema.ServerCapabilities.builder()
                    .tools(true).resources(false, false).prompts(false).build()) // :120-124
            .tools(specifications.toArray(SyncToolSpecification[]::new)) // :125
            .resources(docsCatalog.specifications().toArray(SyncResourceSpecification[]::new)) // :126-127
            .prompts(prompts.stream().sorted(Comparator.comparing(AbstractMcpPrompt::name))
                    .map(AbstractMcpPrompt::specification).toArray(SyncPromptSpecification[]::new)) // :128-131
            .build();
}
```

`resources(false,false)` = subscribe off, listChanged off (`McpDocsResourceCatalog.java:184` comment). `prompts(false)` = listChanged off. Turning either on is a capability flag, not a rewrite.

---

## 5. How to use — curl over `POST /mcp` (Streamable HTTP)

### Prerequisites

```bash
./mvnw spring-boot:run                                # no rag profile needed — resources/prompts are not RAG-gated
# wait for: MCP resources: exposed 47 documentation resources  McpDocsResourceCatalog.java:58
# server on http://localhost:8080 — transport at POST /mcp  McpServerConfiguration.java:59
```

`WebMvcStatelessServerTransport` speaks JSON-RPC 2.0; every call needs `Content-Type: application/json` + `Accept: application/json, text/event-stream`.

### `resources/list` — enumerate what exists

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}' | jq .
# {
#   "result": { "resources": [
#     { "uri":"openapi://spec","name":"OpenAPI specification","mimeType":"application/yaml",
#       "description":"The full OpenAPI specification (YAML) …" },
#     { "uri":"doc://docs/business/01-orders.md","name":"docs/docs/business/01-orders.md",
#       "mimeType":"text/markdown","description":"Order Management API documentation: docs/business/01-orders.md" },
#     … 47 entries sorted by uri  McpDocsResourceCatalog.java:71
#   ]}
# }
```

### `resources/read` — fetch one document verbatim

```bash
# OpenAPI spec
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"openapi://spec"}}' \
  | jq -r '.result.contents[0].text' | head -20
# openapi: 3.1.0
# info: { title: Order Management API … }

# Any markdown file — uri from the list above
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"doc://docs/business/01-orders.md"}}' \
  | jq -r '.result.contents[0].text' | head -40

# Unknown uri → McpError (SDK maps missing resource to error, not empty text)
```

### `prompts/list` + `prompts/get` — fetch instruction templates

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":4,"method":"prompts/list","params":{}}' | jq .
# { "result": { "prompts": [
#   { "name":"ask_docs","description":"Ask a documentation question …","arguments":[{"name":"question","required":true}] },
#   { "name":"summarize_order","description":"Produce a customer-safe summary …","arguments":[{"name":"orderId","required":true}] }
# ]}}

curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"summarize_order","arguments":{"orderId":7}}}' | jq .
# { "result": {
#     "description":"Produce a customer-safe summary …",
#     "messages":[
#       { "role":"user","content":{"type":"text","text":"You are a support agent … order 7 … Rules: …"} },
#       { "role":"user","content":{"type":"text","text":"Please summarise order 7."} }
#     ]}}
# Inject messages into your model call, then call order_status tool — prompt + tool compose

curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":6,"method":"prompts/get","params":{"name":"ask_docs","arguments":{"question":"How does hybrid retrieval work?"}}}' | jq .
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

### Catalog vs hand-written resources — one source of truth

The corpus is already on the classpath via `pom.xml` `<resource>` copying `docs/**` — hand-registering each file invites typos and drift. `McpDocsResourceCatalog.java:39` reuses the **exact glob** `docs/**/*.md` that `DocumentIngestionService` uses for RAG. Result: adding `docs/business/new-feature.md` exposes it as `doc://…` automatically; no code change, no missed registration. The only manual entry is `openapi://spec` (`:122`) because its mime (`application/yaml`) and URI are stable and distinct.

### Why lazy `read()` not eager load — `resources/list` stays fast

`scan()` (`:62`) stores a `Resource` handle + `relativePath`, not the file text. `MarkdownResource.read()` (`:112`) calls `file.getContentAsString(UTF_8)` (`:114`) on every `resources/read`. That means `resources/list` is O(file count) for descriptors only, while large docs cost nothing until requested — and edits to an existing file are reflected without a restart. 
### Why `USER` role only in prompts — MCP's `Role` is `USER`/`ASSISTANT`

`AbstractMcpPrompt.java:49-60` provides `userMessage()`/`assistantMessage()` and no `systemMessage()`. The SDK's `McpSchema.Role` has exactly two values. So `SummarizeOrderPrompt.java:57` sends persona+rules as a `USER` instruction before the task: `"You are a support agent… Rules: …"` → `"Please summarise order 7."` The intent (system) is carried by **message order and content**, not by a role we don't have. When rendering into a model call, map the first `USER` message to your framework's `SystemMessage` if needed.

### Why `resources(false,false)` / `prompts(false)` — capabilities off by default

`McpServerConfiguration.java:122` sets `resources(false,false)` = (subscribe, listChanged) both false and `prompts(false)` = listChanged false. Subscriptions would let clients watch for `resources/updated` push events — unnecessary for static files whose writes don't need a notification layer. Turning them on later is a `ServerCapabilities` flag change, not a code rewrite. Keeping them false avoids promising push semantics we don't implement.

### Why prompts don't create tools — read-only surface stays honest

`SummarizeOrderPrompt.java:30` and `DocsQuestionPrompt.java:29` reference tools in text ("call `order_status`/`docs_search` to fetch facts") but register **no** new tools. Write tools remain behind `app.mcp.write-tool.enabled` and `confirmed=true`. A prompt suggests a safe workflow; it cannot widen the tool surface by itself.

### Failures Hit — what broke

PR #46 first advertised resources without lazy reads — `resources/list` loaded every file content, making it O(corpus) slow. Fix: `McpDocsResourceCatalog.java:112` discovers URIs at boot but reads `TextResourceContents` only on `resources/read`. The second failure was prompts using a SYSTEM role; MCP `Role` enum (`AbstractMcpPrompt.java:22`) only allows USER/ASSISTANT, so the persona was moved into a USER message.

**Payload box — resource vs prompt URIs:**

```json
// resources/list
{"resources":[{"uri":"openapi://spec","name":"OpenAPI spec","mimeType":"application/yaml"},
              {"uri":"doc://business/db.md","name":"docs/business/db.md","mimeType":"text/markdown"}]}
// resources/read
{"contents":[{"uri":"doc://business/db.md","mimeType":"text/markdown","text":"# DB..."}]}
// prompts/get summarize_order
{"messages":[{"role":"USER","content":"You are a support agent... summarise order 7"}]}
```

---

## 7. How to verify — curl + integration tests + logs

### 1. Curl — the four protocol calls

```bash
# resources/list — expect at least 2 URIs (openapi + one doc)
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"resources/list"}' | jq '.result.resources | length'
# ≥ 40 (47 docs + openapi://spec)

# resources/read — spec
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"openapi://spec"}}' \
  | jq -r '.result.contents[0].mimeType'
# application/yaml  McpDocsResourceCatalog.java:143

# prompts/list
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"prompts/list"}' | jq '.result.prompts[].name'
# "ask_docs"  DocsQuestionPrompt.java:24
# "summarize_order"  SummarizeOrderPrompt.java:24

# prompts/get — check defensive parsing of orderId (Number vs String)  SummarizeOrderPrompt.java:41
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"summarize_order","arguments":{"orderId":"42"}}}' \
  | jq '.result.messages[].content.text' | head
```

### 2. Integration tests — `McpServerSdkIntegrationTest.java:32`

```bash
./mvnw test -Dtest=McpServerSdkIntegrationTest
# 4 cases: resources/list returns descriptors sorted by uri :71
#          resources/read openapi://spec returns YAML :143
#          resources/read doc://… returns markdown :108
#          prompts/list + prompts/get render USER messages :57
# Uses real WebMvcStatelessServerTransport on random port — no mocks
```

### 3. Startup log + boot-time sort guarantee

```bash
./mvnw spring-boot:run 2>&1 | grep "MCP resources"
# MCP resources: exposed 48 documentation resources  McpDocsResourceCatalog.java:58
# If "no documentation resources" — check pom.xml <resource> copies docs/ and DOCS_GLOB :39
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build — expose any private corpus as a listable resource surface.** Copy `AbstractMcpResource.java:22` → implement `uri()/name()/description()/mimeType()/read()` (`:25-41`) → add a catalog like `McpDocsResourceCatalog.java:35` that scans your sources (files, DB rows, API listings) at boot → register `docsCatalog.specifications()` in `McpServerConfiguration.java:126`. Prompts follow the same copy: extend `AbstractMcpPrompt.java:22`, implement `name()/description()/arguments()/messages()` (`:25-38`), add `@Component`; `McpServerConfiguration.java:128` picks it up automatically. No transport code, no manual routing.

- **Operate — cost and discoverability.** `resources/list` costs no model tokens; `resources/read` costs no embedding — just a file read (`:114`). Clients cache descriptors and fetch only needed docs, reducing `docs_search` calls for citable answers. `resources(false,false)` (`McpServerConfiguration.java:122`) means no subscription state to manage. Monitor `MCP resources: exposed N` (`:58`) at boot — a drop signals a missing `pom.xml` resource copy or a broken OpenAPI path.

- **Interview — whiteboard the full protocol in 90s.** "PR #46: `McpDocsResourceCatalog` (`:35`) scans `docs/**/*.md` (`:39`) + `openapi://spec` (`:40`) into `AbstractMcpResource` (`:22`) whose `specification()` (`:44`) yields both `resources/list` descriptor and `resources/read` handler with lazy `read()` (`:112`); `AbstractMcpPrompt` (`:22`) yields `prompts/list`+`prompts/get`, e.g. `summarize_order` (`SummarizeOrderPrompt.java:23`) with `USER`-role dual messages (`:57`) composing with `order_status`. Wired in `McpServerConfiguration.java:126-131` with `ServerCapabilities` (`:120`). Verified by `McpServerSdkIntegrationTest.java:32` + `curl resources/list→read` + `prompts/list→get`."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "You have an MCP server with only tools. An assistant must cite project docs verbatim — what is missing and how do you add it?"**

> "Before PR #46 the server only spoke `tools/list`+`tools/call` (`:37`). `docs_search` (`:38`) returns a *generated* answer — uncitable. Add **resources**: `AbstractMcpResource` (`:22`) with `uri()/read()` (`:25/:41`) → `McpDocsResourceCatalog` (`:35`) scanning `docs/**/*.md` (`:39`) plus `openapi://spec` (`:124`) → `specification()` (`:44`) turning each into a `SyncResourceSpecification` with `TextResourceContents` (`:53`) → register `.resources(docsCatalog.specifications())` (`McpServerConfiguration.java:126`) and advertise `resources(false,false)` (`:122`). Then `resources/list` enumerates `doc://docs/business/01-orders.md` (`McpDocsResourceCatalog.java:93`); `resources/read` returns `text/markdown` verbatim (`:108/:114`). Cite the resource URI, not a tool output."

**Q2: "Why are your prompt messages USER-role, and do prompts execute tools?"**

> "MCP's `McpSchema.Role` is `USER`/`ASSISTANT` only — no `SYSTEM` (`AbstractMcpPrompt.java:49`). `SummarizeOrderPrompt` (`:20`) renders two `USER` messages (`:57`) — persona+rules then task — so the system intent is **order+content**, not role. Map the first `USER` message to `SystemMessage` in your framework if needed. Prompts never execute: `messages(args)` (`:38`) returns `List<PromptMessage>`; the client injects them and decides to call `order_status`/`docs_search` (`SummarizeOrderPrompt.java:30`/`DocsQuestionPrompt.java:29`). A prompt suggests workflow; it cannot widen the tool surface — write tools stay gated."

**Q3: "Catalog vs hand-written resources — and why lazy reads plus discovery at boot?"**

> "Hand-written registration drifts when `docs/` grows; the catalog (`McpDocsResourceCatalog.java:35`) reuses the RAG glob `docs/**/*.md` (`:39`) — one source of truth, new files appear on restart. `discover()` (`:51`) stores `MarkdownResource` handles (`:78`) sorted (`:71`); `resources/list` is O(count) descriptors. `read()` (`:112`) is lazy — `file.getContentAsString(UTF_8)` (`:114`) only on `resources/read`, so `list` stays fast and edits to existing files show without restart. Static scan at boot is the tradeoff: a file added mid-run needs a restart to appear, but avoids file-watch complexity. Verified `McpServerSdkIntegrationTest` + `curl resources/list | jq length` + startup `exposed N` (`:58`)."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #47+ picks up

**What PR #46 alone does NOT do (by design):**

- **No resource templates.** SDK supports URI-template resources (`storage://orders/{id}`) so a client discovers an address by pattern — not implemented; corpus is a flat file list (`McpDocsResourceCatalog.java:39`). Good fit if resources ever wrap live DB rows.
- **No `resources/updated` notifications.** `subscribe`/`listChanged` are `false` (`McpServerConfiguration.java:122`) — clients get no push when docs change. Fine for files; a constraint if resources wrap `mcp_tool_audit`.
- **Static discovery at boot.** `discover()` (`:51`) runs once; a file added mid-run is invisible until restart. Content of *existing* files refreshes lazily (`:112`), but presence does not. A file-watcher could fix this with `listChanged:true`.
- **Prompts compose but don't enforce.** `summarize_order`/`ask_docs` (`:20`) remind the model to call tools and cite sources, but nothing prevents a model from ignoring the prompt. Enforcement stays in `SYSTEM_PROMPT` (`RagService.java:34`) and tool-level checks.
- **No per-prompt authorization.** Like PR #37 tools before #48, prompts and resources are readable by any `POST /mcp` caller — no `client_id` scoping. PR #48's `contextExtractor` (`McpServerConfiguration.java:79`) → `mcp_actor` is the model for fixing this.

**Where it picks up:** PR #47-48 add OAuth `client_id` → `mcp_actor`/`mcp_session_id` scoping that can gate resources/prompts; PR #42-45 harden RAG behind resources (`reindex_docs` + hybrid + streaming).

> Next: [`10-mcp-authorization-oauth2.md`](../10-mcp-authorization-oauth2.md) (PR #48) — or back to [`README.md`](./README.md).

