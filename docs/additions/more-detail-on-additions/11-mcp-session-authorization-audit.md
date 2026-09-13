# 11. MCP Session Authorization + Agent-Identity Audit (PR #48)

> PR: [#48 — MCP Session Authorization + Agent-Identity Audit (`contextExtractor` → `mcp_tool_audit`)](https://github.com/anomalyco/order-management-api/pull/48) · Stack: `WebMvcStatelessServerTransport` `contextExtractor` + `McpTransportContext` + `McpAuditService` (`REQUIRES_NEW`) + `mcp_tool_audit` (Liquibase `09_create_mcp_tool_audit.sql`) · Depends on [#47 OAuth 2.1 AS/RS on `/mcp`](./10-mcp-authorization-oauth2.md) + [#37 MCP server `POST /mcp`](./02-agentic-tool-calling.md) · Enables [#49 AI observability](../12-ai-observability.md)

---

## 1. Purpose — what shipped

PR #47 proved **who** you are — `POST /mcp` now requires `Authorization: Bearer <JWT>` with `SCOPE_mcp` (`SecurityConfig.java:71`). PR #48 answers the next two questions: **which session** did you act in, and **what did you do** — durably.

* **Binds actor + session into every tool call** — `McpServerConfiguration.contextExtractor` (`McpServerConfiguration.java:62`) pulls the validated `Jwt` from `SecurityContextHolder`, extracts `client_id` (fallback `sub`) as `mcp_actor`, and injects it into `McpTransportContext` (`McpServerConfiguration.java:89`) so every `tools/call` handler knows its caller without touching business logic.
* **Immutable audit per invocation** — `McpAuditService.record()` (`McpAuditService.java:20`) writes one row to `mcp_tool_audit` (`McpToolAudit.java:14`, `09_create_mcp_tool_audit.sql:15`) for **every** `tools/call` — success or `IllegalArgumentException` failure — with `session_id`, `actor`, `tool_name`, `arguments_json`, `success`, `error_message`, `occurred_at`.
* **Survives rollback** — `@Transactional(propagation = REQUIRES_NEW)` (`McpAuditService.java:20`) commits the audit in its own physical transaction, so a tool that throws still leaves a `success=false` row.

After the PR: `curl` with a PR #47 token → `POST /mcp tools/call api_health` → `psql mcp_tool_audit` shows `actor=mcp-server`, `tool_name=api_health`, `success=true`; a bad `order_status` shows `success=false` + `error_message`. Same `specification(auditService, aiMetrics)` wrapper is used for both read-only and guarded-write tools (`AbstractMcpReadOnlyTool.java:67`, `AbstractMcpWriteTool.java:71`).

---

## 2. Problem — token says who, not session/what

Before PR #48 the MCP transport had coarse identity only:

| Before (PR #47 only) | After (PR #48) |
|---|---|
| `POST /mcp` 401/403 via `SCOPE_mcp` — you know the **bearer is valid** | Same gate **plus** per-call `mcp_actor` extracted from JWT `client_id`/`sub` |
| No session — 10 calls from one token are indistinguishable | `McpTransportContext` carries `mcp_actor` (and `mcp_session_id` when available) into every handler |
| No audit — `cancel_order` with bad `orderId` left only an MCP `isError` payload, no durable record | `mcp_tool_audit` row per call: `tool_name`, `arguments_json`, `success`, `error_message`, `occurred_at` (`McpToolAudit.java:25-44`) |
| Failure visibility = logs only, rolled back with the request | `REQUIRES_NEW` audit commits even when the tool's transaction rolls back |
| Agent `agentic_ask` and MCP shared `execute(Map)` (`AbstractMcpReadOnlyTool.java:124`) but only MCP had HTTP context | Both surfaces share the same `specification(auditService)` overload — one audit path, two protocols |

Without this PR a compromised `mcp-server` token could enumerate `product_search` / `order_status` / `cancel_order` silently — `SCOPE_mcp` is binary, not per-tool or per-session. Auditing after the handler, with actor + session, is the minimum viable traceability for an agent surface.

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 Transport `contextExtractor` → `McpTransportContext` → `mcp_tool_audit`

```
                         Spring Security                MCP Transport                  Tool handler
  Client                FilterChain                     contextExtractor                specification(auditService)
    │── POST /mcp ──────►│                              │                               │
    │  Bearer JWT        │  JwtDecoder HS256 :88        │                               │
    │                    │  → JwtAuthenticationToken    │                               │
    │                    │  → SecurityContextHolder     │                               │
    │                    │         │                    │                               │
    │                    │         └────── extractSecurityContext() :79 ───────────────►│
    │                    │              jwt.getClaim("client_id") :84                    │
    │                    │              fallback jwt.getSubject() :86                    │
    │                    │              → singletonMap("mcp_actor", actor) :89           │
    │                    │              → McpTransportContext.create(ctx) :90            │
    │                    │                              │                               │
    │── tools/call ──────┼──────────────────────────────┼── transportContext ──────────►│
    │  {name, arguments} │                              │  ctx.get("mcp_actor") :108      │
    │                    │                              │  ctx.get("mcp_session_id") :112 │
    │                    │                              │                               │  auditService.record(sessionId, actor, name(), args, success, error) :87/:96
    │                    │                              │                               │  → McpToolAuditRepository.save()  REQUIRES_NEW :20
    │◄── CallToolResult ─┼──────────────────────────────┼────────────────────────────────┤  → INSERT INTO mcp_tool_audit
```

### 3.2 Data flow — one row per invocation (success + failure)

```
  tools/call api_health {}                 tools/call order_status {orderId: 999999}
         │                                          │
         ▼                                          ▼
  AbstractMcpReadOnlyTool.specification :77   AbstractMcpReadOnlyTool.specification :77
    extractSessionId :79 → "unknown"          extractSessionId :79 → "unknown"
    extractActor :80 → "mcp-server"           extractActor :80 → "mcp-server"
    try { execute(args) → "OK"  → record(… true, null) :87
          aiMetrics(STATUS_SUCCESS) :90        } catch (IllegalArgumentException e) {
    return CallToolResult(text,false)            message="Order 999999 not found"
                                                 record(… false, message) :96
                                                 aiMetrics(STATUS_ERROR) :99
                                                 return CallToolResult(message,true)
                                               }
                              │
                              ▼
                    mcp_tool_audit :15  (09_create_mcp_tool_audit.sql:15)
  ┌──────────────────────────────────────────────────────────────────────────────┐
  │ id | session_id | actor      | tool_name    | arguments_json | success | error_message        │ occurred_at │
  │ 42 | unknown    | mcp-server | api_health   | {}             | true    | NULL                 │ 2026-09-11… │
  │ 43 | unknown    | mcp-server | order_status | {"orderId":…}  | false   | Order 999999 not …   │ 2026-09-11… │
  └──────────────────────────────────────────────────────────────────────────────┘
         idx_session_id :28  idx_actor :32  idx_tool_name :36
```

Stateless transport has no server-side session store — `mcp_session_id` is `"unknown"` (`AbstractMcpReadOnlyTool.java:112`) until the stateful `WebMvcStreamableServerTransport` is adopted. The column exists so the upgrade is additive (§10).

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `mcp/McpServerConfiguration.java:57` | **Transport binding** — `mcpTransport()` (`:62`) with `.contextExtractor(this::extractSecurityContext)` (`:66`), `extractSecurityContext()` (`:79`) JWT→`mcp_actor`, `mcpServer()` (`:99`) wiring `readTools`/`writeTools` via `specification(auditService, aiMetrics)` (`:111`,`:115`) |
| `mcp/McpAuditService.java:10` | **Audit sink** — `record(sessionId, actor, toolName, arguments, success, errorMessage)` (`:21`) `@Transactional(REQUIRES_NEW)` (`:20`), `serializeArgs` (`:35`) via `ObjectMapper` |
| `mcp/McpToolAudit.java:14` | **Entity** — `@Table(mcp_tool_audit)` (`:14`) `session_id` (`:25`), `actor` (`:28`), `tool_name` (`:31`), `arguments_json` (`:34`), `success` (`:37`), `error_message` (`:40`), `occurred_at` (`:43`), indexes `:15-17`, builder `:59` |
| `mcp/McpToolAuditRepository.java` | **Spring Data JPA** — `findBySessionId` / `findByActor` / `findByToolName` query methods |
| `mcp/AbstractMcpReadOnlyTool.java:27` | **Read audit wrapper** — `specification(auditService)` (`:62`) → `specification(auditService, aiMetrics)` (`:67`), `callHandler` (`:77`) with `extractActor` (`:107`), `extractSessionId` (`:111`), `auditService.record` success `:87` / error `:96` |
| `mcp/AbstractMcpWriteTool.java:36` | **Write audit wrapper** — identical wrapper for guarded writes (`:71`) so `cancel_order` etc. audit the same way |
| `db/changelog/v1.0/09_create_mcp_tool_audit.sql:15` | **Liquibase** — `CREATE TABLE mcp_tool_audit` (`:15`) + 3 indexes `idx_*` (`:28`,`:32`,`:36`), rollback `DROP` |
| `db/changelog/db.changelog-master.xml:41` | **Changelog include** — `<include file="v1.0/09_create_mcp_tool_audit.sql"/>` |
| `mcp/McpOAuth2IntegrationTest.java:169` | **E2E proof** — 2 audit tests on `RANDOM_PORT` + Testcontainers `postgres:16-alpine`: `toolInvocationCreatesAuditTrail` (`:158`) + `failedToolInvocationRecordsError` (`:180`) |
| `integration/DatabaseSchemaIntegrationTest.java:73` | **Schema gate** — expects 13 tables including `mcp_tool_audit` |

### Snippet 1 — `contextExtractor` bridges Spring Security → MCP (`McpServerConfiguration.java:79`)

```java
// src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:79
private io.modelcontextprotocol.common.McpTransportContext extractSecurityContext(
        ServerRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // :81 after JwtDecoder + SecurityFilterChain
    String actor = "anonymous"; // :82 fallback — no token or non-JWT auth
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) { // :83
        actor = jwt.getClaimAsString("client_id"); // :84 OAuth client for client_credentials
        if (actor == null || actor.isBlank()) {
            actor = jwt.getSubject(); // :86 fallback — authorization_code sub
        }
    }
    Map<String, Object> ctx = singletonMap("mcp_actor", actor); // :89
    return io.modelcontextprotocol.common.McpTransportContext.create(ctx); // :90 SDK carries map into every handler
}
@Bean public WebMvcStatelessServerTransport mcpTransport(ObjectMapper om) {
    return WebMvcStatelessServerTransport.builder() // :63
            .messageEndpoint(MCP_ENDPOINT) // :64  "/mcp"
            .jsonMapper(new JacksonMcpJsonMapper(om)) // :65
            .contextExtractor(this::extractSecurityContext) // :66 THE line — runs after SecurityContext is populated
            .build();
}
```

### Snippet 2 — Audit sink must survive rollback (`McpAuditService.java:20`)

```java
// src/main/java/com/company/orderapi/mcp/McpAuditService.java:20
@Transactional(propagation = Propagation.REQUIRES_NEW) // :20 separate physical TX — commits even if caller's TX rolls back
public void record(String sessionId, String actor, String toolName,
                   Object arguments, boolean success, String errorMessage) { // :21
    String argsJson = serializeArgs(arguments); // :23 ObjectMapper → JSON, never throws (fallback {"serialization_error":…} :42)
    McpToolAudit audit = McpToolAudit.builder() // :24
            .sessionId(sessionId).actor(actor).toolName(toolName)
            .argumentsJson(argsJson).success(success).errorMessage(errorMessage).build();
    repository.save(audit); // :32
}
```

### Snippet 3 — Every handler records success + failure (`AbstractMcpReadOnlyTool.java:67`)

```java
// src/main/java/com/company/orderapi/mcp/AbstractMcpReadOnlyTool.java:67
public final McpStatelessServerFeatures.SyncToolSpecification specification(
        McpAuditService auditService, com.company.orderapi.observability.AiMetrics aiMetrics) {
    Tool tool = Tool.builder().name(name()).description(description()).inputSchema(inputSchema()).build(); // :70
    return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool)
            .callHandler((transportContext, request) -> { // :77 stateless handler gets McpTransportContext, not McpSyncServerExchange
                long start = System.nanoTime(); // :78 for AiMetrics latency
                String sessionId = extractSessionId(transportContext); // :79 "unknown" on stateless :112
                String actor = extractActor(transportContext); // :80 ctx.get("mcp_actor") :108
                Map<String, Object> arguments = (Map<String, Object>) request.arguments(); // :83
                try {
                    String result = execute(arguments); // :85 delegates to run(Map) — one impl for MCP + @Tool
                    if (auditService != null) auditService.record(sessionId, actor, name(), arguments, true, null); // :87
                    if (aiMetrics != null) aiMetrics.recordToolCall(name(), STATUS_SUCCESS, System.nanoTime()-start); // :90
                    return new CallToolResult(result, false); // :92 isError=false
                } catch (IllegalArgumentException e) { // :93 domain validation → MCP tool error, not HTTP 500
                    String message = e.getMessage() == null ? "Tool failed." : e.getMessage(); // :94
                    if (auditService != null) auditService.record(sessionId, actor, name(), arguments, false, message); // :96
                    if (aiMetrics != null) aiMetrics.recordToolCall(name(), STATUS_ERROR, System.nanoTime()-start); // :99
                    return new CallToolResult(message, true); // :101 isError=true
                }
            }).build();
}
private static String extractSessionId(McpTransportContext ctx) { // :111
    Object sid = ctx.get("mcp_session_id"); return sid != null ? sid.toString() : "unknown"; // :113
}
```

Backward compat: `specification()` (`:51`) → `specification(null, null)` — agent surface `AgentToolSet` calls the no-arg overload and skips audit (no transport context there).

### Snippet 4 — Table + indexes (`09_create_mcp_tool_audit.sql:15`)

```sql
-- src/main/resources/db/changelog/v1.0/09_create_mcp_tool_audit.sql:15
CREATE TABLE mcp_tool_audit ( -- :15
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    session_id      VARCHAR(255)  NOT NULL,  -- :17 "unknown" on stateless; real UUID on stateful
    actor           VARCHAR(255)  NOT NULL,  -- :18 OAuth client_id or sub, or "anonymous"
    tool_name       VARCHAR(128)  NOT NULL,  -- :19 e.g. api_health, cancel_order
    arguments_json  TEXT,                    -- :20 raw args as JSON — PII masking is caller's responsibility
    success         BOOLEAN       NOT NULL,  -- :21
    error_message   TEXT,                    -- :22 populated only when success=false
    occurred_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP -- :23 Instant.now() from builder :102
);
CREATE INDEX idx_mcp_tool_audit_session_id ON mcp_tool_audit (session_id); -- :28
CREATE INDEX idx_mcp_tool_audit_actor ON mcp_tool_audit (actor);           -- :32
CREATE INDEX idx_mcp_tool_audit_tool_name ON mcp_tool_audit (tool_name);   -- :36
```

---

## 5. How to use — curl token → MCP call → psql `mcp_tool_audit`

### Prerequisites

```bash
./mvnw spring-boot:run
# issuer http://localhost:8080  SecurityProperties.java:72
# MCP at POST /mcp  McpServerConfiguration.java:59
# DB postgres:16-alpine — Liquibase runs 09_create_mcp_tool_audit.sql:15 on boot
```

### 1. Mint a JWT (PR #47) — 15-min `SCOPE_mcp`

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{sub, scope, iss}'
# { "sub":"mcp-server", "scope":"mcp", "iss":"http://localhost:8080" }
```

### 2. Call an MCP tool with `Authorization: Bearer`

```bash
# success path — api_health has no args, always succeeds  McpOAuth2IntegrationTest.java:158
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"api_health","arguments":{}}}' | jq .

