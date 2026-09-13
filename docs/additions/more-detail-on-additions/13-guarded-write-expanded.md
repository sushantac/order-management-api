# 13. Guarded Write Expanded — Full State Machine (PR #50)

> PR: [#50 — Guarded write expanded: `confirm_order` + `ship_order` — full lifecycle state machine](https://github.com/anomalyco/order-management-api/pull/50) · Stack: MCP Java SDK (Streamable HTTP at `/mcp`), Spring Security `@PreAuthorize`, JPA domain state machine, Transactional Outbox (`OrderStatusChangedMessage`) · Depends on [#41 guarded write `cancel_order`](./04-guarded-write-tool.md) + [#31 outbox] + [#37 MCP `POST /mcp`](../02-agentic-tool-calling.md) · Enables `PLACED → CONFIRMED → SHIPPED` with per-transition audit and outbox

---

## 1. Purpose — scale `cancel_order` to a full lifecycle

PR #41 proved the **guarded-write template** with one mutation: `cancel_order` (`CancelOrderTool.java:37`) behind four rails — deploy flag, `confirmed=true`, `@PreAuthorize`, domain `Order.cancel()` (`Order.java:151`). PR #50 scales that template from **one write to a lifecycle** by adding two tools reusing the same rails:

* **`confirm_order`** — `PLACED → CONFIRMED` via `ConfirmOrderTool.java:16` → `OrderService.confirmOrder()` (`OrderService.java:216`) → `Order.confirm()` (`Order.java:166`) + `OrderStatusChangedMessage` outbox row (`OrderService.java:224`).
* **`ship_order`** — `CONFIRMED → SHIPPED` via `ShipOrderTool.java:16` → `OrderService.shipOrder()` (`OrderService.java:233`) → `Order.ship()` (`Order.java:177`) + outbox row (`OrderService.java:241`).

Lifecycle after the PR:

```
PLACED ──confirm──► CONFIRMED ──ship──► SHIPPED ──(future)──► DELIVERED
  │                    │
  └─────cancel─────────┘
  └─────cancel────────────────────────────► (SHIPPED/DELIVERED are refused)
```

Result: `tools/list` advertises 3 writes when `app.mcp.write-tool.enabled=true` (`ConfirmOrderTool.java:15`, `ShipOrderTool.java:15`, `CancelOrderTool.java:36`); each requires `confirmed=true` (`ConfirmOrderTool.java:49`, `ShipOrderTool.java:49`); each is `@PreAuthorize(SCOPE_order_write)` (`OrderService.java:213`, `:230`); each mutates only through the entity state machine (`Order.java:166`, `:177`); each emits an outbox event in the same `@Transactional` (`OrderService.java:224`, `:241`).

---

## 2. Problem — one write does not make a lifecycle

| Before (PR #41 only) | Why insufficient |
|---|---|
| One write: `cancel_order` (`CancelOrderTool.java:37`) + `Order.cancel()` (`Order.java:151`) | No forward progression — order placed (`OrderService.placeOrder():83`) could only be cancelled. No `CONFIRMED`/`SHIPPED` path over MCP. |
| `Order.cancel()` allowed `PLACED/CONFIRMED → CANCELLED`, rejected `SHIPPED/DELIVERED` (`Order.java:155`) | Correct for cancellation, but no method enforced `PLACED → CONFIRMED` or `CONFIRMED → SHIPPED` — raw `setStatus()` (`Order.java:141`) could bypass rules. |
| `OrderService.updateOrderStatus()` (`OrderService.java:156`) did generic `setStatus(newStatus)` + outbox | Escape hatch, not a state machine — any status jump without validation. Needed typed transitions owning their preconditions. |
| `cancelOrder()` (`OrderService.java:205`) saved without outbox | `CANCELLED` learned only by polling `orders`; inconsistent with `updateOrderStatus()` which did emit `OrderStatusChangedMessage`. |
| `AbstractMcpWriteTool.java:40` template proven once | Unproven whether four-layer guard (flag → confirmed → `@PreAuthorize` → domain) composes for second/third tool without drift. |

Without PR #50, adding `confirm`/`ship` would mean ad-hoc `setStatus()` in controllers or copy-pasted guards with drift. PR #50 makes the state machine explicit in the entity and reuses the guard verbatim.

---

## 3. Solution — `confirm PLACED→CONFIRMED`, `ship CONFIRMED→SHIPPED` with diagram

```
                         Order.java:166                Order.java:177
                   ┌─────────────────────┐       ┌─────────────────────┐
                   │   Order.confirm()   │       │    Order.ship()     │
                   │ PLACED → CONFIRMED  │       │ CONFIRMED → SHIPPED │
                   │ if != PLACED throw  │       │ if != CONF throw    │
                   │ ISE :168 else CONF. │       │ ISE :178 else SHIP. │
                   └──────────┬──────────┘       └──────────┬──────────┘
                              │                             │
             OrderService.java:216           OrderService.java:233
        ┌─────────────────────────┐     ┌─────────────────────────┐
        │ confirmOrder(@PreAuth)  │     │  shipOrder(@PreAuth)    │
        │ @Transactional :212     │     │  @Transactional :229    │
        │ @PreAuthorize :213      │     │  @PreAuthorize :230     │
        │ @Timed order.confirm:214│     │  @Timed order.ship :231 │
        │ order.confirm() :219    │     │  order.ship() :236      │
        │ save() :220 + outbox:224│     │  save() :237 + outbox:241│
        └──────────┬──────────────┘     └──────────┬──────────────┘
                   │                               │
     ConfirmOrderTool.java:16          ShipOrderTool.java:16
  ┌─────────────────────────┐      ┌─────────────────────────┐
  │ @ConditionalOnProperty  │      │ @ConditionalOnProperty  │
  │ write-tool.enabled :15  │      │ write-tool.enabled :15  │
  │ confirmed==TRUE :49     │      │ confirmed==TRUE :49     │
  │ service.confirm :52     │      │ service.ship :52        │
  │ AUDIT mcp.confirm :53   │      │ AUDIT mcp.ship :53      │
  └─────────────────────────┘      └─────────────────────────┘
  Existing: Order.cancel() :151 / cancelOrder() :205 / cancel_order :37
            PLACED/CONFIRMED → CANCELLED, SHIPPED/DELIVERED refused
```

**Four-layer guard reused verbatim:**

| Layer | `confirm_order` | `ship_order` | File:line |
|---|---|---|---|
| 1. Deploy gate | `@ConditionalOnProperty(app.mcp.write-tool.enabled=true)` | same | `ConfirmOrderTool.java:15`, `ShipOrderTool.java:15` |
| 2. Per-call confirmation | `Boolean.TRUE.equals(confirmed)` else `IllegalArgumentException` | same | `ConfirmOrderTool.java:49`, `ShipOrderTool.java:49` |
| 3. Service authz | `@PreAuthorize(SCOPE_order_write / ROLE_API_KEY)` | same | `OrderService.java:213`, `OrderService.java:230` |
| 4. Domain state machine | `Order.confirm()` only `PLACED` | `Order.ship()` only `CONFIRMED` | `Order.java:166`, `Order.java:177` |

Fail-closed: flag off → bean absent; no `confirmed` → `isError` before service; no scope → `403`; wrong status → `IllegalStateException` → `isError`.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `domain/Order.java:166` | `confirm()` (`:166`): `PLACED → CONFIRMED` else `ISE` (`:168`); `ship()` (`:177`): `CONFIRMED → SHIPPED` else `ISE` (`:178`); `cancel()` (`:151`) unchanged |
| `domain/service/OrderService.java:216` | `confirmOrder` — `@Transactional` (`:212`), `@PreAuthorize` (`:213`), `@Timed("order.confirm")` (`:214`), `order.confirm()` (`:219`), `save()` (`:220`), `outbox PLACED→CONFIRMED` (`:221-225`) |
| `domain/service/OrderService.java:233` | `shipOrder` — `@Transactional` (`:229`), `@PreAuthorize` (`:230`), `@Timed("order.ship")` (`:231`), `order.ship()` (`:236`), `save()` (`:237`), `outbox CONFIRMED→SHIPPED` (`:238-242`) |
| `mcp/ConfirmOrderTool.java:16` | `confirm_order` — `@ConditionalOnProperty` (`:15`), `AUDIT mcp.confirm-order` (`:18`), `name()` (`:27`), `description()` MUTATES DATA (`:32`), `inputSchema` requires `orderId+confirmed` (`:39`), `run()` guard (`:49`) → `confirmOrder()` (`:52`) → PII-free reply (`:54`) |
| `mcp/ShipOrderTool.java:16` | `ship_order` — same shape, `name() ship_order` (`:27`), `Only CONFIRMED` (`:33`), guard (`:49`) → `shipOrder()` (`:52`), `AUDIT mcp.ship-order` (`:53`) |
| `mcp/AbstractMcpWriteTool.java:40` | Write base — NOT subclass of `AbstractMcpReadOnlyTool.java:31`; `specification(auditService, aiMetrics)` (`:75`) ensures `AgentToolSet.java:33` never wraps writes |
| `mcp/McpServerConfiguration.java:99` | Wiring — `List<AbstractMcpReadOnlyTool>` (`:101`) + `List<AbstractMcpWriteTool>` (`:102`) → `specification()` (`:111`, `:115`) |

### Snippet 1 — Entity owns the rules (`Order.java:166-183`)

```java
// src/main/java/com/company/orderapi/domain/Order.java:166
public void confirm() { // Order.java:166
    if (status != OrderStatus.PLACED) { // Order.java:167 — single valid predecessor
        throw new IllegalStateException(
                "Order " + getId() + " can only be confirmed from PLACED, currently " + status + "."); // :168
    }
    this.status = OrderStatus.CONFIRMED; // :171
}
public void ship() { // Order.java:177
    if (status != OrderStatus.CONFIRMED) { // :178
        throw new IllegalStateException(
                "Order " + getId() + " can only be shipped from CONFIRMED, currently " + status + ".");
    }
    this.status = OrderStatus.SHIPPED; // :182
}
```

`setStatus()` (`Order.java:141`) is a raw setter — `confirm()`/`ship()`/`cancel()` (`:151`, `:166`, `:177`) are the **only validated paths**. `OrderStatus.java:12` (`PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED`) mirrors DB `ck_orders_status` (PR #2).

### Snippet 2 — Service adds auth + TX + outbox (`OrderService.java:212-244`)

```java
// src/main/java/com/company/orderapi/domain/service/OrderService.java:212
@Transactional // :212 — order save + outbox commit atomically
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')") // :213
@Timed(value = "order.confirm", percentiles = 0.95) // :214
public Order confirmOrder(Long orderId) { // :216
    Order order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown order id " + orderId + "."));
    order.confirm(); // :219 — throws ISE if not PLACED
    Order saved = orders.save(order); // :220
    OrderStatusChangedMessage msg = new OrderStatusChangedMessage( // :221
            UUID.randomUUID().toString(), saved.getId(), saved.getOrderNumber(),
            OrderStatus.PLACED.name(), OrderStatus.CONFIRMED.name(), LocalDateTime.now());
    outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(saved.getId()), // :224
            "OrderStatusChangedMessage", writeJson(msg)));
    return saved;
}
// shipOrder :229-243 is identical with OrderStatus.CONFIRMED→SHIPPED :238-241 and order.ship() :236
```

Difference from `cancelOrder()` (`:205`): `confirm`/`ship` emit `OrderStatusChangedMessage` in same TX. If `Order.confirm()` throws, outbox rolls back — no ghost events (same guarantee as `placeOrder()` at `:125`).

### Snippet 3 — MCP tool reuses guard (`ConfirmOrderTool.java:15-54`)

```java
// src/main/java/com/company/orderapi/mcp/ConfirmOrderTool.java:15
@Component @ConditionalOnProperty(prefix = "app.mcp", name = "write-tool.enabled", havingValue = "true") // :15
public class ConfirmOrderTool extends AbstractMcpWriteTool { // :16
    private static final Logger AUDIT = LoggerFactory.getLogger("mcp.confirm-order"); // :18
    @Override public String name() { return "confirm_order"; } // :27
    @Override public String description() { // :32
        return "MUTATES DATA: confirm the order ... Only PLACED orders can be confirmed; ... Requires confirmed=true."; }
    @Override public JsonSchema inputSchema() { // :39
        return objectSchema(Map.of("orderId", Map.of("type","integer"), "confirmed", Map.of("type","boolean")),
                List.of("orderId", "confirmed")); // :43 both required
    }
    @Override protected String run(Map<String, Object> arguments) { // :47
        long orderId = requiredPositiveLong(arguments, "orderId"); // :48
        if (!Boolean.TRUE.equals(arguments.get("confirmed"))) throw new IllegalArgumentException( // :49
                "Refusing to confirm order " + orderId + ": confirmed must be exactly true.");
        Order order = orderService.confirmOrder(orderId); // :52
        AUDIT.info("confirm_order confirmed=true orderId={} orderNumber={} -> CONFIRMED", order.getId(), order.getOrderNumber()); // :53
        return "Order " + order.getOrderNumber() + " confirmed (status CONFIRMED)."; // :54 PII-free
    }
}
```

`ShipOrderTool.java:16` mirrors with `ship_order` (`:27`), `Only CONFIRMED` (`:33`), `shipOrder()` (`:52`), `mcp.ship-order` (`:53`). `requiredPositiveLong` (`ConfirmOrderTool.java:57`, `ShipOrderTool.java:57`) rejects `null`/`<=0`/non-numeric — same as `CancelOrderTool.java:89`.

---

## 5. How to use — enable flag, curl with confirmed=true/false, psql order status, outbox

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.mcp.write-tool.enabled=true
# without flag → tools/list has 4 reads only

TOKEN_WRITE=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp%20order_write' | jq -r .access_token)
```

### 1. Tool presence by flag

```bash
# WITH flag — 7 tools (4 reads + 3 writes)
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN_WRITE" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[] | {name, required: .inputSchema.required}'
# {"name":"confirm_order","required":["orderId","confirmed"]}
# {"name":"ship_order","required":["orderId","confirmed"]}
# {"name":"cancel_order","required":["orderId","confirmed"]}

# WITHOUT flag — 4 reads only
curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN_WRITE" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
# "api_health" "docs_search" "order_status" "product_search"
```

### 2. Happy path — PLACED → CONFIRMED → SHIPPED

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN_WRITE" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"confirm_order","arguments":{"orderId":1,"confirmed":true}}}' | jq .
# {"result":{"content":[{"type":"text","text":"Order ORD-XXXX confirmed (status CONFIRMED)."}],"isError":false}}

curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN_WRITE" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ship_order","arguments":{"orderId":1,"confirmed":true}}}' | jq .
# {"result":{"content":[{"type":"text","text":"Order ORD-XXXX shipped (status SHIPPED)."}],"isError":false}}
```

### 3. Refused without `confirmed=true` + invalid predecessor

```bash
curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN_WRITE" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"confirm_order","arguments":{"orderId":1}}}' | jq .result.isError
# true — "confirmed must be exactly true." (ConfirmOrderTool.java:49)

curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN_WRITE" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ship_order","arguments":{"orderId":2,"confirmed":true}}}' | jq .result.isError
# true — "can only be shipped from CONFIRMED, currently PLACED" (Order.java:178) — service never commits
```

### 4. psql — status + outbox

```bash
docker compose exec postgres psql -U order -d orderdb -c "SELECT id, order_number, status FROM orders WHERE id=1;"
# 1 | ORD-XXXX | SHIPPED  (after confirm+ship)

docker compose exec postgres psql -U order -d orderdb -c "SELECT status FROM orders WHERE id=2;"
# PLACED — ship refused, row untouched (TX rolled back)

docker compose exec postgres psql -U order -d orderdb -c \
  "SELECT event_type, payload::json->>'oldStatus' as old, payload::json->>'newStatus' as new FROM outbox WHERE aggregate_id='1' ORDER BY id;"
# OrderStatusChangedMessage | PLACED    | CONFIRMED
# OrderStatusChangedMessage | CONFIRMED | SHIPPED
# failed transition writes NO row — count unchanged before/after refused ship

./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.mcp.write-tool.enabled=true 2>&1 | grep -E "mcp\.(confirm|ship)-order"
# INFO mcp.confirm-order — confirm_order confirmed=true orderId=1 -> CONFIRMED (ConfirmOrderTool.java:53)
# INFO mcp.ship-order — ship_order confirmed=true orderId=1 -> SHIPPED (ShipOrderTool.java:53)
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

**State machine in entity (`Order.java:166`, `:177`), not service** — `confirm()`/`ship()` throw `ISE` with current status (`:168`, `:178`), so every path (MCP, REST, job) hits the same rule. Trap: `updateOrderStatus()` (`OrderService.java:156`) does `setStatus(newStatus)` generically and bypasses checks if called directly. PR #50 keeps `updateOrderStatus()` for admin repair but introduces typed methods — entity is single source of truth, service adds auth+outbox. Comment at `Order.java:146` ("ONLY allowed path to CANCELLED") extends to `:162`/`:174`.

**One outbox event per committed transition (`OrderService.java:224`, `:241`)** — `confirmOrder`/`shipOrder` call `outbox.saveAndFlush` in same `@Transactional` as `save()`. If `Order.confirm()` throws, no row is written — no phantom event. Alternative direct Kafka publish is not transactional (DB commit + Kafka fail = drift). Reusing `OutboxEntry.pending()` + polling publisher (PR #31) from `placeOrder()` (`:125`) keeps one durability pattern.

**Four-layer guard reused verbatim** — `ConfirmOrderTool.java:15`+`:49` and `ShipOrderTool.java:15`+`:49` are line-for-line same as `CancelOrderTool.java:36`+`:77`; `OrderService.java:213`+`:230` same as `:202`. Reviewer can `diff CancelOrderTool ConfirmOrderTool` and see only verb changes. Trap: per-tool flags (`app.mcp.confirm-enabled`) or per-tool scopes explode deploy config and fragment authz. One flag, one scope (`SCOPE_order_write`), one confirmation shape (`confirmed:Boolean.TRUE`) keeps mental model flat — `deliver_order` later is one entity method + service method + tool file.

**Strict `requiredPositiveLong` + `Boolean.TRUE.equals`** — `ConfirmOrderTool.java:57` rejects `null`/`<=0`/non-numeric; `:49` rejects anything not exactly `Boolean.TRUE` (`"true"` string, `1`, absent). Same as `CancelOrderTool.java:89`+`:77`. Trap: `Boolean.parseBoolean("true")` would accept string `"true"` and let a hallucinating model `{"orderId":"1","confirmed":"true"}` silently mutate. Strict parsing → `isError=true` with human message, no committed row.

---

## 7. How to verify — MCP + DB + outbox + tests

```bash
# 1. Flag gate
curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN_WRITE" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq '.result.tools[].name'
# default: 4 reads; with --app.mcp.write-tool.enabled=true: + confirm_order, ship_order, cancel_order

# 2. DB lifecycle — only PLACED→CONFIRMED→SHIPPED commits
docker compose exec postgres psql -U order -d orderdb -c "SELECT status FROM orders WHERE id=10;" # PLACED
curl -d '{"name":"confirm_order","arguments":{"orderId":10,"confirmed":true}}' ... | jq .result.isError # false
docker compose exec postgres psql -U order -d orderdb -c "SELECT status FROM orders WHERE id=10;" # CONFIRMED
curl -d '{"name":"confirm_order","arguments":{"orderId":10,"confirmed":true}}' ... | jq .result.isError # true — :168
curl -d '{"name":"ship_order","arguments":{"orderId":10,"confirmed":true}}' ... | jq .result.isError    # false
docker compose exec postgres psql -U order -d orderdb -c "SELECT status FROM orders WHERE id=10;" # SHIPPED
curl -d '{"name":"ship_order","arguments":{"orderId":10,"confirmed":true}}' ... | jq .result.isError    # true — :178

# 3. Outbox — one row per success, zero on failure
docker compose exec postgres psql -U order -d orderdb -c \
  "SELECT payload::json->>'oldStatus' as old, payload::json->>'newStatus' as new FROM outbox WHERE aggregate_id='10' ORDER BY id;"
# PLACED→CONFIRMED, CONFIRMED→SHIPPED (no row for failed second confirm/ship)

# 4. Tests — entity + service + MCP + agent isolation
./mvnw test -Dtest=OrderTest,OrderServiceTest,McpServerWriteToolIntegrationTest -Dspring.profiles.active=test
# OrderTest: confirm from PLACED ok, from CONFIRMED/SHIPPED/CANCELLED → ISE (:168); ship from CONFIRMED ok, from PLACED → ISE (:178)
# McpServerWriteToolIntegrationTest: POST /mcp tools/call confirm/ship with confirmed true/false + lifecycle
./mvnw test -Dtest=AgentToolSetTest -Dspring.profiles.active=test
# AgentToolSetTest.java:86 noneMatch(cancel|confirm|ship|create|delete|update) still passes

# 5. Authz — read-scope token cannot mutate
TOKEN_READ=$(curl -s -X POST http://localhost:8080/oauth2/token -u mcp-server:mcp-server-secret-learning \
  -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN_READ" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"confirm_order","arguments":{"orderId":10,"confirmed":true}}}' | jq .
# 403 / isError from @PreAuthorize — OrderService.java:213
```

---

## 8. How this helps you on the job — build / operate / interview

* **Build — add `deliver_order` in 15 min by copying template.** Create `DeliverOrderTool extends AbstractMcpWriteTool` (`AbstractMcpWriteTool.java:40`, like `ConfirmOrderTool.java:16`), `@ConditionalOnProperty(app.mcp.write-tool.enabled)` (`:15`), `inputSchema` requiring `orderId+confirmed` (`:39`), guard `Boolean.TRUE.equals` (`:49`), delegate to `OrderService.deliverOrder()` with `@PreAuthorize` + `@Transactional` + `@Timed("order.deliver")` (`OrderService.java:212` pattern) and `Order.deliver()` checking `SHIPPED` (`Order.java:177` pattern) + `outbox SHIPPED→DELIVERED` (`OrderService.java:224` pattern), audit `Logger("mcp.deliver-order")` (`ConfirmOrderTool.java:18` pattern). `McpServerConfiguration.java:102` auto-registers it. Lock with `AgentToolSetTest.java:86`.

* **Operate — reason about every failure without reading code.** Flag off → `tools/list` no writes, zero risk; `confirmed` missing/false → `isError` before DB, `AbstractMcpWriteTool.java:101` `success=false`; no `SCOPE_order_write` → `403`; wrong predecessor → `ISE` from `Order.java:166`/`Order.java:177` with message `"can only be confirmed from PLACED, currently SHIPPED"`; outbox `order.status.changed` replays lifecycle into Kafka without polling `orders`; metrics `order.confirm`/`order.ship` (`OrderService.java:214`, `:231`) at `/actuator/prometheus` alongside `order.cancel` (`:203`) for p95 per transition.

* **Interview — whiteboard full lifecycle in 90s with receipts.** "PR #50 adds `confirm_order` (`ConfirmOrderTool.java:16`) and `ship_order` (`ShipOrderTool.java:16`) reusing PR #41's four rails: (1) `@ConditionalOnProperty(app.mcp.write-tool.enabled)` (`:15`), (2) `confirmed==Boolean.TRUE` (`:49`) with `required: [orderId, confirmed]` (`:43`), (3) `@PreAuthorize(SCOPE_order_write)` + `@Transactional` (`OrderService.java:213`, `:230`), (4) entity `Order.confirm() PLACED→CONFIRMED` (`Order.java:166`) and `Order.ship() CONFIRMED→SHIPPED` (`Order.java:177`). Each service emits one `OrderStatusChangedMessage` in same TX (`:224`, `:241`) so row+event commit atomically. Plus `AbstractMcpWriteTool.java:40` sibling hierarchy keeps `AgentToolSet.java:33` read-only — proven by `AgentToolSetTest.java:86`. Audit `mcp.confirm-order`/`mcp.ship-order` (`:18`), PII-free replies."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "Why separate `confirm_order`/`ship_order` instead of generic `update_order_status`?"**

> "Generic `update_order_status(orderId, newStatus)` reintroduces `OrderService.updateOrderStatus()` (`OrderService.java:156`) escape hatch — `setStatus(newStatus)` lets caller jump `PLACED → SHIPPED` skipping `CONFIRMED`, which business forbids. Typed `Order.confirm()` (`Order.java:166` only from `PLACED`) and `Order.ship()` (`Order.java:177` only from `CONFIRMED`) make invalid jumps impossible. Per-tool surface also gives per-transition deploy gating (`@ConditionalOnProperty` `:15`), per-transition audit logger (`mcp.confirm-order` `:18`), per-transition metric (`order.confirm` `:214`), and model-facing description (`ConfirmOrderTool.java:32` says `Only PLACED`). Generic tool collapses that into one metric and one ambiguous description — you lose observability and state machine drifts into service."

**Q2: "`cancel` allows two predecessors but `confirm`/`ship` each allow one — why asymmetry, and how does outbox help?"**

> "`cancel` (`Order.java:151`) is a terminal sink — both `PLACED` and `CONFIRMED` are pre-fulfillment, so either can cancel; `SHIPPED`/`DELIVERED` cannot. `confirm`/`ship` (`Order.java:166`, `:177`) are forward edges in a linear pipeline — exactly one predecessor by definition (`PLACED→CONFIRMED→SHIPPED`). Outbox helps because each writes one `OrderStatusChangedMessage` with `oldStatus`/`newStatus` in same TX (`OrderService.java:224`, `:241`) — consumer replaying `order.status.changed` sees `PLACED→CONFIRMED` then `CONFIRMED→SHIPPED` and rebuilds state without polling. `cancelOrder` still lacks outbox (`OrderService.java:205`) — PR #50 shows where to retrofit."

**Q3: "Attacker can send `confirmed:true` — doesn't this collapse to just `@PreAuthorize`?"**

> "Four layers, four attackers. (1) Flag off (`ConfirmOrderTool.java:15`): bean absent, `tools/list` never advertises `confirm_order`. (2) `confirmed` (`:49`): hallucinating model without `confirmed` is refused before `OrderService` — stops accidents, teaches model to ask user. (3) `@PreAuthorize` (`OrderService.java:213`): attacker with `confirmed:true` still needs `SCOPE_order_write` — read-scope token gets `403`. (4) State machine (`Order.java:166`): even with flag+confirmed+scope, `ship_order` on `PLACED` throws `ISE` (`:178`) — entity owns rule, no invented transition. Audit (`:53` `mcp.confirm-order`) records every success. No single layer is the boundary — defense in depth."

---

## 10. Honest limits & next steps — what it doesn't do

**What PR #50 alone does NOT do (by design):**

* **No `DELIVERED` transition.** `OrderStatus.java:12` has `DELIVERED` but no `Order.deliver()` and no `DeliverOrderTool`. Next typed transition: `Order.deliver()` checking `SHIPPED`, `OrderService.deliverOrder()` with `@PreAuthorize` + outbox `SHIPPED→DELIVERED`, `DeliverOrderTool extends AbstractMcpWriteTool`.
* **`cancelOrder()` still has no outbox.** `OrderService.cancelOrder()` (`OrderService.java:205`) does `save()` without `outbox.saveAndFlush` — `CANCELLED` via polling only. Retrofit is one `outbox.saveAndFlush(PLACED/CONFIRMED→CANCELLED)` call (`OrderService.java:224` pattern).
* **No stock/payment compensation.** Like `cancel` not restoring `Product.stockQuantity` (`OrderService.java:98`), `confirm`/`ship` are status-only — no capture or allocation. Production would add payment capture on `confirm`, inventory allocation on `ship`.
* **No idempotency key.** Replay `confirm_order({"orderId":1,"confirmed":true})` twice → second is `ISE` (`Order.java:168`), not silent success. True idempotency needs `mcp_idempotency` table or `order_version` — PR #48 audit gives actor/session for dedup, not a key.
* **Generic `updateOrderStatus()` (`OrderService.java:156`) remains** — `setStatus(newStatus)` without state machine, useful for admin repair but can bypass `confirm()`/`ship()` if called directly. Harden to delegate to typed methods or remove from MCP surface.
* **Terminal states not fully guarded.** Future `Order.deliver()` on `CANCELLED` would need explicit check mirroring `ship()`'s `status != CONFIRMED` (`Order.java:178`).

**Where to go next:**

* Retrofit `cancel` outbox — `OrderStatusChangedMessage(old→CANCELLED)` in `OrderService.cancelOrder()` (`:205`) same TX via `writeJson()` (`:176`) + `OutboxEntry.pending()` (`:224` pattern).
* Add `DELIVERED` — `Order.deliver()` (`:177` pattern) + `OrderService.deliverOrder()` (`:233` pattern) + `DeliverOrderTool` (`ConfirmOrderTool.java:16` pattern); then deprecate `updateOrderStatus()`.
* Idempotency — store `X-Idempotency-Key` from `McpTransportContext` into `McpAuditService` (PR #48) and short-circuit replay.

> Next: [`12-ai-observability.md`](./12-ai-observability.md) (PR #49) · or back to [`README.md`](./README.md) · high-level companion [`docs/additions/13-guarded-write-expanded.md`](../../13-guarded-write-expanded.md)
