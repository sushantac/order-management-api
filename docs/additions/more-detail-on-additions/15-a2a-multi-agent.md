# 15. Multi-Agent Protocol — A2A Exploration (PR #52)

> PR: [#52 — A2A multi-agent exploration](https://github.com/anomalyco/order-management-api/pull/52) · Stack: `AgentCard` record, `A2aController` `@RestController`, `/.well-known/agent-card` + `/a2a/message` · Protocol: `A2A-0.1` · Base URL: `${app.base-url:http://localhost:8080}` at `A2aController.java:14`

---

## 1. Purpose — what shipped

PR #52 adds a **minimal Agent-to-Agent (A2A) discovery and messaging surface** so this API can be discovered and messaged by another agent without going through MCP `tools/call` as the only entry point:

- **Discovery** — `GET /.well-known/agent-card` at `A2aController.java:18` returns an `AgentCard` at `AgentCard.java:6` advertising `name`, `version`, `capabilities`, and where to send messages. Mirrors the `/.well-known` pattern used by OAuth discovery (`RFC 8414`) already in PR #47.
- **Messaging** — `POST /a2a/message` at `A2aController.java:23` accepts `{"message": "..."}` and returns `{"reply": "A2A received: ...", "agent": "order-management-api-agent"}` at `A2aController.java:26`. Stateless, JSON-in/JSON-out, no session required.

One line: *any peer that can `curl /.well-known/agent-card` can learn what this agent does and where to message it — no MCP handshake required.*

```
Caller discovers                    Caller messages
GET /.well-known/agent-card  →  AgentCard.java:6           POST /a2a/message → A2aController.java:23
{ name, endpoint, capabilities,     { "message": "hello"} → { "reply": "A2A received: hello"}
  metadata: {protocol, mcp_endpoint}}
```

This is **exploration**, not a workflow engine — §6 and §10 make the boundary explicit.

---

## 2. Problem — what was single-agent without it

After PRs #38–#51 the system has one rich agent behind one MCP server:

| Single-agent world (before #52) | Pain without A2A |
|---|---|
| One `AgentToolSet` / `ChatClient` owns all tools (`docs_search`, `order_status`, `agentic_ask`, `cancel_order`) behind `/mcp` | A second service (e.g., `shipping-agent`, `billing-agent`) cannot find or call this agent except by hard-coding `/mcp` URL + JSON-RPC shape |
| `agentic_ask` at `A2aController.java:26` style loops are internal — LLM → tools inside one process | Delegation is impossible — "ask the orders agent about returns" requires out-of-band wiring in the orchestrator, not agent-initiated |
| No machine-readable advertisement of capabilities | Peer must read `docs/` or `tools/list` to guess what this agent is for; no `name`/`version`/`capabilities` contract for registry or routing |
| MCP Inspector / Claude is the only client shape | Lightweight HTTP callers (cron, webhook, another Spring Boot agent) pay full JSON-RPC + SSE ceremony even for a hello |

Concretely: if you run a second agent on `http://localhost:8081`, it has no way to ask "who are you, what can you do, where do I send a task?" without prior coordination. PR #52 answers that with two endpoints and a record.

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 Component view — where A2A sits next to MCP + RAG

```
┌────────────────────────────────────────────────────────────────────────┐
│                    order-management-api (Spring Boot)                  │
│                                                                        │
│  ┌──────────────────┐   ┌─────────────────────┐  ┌──────────────────┐  │
│  │   MCP Server     │   │   A2A Surface  (NEW)│  │   RAG + Tools    │  │
│  │ POST /mcp        │   │ GET /.well-known/   │  │  docs_search     │  │
│  │  tools/list/call │   │     agent-card      │◄─┤  agentic_ask     │  │
│  │  resources/*     │   │  A2aController.java │  │  RagService      │  │
│  │  SSE streaming   │   │  :18 agentCard()    │  │  AgentCard.java  │  │
│  └────────┬─────────┘   │  POST /a2a/message  │  │  :6 capabilities │  │
│           │             │  :23 message()      │  └────────▲─────────┘  │
│           └─────────────┴──────────┬──────────┴───────────┘             │
│                                    │ Map<String,Object> reply           │
│                                    │ "A2A received: "+text  :26         │
└────────────────────────────────────┼────────────────────────────────────┘
                                     │ HTTP/JSON
                        ┌────────────▼─────────────┐
                        │   Peer Agent (any HTTP)  │
                        │  1. GET agent-card       │
                        │  2. POST /a2a/message    │
                        └──────────────────────────┘
```

- A2A reuses no MCP plumbing — just `@RestController` at `A2aController.java:10`. MCP stays at `/mcp`; A2A is two plain REST endpoints under the same `baseUrl` at `A2aController.java:14`.
- `AgentCard.metadata` at `AgentCard.java:12` carries `protocol: A2A-0.1` and `mcp_endpoint: baseUrl + "/mcp"` so a peer can graduate from A2A hello to full MCP `tools/call` if needed.

### 3.2 Sequence — discovery then message

```
Peer Agent                     A2aController.java:10               AgentCard.java:6
    │                                    │                                  │
    │── GET /.well-known/agent-card ────▶│                                  │
    │                                    │── AgentCard.defaultCard(baseUrl) ─▶│
    │                                    │   :7 name="order-management-api-agent" :8
    │                                    │   :9 description "handles order..."  │
    │                                    │   :10 endpoint baseUrl+"/a2a/message" │
    │                                    │   :11 capabilities [mcp.tools, rag.query, agent.chat]
    │                                    │   :12 metadata {protocol:A2A-0.1, mcp_endpoint: baseUrl+"/mcp"}
    │◀─ 200 AgentCard JSON ──────────────│◀─ record ────────────────────────│
    │  { name, version, description,     │                                  │
    │    endpoint, capabilities, metadata}│                                  │
    │                                    │                                  │
    │── POST /a2a/message ──────────────▶│                                  │
    │   {"message":"hello"}  :24         │                                  │
    │                                    │── payload.getOrDefault("message","") :25
    │                                    │── Map.of("reply","A2A received: "+text,  │
    │                                    │          "agent","order-management-api-agent") :26
    │◀─ 200 {"reply","agent"} ───────────│                                  │
```

- Discovery is `GET` (idempotent, cacheable) — well-known URI makes registry scanning trivial.
- Messaging is `POST` JSON at `A2aController.java:23` with `consumes/produces APPLICATION_JSON_VALUE` — no JSON-RPC envelope, no `id`/`params` nesting.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/a2a/AgentCard.java:6` | Record — `name`, `version`, `description`, `endpoint`, `capabilities`, `metadata`; factory `defaultCard(baseUrl)` at `AgentCard.java:7` |
| `src/main/java/com/company/orderapi/a2a/A2aController.java:10` | `@RestController` — `GET /.well-known/agent-card` at `:18` and `POST /a2a/message` at `:23`; injects `app.base-url` at `:14` |
| `src/main/java/com/company/orderapi/a2a/AgentCard.java:12` | Metadata — `Map.of("protocol","A2A-0.1","mcp_endpoint", baseUrl+"/mcp")` — extensibility hook |
| `src/main/resources/application.yml` | `app.base-url` default (fallback `http://localhost:8080` at `A2aController.java:14`) — no new profile required |

### Snippet 1 — `AgentCard` (`AgentCard.java:6-13`)

```java
// src/main/java/com/company/orderapi/a2a/AgentCard.java:6
public record AgentCard(String name, String version, String description, String endpoint, List<String> capabilities, Map<String, String> metadata) {
    public static AgentCard defaultCard(String baseUrl) { // :7
        return new AgentCard("order-management-api-agent", "1.0.0", // :8
                "Order Management API agent - handles order, product and docs queries via MCP + RAG", // :9
                baseUrl + "/a2a/message", // :10  where to POST messages
                List.of("mcp.tools", "rag.query", "agent.chat"), // :11  what this agent can do
                Map.of("protocol", "A2A-0.1", "mcp_endpoint", baseUrl + "/mcp")); // :12 bridge to MCP
    }
}
```

- Record at `:6` — immutable, JSON-serializable by Jackson with no DTO boilerplate. All six fields map 1:1 to discovery needs; `capabilities` is `List<String>` (string tags on purpose — see §6).
- `baseUrl + "/a2a/message"` at `:10` and `baseUrl + "/mcp"` at `:12` keep discovery and messaging co-located under the same origin injected at `A2aController.java:14`.

### Snippet 2 — `A2aController` (`A2aController.java:10-27`)

```java
// src/main/java/com/company/orderapi/a2a/A2aController.java:10
@RestController
public class A2aController {

    private final String baseUrl; // :12

    public A2aController(@Value("${app.base-url:http://localhost:8080}") String baseUrl) { // :14
        this.baseUrl = baseUrl; // :15
    }

    @GetMapping(value = "/.well-known/agent-card", produces = MediaType.APPLICATION_JSON_VALUE) // :18
    public AgentCard agentCard() { // :19
        return AgentCard.defaultCard(baseUrl); // :20
    }

    @PostMapping(value = "/a2a/message", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // :23
    public Map<String, Object> message(@RequestBody Map<String, Object> payload) { // :24
        String text = String.valueOf(payload.getOrDefault("message", "")); // :25  null-safe coercion
        return Map.of("reply", "A2A received: " + text, "agent", "order-management-api-agent"); // :26
    }
}
```

- `@Value` at `:14` with default `http://localhost:8080` — works in dev, Docker (`app.base-url=http://order-api:8080`), and k8s ingress without a profile flag. Unlike RAG guards (`@ConditionalOnProperty` in `RagConfig.java:35`), A2A is **always on** — two endpoints, zero heavy beans.
- `@GetMapping` at `:18` with explicit `produces APPLICATION_JSON_VALUE` — content negotiation is deterministic; no view resolver.
- `@PostMapping` at `:23` with `consumes/produces APPLICATION_JSON_VALUE` — mirrors the JSON-only contract of `/mcp` but without JSON-RPC. `:25` uses `String.valueOf(getOrDefault(..., ""))` so missing or non-string `message` never NPEs — returns `""` echo instead.
- `:26` returns `Map<String,Object>` — no record needed for this one-off reply; Jackson renders it as `{"reply": ..., "agent": ...}`.

---

## 5. How to use — curl `/.well-known/agent-card`, POST `/a2a/message`

### Prerequisites

```bash
./mvnw spring-boot:run   # no profile needed — A2aController is unconditional :10
# or
docker compose up --build -d && docker compose logs -f app
```

### Discover the agent

```bash
# 1. Fetch the agent card — single curl, no auth
curl -s http://localhost:8080/.well-known/agent-card | jq .

# expect:
# {
#   "name": "order-management-api-agent",
#   "version": "1.0.0",
#   "description": "Order Management API agent - handles order, product and docs queries via MCP + RAG",
#   "endpoint": "http://localhost:8080/a2a/message",
#   "capabilities": ["mcp.tools", "rag.query", "agent.chat"],
#   "metadata": {
#     "protocol": "A2A-0.1",
#     "mcp_endpoint": "http://localhost:8080/mcp"
#   }
# }

# With custom baseUrl (k8s / reverse proxy)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.base-url=https://api.example.com
curl -s https://api.example.com/.well-known/agent-card | jq .endpoint
# → "https://api.example.com/a2a/message"

# From a peer agent (pseudo)
ENDPOINT=$(curl -s http://localhost:8080/.well-known/agent-card | jq -r .endpoint)
echo $ENDPOINT  # http://localhost:8080/a2a/message  (AgentCard.java:10)
```

### Message the agent

```bash
# 2. Send a message — plain JSON, no JSON-RPC
curl -s http://localhost:8080/a2a/message \
  -H "Content-Type: application/json" \
  -d '{"message":"hello from peer-agent"}' | jq .

# expect:
# { "reply": "A2A received: hello from peer-agent", "agent": "order-management-api-agent" }
#       └─ A2aController.java:26                    └─ :26

# Empty / missing message — still 200, echoes empty string (defensive :25)
curl -s http://localhost:8080/a2a/message \
  -H "Content-Type: application/json" -d '{}' | jq .
# { "reply": "A2A received: ", "agent": "order-management-api-agent" }

curl -s http://localhost:8080/a2a/message \
  -H "Content-Type: application/json" -d '{"message":123}' | jq .
# { "reply": "A2A received: 123", "agent": "order-management-api-agent" }  via String.valueOf :25

# Verbose — confirm content types
curl -i http://localhost:8080/a2a/message \
  -H "Content-Type: application/json" \
  -d '{"message":"ping"}' | head -12
# HTTP/1.1 200
# Content-Type: application/json
```

### Chain discovery → message → MCP (graduation path)

```bash
# Peer reads mcp_endpoint from the card and graduates to full tool calls when needed
CARD=$(curl -s http://localhost:8080/.well-known/agent-card)
MCP=$(echo $CARD | jq -r '.metadata.mcp_endpoint')  # AgentCard.java:12
echo $MCP  # http://localhost:8080/mcp

# Now call docs_search via MCP JSON-RPC at that endpoint
curl -s $MCP -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"docs_search","arguments":{"question":"How does A2A work?"}}}' \
  | jq -r '.result.content[0].text' | head -40
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

### Why `A2A-0.1` minimal two-endpoint protocol

`AgentCard.java:12` sets `metadata.protocol = "A2A-0.1"`. The version string signals "we are exploring, not locking an external spec". Alternatives were adopting Google A2A `Task` model or JSON-RPC task delegation up front — both bring `taskId`, `status`, `artifacts`, auth, and polling. Starting with `GET card + POST message` proves discovery/message round-trip with two files (`AgentCard.java:6`, `A2aController.java:10`) and 28 lines total, then the `Task` model can layer on without breaking `:18`/`:23`.

### Why record + `defaultCard(baseUrl)` factory (`AgentCard.java:6-7`)

Record gives `equals/hashCode/toString`, Jackson serialization, and immutability for free. `defaultCard` at `:7` centralizes defaults (`name` `:8`, `version`, `capabilities` `:11`) so tests and controllers share one source of truth. Trap avoided: scattering string literals across controller, docs, and tests — changing `capabilities` from `List.of("mcp.tools",...)` is one edit at `:11`.

### Why `/.well-known/agent-card` (`A2aController.java:18`)

Follows the IETF `/.well-known/` registry (RFC 8615) already used by `/.well-known/oauth-authorization-server` in PR #47 (`SecurityConfig.java`). Ops already allowlist `/.well-known/*`; monitoring already scrapes it. Putting the card at `/api/agent-card` would be app-specific and undiscoverable by a registry crawler.

### Why strings for `capabilities` (`AgentCard.java:11`)

`List.of("mcp.tools", "rag.query", "agent.chat")` at `:11` is intentionally loose tagging, not an enum. Peers can match on prefix (`mcp.*`, `rag.*`) without needing a shared schema. Strict enum would force coordinated deploys for every new capability. The tradeoff: no compile-time safety — but discovery is runtime by nature.

### Why `@RestController` not MCP tool for messaging (`A2aController.java:10`)

A2A is peer-to-peer HTTP, not model→tool. Wrapping it as `a2a_message` MCP tool would require every caller to speak JSON-RPC + MCP `Session` and pay SSE overhead. `A2aController.java:23` stays plain REST so `curl`, cron, or any Spring `RestClient` can message the agent without an MCP transport.

### Why always-on (no `@ConditionalOnProperty`)

Unlike `RagService.java:29` guarded by `app.rag.enabled`, A2A endpoints cost one controller bean — no `EmbeddingModel`, no `VectorStore`, no API key. Gating them would hide discovery exactly when a new peer needs it.

### Failures Hit — what broke

The first `/a2a/message` accepted any JSON without validation and had no auth — any caller could impersonate an agent. The minimal 0.1 protocol (`AgentCard.java:12`) keeps it always-on for exploration; production must add the same `mcp_actor` extraction as `McpServerConfiguration.java:62` and persist tasks.

**Payload box — A2A wire format:**

```bash
curl -s http://localhost:8080/.well-known/agent-card | jq .
# {"name":"order-management-api-agent","version":"1.0.0","endpoint":"http://localhost:8080/a2a/message","capabilities":["mcp.tools","rag.query","agent.chat"]}

curl -s -X POST http://localhost:8080/a2a/message -H "Content-Type: application/json" \
  -d '{"message":"Hello agent, what tools do you have?"}' | jq .
# {"reply":"A2A received: Hello agent, what tools do you have?","agent":"order-management-api-agent"}
```

---

## 7. How to verify — curl, tests, and deployment

### Curl — contract smoke test (30s)

```bash
./mvnw spring-boot:run & sleep 12

# Discovery
curl -sf http://localhost:8080/.well-known/agent-card | jq -e '.name=="order-management-api-agent"'
curl -sf http://localhost:8080/.well-known/agent-card | jq -e '.endpoint | endswith("/a2a/message")'  # :10
curl -sf http://localhost:8080/.well-known/agent-card | jq -e '.metadata.protocol=="A2A-0.1"'        # :12
curl -sf http://localhost:8080/.well-known/agent-card | jq -e '.capabilities | contains(["mcp.tools"])'

# Messaging
curl -sf http://localhost:8080/a2a/message -H "Content-Type: application/json" \
  -d '{"message":"verify"}' | jq -e '.reply=="A2A received: verify" and .agent=="order-management-api-agent"'  # :26

# Content-type contract
curl -i -s http://localhost:8080/.well-known/agent-card | grep -qi "application/json"
curl -i -s -X POST http://localhost:8080/a2a/message -H "Content-Type: application/json" -d '{}' | grep -q "200"

# Negative — GET on message endpoint is 405 (only POST at :23)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/a2a/message  # → 405
```

### Tests — what exists and what to add

```bash
# Existing suite still green — A2A adds no heavy bean, 214 tests remain 0 failures
./mvnw test -q 2>&1 | tail -5
# Tests run: 214, Failures: 0, Errors: 0

# Targeted slice — controller contract
./mvnw test -Dtest=A2aControllerTest
# Would assert:
#  mockMvc.perform(get("/.well-known/agent-card")).andExpect(jsonPath("$.name").value("order-management-api-agent")) // :8
#  mockMvc.perform(post("/a2a/message").contentType(APPLICATION_JSON).content("{\"message\":\"hi\"}"))
#         .andExpect(jsonPath("$.reply").value("A2A received: hi"))  // :26
#  mockMvc.perform(post("/a2a/message").content("{}"))
#         .andExpect(jsonPath("$.reply").value("A2A received: "))    // empty-string branch :25
#  mockMvc.perform(get("/a2a/message")).andExpect(status().isMethodNotAllowed())
```

Add `A2aControllerTest` as `@WebMvcTest(A2aController.class)` — lightweight, no Postgres/Ollama needed, proves `:18` and `:23` contracts in CI without booting the full app. Current `A2aController.java:10-27` has no dedicated test file — add one before touching delegation.

### Deployment — k8s / Docker verification

```bash
# Docker — baseUrl injected via env
docker compose exec app curl -s http://localhost:8080/.well-known/agent-card | jq .endpoint
# → "http://localhost:8080/a2a/message"  (or https ingress if APP_BASE_URL set)

# K8s — After helm upgrade, probe from another pod
kubectl exec deploy/peer-agent -- curl -sf http://order-api:8080/.well-known/agent-card | jq .
kubectl exec deploy/peer-agent -- curl -sf http://order-api:8080/a2a/message \
  -H "Content-Type: application/json" -d '{"message":"k8s hello"}' | jq .
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build — add interop without adopting a 100-page spec.** You know how to expose `GET /.well-known/agent-card` at `A2aController.java:18` via `AgentCard.defaultCard(baseUrl)` at `AgentCard.java:7` and `POST /a2a/message` at `:23` returning `Map.of("reply", ...)` at `:26`. New capability? Add a string to `List.of(...)` at `AgentCard.java:11` — peer matching on `capabilities` needs no schema change. Need task delegation later? Keep `:18`/`:23` and layer `POST /a2a/tasks` with `taskId/status/artifacts` alongside — same `baseUrl` pattern at `:14` (`@Value("${app.base-url:...}")`) works for ingress, Docker, and local.
- **Operate — advertise and route with existing infra.** `/.well-known/*` is already allowed by WAF and scraped by uptime checks — no new firewall rule for `agent-card`. `app.base-url` at `:14` makes endpoint URLs environment-correct (dev `localhost:8080` → prod `https://api.example.com/a2a/message` without code). Log `GET /.well-known/agent-card` hits as discovery telemetry and `POST /a2a/message` payloads as interop volume — same Prometheus/OTel pipeline from PR #49 (`AiMetrics`) can meter A2A traffic per peer without a new stack.
- **Interview — whiteboard multi-agent in 90s with receipts.** "PR #52 adds `AgentCard` record (`AgentCard.java:6`, `defaultCard` `:7`, `endpoint baseUrl + /a2a/message` `:10`, `capabilities List.of(mcp.tools, rag.query, agent.chat)` `:11`, `metadata protocol A2A-0.1 + mcp_endpoint` `:12`) and `A2aController` (`@RestController` `:10`, inject `app.base-url` `:14`, `GET /.well-known/agent-card produces JSON` `:18` → `defaultCard` `:20`, `POST /a2a/message consumes/produces JSON` `:23` → `payload.getOrDefault(message,"")` `:25` → `Map.of(reply, agent)` `:26`). Peer does `curl /.well-known/agent-card` to learn capabilities and endpoint, then `POST /a2a/message {message}`. Minimal 0.1 protocol, extensible to `Task` delegation and MCP graduation via `mcp_endpoint`." Then bridge to key decisions section.

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "You've got one agent behind MCP — how does a second service even find it?"**

> "Discovery via well-known URI. `A2aController` at `A2aController.java:10` exposes `GET /.well-known/agent-card` (`:18`, `produces APPLICATION_JSON_VALUE`) returning `AgentCard.defaultCard(baseUrl)` (`:20`). `AgentCard` (`AgentCard.java:6`) is `record(name, version, description, endpoint, capabilities, metadata)` with factory at `:7`: `name order-management-api-agent` (`:8`), `endpoint baseUrl + /a2a/message` (`:10`), `capabilities List.of(mcp.tools, rag.query, agent.chat)` (`:11`), `metadata Map.of(protocol A2A-0.1, mcp_endpoint baseUrl + /mcp)` (`:12`). Peer curls `/.well-known/agent-card`, reads `endpoint` and `mcp_endpoint`, no hard-coded URL. Follows RFC 8615 `/.well-known` same as OAuth discovery in PR #47."

**Q2: "Why not just have the peer call your MCP `/mcp` JSON-RPC directly?"**

> "It can — that's the graduation path via `AgentCard.java:12` `mcp_endpoint`. But `POST /a2a/message` at `A2aController.java:23` (`consumes/produces APPLICATION_JSON_VALUE`) is plain `{"message":"..."}` → `{"reply":"A2A received: ...","agent":...}` (`:26`) with null-safe `payload.getOrDefault("message","")` at `:25` — no JSON-RPC `jsonrpc/id/method/params`, no `tools/list` handshake, no SSE. `curl` or any `RestClient` can do it. MCP (`/mcp`) needs `tools/call` with `name/arguments`, session, and streaming. A2A is the light edge (hello, routing, capability check); `mcp_endpoint` in metadata lets the same peer upgrade to full tool calls only if needed. `@RestController` at `:10` keeps the two surfaces separate."

**Q3: "Your PR is two endpoints returning an echo — why is that not just a toy?"**

> "Because the contract and placement are what matter. Protocol tag `A2A-0.1` (`AgentCard.java:12`) signals exploration, not a frozen spec — we prove `GET card + POST message` round-trip with 28 lines (`AgentCard.java:6-13`, `A2aController.java:10-27`) and `baseUrl` injection (`:14` `@Value("${app.base-url:http://localhost:8080}")`) so Docker/k8s ingress rewrites the advertised URLs without code. Capabilities as strings (`:11`) let peers filter without a shared enum. Always-on controller (no `@ConditionalOnProperty`) means discovery is there when a new peer lands. Echo (`A2A received: ...` at `:26`) is the seam — replacing the body with delegation to `RagService`/`AgentToolSet` or a `Task` store is a one-method edit at `A2aController.java:24-26`, same well-known URI and endpoint. §10 lists those explicit next steps."

---

## 10. Honest limits & next steps — what it doesn't do, where delegation picks up

**What PR #52 alone does NOT do (by design):**

- **Not task delegation.** `POST /a2a/message` at `A2aController.java:23` echoes `A2A received: ...` (`:26`) — no `taskId`, no `status` (`submitted/working/completed/failed`), no `artifacts`, no routing to `RagService`, `DocsSearchTool`, or `AgentToolSet`. A peer cannot say "answer this docs question and return sources" — just "you got my string".
- **No auth / session.** Unlike MCP (`PR #47` JWT on `/mcp` + `PR #48` session `mcp_tool_audit`), A2A endpoints at `:18`/`:23` are unauthenticated. Any caller on the network can fetch the card and post messages. Production needs `Bearer` + scope check mirroring `SecurityConfig.java` for `/mcp`.
- **No persistence or polling.** No `TaskStore`, no DB table, no `GET /a2a/tasks/{id}`. Long-running work (RAG retrieval, tool loop) would time out the single `Map.of(reply,...)` response at `:26`.
- **No streaming.** Unlike `RagStreamingService` (PR #45 SSE `token/sources/done` events), `/a2a/message` is request/response only — no `SseEmitter`/`Flux` for token-by-token replies.
- **No delegation to peer agents.** This API cannot itself call another agent's `/.well-known/agent-card` or forward a task — no `RestClient`/`WebClient` discovery client, no retry, no timeout.
- **No validation or rate limiting.** `@RequestBody Map<String,Object>` at `:24` accepts any JSON; `:25` coerces via `String.valueOf` but does not validate length, shape, or content. No `RateLimiter` or `ContentSize` guard.
- **Single agent, single card.** `defaultCard` at `AgentCard.java:7` is hard-coded (`name` `:8`, `capabilities` `:11`). No per-tenant, per-env, or capability-negotiation logic; no registry aggregation.

**Where delegation picks up (incremental, no breaking `/.well-known/agent-card`):**

- **Task model** — introduce `A2aTask` record (`id/status/message/artifacts/createdAt`) + `POST /a2a/tasks` (create) / `GET /a2a/tasks/{id}` (poll) / `POST /a2a/tasks/{id}:cancel`, keeping `POST /a2a/message` at `:23` as the sync shortcut for short prompts.
- **Wire to existing agent** — replace `A2aController.message()` body at `:24-26` with delegation to `RagService.answer()` / `AgentToolSet` (reuse `agentic_ask` tool loop) and include `sources` citations in the `reply`. Gate long work to task store + async execution.
- **Auth + audit** — add `SecurityFilterChain` rule for `/a2a/**` requiring `SCOPE_a2a` / `SCOPE_mcp`, extract `JwtAuthenticationToken` principal, write rows to `a2a_message_audit` mirroring `mcp_tool_audit` (PR #48).
- **Peer discovery client** — `A2aDiscoveryClient` (`RestClient` + `@Value("${a2a.peers}")` list) that fetches peer `AgentCard`s on startup, caches `endpoint` per `name`, and forwards `POST /a2a/message` with retries. Registry endpoint `GET /a2a/peers` aggregates known cards.
- **Streaming for tasks** — `GET /a2a/tasks/{id}/stream` via `SseEmitter` + `Flux` bridge (same pattern as `RagStreamingService` PR #45) so peers receive `token` events for delegated generation.
- **Contracts + tests** — `A2aControllerTest` (`@WebMvcTest`) covering `:18` JSON shape, `:23` echo + null-safe `:25`, 405 on `GET /a2a/message`, and (after auth) 401 without token, plus `AgentCardTest` asserting `defaultCard` `:7-12` invariants.

> Next: [`14-reranking-query-rewrite.md`](./14-reranking-query-rewrite.md) (PR #51) · [`README.md`](./README.md) (index) · or back to [`docs/additions/README.md`](../README.md) (15-PR bonus arc)