# failure path — order_status with non-existent id → isError=true  :180
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"order_status","arguments":{"orderId":999999}}}' | jq .
```

### 3. Query the audit trail — the proof

```bash
# via psql (Testcontainers or docker-compose postgres)
psql -h localhost -U orderapi -d orderdb -c \
  "SELECT session_id, actor, tool_name, arguments_json, success, left(error_message,60), occurred_at
   FROM mcp_tool_audit ORDER BY occurred_at DESC LIMIT 5;"

# expect:
#  session_id | actor      | tool_name    | arguments_json      | success | error_message            | occurred_at
#  unknown    | mcp-server | order_status | {"orderId":999999}  | f       | Order 999999 not found   | 2026-09-11 …
#  unknown    | mcp-server | api_health   | {}                  | t       |                          | 2026-09-11 …

# filter by actor or tool — indexes on actor :32 / tool_name :36 / session_id :28
psql -c "SELECT * FROM mcp_tool_audit WHERE actor='mcp-server' AND tool_name='api_health' ORDER BY occurred_at DESC LIMIT 1;"
psql -c "SELECT * FROM mcp_tool_audit WHERE session_id='unknown' ORDER BY occurred_at DESC;"  # all stateless calls group here today
```

No separate audit endpoint is shipped — `mcp_tool_audit` is the source of truth; expose `GET /api/audit/mcp` only if you need it.

---

## 6. Key decisions — why these choices win (and the traps avoided)

**`REQUIRES_NEW`, not `REQUIRED`** — `McpAuditService.java:20` must commit independently. Tool logic may run in a request transaction that rolls back on `IllegalArgumentException` (e.g. `OrderStatusTool` validation). With `REQUIRED` the `INSERT` rolls back too — silent audit loss. `REQUIRES_NEW` opens a new physical transaction; cost is one extra `BEGIN/COMMIT` (~1-2 ms), acceptable for audit durability. Append-only table means no compensating rollback needed.

**`McpTransportContext` vs `McpSyncServerExchange` — the stateless trap** — The project uses `WebMvcStatelessServerTransport` → `McpStatelessSyncServer` (`McpServerConfiguration.java:62`+`:118`). The stateless spec's `callHandler` is `BiFunction<McpTransportContext, CallToolRequest, CallToolResult>` (`AbstractMcpReadOnlyTool.java:77`), **not** `Function<McpSyncServerExchange, …>`. The stateful `McpSyncServerExchange` (which has `sessionId()`, `getClientInfo()`, `requestId()`) simply never arrives. The *only* injection point is `contextExtractor` (`:66`) → `McpTransportContext` map. Mistaking the two signatures compiles with the wrong import but never delivers `sessionId`.

**`client_id` vs `sub` — who is the actor?** — For `client_credentials` (machine-to-machine `mcp-server`) both claims are the client (`mcp-server`); reading `client_id` (`McpServerConfiguration.java:84`) with fallback to `sub` (`:86`) keeps machine identity stable. For `authorization_code` (`mcp-console` PKCE, PR #47 `:116`) `sub` would be the human and `client_id` would be `mcp-console` — choosing `client_id` audits the *OAuth client*, not the end-user. To audit humans, prefer `sub` when `grant_type` is `authorization_code` (future: inspect `amr`/`scope`).

**`singletonMap("mcp_actor", …)` — one key, not two** — Only `mcp_actor` is populated today (`McpServerConfiguration.java:89`); `mcp_session_id` is read from the context (`AbstractMcpReadOnlyTool.java:112`) but the stateless transport never sets it, so `extractSessionId` returns `"unknown"`. Adding a real session later means the `contextExtractor` starts setting `mcp_session_id` — no handler change, no column migration.

**Backward compat via overloads** — `specification()` (`:51`) and `specification(auditService)` (`:62`) delegate to the 2-arg form with `null` checks (`:86`,`:95`). The agent surface (`AgentToolSet`) never has a transport context and safely calls the no-arg version with no audit.

---

## 7. How to verify — curl + integration tests + DB

### 1. Curl — three assertions in 30s

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"api_health","arguments":{}}}' | jq .result.isError # false
psql -c "SELECT actor, tool_name, success FROM mcp_tool_audit WHERE tool_name='api_health' ORDER BY occurred_at DESC LIMIT 1;" # mcp-server | api_health | t
```

