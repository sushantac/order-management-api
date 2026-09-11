# 13. Guarded Write Expanded: from one tool to a full state machine

> **PR #50** — `cancel_order` proved the guarded pattern (off by default, `confirmed=true`, `@PreAuthorize`, domain rules, audit). This PR scales that pattern to the whole order lifecycle: `confirm_order` (PLACED → CONFIRMED) and `ship_order` (CONFIRMED → SHIPPED), each with its own state-machine guard, outbox event, and metric.

---

## 1. Mental model

Orders move PLACED → CONFIRMED → SHIPPED → DELIVERED, with CANCELLED as a terminal branch from PLACED/CONFIRMED only. Each transition is a **guarded write tool**: the same four layers as `cancel_order`:

1. **Capability gate** — bean only exists when `app.mcp.write-tool.enabled=true`
2. **Ergonomic guard** — `confirmed=true` required, else tool error
3. **AuthZ** — `@PreAuthorize` with `order_write` scope
4. **Domain guard** — `Order.confirm()` / `ship()` throw `IllegalStateException` if status is wrong

---

## 2. Code

| File | Change |
|------|--------|
| `domain/Order.java` | `confirm()` (PLACED→CONFIRMED) + `ship()` (CONFIRMED→SHIPPED) |
| `domain/service/OrderService.java` | `confirmOrder()` / `shipOrder()` with `@PreAuthorize`, `@Timed`, `OrderStatusChangedMessage` outbox |
| `mcp/ConfirmOrderTool.java` | New tool, conditional on write-tool.enabled |
| `mcp/ShipOrderTool.java` | New tool |
| `application.yml` | Restored correct DB wiring (248-line baseline) + added new platform topics `cart-checkout-initiated`, `order-placed`, `order-status-changed` |

Each tool logs `AUDIT.info` and returns only orderNumber + status (PII-free).

---

## 3. Try

```bash
./mvnw test -Dtest=OrderServiceTest # unit state-machine tests
```

---

*Next: PR #51 — semantic reranking & query rewriting for RAG.*
