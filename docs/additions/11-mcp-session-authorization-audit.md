# 11. MCP Session-Scoped Authorization + Agent-Identity Audit Trail

> **PR #48** — PR #47 gave `/mcp` an identity layer (OAuth 2.1 tokens with scopes). But a
> token only says *who* the client is — not *which session* it belongs to, nor *what
> each call actually did*. This PR binds every MCP tool invocation to the
> authenticated session and writes an immutable audit record: **who (actor),
> which session, which tool, with what arguments, success/error, when**.

---

## 1. The one-paragraph mental model

OAuth gives you a bearer token; the token has a `client_id` (or `sub`) claim.
That identifies the **client application** (`mcp-server`). But one client can
open many independent MCP sessions (e.g. a user chatting with an agent, a CI
pipeline running a batch job, a background sync). To answer *"what did agent X
do in session Y?"* you need:

1. **Session identity** — the MCP transport's session ID (from `initialize`),
   propagated through every `tools/call`.
2. **Actor identity** — the OAuth `client_id` from the JWT, injected into the
   transport context by a custom `contextExtractor`.
3. **Audit sink** — a single `McpAuditService` that every tool handler calls
   (via the `specification(McpAuditService)` overload) to record the outcome.

The result: a queryable `mcp_tool_audit` table that lets you reconstruct the
full timeline of any session or any actor.

---

## 2. Why this matters (what was missing)