### 2. Integration tests — `McpOAuth2IntegrationTest.java:169`

```bash
./mvnw test -Dtest=McpOAuth2IntegrationTest
# 8 cases total (6 from PR #47 + 2 new audit):
#  clientCredentialsTokenGrantsMcpAccess        :79  HS256+iss+scope → McpSyncClient.listTools() 200
#  noTokenIsRejectedOnTheMcpEndpoint            :99  no Bearer → 401
#  tokenWithoutMcpScopeCannotCallTheMcpEndpoint :109 mcp-internal/internal → 403
#  tokenEndpointRejectsWrongClientSecret        :124 wrong secret → 401
#  authorizationServerMetadataIsPublished       :130 RFC 8414 discovery 200
#  publicClientRequiresPkceAndCarriesNoSecret   :146 mcp-console requireProofKey==true
#  toolInvocationCreatesAuditTrail              :158 POST /mcp tools/call api_health → SELECT * FROM mcp_tool_audit WHERE tool_name='api_health' → actor=mcp-server success=true
#  failedToolInvocationRecordsError             :180 tools/call order_status {orderId:999999} → success=false error_message LIKE '%not found%'
# Uses RANDOM_PORT + Testcontainers postgres:16-alpine :61, HttpClientStreamableHttpTransport :254
# Extra: DatabaseSchemaIntegrationTest.java:73 asserts 13 tables (includes mcp_tool_audit)
```

