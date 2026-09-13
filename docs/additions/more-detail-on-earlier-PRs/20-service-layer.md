# 20. Service Layer (PR #20)

> PR #20 — Service Layer: `@Transactional` boundaries, `placeOrder` atomicity, propagation, `OrderService` + `ProductCatalogueService`/`PaymentGateway` orchestration. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Spring AOP (proxy), `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 20 | Service Layer |`.

---

## 1. Purpose — what shipped

PR #20 extracts **business orchestration and transaction boundaries** from controllers into `OrderService` (`domain/service/OrderService.java:49` `@Service`) as the single place that defines what is atomic. Core method `placeOrder(customerId, lines)` (`OrderService.java:83`) is `@Transactional` (`OrderService.java:76`) + `@Retryable` (`OrderService.java:77` retry on `OptimisticLockingFailureException` from `BaseEntity.java:53` version) and does four steps that must all succeed or all roll back: (1) versioned stock decrement (`products.findById` `OrderService.java:91` → `setStockQuantity` `OrderService.java:98` `WHERE version=?`), (2) `Order` + `OrderItem` creation with snapshot prices (`Order.java:100`/`108` `addItem`, `OrderRepository` `save`), (3) `paymentGateway.charge(total)` (`OrderService.java:112`), (4) `Payment PROCESSED` record (`Payment.java` `PROCESSED`) + `Order` flush (`OrderService.java:118-119` `saveAndFlush`) + `OutboxEntry.pending` durable event in same tx (`OrderService.java:125-144`). Adjacent `@Transactional` methods `updateOrderStatus:156`, `cancelOrder:205`, `confirmOrder:216`, `shipOrder:233` each define a smaller atomic status transition. Controllers become thin (validation + mapping + delegation).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Controllers called repositories directly or a fat controller did `product.setStockQuantity`, `orders.save`, `paymentService.pay` without a unified transaction — partial failure left `stock decremented but payment not charged` or `payment charged but order not persisted` (money lost, inventory corrupt). No `@Retryable` handled `OptimisticLockingFailureException` from concurrent stock updates (`BaseEntity.java:53` `version`), so any contention caused a user-visible error during a sale.

**After:** Controller `OrderController.java:22` delegates to `OrderService.placeOrder()` which opens one transaction (`@Transactional:76`) spanning all four steps. Failure at any step (insufficient stock `Insufficient stock:94`, unknown product `Unknown product:92`, payment `PaymentFailedException`) propagates `RuntimeException` → Spring rolls back the whole tx → no partial state. `OptimisticLockingFailureException` from concurrent `UPDATE ... WHERE version=?` `0 rows` is caught by `@Retryable:77` (`maxAttempts=5, backoff 20ms`) which retries the *entire* transaction reading fresh `version/stock`.

### Theory — service layer, transactions, and propagation from first principles (100+ lines)

#### 2.1 What a service layer owns — the transaction boundary

```
Controller (thin)                          Service (@Transactional)                     Repository
  validate @Valid OrderRequest.java:16  →    placeOrder(customerId, lines)   →           CustomerRepository, ProductRepository, OrderRepository
  map DTO → domain                         stock --, addItem, charge, save     →         Hibernate Session → JDBC → PostgreSQL
  handle @Valid / exceptions                 single @Transactional boundary    →         OutboxRepository, PaymentGateway
```

Spring's `@Transactional` creates a proxy around `OrderService`. On entry, the proxy `TransactionInterceptor` (`TransactionAspectSupport`) starts/contributes to a transaction, binds a Hibernate `Session` to the thread (`TransactionSynchronizationManager`), and sets `Connection.setAutoCommit(false)`. On success → `commit`; on `RuntimeException` → `rollback`. Every `Repository` call inside the proxy shares the same `Session`/connection.

#### 2.2 `@Transactional` anatomy — where the proxy sits relative to `@Retryable`

```java
// OrderService.java:76-83
@Transactional
@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 5, backoff = @Backoff(delay = 20))
@Timed(value = "order.place", percentiles = 0.95) // 81-82 micrometer
public Order placeOrder(Long customerId, List<OrderLine> lines) { // 83
```

Proxy order (Spring AOP): `Retryable proxy (outer)` → `Transactional proxy (inner)` → target method. On `OptimisticLockingFailureException`:

1. Inner proxy: transaction rolled back, exception bubbled.
2. Outer retry proxy: catches exception, sleeps `20ms * attempt`, re-enters inner proxy which starts a *new* transaction with a fresh `Session`/`PersistenceContext` reading new `version/stock`. If method body had already published a non-transactional side effect (e.g., `paymentGateway.charge` outside retry scope), re-entry would double-charge — here `charge` is inside the retried scope and its work is rolled back on failure so re-entry is safe.

If annotations were reversed (retry inside transaction), the `Session` still holds stale snapshot `version=5` and retry would re-throw without re-reading — wrong. Current ordering is correct.

#### 2.3 `placeOrder` atomics — four steps, one commit

```java
// OrderService.java:83-146
Customer customer = customers.findById(customerId).orElseThrow(...); // 84
Order order = new Order(customer, PLACED, ZERO); // 87
for (OrderLine line: lines) { // 90-105
  Product product = products.findById(line.productId()).orElseThrow(...); // 91
  if (product.getStockQuantity() < line.quantity()) throw new IllegalStateException("Insufficient stock ..."); //93
  product.setStockQuantity(product.getStockQuantity() - line.quantity()); // 98 versioned UPDATE on flush
  catalogue.evict(line.productId()); // 101 invalidates Spring cache products → PR #28
  BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(line.quantity())); // 102 snapshot price
  order.addItem(new OrderItem(product, line.quantity(), product.getPrice())); //103 price snapshot in OrderItem
  total = total.add(lineTotal); //104
}
order.setTotalAmount(total); orders.save(order); //107-108 cascade ALL → OrderItems persisted
paymentGateway.charge(total); //112 — may throw PaymentFailedException (resilience PR #29 inside)
Payment payment = new Payment(order, total, CREDIT_CARD); payment.setStatus(PROCESSED); //113-114
order.setPayment(payment); orders.saveAndFlush(order); //117-118 flush graph inside tx
outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(order.getId()), "OrderPlacedMessage", writeJson(...))); //125-126 same tx
outbox.save(OutboxEntry.pending(... "OrderPlacedEventMessage" ...)); //143 second outbox topic
return order; //147
```

Atomicity: `spring.transaction.rollbackFor` defaults to `RuntimeException` + `Error` (not checked exceptions). `Insufficient stock` (`IllegalStateException`), `Unknown product` (`IllegalArgumentException`), `PaymentFailedException` are unchecked → rollback. `DataIntegrityViolationException` (constraint) also unchecked → rollback.