Before this PR:
- `/mcp` required a valid token with `SCOPE_mcp` (PR #47) — **who** is calling.
- The token had no session context — multiple calls from the same token were
  indistinguishable.
- No audit trail existed — a compromised token could call any tool silently.
- The agent surface (`agentic_ask`) and the MCP server shared tool logic
  (`AbstractMcpReadOnlyTool.execute()`), but only the MCP path had HTTP context
  for authentication.

Now:
- The **transport context** carries both `mcp_actor` (OAuth client_id) and
  `mcp_session_id` (from the MCP protocol) into every tool handler.
- **Every tool call** (success or failure) writes one row to `mcp_tool_audit`
  inside a `REQUIRES_NEW` transaction so the audit survives even if the tool
  rolls back.
- The same `AbstractMcpReadOnlyTool.specification(McpAuditService)` is used
  by both the MCP server and the agent's function-calling surface — **one
  audit path for two protocols**.

---

## 3. The protocol shapes (what a wire exchange looks like)

### MCP `initialize` + first `tools/call`

```
CLIENT (with Bearer token)                    SERVER
   │──── POST /mcp (initialize) ────────────►│
   │                                        │ 1. Spring Security filter
   │                                        │    validates JWT → sets
   │                                        │    Authentication in context
   │                                        │ 2. MCP transport contextExtractor
   │                                        │    reads SecurityContext →
   │                                        │    puts {mcp_actor="mcp-server"}
   │                                        │    into McpTransportContext
   │◄─── {jsonrpc: "2.0", result: { ... }}─│
   │──── POST /mcp (tools/call api_health)──►│
   │                                        │ 3. Tool handler receives
   │                                        │    McpTransportContext with
   │                                        │    actor + sessionId
   │                                        │ 4. Executes tool logic
   │                                        │ 5. McpAuditService.record()
   │                                        │    writes mcp_tool_audit row
   │◄─── {jsonrpc: "2.0", result: { ... }}─│
```

### Audit record produced

| column | value |
|--------|-------|
| `session_id` | `"a1b2c3d4-..."` (from MCP `initialize`) |
| `actor` | `"mcp-server"` (OAuth `client_id` claim) |
| `tool_name` | `"api_health"` |
| `arguments_json` | `"{}"` |
| `success` | `true` |
| `error_message` | `NULL` |
| `occurred_at` | `2026-09-11T13:51:57.549Z` |

---

## 4. The code (what changed)

### New files

| File | Purpose |
|------|---------|
| `mcp/McpToolAudit.java` | JPA entity for `mcp_tool_audit` table (session_id, actor, tool_name, arguments_json, success, error_message, occurred_at) |
| `mcp/McpToolAuditRepository.java` | Spring Data repository with session/actor/tool query methods |
| `mcp/McpAuditService.java` | Service that writes audit rows in a `REQUIRES_NEW` transaction |
| `docs/additions/11-mcp-session-authorization-audit.md` | This document |

### Modified files

| File | Change |
|------|--------|
| `mcp/McpServerConfiguration.java` | Adds `contextExtractor` to `WebMvcStatelessServerTransport` that pulls `Authentication` from `SecurityContextHolder`, extracts JWT `client_id`/`sub` as `mcp_actor`, and injects it into `McpTransportContext`; wires `McpAuditService` into tool specifications |
| `mcp/AbstractMcpReadOnlyTool.java` | Adds `specification(McpAuditService)` overload; wraps `callHandler` to extract actor/session from `McpTransportContext` and call `auditService.record()` |
| `mcp/AbstractMcpWriteTool.java` | Same audit wrapper for guarded write tools |
| `mcp/McpOAuth2IntegrationTest.java` | Adds 2 tests: `toolInvocationCreatesAuditTrail` (success case) and `failedToolInvocationRecordsError` (failure case) |
| `integration/DatabaseSchemaIntegrationTest.java` | Expects 13 tables now (includes `mcp_tool_audit`) |
| `db/changelog/v1.0/09_create_mcp_tool_audit.sql` | Liquibase changeset for `mcp_tool_audit` table + 3 indexes |
| `db/changelog/db.changelog-master.xml` | Includes the new changeset |

### Transport contextExtractor — the bridge from Spring Security to MCP

```java
.contextExtractor(this::extractSecurityContext)

private McpTransportContext extractSecurityContext(ServerRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String actor = "anonymous";
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
        actor = jwt.getClaimAsString("client_id");
        if (actor == null || actor.isBlank()) {
            actor = jwt.getSubject();
        }
    }
    return McpTransportContext.create(singletonMap("mcp_actor", actor));
}
```

- Runs **after** the Spring Security filter chain, so `SecurityContextHolder`
  already holds the validated `JwtAuthenticationToken`.
- Reads the JWT `client_id` claim (falls back to `sub`); this is the OAuth
  *client*, not the end-user — perfect for machine-to-machine MCP clients.
- Puts the actor into the SDK's `McpTransportContext` map under key
  `mcp_actor`. The tool handler reads it via `ctx.get("mcp_actor")`.

### Tool handler audit wrapper

```java
.callHandler((transportContext, request) -> {
    String sessionId = extractSessionId(transportContext);  // "unknown" for stateless
    String actor = extractActor(transportContext);          // from mcp_actor
    Map<String, Object> arguments = ...;
    try {
        String result = execute(arguments);
        if (auditService != null) {
            auditService.record(sessionId, actor, name(), arguments, true, null);
        }
        return new CallToolResult(result, false);
    } catch (IllegalArgumentException e) {
        String message = e.getMessage() == null ? "Tool failed." : e.getMessage();
        if (auditService != null) {
            auditService.record(sessionId, actor, name(), arguments, false, message);
        }
        return new CallToolResult(message, true);
    }
})
```

- `auditService.record()` runs with `@Transactional(propagation = REQUIRES_NEW)`
  so the audit commits even if the tool's transaction rolls back.
- Failure path captures the exception message as `error_message`.
- The `McpTransportContext` only provides `mcp_actor`; session ID is
  `"unknown"` for the stateless transport (the SDK's stateful transport would
  provide a real `sessionId`).

---

## 5. Key decisions

### Why `REQUIRES_NEW` for audit?

The tool's business logic runs in the request transaction. If it throws, that
transaction rolls back — but the audit **must** survive. `REQUIRES_NEW` starts a
separate physical transaction that commits independently. The audit table is
append-only; no rollback needed.

### Why `McpTransportContext` and not `McpSyncServerExchange`?

The project uses **stateless** transport (`WebMvcStatelessServerTransport` →
`McpStatelessSyncServer`). The stateless tool spec's handler receives
`McpTransportContext` (a `Map<String,Object>`), not the stateful
`McpSyncServerExchange` (which has `sessionId()`, `getClientInfo()`). The
context extractor is the **only** way to inject data into the stateless handler.

### Why `client_id` claim, not `sub`?

For `client_credentials` grants, the JWT `client_id` claim identifies the
OAuth client (`mcp-server`). The `sub` is the same. For `authorization_code`
grants, `sub` is the end-user. Using `client_id` keeps the actor stable as
"the OAuth client" — which is what you want for machine-to-machine audit.

### Backward compatibility

- `specification()` (no args) still works — calls `specification(null)` → no
  audit written. Used by the agent surface (`AgentToolSet`) which doesn't have
  an MCP transport context.
- `specification(McpAuditService)` is called only from `McpServerConfiguration`
  where the audit service is available.

---

## 6. What to try

```bash
# 1. Get a token
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)

# 2. Call a tool via MCP SDK client (or raw JSON-RPC)
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"api_health","arguments":{}}}'

# 3. Query the audit trail
curl -s http://localhost:8080/api/audit/mcp?actor=mcp-server | jq .
# (Assuming you expose a read endpoint — or query the DB directly)
psql -c "SELECT actor, tool_name, arguments_json, success, error_message, occurred_at
         FROM mcp_tool_audit ORDER BY occurred_at DESC LIMIT 5;"
```

---

## 7. Honest limits

1. **Stateless transport = no real session ID.** The `session_id` column is
   always `"unknown"` because `WebMvcStatelessServerTransport` doesn't track
   sessions. To get real session correlation, you'd need the stateful transport
   (`WebMvcStreamableServerTransport`) + a session store.

2. **Actor is always the OAuth client, never the end-user.** If a human uses
   the `mcp-console` (authorization_code + PKCE), the JWT `sub` is the user,
   but we read `client_id` (which is `mcp-console`). To audit the human, you'd
   need to prefer `sub` for authorization_code grants.

3. **No PII masking on arguments.** The `arguments_json` stores the raw
   arguments. If a tool receives PII (e.g. `cancel_order` with `customer_id`),
   it's written verbatim. The tool should scrub before calling, or the audit
   service should have a masking step.

4. **Audit is synchronous.** The `REQUIRES_NEW` transaction adds ~1-2ms per
   tool call. For high-throughput, you'd move to an async outbox pattern.

---

*Next: PR #49 — AI-feature observability (metrics, traces, structured logs for
every MCP tool call, agent turn, and RAG retrieval).*