### 3. DB direct — prove `REQUIRES_NEW` survives failure

```bash
# after a failed tools/call (section 5 step 2), even though the MCP response is isError=true,
# the row exists — if REQUIRES_NEW were missing, a rolled-back TX would have zero rows:
psql -c "SELECT tool_name, success, error_message FROM mcp_tool_audit WHERE tool_name='order_status' ORDER BY occurred_at DESC LIMIT 1;"
# order_status | f | Order 999999 not found
```

---

## 8. How this helps you on the job — build / operate / interview

* **Build — add auditable tools without touching auth again.** Copy `McpServerConfiguration.java:62-91` → `contextExtractor` pulling `Jwt` `client_id`/`sub` into `McpTransportContext` → add `McpAuditService.java:20` (`REQUIRES_NEW` + `ObjectMapper`) + `McpToolAudit.java:14` + `09_create_mcp_tool_audit.sql:15` → wrap both `AbstractMcp*Tool` specs (`:67`/`:71`) to `record()` success `:87` and error `:96`. New tools (`product_search`, `cancel_order`) are automatically audited by virtue of being in the `readTools`/`writeTools` lists (`McpServerConfiguration.java:109-116`).

* **Operate — reconstruct "who did what" in one query.** `session_id` groups calls from one MCP `initialize` (today `unknown` — see §10), `actor` groups by OAuth client, `tool_name` + `arguments_json` reconstruct intent, `success`/`error_message` separate real failures from `isError` payloads. Index trio (`:28`,`:32`,`:36`) keeps `WHERE actor=? AND tool_name=? ORDER BY occurred_at DESC` fast. Rotate `jwtSecret` (`SecurityProperties.java:20`) and `McpAuditService` rows remain — audit is decoupled from token lifetime (15 min ` :111`).

