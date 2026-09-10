# 09. MCP Resources & Prompts: making the protocol a read-the-world surface

> **PR #46** — PR #37 exposed the MCP server with only **tools** (`tools/list`,
> `tools/call`). By design, tools made the assistant *do* things. But the MCP
> protocol defines three surfaces, and the other two were missing: **resources**
> (listable, readable *documents*) and **prompts** (parameterized instruction
> templates). This PR completes the MCP surface so a client can read the project
> documentation verbatim and fetch canned instruction templates — without ever
> executing a tool.

---

## 1. The one-paragraph mental model

The Model Context Protocol gives the assistant three ways to interact with a
server:

- **Tools** — *things to call.* Functions that execute: query orders, search
  products, cancel an order. The assistant decides when to run them.
- **Resources** — *things to read.* Documents and data: a PDF, a spec file, a
  database row. The client can enumerate them (`resources/list`) and fetch any
  one verbatim (`resources/read`). No side effects. Read-only by definition.
- **Prompts** — *things to follow.* Reusable instruction templates: "summarise
  that order", "answer from the docs". The client fetches the rendered messages
  via `prompts/get` and injects them into its conversation with the model.

> Resource `doc://…` = **the document itself**. Tool `docs_search` = **the
> answer the model built from it**. The distinction is subtle but crucial: a
> resource is *the ground truth a client can cite*; a tool result is *the
> model's claim about it*.

---

## 2. Why this matters (what was missing)

Before this PR the MCP server said *"I can do things"* but never *"I have
content"*. Consider an assistant that needs to brief an engineer on how the
locking works:

- **With only tools:** it can only run `docs_search` and trust whatever
  generated answer comes back — an opaque summary, possible hallucination,
  unverifiable, uncitable.
- **With resources:** it lists the corpus, finds `doc://business/db/…`, reads
  the *markdown source itself*, and can quote it or cite it directly.

And consider a support agent who must summarise an order every shift:

- **With only tools:** every session reinvents the instruction set ("call
  order_status, only use returned facts, never leak PII, end with…").
- **With prompts:** the `summarize_order` template packages those rules once,
  parameterized by order id, and any client (Claude Desktop, a custom app, a
  REPL) renders it via `prompts/get`.

Resources remove the *trust* problem; prompts remove the *repetition* problem.

---

## 3. The protocol shapes (what a wire exchange looks like)

### `resources/list` → `resources/read`

```
CLIENT                              SERVER
  │── resources/list ───────────────►│
  │◄── { resources: [                  │
  │      { uri: "openapi://spec",       │
  │        name: "OpenAPI specification",
  │        mimeType: "application/yaml" },
  │      { uri: "doc://business/…",   │
  │        name: "docs/business/…",   │
  │        mimeType: "text/markdown" } ] }
  │── resources/read {uri:"openapi://spec"} ►
  │◄── { contents: [ { uri, mimeType,
  │                    text: "openapi: 3.1.0 …\n …" } ] }
```

### `prompts/list` → `prompts/get`

```
  │── prompts/list ─────────────────►│
  │◄── { prompts: [                    │
  │      { name: "summarize_order",    │
  │        description: "…",           │
  │        arguments: [orderId req] }, │
  │      { name: "ask_docs", … } ] } │
  │── prompts/get {name:"summarize_order",
  │                arguments:{orderId:7}} ►
  │◄── { messages: [
  │      { role: USER, content: "You are a support agent…" },
  │      { role: USER, content: "Please summarise order 7." } ] }
```

Note the three "verbs" encode MCP's trust model: **list** (enumerate what
exists), **read/get** (fetch a specific thing), **call** (perform an action).
Only tools get the third verb.

---

## 4. The code (what changed)

### New files

| File | Purpose |
|------|---------|
| `mcp/AbstractMcpResource.java` | Contract for any resource: `uri()`, `name()`, `description()`, `mimeType()`, `read()` → SDK `SyncResourceSpecification` |
| `mcp/McpDocsResourceCatalog.java` | Discovers `docs/**/*.md` + `docs/api/openapi.yaml` on the classpath and serves them as resources |
| `mcp/AbstractMcpPrompt.java` | Contract for any prompt: `name()`, `description()`, `arguments()`, `messages(args)` → SDK `SyncPromptSpecification` |
| `mcp/SummarizeOrderPrompt.java` | The `summarize_order` template — persona + PII rules, parameterized by `orderId` |
| `mcp/DocsQuestionPrompt.java` | The `ask_docs` template — "answer from docs_search context, name your sources" |

### Modified files

| File | Change |
|------|--------|
| `mcp/McpServerConfiguration.java` | Injects the catalog + prompt beans; registers `.resources(...)` / `.prompts(...)`; advertises `resources`/`prompts` in **capabilities** |
| `mcp/McpServerSdkIntegrationTest.java` | 4 new end-to-end tests: resources/list, resources/read (spec + a markdown file), prompts/list, prompts/get |

### McpDocsResourceCatalog — discovery + lazy reads

```java
@Component
public class McpDocsResourceCatalog {
    private static final String DOCS_GLOB = "docs/**/*.md";       // same glob as RAG ingestion
    private static final String OPENAPI_PATH = "docs/api/openapi.yaml";

    // In the constructor we DISCOVER the corpus (names/URIs/mime types).
    // The actual file content is read LAZILY inside read() at read time.

    private void scan(String pattern) throws IOException {
        for (Resource file : resolver.getResources(pattern)) {
            if (file.isReadable()) {
                resources.add(MarkdownResource.of(filePath(file), file));
            }
        }
    }

    public List<SyncResourceSpecification> specifications() {
        return resources.stream().map(AbstractMcpResource::specification).toList();
    }
}
```

