# 04. Guarded write tool: `cancel_order` — the first mutation, and the rails that make it safe

> **Bonus PR #41** — builds directly on
> [`02-agentic-tool-calling.md`](02-agentic-tool-calling.md). PRs #38–#40 added
> tools that only ever *read*: product search, order status, docs search, and an
> agent that orchestrates them. Safety rested on "there is nothing a tool can
> change." This PR makes that interval end **deliberately**: it adds the first
> mutating tool (`cancel_order`) on the MCP server — and wraps it in so many
> guard rails that the design lesson is the rails, not the mutation.

---

## 1. The problem in one sentence

Read-only tools are trivially safe. The moment a tool can change state, a
single unguarded implementation is one confused HTTP caller (or one model
hypothesis during function calling) away from "cancelled the wrong order" — so
a write tool must be stage-gated, confirmation-required, authorized at the
service layer, domain-disciplined, audited, and structurally unreachable by the
agent.

```
 caller ── cancel_order({orderId, confirmed:true})        ┌─ 1) feature gate ───────────┐
          │                                                │ app.mcp.write-tool.enabled  │
          ▼                                                └──────────────┬──────────────┘
   MCP server (CancelOrderTool)   ── tool exists only if the prop is true    │
          │  confirmed != true ? refuse ──┐  (ergonomic rail, NOT the        │
          ▼                              ▼   security boundary)              │
   OrderService.cancelOrder(id) ─────────────────────────►  2) @PreAuthorize │
          │  @Transactional @Timed                          (order_write)    │
          ▼                                                                ▼
   Order.cancel()  ── domain state machine ──►  CANCELLED           agent NEVER sees
          │  audit log: "mcp.cancel-order" INFO                          cancel_order
          ▼
    committed row + PII-free reply ("Order <no> cancelled")
```

---

## 2. The guard rails (each is a separate, testable decision)

### 2.1 Feature gate — the capability does not exist by default (`CancelOrderTool`)

```java
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "write-tool.enabled", havingValue = "true")
public class CancelOrderTool extends AbstractMcpWriteTool { ... }
```

No property → no bean → `McpServerConfiguration` collects an empty write-tool
list → `tools/list` advertises only the read tools. `McpServerSdkIntegrationTest`
asserts this exactly: `tools` contains `api_health`, `order_status`,
`product_search` and `doesNotContain("cancel_order", "create_order",
"delete_order")`. Opt-in at deploy time is the first rail, and the cheapest.

### 2.2 Structural separation — `AbstractMcpWriteTool` is NOT the read base

`McpTool` (`OrderStatusTool`, `ProductSearchTool`, …) and the new write base
share nothing but the final `specification()`/`execute()` envelope:

```java
public abstract class AbstractMcpWriteTool {   // deliberately separate hierarchy
    public final McpSchema.Tool specification() { ... }   // name + description + inputSchema
    public final CallToolResult execute(Map<String, Object> args) { ... } // error → isError
    protected abstract String run(Map<String, Object> args);
    protected static JsonSchema objectSchema(...) { ... }
    protected static void requiredPositiveLong(...) { ... }
}
```

Same pattern, but a **different base class**. That sound, trivial decision is
what makes the next rail enforceable: the automatic tool collection can tell
writes from reads by type (`List<AbstractMcpReadOnlyTool>` vs
`List<AbstractMcpWriteTool>`) without naming conventions.

### 2.3 The agent can never call it — the biggest lesson

`AgentToolSet` is what the model may invoke via function calling. `CancelOrderTool`
is **not** there and cannot be, because:

- `AgentToolSet` builds its `@Tool` methods from injected, read-only services
  (`ProductRepository`, `OrderRepository`, `RagService`, `DocumentIngestionService`);
  `OrderService.cancelOrder` is a dependency of the *MCP* write tool only;
- the regression test locks the boundary:

```java
// AgentToolSetTest
assertThat(names).noneMatch(n -> n.contains("cancel") || n.contains("create")
        || n.contains("delete") || n.contains("update"));
```

So the agent consumed PR #39's read-only surface stays literally incapable of a
mutation — no matter what the model agrees to "call". One host serves both
capabilities; the *agent* is what stays read-only.

### 2.4 Per-call confirmation — `confirmed` is required, not defaulted

`inputSchema` requires both `orderId` and `confirmed`; the run refuses anything
but `Boolean.TRUE`:

```java
Object confirmed = arguments.get("confirmed");
if (!Boolean.TRUE.equals(confirmed)) {
    throw new IllegalArgumentException("Refusing to cancel order " + orderId
            + ": confirmed must be exactly true. Please confirm, then call again with confirmed=true.");
}
```