* **Interview — 90s whiteboard.** "PR #48: `WebMvcStatelessServerTransport.contextExtractor` (`McpServerConfiguration.java:66`) reads `SecurityContextHolder.getAuthentication()` (`:81`), extracts `Jwt` `client_id` fallback `sub` (`:84-86`) as `mcp_actor` into `McpTransportContext` (`:90`). Both `AbstractMcpReadOnlyTool` (`:67`) and `AbstractMcpWriteTool` (`:71`) wrap `callHandler` to `extractActor`/`extractSessionId` (`:107`/`:111`) and call `McpAuditService.record()` (`:87`/`:96`) with `@Transactional(REQUIRES_NEW)` (`McpAuditService.java:20`) into `mcp_tool_audit` (`:15`) with 3 indexes. Verified by `McpOAuth2IntegrationTest` 2 audit cases (`:158`,`:180`) + `psql mcp_tool_audit` + 13-table schema gate."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "You already had OAuth on `/mcp` — why wasn't that sufficient, and what does `contextExtractor` buy you?"**

> "PR #47's `SCOPE_mcp` (`SecurityConfig.java:71`) proves the bearer is valid — binary gate. It doesn't bind *which session* or *what was attempted*. An agent that calls `product_search` then `cancel_order` twice looks identical to two separate agents reusing a leaked token. PR #48's `contextExtractor` (`McpServerConfiguration.java:66`) runs *after* the security filter, reads the validated `Jwt` from `SecurityContextHolder` (`:81`), extracts `client_id`→`sub` as `mcp_actor` (`:84-86`) into `McpTransportContext` (`:90`), and every `specification(auditService)` handler (`AbstractMcpReadOnlyTool.java:77`) pulls `actor` + `session_id` and writes one `mcp_tool_audit` row with `REQUIRES_NEW` (`McpAuditService.java:20`). Now coarse scope becomes per-call, per-session traceability — you can `SELECT * FROM mcp_tool_audit WHERE actor='mcp-server' ORDER BY occurred_at`."