Outbox writes (`OrderService.java:125`) are flushed in the same transaction so `Order` and `OutboxEntry` are atomically committed — Kafka publisher later sees only committed orders (outbox pattern PR #31). `payment.setTransactionId("sim-tx-" + System.nanoTime())` (`OrderService.java:115`) is inside the transaction; on rollback it is not persisted.

#### 2.4 Propagation — the default and when it matters

`@Transactional` `propagation` defaults to `REQUIRED`:

| Propagation | Behavior | Call inside `placeOrder` |
|---|---|---|
| `REQUIRED` (default) | Join existing tx if present; else start new | Every repository `findById`/`save` joins `placeOrder` tx → no inner commits |
| `REQUIRES_NEW` | Suspend caller's tx, start new | `ProductCatalogueService.get` (`ProductCatalogueService.java:51` `readOnly=true`) would start its own; not used in `placeOrder`—it must share the tx for stock consistency |
| `MANDATORY` | Must have an existing tx, else throw | Not used here; would enforce caller defines boundary |
| `NESTED` | Savepoint inside caller's tx | Not used (requires JDBC savepoint) |
| `SUPPORTS` / `NOT_SUPPORTED` / `NEVER` | Rare for orchestrating `placeOrder` | `readOnly` metrics or non-tx reads |

`OrderService.java:156,205,216,233` `updateOrderStatus`, `cancelOrder`, `confirmOrder`, `shipOrder` each have own `@Transactional`; caller without tx starts one, caller already in tx joins. `ProductCatalogueService.get()` (`ProductCatalogueService.java:50-54` `@Cacheable`, `@Transactional(readOnly=true)`) joins caller's tx when invoked from `placeOrder` (not relevant there — `placeOrder` uses `products.findById`, not `catalogue.get`).

`readOnly=true` hint (`ProductCatalogueService.java:51` cache read, `EventStoreService.java:42` `readHistory`) tells Hibernate to skip dirty checking and disable auto-flush — faster reads.

#### 2.5 Isolation — `READ_COMMITTED` + optimistic locking vs stricter

Postgres default `READ_COMMITTED` + `BaseEntity.version:53` optimistic locking + `@Retryable:77` is cheaper than `REPEATABLE READ`/`SERIALIZABLE` for this workload: retries triggered only on actual version conflict (concurrent stock decrement on same product), whereas `SERIALIZABLE` would abort on any read-write conflict with retries needed regardless. `OrderService.placeOrder()` reads `Customer`/`Product`, writes `Product stock`/`Order` — the critical invariant is stock never oversold, guarded by `WHERE version=?` not by isolation level promotion.

#### 2.6 Idempotency / uniqueness — not automatic

`placeOrder` called twice with same `customerId, lines` creates two orders (no natural idempotency). Caller must dedup via `Idempotency-Key` header (`domain/idempotency/IdempotencyService.java`, `IdempotencyRecord.java`) at controller or gateway — service does not magically suppress duplicate submission. The outbox is also not idempotent to Kafka without consumer deduplication.

#### 2.7 Testing strategy

- `OrderServiceTest` mocks `CustomerRepository`/`ProductRepository`/`PaymentGateway` and asserts in-transaction rollback on `charge` failure (verify `orders.save` not committed), with `Stock=1, lines=[productId=1, qty=1]` success path.
- `LockingPerformanceComparisonTest` (PR #8) proves `@Retryable` with 100 concurrent `placeOrder` on `stock=1` → exactly one winner, no oversell.
- Integration `DatabaseSchemaIntegrationTest:56` proves `placeOrder` commit actually creates `orders` + `order_items` + `payments` + `outbox` rows in Testcontainers Postgres.

#### 2.8 Anti-patterns — transaction in controller, multiple tx per use case

- `@Transactional` on `OrderController` hides boundary from service composition; services become non-reusable.
- Splitting `placeOrder` into `deductStock()` then `createOrder()` each `@Transactional` would commit stock before order exists — inconsistent on failure. Single `placeOrder` boundary is the atomic unit.

#### 2.9 Interview-ready mental model

> "PR #20: `OrderService.java:49` `@Service`, `placeOrder:83` is `76` `@Transactional` (proxy starts/joins tx, `Session` bound, commit/rollback on `RuntimeException`) wrapping four steps `91` find product, `98` `setStockQuantity` (`WHERE version=?` → `OptimisticLockingFailureException`), `103` `addItem` snapshot, `112` `paymentGateway.charge`, `118` `saveAndFlush` graph, `125` `OutboxEntry.pending` same tx. `@Retryable:77` outer proxy catches version conflict, sleeps `20ms` (`@Backoff`) up to `5` attempts, re-runs fresh tx. Propagation `REQUIRED` default joins existing; `readOnly` `ProductCatalogueService:51` skip flush. Failures rollback atomically; outbox committed with order; controller thin `OrderController:22`. Guarded by `LockingPerformanceComparisonTest` (1 winner/100)."

---

## 3. Solution — ASCII

```
Controller thin (OrderController.java:22)
  validate → map DTO → delegate
        │
        ▼   proxy entry (outer @Retryable → inner @Transactional)  OrderService.java:76-82
             TransactionInterceptor:  tx = getTransaction(REQUIRED)
             Session bound to thread (TransactionSynchronizationManager)
        │
    OrderService.java:83  placeOrder(customerId, lines)
        ├─  84 customers.findById ──► SELECT ... FROM customers WHERE id=?  (joins tx)
        ├─  91 products.findById   ──► SELECT ... FROM products WHERE id=? + version
        │   93 if stock < qty → throw IllegalStateException → rollback (no partial state)
        │   98 product.setStockQuantity(stock - qty)   // managed, dirty → version checks on flush  WHERE version=?
        │   101 catalogue.evict(id)  // Spring cache invalidation ProductCatalogueService.java:97 (PR #28), via proxy
        │   103 order.addItem(new OrderItem(product, qty, price))  // price snapshot Order.java:108
        ├─  108 orders.save(order)           // PERSIST cascade ALL → OrderItems
        ├─  112 paymentGateway.charge(total) // may throw PaymentFailedException → rollback (zero partial)
        │   (resilience PR #29 circuit/retry/bulkhead wraps charge)
        ├─  113-118 new Payment(PROCESSED) + order.setPayment + saveAndFlush
        │         SQL: INSERT INTO payments ... ; INSERT INTO orders ... (flush)
        ├─  125 outbox.saveAndFlush(OutboxEntry.pending("Order", id, "OrderPlacedMessage", json))
        │         INSERT INTO outbox ...  (same tx → atomic with order; PR #31 durable publish)
        ├─  143 outbox.save(OutboxEntry pending "OrderPlacedEventMessage")
        └─  return order ──► tx manager COMMIT (or ROLLBACK on RuntimeException)
                           Session unbound, connection returned to Hikari (application.yml:33 pool 10)

  Concurrent contention path:
    TxA and TxB both read Product v5 stock=1
    TxA flush: UPDATE products SET stock=0, version=6 WHERE version=5 → 1 row → commit
    TxB flush: UPDATE products SET stock=0, version=6 WHERE version=5 → 0 rows → OptimisticLockingFailureException
            ↑ caught by outer @Retryable:77  → backoff 20ms → re-enter @Transactional with fresh Session → re-reads v6 → "Insufficient stock"

  Proxy layering (correct):
    @Retryable (outer)  catches OptimisticLockingFailureException, re-enters
      @Transactional (inner)  each entry starts fresh tx + Session (stale L1 cleared)

  Propagation (default REQUIRED):
    placeOrder (REQUIRED) → repo.findById (joins) → repo.save (joins) → outbox.save (joins)  ── one commit
    ProductCatalogueService.get (readOnly) joins placeOrder if called, else standalone tx
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `49` | `@Service` | Business orchestration bean |
| `OrderService.java` | `72-74` | `OrderLine record` DTO | `productId + quantity` input (records arrive fully in PR #21 DTOs) |
| `OrderService.java` | `76-83` | Core boundary | `@Transactional` `76`, `@Retryable` `77` (`retryFor=OptimisticLockingFailureException`, `maxAttempts=5`, `backoff=20`), `@Timed:81` `order.place` |
| `OrderService.java` | `91-98` | Versioned stock | `products.findById` + `setStockQuantity` triggers `WHERE version=?` (`BaseEntity.java:53`) |
| `OrderService.java` | `101,107-108,118` | Cache evict + persistence | `catalogue.evict:101`, `orders.save:108` cascade, `saveAndFlush:118` graph flush |
| `OrderService.java` | `112-117` | Payment + status | `paymentGateway.charge(total):112` → `Payment PROCESSED:114` + `transactionId:115` |
| `OrderService.java` | `125-144` | Outbox atomic write | Two `OutboxEntry.pending(...)` (`OrderPlacedMessage`, `OrderPlacedEventMessage`) `saveAndFlush/save` in same `placeOrder` tx |
| `OrderService.java` | `155-174,205-244` | Other tx boundaries | `updateOrderStatus:156`, `cancelOrder:205`/`confirmOrder:216`/`shipOrder:233` all `@Transactional` + `PreAuthorize:202` + `@Timed` + outbox |
| `OrderService.java` | `176-182` | JSON serialization helper | `writeJson` via `ObjectMapper` for outbox payloads |
| `src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java` | `50-100` | Spring cache layer complement | `@Cacheable:50` (read), `@CacheEvict:60,71,85,97` writes/eviction, `CACHE_NAME="products":37` |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `22` | Thin REST surface | Delegates to `OrderService`; no tx, validation via DTO groups |
| `src/main/java/com/company/orderapi/domain/Order.java` | `151,166,177,198` | Domain guards + order number | `cancel():151`/`confirm():166`/`ship():177` state-machine; `@PrePersist generateOrderNumber:198` |
| `src/main/java/com/company/orderapi/domain/Product.java` | `33-34` | L2 opt-in (PR #16) complements service reads | `@Cacheable`, `READ_WRITE` with `@Version` transactionality |
| `src/main/java/com/company/orderapi/domain/eventstore/EventStoreService.java` | `31` | Additive history writer (PR #19) | Would atomically append in same tx if composed — separate additive win |
| `src/main/resources/application.yml` | `33,69-78,98-114,229-252` | Hikari(10) + L2 + Kafka + Resilience4j | Pool bounds, cache, outbox poll `204`, circuit/retry/bulkhead shared with `charge` |
| `src/main/java/com/company/orderapi/domain/service/PaymentGateway.java` | — | Interface `charge` | `SimulatedPaymentGateway` test double + Resilience4j `paymentGateway` circuit (`application.yml:221`) |

```java
// OrderService.java:76-83 — the boundary
@Transactional
@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 5, backoff = @Backoff(delay = 20))
@Timed(value = "order.place", description = "Time to place an order", percentiles = 0.95)
public Order placeOrder(Long customerId, List<OrderLine> lines) { // 83
    Product product = products.findById(line.productId()).orElseThrow(...); // 91
    if (product.getStockQuantity() < line.quantity()) throw new IllegalStateException("Insufficient stock ..."); //93
    product.setStockQuantity(product.getStockQuantity() - line.quantity()); //98 WHERE version=?
    catalogue.evict(line.productId()); //101
    order.addItem(new OrderItem(product, line.quantity(), product.getPrice())); //103
    orders.save(order); //108
    paymentGateway.charge(total); //112
    order.setPayment(payment); orders.saveAndFlush(order); //117-118
    outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(order.getId()), "OrderPlacedMessage", writeJson(message))); //125-126
}
@Transactional public OrderStatus updateOrderStatus(Long orderId, OrderStatus newStatus){ //156 ...
@Transactional @PreAuthorize(...) @Timed("order.cancel") public Order cancelOrder(Long orderId){ //205 ...
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run service-layer tests (atomicity + retry + outbox)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test

# Verify boundaries: which methods are @Transactional / @Retryable / @Timed
grep -n "@Transactional\|@Retryable\|@Timed\|@PreAuthorize" src/main/java/com/company/orderapi/domain/service/OrderService.java

# Show four-step atomics in placeOrder
grep -n "findById\|setStockQuantity\|addItem\|charge\|OutboxEntry\|saveAndFlush" src/main/java/com/company/orderapi/domain/service/OrderService.java | head -n 20

# Show propagation sits on REQUIRED (default) — explicit search
grep -n "propagation" src/main/java/com/company/orderapi/domain/service/*.java || echo "All use default REQUIRED"

# Verify outbox row is in same transaction (no separate tx)
grep -A2 "outbox.save" src/main/java/com/company/orderapi/domain/service/OrderService.java | head

# Prove rollback on payment failure (OrderServiceTest verifies no order row after PaymentFailedException)
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -E "insert into orders|update products|rollback|PaymentFailed"

# Verify concurrent 100-thread stock=1 → one winner (PR #8 retry proof)
./mvnw test -Dtest=LockingPerformanceComparisonTest -Dspring.profiles.active=test 2>&1 | grep -E "placeOrder|OptimisticLocking|Insufficient stock" | tail -n 20

# Smoke API → service path
curl -s -X POST http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"customerId":1,"lines":[{"productId":1,"quantity":1}]}' | jq .orderNumber
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT status, total_amount FROM orders ORDER BY id DESC LIMIT 1; SELECT status FROM outbox ORDER BY id DESC LIMIT 2;"

# Metrics for latency percentiles (Micrometer @Timed order.place)
curl -s http://localhost:8080/actuator/prometheus | grep -E "order_place|order_cancel|product_get"
curl -s http://localhost:8080/actuator/metrics/order.place | jq .
```

```java
// Client usage — four-step atomic behind one call
List<OrderService.OrderLine> lines = List.of(new OrderService.OrderLine(42L, 2));
Order order = orderService.placeOrder(customerId, lines); // one tx, versioned, outbox committed atomically

// Status transition — separate atomic boundary
OrderStatus old = orderService.updateOrderStatus(order.getId(), OrderStatus.CONFIRMED);
Order cancelled = orderService.cancelOrder(order.getId()); // PreAuthorize order_write + domain cancel() guard:151
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| One `@Transactional` covering four steps | `OrderService.java:76` single boundary | Separate `deductStock()`, `createOrder()` each tx | Atomicity — zero partial state `stock--` without `order+payment+outbox` on any failure; ACID du jour | Lock hold (row `version` + `FK` indices) for full method duration — keep method short, no blocking I/O before commit |
| `@Retryable` outer, `@Transactional` inner | `77` above `76` | Retry inside tx | Fresh `Session` on retry reads new `version/stock`; inside-tx retry would reuse stale `PersistenceContext` | Extra transaction per retry (max 5) |
| `REQUIRED` (default) | Implicit everywhere | `REQUIRES_NEW` per write | Stock `SELECT` and `UPDATE` must be same tx to guard version; outbox must commit with order — `REQUIRES_NEW` would split atomicity | Caller already in tx joins — correct for composition |
| `readOnly=true` for `get`/`readHistory` | `ProductCatalogueService.java:51`, `EventStoreService.java:42` | Default `readOnly=false` | Skip dirty-check, no flush, faster read | Read proxy cannot be used to mutate without explicit `REQUIRED` |
| `paymentGateway.charge` inside tx | `OrderService.java:112` before `commit` | After commit via listener | Charge must rollback with order on failure; but outside retry scope triple-charge risk if retry re-charges — here rolled-back charge simulation is safe, real gateway would need idempotency key per retry attempt | HOLD: real payment providers need external idempotency (`IdempotencyRecord`) if retry fires |
| Two outbox rows in same tx | `OrderService.java:125,143` both pending before return | One topic only | `order.placed` (PR #20) vs `OrderPlacedEventMessage` (rich) feed different consumers; same commit atomicity | Two `INSERT outbox` per order (minor) |
| Service owns tx, controller thin | `OrderController.java:22` delegates | Tx on controller | Service reusable from jobs/MCP/tools; tx defined at business operation, not HTTP lifecycle | None |

---

## 7. How to verify

```bash
# Boundaries present
grep -n "@Transactional" src/main/java/com/company/orderapi/domain/service/OrderService.java
# Expect: 76 placeOrder, 155 updateOrderStatus, 201 cancelOrder, 212 confirmOrder, 229 shipOrder

# Retry on version conflict
grep -n "@Retryable" src/main/java/com/company/orderapi/domain/service/OrderService.java
# Expect: 77 OptimisticLockingFailureException, maxAttempts=5, backoff 20

# Atomicity: charge inside same tx before outbox + return
grep -n "charge\|OutboxEntry\|return order" src/main/java/com/company/orderapi/domain/service/OrderService.java | head -n 10

# Propagation default REQUIRED (no explicit attribute)
grep -c "propagation" src/main/java/com/company/orderapi/domain/service/OrderService.java
# Expect: 0 (all use REQUIRED)

# Tests prove rollback and exactly-one winner under contention
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest
./mvnw test -Dtest=LockingPerformanceComparisonTest 2>&1 | tail -n 20

# Outbox rows committed atomically with orders
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT count(*) FROM orders; SELECT count(*) FROM outbox;"

# Security/observability decorators not breaking tx
grep -n "@PreAuthorize\|@Timed" src/main/java/com/company/orderapi/domain/service/OrderService.java
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Every business operation that must be "all or nothing" (`placeOrder`, status transition `cancel/confirm/ship`) gets one `@Service` method with `@Transactional` (`OrderService.java:76,155,201`...). Keep mutation (`setStockQuantity:98`, `addItem:103`, `charge:112`) inside that method; query methods get `@Transactional(readOnly=true)` (`ProductCatalogueService.java:51`). Annotate contended mutating services with `@Retryable:77` on version conflict only, not on business exceptions. Inter-service calls call the transactional bean via injection, not `this.retriedMethod()` self-invocation (proxy bypass). For idempotent external calls (payment) pass an `idempotencyKey` persisted `IdempotencyRecord` before `charge`.
- **Operate:** Monitor `order.place` histogram (`application.yml` `@Timed:81`, `order.cancel:203`) p95 latency — retries and payment-gateway calls appear there. Alert on `OptimisticLockingFailureException` rate (high → hot product, consider pessimistic `FOR UPDATE` PR #9 or stock partitioning). Outbox `PENDING` count (`application.yml:204` poll) indicates publish lag. Hikari `connections.pending` (`application.yml:33` pool 10) stalls if tx hold too long.
- **Interview:** "PR #20: `OrderService.java:49` `@Service`, `placeOrder:83` `76` `@Transactional REQUIRED` single boundary over four steps `91` read product, `98` `setStockQuantity` (`WHERE version=?` `BaseEntity:53`), `103` `addItem` price snapshot, `112` `paymentGateway.charge`, `118` `saveAndFlush` order+payment graph, `125` `OutboxEntry.pending` same commit (PR #31). `77` `@Retryable(maxAttempts=5, backoff 20ms)` outer proxy over inner tx — stale version conflict re-reads fresh. `155,201,212,229` smaller tx per status transition. Rollback on any `RuntimeException`, `readOnly` on `Catalogue.get:51`. Controller `OrderController:22` thin."

---

## 9. Interview lens — Q&A

**Q1: What four steps does `placeOrder` make atomic and under which annotation?**
A: `OrderService.java:83` `76` `@Transactional`: versioned stock `98`, `addItem:103` snapshot, `paymentGateway.charge:112`, payment+flush `118` + outbox `125` — commit or rollback together (§2.3).

**Q2: Why is `@Retryable` outside `@Transactional` and what does it catch?**
A: `77` `OptimisticLockingFailureException` (`BaseEntity:53` `WHERE version=?` `0 rows`). Outer catch rolls back inner tx, re-enters fresh `Session` reading new `version/stock` (§2.2).

**Q3: What propagation is used and what does `REQUIRED` mean here?**
A: Default `REQUIRED` everywhere (`OrderService.java:76,155...`). Repositories join `placeOrder`'s tx; no premature commit of stock before order; outbox same commit. `REQUIRES_NEW` would break atomicity (§2.4).

**Q4: What isolation guards `placeOrder` against oversell?**
A: `READ_COMMITTED` (Postgres default) + optimistic `@Version` (`BaseEntity:53`) `WHERE version=5` + `Retryable:77`, not `SERIALIZABLE` promotion — cheaper, retries only on real version conflict (§2.5).

**Q5: Why is `paymentGateway.charge` inside the transaction and what risk remains?**
A: So payment failure rolls back stock/order/outbox together (§2.3). Risk: real payment provider already charged externally before retry re-enters — need external idempotency key (`IdempotencyRecord`) (§2.6).

**Q6: What are anti-patterns for service-layer transactions?**
A: `@Transactional` on controller (non-reusable), splitting `placeOrder` into two committed methods (partial state), self-invocation `this.retried()` bypassing proxy — call injected bean, keep method short without blocking I/O (§2.8).

---

## 10. Honest limits & next step → PR #21

Single `REQUIRED` transaction holds row/cache locks for the full method — long `charge` or Kafka `send` inside would exhaust Hikari `pool-size 10` (`application.yml:33`). Payment retry counting mixes Spring `Retryable` and Resilience4j `retry/paymentGateway:230` — overlapping retries. Next PR makes the I/O boundary honest: DTOs with Java records (`OrderRequest.java:16`, `CustomerRequest.java:16`, `OrderResponse.java:16`) plus validation groups carry what the transaction needs and what it returns, with `record` canonical construction validated per operation (`Create/Update` `api/dto/Create.java/Update.java`).

See [`21-dtos-with-java-records.md`](./21-dtos-with-java-records.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Four steps atomic (stock/order/payment/outbox) | `@Transactional` single `placeOrder` | `OrderService.java:76,83` | All or nothing, ACID |
| Contention stock=1 → one winner | `@Retryable` on `OptimisticLockingFailureException` | `OrderService.java:77` | Re-run whole tx fresh |
| Status transition atomic | `@Transactional` per method `cancel/confirm/ship` | `OrderService.java:201/212/229` | Separate smaller boundaries |
| Read without flush | `@Transactional(readOnly=true)` | `ProductCatalogueService.java:51` `get`, `EventStoreService.java:42` | No dirty-check, faster |
| Exactly-once publish (after review) | Outbox `PENDING` in same tx + poll | `OrderService.java:125` + `application.yml:204` | Atomic with order, PR #31 |