This is an **ergonomic rail, intentionally not the security boundary** — it
protects against an accidental call (stray `confirmed:false`/absent/string
`"true"`), not against a deliberate attacker. Fraud-protection reasoning: never
let `false` mean anything but no.

### 2.5 Service-level authorization — the real boundary (`OrderService.cancelOrder`)

```java
@Transactional
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')")
@Timed(value = "order.cancel", description = "Time to cancel an order", percentiles = 0.95)
public Order cancelOrder(Long orderId) {
    Order order = orders.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
    order.cancel();
    return orders.save(order);
}
```

Same guard as every other mutation in the app (`SCOPE_order_write` or
`ROLE_API_KEY`, short-circuiting when the disable flag is set), plus a Micrometer
timer. Authorization lives in the service, not the transport — any future caller
of the same capability inherits the check.

### 2.6 Domain state machine — the rules live on the entity (`Order.cancel()`)

```java
public void cancel() {
    if (status == OrderStatus.CANCELLED) {
        throw new IllegalStateException("Order " + getId() + " is already cancelled.");
    }
    if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
        throw new IllegalStateException("Order " + getId() + " cannot be cancelled once " + status + ".");
    }
    this.status = OrderStatus.CANCELLED;
}
```

`@OneToMany (orphanRemoval=true)`-free, immutable-status flow: PLACED/CONFIRMED →
CANCELLED, never in reverse, never from SHIPPED/DELIVERED, idempotency refused
(double-cancel is an error, not a silent success). Verified end-to-end against
the committed DB in `OrderServiceTest` (including the SHIPPED case via a
persisted shipping address).

### 2.7 Audit + PII-free reply

```java
private static final Logger AUDIT = LoggerFactory.getLogger("mcp.cancel-order");
...
AUDIT.info("cancel_order confirmed=true orderId={} orderNumber={} -> CANCELLED",
        order.getId(), order.getOrderNumber());
return "Order " + order.getOrderNumber() + " cancelled (status CANCELLED).";
```

A structured, dedicated logger (JSON in prod) records each *successful* mutation
with the key facts; the reply exposes only order number + new status — no
customer data, consistent with every read tool. `McpServerWriteToolIntegrationTest`
asserts the reply never leaks the customer email.

---

## 3. How the tests back every rail

| Rail | Test |
|---|---|
| Gate off by default | `McpServerSdkIntegrationTest` (RAG-off context, no prop) — tools are exactly the 3 read ones, `doesNotContain(cancel_order, …)` |
| Gate on, then the full stack | `McpServerWriteToolIntegrationTest` (`app.mcp.write-tool.enabled=true`) — tools/list shows `cancel_order` with required `["orderId","confirmed"]`, confirmed call commits CANCELLED, unconfirmed call returns an error and leaves PLACED |
| Agent can't reach it | `AgentToolSetTest` — write-name exclusion assertion |
| Confirmation logic | `CancelOrderToolTest` — refuses without confirmation *and never touches the service*; good call delegates |
| State machine | `OrderStatusTransitionTest` — cancel-on-cancelled and cancel-on-shipped/delivered are errors |
| Service + DB | `OrderServiceTest` (+4) — committed CANCELLED, unknown id → `IllegalArgumentException`, SHIPPED stays SHIPPED, double-cancel rejected |

**Verification:** `./mvnw test` → **164 tests, 0 failures** (147 before + 17 new).

---

## 4. What you would say in an interview

- **"How do you add a mutating tool safely?"** — Enumerate rails first: feature
  gate so it doesn't exist by default; separate read/write tool hierarchies so
  collection code can reason about them by type; make the *agent* — the one
  caller that decides for itself — structurally unable to reach it; require an
  explicit per-call confirmation; enforce the real authorization at the service
  layer (scope/API-key), not in the tool; keep domain rules on the entity; audit
  every successful mutation; return no PII.
- **"Why not just make the agent say 'please cancel'?"** — Prompting is a
  behavioural contract, not a boundary. The boundary is that the capability is
  not in the agent's tool set at all.
- **"Confirmation isn't real security then?"** — Correct. It is a crisp,
  testable ergonomic rail against accidents. The security boundary is the
  service-layer `@PreAuthorize` + the domain state machine; the gate and the
  agent separation keep the surface small in the first place. Belt, suspenders,
  *and* the trousers stay in the drawer.

---

## 5. Apply this at work

1. Before the first write tool: agree the rails (gate flag, confirmation
   convention, service-layer authz, audit logger, PII-free replies) and write
   tests *for the absence* — "off by default", "not in the agent surface".
2. Keep a read-only public/protocol surface for as long as you can; future
   read-only tools are much cheaper to add than mutations are to guard.
3. Never default an irreversible flag. `confirmed:false` must refuse, loudly.
4. Return data that is useful *and* sanitised — an order number, not the
   customer's PII.