**Q2: "Why `REQUIRES_NEW` for audit? Why `McpTransportContext` instead of `McpSyncServerExchange`?"**

> "Two traps. First, `REQUIRES_NEW`: the tool's validation throws `IllegalArgumentException` (`AbstractMcpReadOnlyTool.java:93`) which the handler maps to `CallToolResult(isError=true)` — but if the tool touched a DB transaction that rolled back, a `REQUIRED` audit `INSERT` rolls back with it — silent loss. `REQUIRES_NEW` (`McpAuditService.java:20`) forces a new physical transaction that commits regardless. Second, transport: we're stateless (`WebMvcStatelessServerTransport` `:62` → `McpStatelessSyncServer` `:118`). The stateless `callHandler` signature is `(McpTransportContext, CallToolRequest)` (`:77`), not `(McpSyncServerExchange, …)` — `McpSyncServerExchange` with `sessionId()`/`getClientInfo()` never arrives on stateless. The only injection point is `contextExtractor` (`:66`) → `McpTransportContext` map. Wrong import compiles, delivers nothing."

**Q3: "Your audit shows `session_id='unknown'` and `actor='mcp-server'` — isn't that useless? How would you fix it without breaking the table?"**

> "Honest limit by design (§10). Stateless `WebMvcStatelessServerTransport` has no session store, so `extractSessionId` (`:111`) returns `"unknown"` and `actor` is always the OAuth client, not the human. It's still useful — you correlate by `actor` + `tool_name` + time window via indexed queries (`:28`,`:32`,`:36`). To fix it additively: (1) swap to `WebMvcStreamableServerTransport` (stateful, with session store) and have `contextExtractor` set `mcp_session_id` from the SDK's `sessionId` — handlers already read it (`:111`), zero code change; (2) for human actor, prefer `Jwt.getSubject()` when the token's `grant_type` is `authorization_code` (or inspect `scope`/`amr`) — keep `client_id` for `client_credentials`. The columns (`McpToolAudit.java:25-28`) and indexes already anticipate real values — no migration, just the extractor."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #49 picks up