**Why lazy reads?** `resources/list` should be fast no matter how many files
exist. The corpus size is discovered once at boot; content is only loaded for
the one URI a client actually asks for — and edits are picked up without a
restart. (Contrast: the RAG pipeline eagerly chunks+embeds everything at
ingest, because search needs the vectors — a resource read does not.)

### AbstractMcpResource — the single shape exposed twice

```java
public final McpStatelessServerFeatures.SyncResourceSpecification specification() {
    McpSchema.Resource resource = McpSchema.Resource.builder()
            .uri(uri()).name(name()).description(description()).mimeType(mimeType())
            .build();

    return new McpStatelessServerFeatures.SyncResourceSpecification(
            resource, (tc, request) ->
                    new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(uri(), mimeType(), read()))));
}
```

One method produces both protocol shapes: the **descriptor** object (for
`resources/list`) and the **read handler** (for `resources/read`). The SDK
derives the JSON-RPC responses from this single specification.

### McpServerConfiguration — wiring, in three moves

```java
McpServer.sync(transport)
        .serverInfo("order-management-api-mcp", "1.0.0")
        // 1. advertise: the client learns these surfaces exist
        .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(false, false)   // (subscribe, listChanged) - both off
                .prompts(false)            // listChanged off
                .build())
        // 2. register every discovered doc + the OpenAPI spec as resources
        .resources(docsCatalog.specifications()
                .toArray(SyncResourceSpecification[]::new))
        // 3. register every prompt bean, sorted by name
        .prompts(prompts.stream()
                .sorted(Comparator.comparing(AbstractMcpPrompt::name))
                .map(AbstractMcpPrompt::specification)
                .toArray(SyncPromptSpecification[]::new))
        .build();
```

**The adjacent-cool parameter:** `resources(false, false)` — *subscribe*
(is the client allowed to ask for change notifications?) and *listChanged* (will
the server send them?). Both are false: resources are static files whose writes
don't need a push notification layer today. Turning them on later is a
capability advertisement change, not a code rewrite.

---

## 5. Key decisions

### Why a catalog rather than hand-written resources?

The corpus is already on the classpath (the `pom.xml` `<resource>` block copies
`docs/` → `docs/`). Hand-registering each file would be friendlier to typos
than to maintenance. The catalog reuses the exact glob the RAG ingestion uses —
**one source of truth for "what is documentation"**. New files appear as
resources automatically.

### Why prompts over hard-coded system prompts?

A prompt template is downloadable, inspectable, versioned with the server, and
transparent to the *user* (they can see the instructions the model is about to
receive). Coaxing the same instructions into an unprompted client corpus is
fragile — some other assistant might phrase its own instructions and leak
customer data. Prompts standardize the safe path.

### PromptMessage roles are USER/ASSISTANT only — where's SYSTEM?

MCP's `Role` enum has exactly `USER` and `ASSISTANT`. So the persona + rules
message travels as a *USER-role* instruction message: "You are a support
agent… Please summarise order 7." The semantic intent (system) is carried by
**message order and content**, not by a role we don't have. Worth knowing when
you render `prompts/get` results into a model call — map the first USER message
to your framework's SystemMessage.

### Why the toolkit stays read-only

The prompt templates *reference* `order_status`/`docs_search` ("call the tool
to fetch facts") but register **no** new tools. The write tools stay behind
`app.mcp.write-tool.enabled` and their `confirmed=true` stage gate. A prompt
can suggest a safe workflow; it can never widen the tool surface by itself.

---

## 6. What to try

```bash
# Boot the app with the RAG profile (needs DEEPSEEK_API_KEY + Ollama)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag

# From another shell: point any MCP client at POST /mcp.
# With the official CLI, or the raw Streamable-HTTP transport:
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"resources/list"}'
```

Then:
1. `resources/list` → find `openapi://spec` and the `doc://…` URIs
2. `resources/read` with `{"uri":"openapi://spec"}` → get the whole YAML back
3. `prompts/list` → the two template names
4. `prompts/get` with `{"name":"summarize_order","arguments":{"orderId":7}}`
   → the rendered persona + task messages
5. `tools/call product_search …` → tools still work exactly as before

---

## 7. Honest limits

1. **No resource templates.** The SDK supports URI-template resources
   (`storage://orders/{id}`) so a client can *discover* a resource address by
   pattern — not implemented here; our corpus is a flat file list.

2. **No `resources/updated` notifications.** `subscribe`/`listChanged` are off,
   so clients don't get push events when docs change. Fine for files; a
   constraint if resources ever wrap live database rows.

3. **Static discovery at boot.** The catalog scans once at construction. A file
   added mid-run won't appear until restart (though *content* of existing files
   refreshes on each read).

4. **Prompts are inert without tools.** `ask_docs` and `summarize_order` assume
   the accompanying read-only tools exist. A client that fetches a prompt needs
   the full capability set to act on it — worth documenting per-prompt.

---

*Next: PR #47 — MCP Authorization: OAuth 2.1 + PKCE for real identity on `/mcp`.*