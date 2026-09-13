# 04. Guarded Write Tool — cancel_order’s four-layer guard (PR #41)

> PR: [#41 — Guarded write tool `cancel_order` — first mutation behind four rails](https://github.com/anomalyco/order-management-api/pull/41) · Stack: MCP Java SDK (Streamable HTTP at `/mcp`), Spring Security `@PreAuthorize`, JPA domain state machine · Depends on [#37 MCP server](https://github.com/anomalyco/order-management-api/pull/37) + [#38 RAG](../more-detail-on-additions/01-rag-and-docs-search.md)

---

## 1. Purpose — first mutating MCP tool

PR #41 ships the **first tool that mutates data**: `cancel_order` on the MCP server (`CancelOrderTool.java:37`). Every tool before it was read-only by construction (`api_health`, `product_search`, `order_status`, `docs_search` via `AbstractMcpReadOnlyTool.java:31`) — safety rested on "there is nothing to change."

Concretely it ships:

- `CancelOrderTool` (`src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:37`) — MCP tool `cancel_order` gated off by default, requiring `confirmed=true` per call, audited, PII-free.
- `AbstractMcpWriteTool` (`src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java:40`) — separate base class for every future write tool (not a subclass of the read base).
- `OrderService.cancelOrder()` (`src/main/java/com/company/orderapi/domain/service/OrderService.java:205`) with `@PreAuthorize` + `@Transactional` + `@Timed`.
- Wiring + tests proving the agent **cannot** reach it (`AgentToolSet.java:33`, `AgentToolSetTest.java:74`).

One line: *four gates must say yes — and the agent never gets to ask.*
---

## 2. Problem — why not just expose a write

**Before PR #41** the MCP surface was trivially safe precisely because it was read-only:

| Before (PR #37-40 read-only world) | Why a naive write breaks it |
|---|---|
| `tools/list` advertises 4 read tools; worst case is a wasted call or hallucinated answer | One unguarded `cancel_order({orderId})` from a confused HTTP caller or a model hypothesis during function calling cancels the wrong order — irreversible from the customer's view |
| Agent (`AgentToolSet.java:33` + `AgentService.java:41`) could chain tools but never change state — even prompt injection could not mutate | If the agent wraps a write tool, *any* successful prompt injection becomes a state change; the model decides, the DB pays |
| Auth was uniform: the MCP endpoint is authenticated, read tools need no scope | Writes need **authorization** (`SCOPE_order_write` / `ROLE_API_KEY`), not just authentication — a read-scope token must not cancel |

Without staged gates, a write is one missing `if` away from a support ticket. PR #41 makes each gate a separate, deploy-time or code-level decision that fails closed.

---

## 3. Solution — four-layer guard (enabled flag → confirmed token → @PreAuthorize → domain state machine)

```
                        ┌─────────────────────────────────────────────────┐
                        │  Layer 1 — Deploy gate                          │
                        │  app.mcp.write-tool.enabled (default: absent)   │
                        │  @ConditionalOnProperty                          │
                        │  CancelOrderTool.java:36                         │
                        └───────────────────────┬─────────────────────────┘
                                                │ true ? bean exists : absent
                                                ▼
                        ┌─────────────────────────────────────────────────┐
                        │  Layer 2 — Per-call confirmation                │
                        │  confirmed MUST be exactly Boolean.TRUE          │
                        │  CancelOrderTool.java:77                         │
                        │  inputSchema requires ["orderId","confirmed"]     │
                        │  CancelOrderTool.java:61                         │
                        └───────────────────────┬─────────────────────────┘
                                                │ confirmed==true ? continue : IllegalArgumentException (isError=true)
                                                ▼
                        ┌─────────────────────────────────────────────────┐
                        │  Layer 3 — Service authorization                │
                        │  @PreAuthorize(SCOPE_order_write / ROLE_API_KEY)│
                        │  OrderService.java:202 + @Transactional          │
                        │  OrderService.java:201                           │
                        └───────────────────────┬─────────────────────────┘
                                                │ has scope ? continue : 403
                                                ▼
                        ┌─────────────────────────────────────────────────┐
                        │  Layer 4 — Domain state machine                 │
                        │  Order.cancel() — entity owns the rule           │
                        │  Order.java:151                                  │
                        │  PLACED/CONFIRMED → CANCELLED only              │
                        │  SHIPPED/DELIVERED/CANCELLED → error            │
                        └───────────────────────┬─────────────────────────┘
                                                │ valid transition ? commit : IllegalStateException
                                                ▼
                                      AUDIT + PII-free reply
                        ┌─────────────────────────────────────────────────┐
                        │  Audit: Logger "mcp.cancel-order" INFO          │
                        │  CancelOrderTool.java:39 + :84                  │
                        │  Reply: "Order ORD-… cancelled (status CANCELLED)"│
                        │  CancelOrderTool.java:86  (no customer data)    │
                        └─────────────────────────────────────────────────┘

  Agent isolation (orthogonal rail): AgentToolSet.java:33 only injects
  AbstractMcpReadOnlyTool beans — CancelOrderTool (AbstractMcpWriteTool)
  is structurally absent from the agent's @Tool surface. Test:
  AgentToolSetTest.java:86 noneMatch(cancel|create|delete|update).
```
Fail-closed: flag off → absent; no confirmed → isError before service (`CancelOrderToolTest.java:34` `never()`); no scope → 403; bad state → throw.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java:40` | Separate base for every guarded write tool — deliberately NOT a subclass of `AbstractMcpReadOnlyTool.java:31`; owns `specification(auditService, aiMetrics)` (`AbstractMcpWriteTool.java:75`) + `execute(Map)` (`AbstractMcpWriteTool.java:125`) |
| `src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:37` | `cancel_order` impl — `@ConditionalOnProperty(app.mcp.write-tool.enabled=true)` (`CancelOrderTool.java:36`), `AUDIT` logger (`CancelOrderTool.java:39`), `confirmed` guard (`CancelOrderTool.java:77`), delegates to `orderService.cancelOrder()` (`CancelOrderTool.java:83`) |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java:201` | `@Transactional` + `@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')")` (`OrderService.java:202`) + `@Timed("order.cancel")` (`OrderService.java:203`) |
| `src/main/java/com/company/orderapi/domain/Order.java:151` | `cancel()` state machine — `CANCELLED` and `SHIPPED`/`DELIVERED` are errors, otherwise `status=CANCELLED` (`Order.java:151-159`) |
| `src/main/java/com/company/orderapi/mcp/McpServerConfiguration.java:99` | Registers two separate lists: `List<AbstractMcpReadOnlyTool> readTools` (`McpServerConfiguration.java:101`) + `List<AbstractMcpWriteTool> writeTools` (`McpServerConfiguration.java:102`) → `specification(auditService, aiMetrics)` (`McpServerConfiguration.java:111` + `:115`) |
| `src/main/java/com/company/orderapi/agent/AgentToolSet.java:33` | Agent function surface — `@ConditionalOnProperty(app.rag.enabled)` (`AgentToolSet.java:32`), only wraps read tools; no reference to `AbstractMcpWriteTool` |
| `src/test/java/com/company/orderapi/mcp/CancelOrderToolTest.java:22` | Unit — `confirmed` gate + PII-free reply + schema asserts |
| `src/test/java/com/company/orderapi/mcp/McpServerWriteToolIntegrationTest.java:39` | Integration — real MCP client over `POST /mcp` with `app.mcp.write-tool.enabled=true`, proves commit vs refusal |
| `src/test/java/com/company/orderapi/agent/AgentToolSetTest.java:74` | Boundary test — `noneMatch(cancel|create|delete|update)` (`AgentToolSetTest.java:86`) |

### Snippet 1 — Deploy gate (`CancelOrderTool.java:36-45`)

```java
// src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:36
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "write-tool.enabled", havingValue = "true")
public class CancelOrderTool extends AbstractMcpWriteTool {

    private static final Logger AUDIT = LoggerFactory.getLogger("mcp.cancel-order"); // CancelOrderTool.java:39
    private final OrderService orderService; // CancelOrderTool.java:41

    @Override public String name() { return "cancel_order"; } // CancelOrderTool.java:48
    @Override public String description() { // CancelOrderTool.java:53
        return "MUTATES DATA: cancel the order with the given id. Only PLACED and "
                + "CONFIRMED orders can be cancelled; shipped/delivered/cancelled "
                + "orders are refused. Requires confirmed=true. Returns only the "
                + "order number and its new status - never customer data.";
    }
```

### Snippet 2 — Structural separation (`AbstractMcpWriteTool.java:12-40`)

```java
// src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java:12
/**
 * Deliberately NOT a subclass of AbstractMcpReadOnlyTool. The two tool families
 * are kept structurally separate so a write tool can never satisfy
 * List<AbstractMcpReadOnlyTool> and silently appear where only read-only
 * functionality is allowed (tool list assertions, AgentToolSet).
 */
// src/main/java/com/company/orderapi/mcp/AbstractMcpWriteTool.java:40
public abstract class AbstractMcpWriteTool {
    public abstract String name();        // AbstractMcpWriteTool.java:43
    public abstract String description(); // AbstractMcpWriteTool.java:46 — MUST state MUTATES DATA
    public abstract JsonSchema inputSchema(); // AbstractMcpWriteTool.java:49 — MUST require confirmed
    protected abstract String run(Map<String, Object> arguments); // AbstractMcpWriteTool.java:57
    public final String execute(Map<String, Object> arguments) { return run(arguments); } // AbstractMcpWriteTool.java:125
}
```


### Snippet 3 — Per-call confirmation (`CancelOrderTool.java:61-86`)

```java
// src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:61
@Override public JsonSchema inputSchema() {
    return objectSchema(Map.of(
            "orderId", Map.of("type","integer","description","Numeric id of the order to cancel."),
            "confirmed", Map.of("type","boolean","description","Must be true to execute this mutating tool. "
                    + "Anything else refuses the call.")),
            List.of("orderId", "confirmed")); // CancelOrderTool.java:70 — both required
}
// src/main/java/com/company/orderapi/mcp/CancelOrderTool.java:74
@Override protected String run(Map<String, Object> arguments) {
    long orderId = requiredPositiveLong(arguments, "orderId"); // CancelOrderTool.java:75
    Object confirmed = arguments.get("confirmed");              // CancelOrderTool.java:76
    if (!Boolean.TRUE.equals(confirmed)) {                      // CancelOrderTool.java:77
        throw new IllegalArgumentException(                     // → handled as isError=true in AbstractMcpWriteTool.java:101
                "Refusing to cancel order " + orderId + ": confirmed must be exactly true. "
                        + "Please confirm, then call again with confirmed=true.");
    }
    Order order = orderService.cancelOrder(orderId);            // CancelOrderTool.java:83 — only now touch the service
    AUDIT.info("cancel_order confirmed=true orderId={} orderNumber={} -> CANCELLED",
            order.getId(), order.getOrderNumber());             // CancelOrderTool.java:84
    return "Order " + order.getOrderNumber() + " cancelled (status CANCELLED)."; // CancelOrderTool.java:86
}
```

### Snippet 4 — Service authorization (`OrderService.java:201-210`)

```java
// src/main/java/com/company/orderapi/domain/service/OrderService.java:201
@Transactional                                                    // OrderService.java:201
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')") // OrderService.java:202
@Timed(value = "order.cancel", description = "Time to cancel an order", percentiles = 0.95) // OrderService.java:203
public Order cancelOrder(Long orderId) {                         // OrderService.java:205
    Order order = orders.findById(orderId)                       // OrderService.java:206
            .orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
    order.cancel();                                              // OrderService.java:208 — entity rule
    return orders.save(order);                                   // OrderService.java:209
}
```

### Snippet 5 — Domain state machine (`Order.java:151-159`)

```java
// src/main/java/com/company/orderapi/domain/Order.java:151
public void cancel() {
    if (status == OrderStatus.CANCELLED) {                       // Order.java:152
        throw new IllegalStateException("Order " + getId() + " is already cancelled.");
    }
    if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) { // Order.java:155
        throw new IllegalStateException(
                "Order " + getId() + " cannot be cancelled once " + status + ".");
    }
    this.status = OrderStatus.CANCELLED;                         // Order.java:159
}
```

Only PLACED/CONFIRMED → CANCELLED is valid; double-cancel and shipped/delivered are errors.

### Snippet 6 — Agent exclusion (`AgentToolSet.java:33` + `AgentToolSetTest.java:74`)

```java
// src/main/java/com/company/orderapi/agent/AgentToolSet.java:33 — only read tools injected, no AbstractMcpWriteTool
// src/test/java/com/company/orderapi/agent/AgentToolSetTest.java:86
assertThat(names).noneMatch(n -> n.contains("cancel") || n.contains("create") || n.contains("delete") || n.contains("update"));
```

---

## 5. How to use — enable flag, curl with confirmed=true/false, audit log

### Prerequisites

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.mcp.write-tool.enabled=true  # omit flag → cancel_order absent
```

### 1. Confirm the tool appears only when enabled

```bash
# With flag ON — cancel_order is listed, schema requires confirmed
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | jq '.result.tools[] | {name, description, required: .inputSchema.required}'
# expect: {"name":"cancel_order","description":"MUTATES DATA: ...","required":["orderId","confirmed"]}

# With flag OFF (restart without arg) — cancel_order absent
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | jq '.result.tools[].name'
# "api_health" "docs_search" "order_status" "product_search" — no cancel_order
```

### 2. Cancel with `confirmed=true` (happy path)

```bash
# Create an order to cancel (any PLACED order works — use the REST API or a seeded id)
# Example: cancel order 1
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cancel_order","arguments":{"orderId":1,"confirmed":true}}}' \
  | jq .
# {"result":{"content":[{"type":"text","text":"Order ORD-XXXXXXXXXX cancelled (status CANCELLED)."}],"isError":false}}

```

### 3. Refused without `confirmed=true` (ergonomic rail)

```bash
# Missing/false/string "true" → isError=true, service never touched (CancelOrderToolTest.java:42 never())
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream"   -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"cancel_order","arguments":{"orderId":1}}}' | jq .
# isError true — "confirmed must be exactly true"
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream"   -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"cancel_order","arguments":{"orderId":1,"confirmed":false}}}' | jq .
# isError true — only Boolean.TRUE passes (CancelOrderTool.java:77 → AbstractMcpWriteTool.java:101)
```

### 4. Audit log + metrics

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.mcp.write-tool.enabled=true 2>&1 | grep "mcp.cancel-order"
# INFO  mcp.cancel-order — cancel_order confirmed=true orderId=1 orderNumber=ORD-XXXXXXXXXX -> CANCELLED (CancelOrderTool.java:84)
curl -s http://localhost:8080/actuator/metrics/order.cancel | jq .  # @Timed OrderService.java:203
curl -s http://localhost:8080/actuator/prometheus | grep order_cancel
# Reply + audit expose only orderNumber/status — no customer data (CancelOrderTool.java:86, OrderStatusTool.java:56)
```

---

## 6. Key decisions — why NOT a subclass of read-only, why confirmed is not a security boundary

### Why `AbstractMcpWriteTool` is deliberately NOT a subclass of `AbstractMcpReadOnlyTool`

The comment at `AbstractMcpWriteTool.java:15` is the decision:

> *Deliberately NOT a subclass of `AbstractMcpReadOnlyTool`. The two families are structurally separate so a write tool can never satisfy `List<AbstractMcpReadOnlyTool>` and silently appear where only read-only functionality is allowed.*

Options considered:

| Option | Consequence |
|---|---|
| Write extends read base | Every `List<AbstractMcpReadOnlyTool>` injection silently includes the write — one import and a write is in the agent; tests rely on string checks. |
| Write is separate hierarchy (`AbstractMcpWriteTool.java:40`) — **chosen** | `McpServerConfiguration.java:101-102` injects two typed lists; `AgentToolSet.java:33` depends only on reads; a write *cannot* satisfy the read list. Duplication of `objectSchema` is the price. |

The type boundary makes `AgentToolSetTest.java:86` a type guarantee, not just a naming convention.

### Why `confirmed=true` is an ergonomic rail, NOT the security boundary

`CancelOrderTool.java:77` `Boolean.TRUE.equals(confirmed)` is intentionally **not** treated as auth:

- It protects against **accidents**: model hallucinates `confirmed:false`, caller omits the field, JSON sends `"true"` as string, or a script fires without user affirmation. All are refused before `OrderService` is touched (`CancelOrderToolTest.java:42` `never()`).
- It does **not** protect against a determined attacker: an attacker who can call `POST /mcp` can send `confirmed:true` — the rail would not stop them. That is why the real boundary is `OrderService.java:202` `@PreAuthorize(SCOPE_order_write)` plus the transport auth (`McpServerConfiguration.java:79` `extractSecurityContext` → `mcp_actor`) and the domain rule (`Order.java:151`).
- Naming matters: the schema marks both `orderId` and `confirmed` as `required` (`CancelOrderTool.java:70`), the description says `Requires confirmed=true` (`CancelOrderTool.java:53`), and the error message tells the caller how to confirm (`CancelOrderTool.java:79-80`). Fraud-protection reasoning: never let `false` or absent silently mean "no, but proceed anyway" — refuse loudly with a human message, surfaced as `isError=true` (`AbstractMcpWriteTool.java:109`).

In short: **flag = capability exists, confirmed = you meant it, @PreAuthorize = you may, state machine = it is valid** — four different questions, four different answers.

---

## 7. How to verify — curl / MCP + DB + unit tests

### 1. Tool absence/presence by flag

```bash
# OFF (default) — 4 read tools only
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
# "api_health" "docs_search" "order_status" "product_search"

# ON — 5 tools, cancel_order requires confirmed
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.mcp.write-tool.enabled=true &
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[] | select(.name=="cancel_order") | .inputSchema.required'
# ["orderId","confirmed"]
```

### 2. DB — row is committed only on success

```bash
docker compose exec postgres psql -U order -d orderdb -c "SELECT order_number, status FROM orders WHERE id=1;"
# ORD-XXXXXXXXXX | CANCELLED  (after confirmed=true)
docker compose exec postgres psql -U order -d orderdb -c "SELECT status FROM orders WHERE id=2;"
# PLACED  (after confirmed=false — McpServerWriteToolIntegrationTest.java:96)
```

### 3. Unit + integration — four rails in tests

```bash
./mvnw test -Dtest=CancelOrderToolTest,McpServerWriteToolIntegrationTest,AgentToolSetTest -Dspring.profiles.active=test
# CancelOrderToolTest (4): refusesWithoutConfirmation (CancelOrderToolTest.java:34), confirmedTrue (CancelOrderToolTest.java:46),
#   badOrderId (CancelOrderToolTest.java:58), schemaRequiresConfirmed (CancelOrderToolTest.java:69)
# McpServerWriteToolIntegrationTest (3): confirmedCommits (McpServerWriteToolIntegrationTest.java:77),
#   unconfirmedRefused (McpServerWriteToolIntegrationTest.java:96), toolsListRequiresConfirmation (McpServerWriteToolIntegrationTest.java:112)
# AgentToolSetTest: agentSurfaceStaysStrictlyReadOnlyNoWriteTools (AgentToolSetTest.java:74)
```

### 4. Audit + metrics — production observability

```bash
# Audit log (structured JSON in prod, plain INFO locally)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.mcp.write-tool.enabled=true 2>&1 \
  | grep "mcp.cancel-order"   # CancelOrderTool.java:39 logger name

# Micrometer timer + Actuator
curl -s http://localhost:8080/actuator/metrics/order.cancel | jq '.measurements'
curl -s http://localhost:8080/actuator/prometheus | grep -E "order_cancel|mcp_tool"
# PR #48/49 add session-scoped audit (McpAuditService) + AiMetrics.recordToolCall
# (AbstractMcpWriteTool.java:95 + :98) — same rails, richer dimensions
```

---
## 8. How this helps you on the job — build / operate / interview

- **Build — add a new write safely on day one.** You know the template: create `ShipOrderTool extends AbstractMcpWriteTool` (`AbstractMcpWriteTool.java:40`, like `CancelOrderTool.java:37`), annotate `@ConditionalOnProperty(app.mcp.write-tool.enabled)` (`CancelOrderTool.java:36`), implement `inputSchema` requiring `confirmed` + domain id (`CancelOrderTool.java:61`), guard with `Boolean.TRUE.equals` (`CancelOrderTool.java:77`), delegate to a new `OrderService.shipOrder()` with `@PreAuthorize` (`OrderService.java:202` pattern) and `Order.ship()` state check (`Order.java:177` pattern), audit via `Logger("mcp.ship-order")` (`CancelOrderTool.java:39` pattern), and inject into `McpServerConfiguration.java:102` automatically. Then lock the agent boundary with `AgentToolSetTest.java:86`. Copy-paste a PII-free reply (`CancelOrderTool.java:86`).

- **Operate — explain the cost and failure mode of each rail.** Flag off → `tools/list` has no write, zero risk, zero cost; confirmed missing → `isError=true` before DB, visible in MCP response and audit `success=false` (`AbstractMcpWriteTool.java:101-104`); scope missing → `403` from Spring Security, not a silent tool error; bad state → `IllegalStateException` from `Order.java:151`, surfaced as `isError`. You can tail `mcp.cancel-order` (`CancelOrderTool.java:39`) and `order.cancel` (`OrderService.java:203`) separately. Rolling out a new write is a flag flip reviewed in deploy config, not a code deploy.

- **Interview — whiteboard a guarded mutation in 90 seconds with receipts.** "PR #41 `cancel_order` (`CancelOrderTool.java:37`) is four rails: (1) deploy gate `@ConditionalOnProperty(app.mcp.write-tool.enabled)` (`CancelOrderTool.java:36`) so `McpServerConfiguration.java:102` gets an empty write list by default; (2) per-call `confirmed==Boolean.TRUE` (`CancelOrderTool.java:77`) with `required: [orderId, confirmed]` (`CancelOrderTool.java:70`); (3) service `@PreAuthorize(SCOPE_order_write)` + `@Transactional` (`OrderService.java:201-202`); (4) entity `Order.cancel()` state machine PLACED→CANCELLED (`Order.java:151`). Plus structural isolation: `AbstractMcpWriteTool.java:40` is NOT a subclass of `AbstractMcpReadOnlyTool`, so `AgentToolSet.java:33` cannot reach it — proven by `AgentToolSetTest.java:86`. Audit `mcp.cancel-order` (`CancelOrderTool.java:39`), PII-free reply (`CancelOrderTool.java:86`)."

---
## 9. Interview lens — 3 Q&A you can now answer

**Q1: "Walk me through a write tool you'd trust an LLM to be near."**

> "Four rails: flag `app.mcp.write-tool.enabled` (`CancelOrderTool.java:36`) → no bean without it (`McpServerConfiguration.java:102`); confirmed==`Boolean.TRUE` (`CancelOrderTool.java:77`) with required `[orderId,confirmed]` (`CancelOrderTool.java:70`) → `isError` before service (`CancelOrderToolTest.java:42`); `@PreAuthorize(SCOPE_order_write)` (`OrderService.java:202`) → 403 without scope; `Order.cancel()` (`Order.java:151`) → shipped/double-cancel error. Agent isolated via separate hierarchy (`AbstractMcpWriteTool.java:40` vs read) → `AgentToolSetTest.java:86`; audit `mcp.cancel-order` (`CancelOrderTool.java:39`)."

**Q2: "Why is `confirmed` not a security boundary? Isn't that just security theater?"**

> "`confirmed` (`CancelOrderTool.java:77`) stops accidents (false/missing/string) before `OrderService` is touched (`CancelOrderToolTest.java:42`). An attacker can send `confirmed:true`, so the real boundary is `OrderService.java:202` `@PreAuthorize` + `Order.java:151` + `McpServerConfiguration.java:79`."

**Q3: "Why not just make `AbstractMcpWriteTool` extend `AbstractMcpReadOnlyTool` and reuse the helpers?"**

> "If it extended the read base, `CancelOrderTool` would satisfy `List<AbstractMcpReadOnlyTool>` and silently appear in the agent or read-only assertions. Sibling hierarchies (`AbstractMcpReadOnlyTool.java:31` vs `AbstractMcpWriteTool.java:40`) let `McpServerConfiguration.java:101-102` inject two typed lists; `AgentToolSet.java:33` depends only on reads. The duplication is the price; guarantee is `AgentToolSetTest.java:86`."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #50+ pick up

**What PR #41 alone does NOT do (by design):**

- **One write, one entity.** Only `cancel_order` + `Order.cancel()` (`Order.java:151`). No `confirm_order`/`ship_order` yet — those are PR #50, reusing the same four rails + `AbstractMcpWriteTool` so the template is proven before the state machine grows to `PLACED → CONFIRMED → SHIPPED → DELIVERED` (`Order.java:166` + `:177`).
- **No outbox / status-changed event on cancel.** `OrderService.updateOrderStatus()` writes `OrderStatusChangedMessage` to the outbox, but `cancelOrder()` (`OrderService.java:205`) just saves the row — downstream consumers learn CANCELLED via polling, not an event. PR #31+ extends the outbox to all transitions if needed.
- **No stock compensation.** Cancelling does not restore `Product.stockQuantity` (which `OrderService.placeOrder()` decremented). Correct for PR #41's scope (rule is "when can you cancel"), but a production cancel would need compensating stock + payment refund — both out of scope here.
- **Audit is logger-only, not a table.** `mcp.cancel-order` (`CancelOrderTool.java:39`) is structured INFO (JSON in prod) plus `McpAuditService.record()` (`AbstractMcpWriteTool.java:95`) from PR #48; there is no `mcp_audit` table query in PR #41 itself — add `McpAuditService` persistence or query `psql mcp_audit` after PR #48.

**Where PR #50+ pick up:**

- **PR #50** adds `confirm_order`/`ship_order` (`ConfirmOrderTool.java:16`/`ShipOrderTool.java:16`) + `Order.confirm()` (`Order.java:166`)/`ship()` (`Order.java:177`).
- **PR #48** adds session-scoped `mcp_actor`/`mcp_session_id` via `McpServerConfiguration.java:66` `contextExtractor` and wraps every `specification(auditService, aiMetrics)` (`AbstractMcpWriteTool.java:75` + `AbstractMcpReadOnlyTool.java:67`) so each `cancel_order` records actor/session/outcome.
- **PR #42** adds the second guarded write on a different domain — `reindex_docs` (`ReindexDocsTool.java:37`) also `extends AbstractMcpWriteTool` with `app.mcp.write-tool.enabled` + `confirmed` — proving the template is reusable beyond orders.

> Next: [`03-chat-memory.md`](./03-chat-memory.md) (PR #40 — memory before mutation) · or for the write expansion path — [`13-guarded-write-expanded.md`](../13-guarded-write-expanded.md) (PR #50) · or [`05-rag-productionization.md`](./05-rag-productionization.md) (PR #42 — second write tool on RAG).