**What PR #48 alone does NOT do (by design):**

* **Stateless `session_id` is always `"unknown"`** — `AbstractMcpReadOnlyTool.java:112` fallback. `WebMvcStatelessServerTransport` (`McpServerConfiguration.java:62`) doesn't track sessions; the `session_id` column + `idx_mcp_tool_audit_session_id` (`:28`) exist for the stateful upgrade (`WebMvcStreamableServerTransport` + session store) where `contextExtractor` will populate `mcp_session_id`.
* **Actor is the OAuth client, never the end-user** — `McpServerConfiguration.java:84` prefers `client_id`; `mcp-console` human `sub` is ignored. Per-user audit needs grant-type-aware actor selection.
* **No PII masking on `arguments_json`** — raw `ObjectMapper` serialization (`McpAuditService.java:40`). Tools receiving PII should scrub before `record()` or add a masking interceptor — `mcp_tool_audit.arguments_json` is verbatim.
* **Synchronous audit latency** — `REQUIRES_NEW` adds ~1-2 ms + one `INSERT` per `tools/call`. High-throughput needs async outbox / batching, not inline TX.
* **No read endpoint** — audit is DB-only; no `GET /api/audit/mcp` is shipped. Query `psql mcp_tool_audit` or add a read controller later.
* **Tool-level, not order-level, authorization** — audit records *what* was done, but does not gate *whether* `cancel_order` for order 7 is allowed for this actor. That's domain auth (entity owner check) — separate concern.

**Where PR #49 picks up:**

PR #49 keeps the same `specification(auditService, aiMetrics)` seam (`AbstractMcpReadOnlyTool.java:67` + `AbstractMcpWriteTool.java:71`) but layers `AiMetrics.recordToolCall` (Micrometer `Timer` × `tool_name` × `success`) and OTel GenAI traces/spans per invocation on top of the durable audit rows — after #48 you can *query what happened*; after #49 you can *alert and trace how long it took*.

> Next: [`12-ai-observability.md`](./12-ai-observability.md) (PR #49) · or back to [`README.md`](./README.md) · high-level companion [`docs/additions/11-mcp-session-authorization-audit.md`](../../11-mcp-session-authorization-audit.md)

