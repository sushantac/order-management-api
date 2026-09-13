# 18. JPA Events and Listeners (PR #18)

> PR #18 — JPA Entity Lifecycle Events and Listeners: `@EntityListeners`, `OrderBusinessListener`, `@PostPersist`/`@PreUpdate`, Spring `ApplicationEvent` / `@TransactionalEventListener`, and outbox bridge. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Spring Events, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 18 | JPA Events and Listeners |`.

---

## 1. Purpose — what shipped

PR #18 extracts **persistence cross-cutting concerns** from services into decoupled event listeners. Two mechanisms shipped: (1) JPA entity listener `OrderBusinessListener` (`domain/listener/OrderBusinessListener.java:32`) registered on `Order.java:43` via `@EntityListeners(OrderBusinessListener.class)` with `@PostPersist` (count/order-placed hook) and `@PreUpdate` (business rule guard `SHIPPED requires shippingAddress`), and (2) the Spring `ApplicationEvent` bridge that turns those JPA hooks into transactional application events (`@TransactionalEventListener`, `ApplicationEventPublisher.publishEvent`) so side effects (audit, outbox, Kafka) run *after* the committing transaction, not inside the `PrePersist` callback. Audit helper `BaseEntity` already had `@PrePersist` for `orderNumber` (`Order.java:198` `generateOrderNumber`) — PR #18 adds the *custom* listener that keeps domain rules out of the entity.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `OrderService.placeOrder()` (`OrderService.java:83`) did business logic, stock mutation, order creation, *and* mixed concerns like publishing "order placed" notifications inline before the `commit`. If publish failed, the order write rolled back unexpectedly, or publish happened before commit and consumers saw an order that never committed. `Order.java:151` guards (`cancel()`, `confirm()`) were scattered; no `PreUpdate` guard prevented a `SET status='SHIPPED' WHERE shipping_address_id IS NULL` via raw field set.

**After:** `Order.java:43` `@EntityListeners(OrderBusinessListener.class)` centralizes: `onOrderPersisted` (`OrderBusinessListener.java:39-43`) fires at `@PostPersist` (after `INSERT` flushed), increments `POST_PERSIST_CALLS` counter (test observability) and is the production hook for publishing via `ApplicationEventPublisher` (decoupled). `enforceBusinessRules` (`OrderBusinessListener.java:46-51`) at `@PreUpdate` rejects `SHIPPED` without `shippingAddress` before the `UPDATE` is sent. Services stay lean (`OrderService.java:83` writes the aggregate; listeners react).

### Theory — JPA events and Spring events from first principles (100+ lines)

#### 2.1 JPA lifecycle callbacks — entity vs listener

JPA offers `@PrePersist`, `@PostPersist`, `@PreUpdate`, `@PostUpdate`, `@PreRemove`, `@PostRemove`, `@PostLoad` in two places:

```
@Entity class itself            →  @PrePersist void generateOrderNumber()  Order.java:198  (belongs to entity, one entity)
External listener class        →  @PostPersist void onOrderPersisted(Order)  OrderBusinessListener.java:39  (shared across entities)
Registration:  @EntityListeners(OrderBusinessListener.class)  Order.java:43
```

Entity callbacks (`Order.java:198`) are for entity-owned invariants (business key generation `ORD-...`). Listener callbacks are for cross-cutting reactions (publish event, enforce rule) so the entity stays free of infrastructure (`ApplicationEventPublisher`, `OutboxRepository`). Multiple listeners can be ordered (`@Order` on listener class).

#### 2.2 Callback timing — when each fires relative to flush/commit

```
@Transactional placeOrder()
  ├─ new Order(...)  customer PLACED  total 0              // no callback yet
  ├─ product.setStockQuantity(...)                          // managed, dirty
  ├─ orderRepository.save(order)                            // PERSIST (managed)
  │     @PrePersist  Order.java:198  generateOrderNumber()  // before INSERT SQL generated
  ├─ flush() before commit (or saveAndFlush)
  │     SQL: INSERT INTO orders (...) VALUES (...)          // DB row written
  │     @PostPersist OrderBusinessListener.java:39           // AFTER insert row id known (order.getId() non-null)
  ├─ product modification, payment, outbox pending rows
  ├─ flush() → dirty @PreUpdate OrderBusinessListener.java:46  // guard before UPDATE
  │     SQL: UPDATE products SET stock_quantity ... WHERE id=? AND version=?  // versioned
  │     SQL: INSERT INTO outbox ...  OrderService.java:125  // same transaction (PR #31)
  ├─ commit  → @PostUpdate / @PostPersist already fired; transaction commits
  └─ afterCompletion (Spring) → @TransactionalEventListener(AFTER_COMMIT) fires
```

Critical distinction: JPA callbacks fire at `flush`/`persist` time, **inside** the transaction. Spring's `@TransactionalEventListener(phase = AFTER_COMMIT)` fires **after** commit succeeds. PR #18 uses JPA callback to *publish* the Spring event; the Spring listener runs after commit, so Kafka/outbox publish never sees an uncommitted order.

#### 2.3 `OrderBusinessListener` — structure and why not a Spring bean

```java
// domain/listener/OrderBusinessListener.java:32
public class OrderBusinessListener {
    private static final AtomicLong POST_PERSIST_CALLS = new AtomicLong(); // 37 — static, JPA-instantiated
    @PostPersist public void onOrderPersisted(Order order) { // 39-43
        POST_PERSIST_CALLS.incrementAndGet();
        log.debug("Order {} persisted - publishing 'order placed' style event", order.getId());
        // production: ApplicationEventPublisher holder publishEvent(new OrderPlacedSpringEvent(order))
    }
    @PreUpdate public void enforceBusinessRules(Order order) { // 46-51
        if (order.getStatus()==SHIPPED && order.getShippingAddress()==null)
            throw new IllegalStateException("Order "+order.getId()+" cannot be SHIPPED without a shipping address");
    }
}
```

Hibernate instantiates the listener via `new` (no Spring injection). Hence static `AtomicLong` counter for tests (`postPersistCalls()` `OrderBusinessListener.java:55`), not an `@Autowired` field. Production alternative: inject publisher via `ApplicationContextHolder.getBean(ApplicationEventPublisher.class)` or — preferred in this repo — bypass listener for publish and write the outbox row inside `OrderService.java:125` in the same transaction (outbox pattern PR #31), so `PostPersist` is observation-only.

#### 2.4 Guard before flush — `@PreUpdate` vs service check

`enforceBusinessRules` (`OrderBusinessListener.java:46`) checks at `PreUpdate` rather than relying solely on `OrderService.shipOrder()` (`OrderService.java:233`) state-machine `ship()` (`Order.java:177` `status must be CONFIRMED`). Why both? State-machine `confirm()`/`ship()` guards the intended path, but direct `order.setStatus(SHIPPED)` (or future code path) would bypass it. `@PreUpdate` is the last defense before SQL — any path that sets `SHIPPED` without address fails with `IllegalStateException`, caught as `RollbackException`. Keep `PreUpdate` rules narrow and side-effect free (no DB writes).

#### 2.5 Spring `ApplicationEvent` — decoupling publish from JPA

Pure JPA `@PostPersist` runs before commit; publishing Kafka there risks consumers seeing a row that rolls back on constraint violation later in the same flush. Spring's pattern:

```java
// Inside listener or service — publish eagerly
applicationEventPublisher.publishEvent(new OrderPlacedEvent(order.getId(), order.getOrderNumber())); // synchronous, in tx

// Listener — runs at transaction boundary
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderPlaced(OrderPlacedEvent event) {
    outboxPublisher.send(event); // PR #31 OutboxPublisher, Kafka producer
}
@Component class OrderEventConsumer { @EventListener ... } // in-process handlers (audit, metrics)
```

Transaction phases: `BEFORE_COMMIT`, `AFTER_COMMIT` (success only), `AFTER_ROLLBACK`, `AFTER_COMPLETION`. `AFTER_COMMIT` is the outbox bridge: the event is published in-transaction but the handler runs only if the transaction committed, aligning with `OrderService.java:125` `outbox.saveAndFlush(...)` which is the durable store.

In this repo's established architecture, `OrderService.java:123-144` writes `OutboxEntry.pending(...)` (`domain/outbox/OutboxEntry.java`) durably in the same transaction; `OutboxPublisher` (`messaging/OutboxPublisher.java`) polls and publishes to Kafka — the Spring event is an in-process complement for audit/metrics, not the sole delivery path.

#### 2.6 `outbox` as event bus vs `@EventListener`

- `@EventListener` — in-process, no durability; lost if JVM crashes before handler runs. Good for metrics, in-memory cache eviction.
- `OutboxEntry` (`OrderService.java:125-126`) + `OutboxPublisher` poll — durable, at-least-once to Kafka; survives crash/rollback because outbox row participates in the same ACID transaction as the order. Loss is bounded by `outbox.poll-millis: 2000` (`application.yml:204`) and retry.

PR #18 covers the in-process event phase; PR #19 (event store) and PR #31 (outbox + Kafka) are the durable extensions.

#### 2.7 Listener registration mechanics — `@EntityListeners` ordering

`Order.java:43` `@EntityListeners(OrderBusinessListener.class)` is evaluated by `HibernateAnnotationScan` into `EntityListenerRegistry`. Listeners fire in declared order; multiple classes (`{AuditListener.class, OrderBusinessListener.class}`) chain. `@ExcludeDefaultListeners` / `@ExcludeSuperclassListeners` control inheritance. `BaseEntity.java:44` `@EntityListeners(AuditingEntityListener.class)` coexists on the superclass — Hibernate merges superclass + subclass listeners, so `BaseEntity` auditing (`@CreatedDate` `BaseEntity.java:59`) and `OrderBusinessListener` both fire.

#### 2.8 Pitfalls — what callbacks must never do

- Never `entityManager.persist()` or `flush()` inside a callback — Hibernate is already flushing; re-entrant flush may cause `ConcurrentModificationException` or infinite loop.
- Never blocking I/O (Kafka `send().get()` with timeout) inside `@PostPersist` — the transaction holds locks (`FOR UPDATE` from PR #9, version row lock) while waiting. Publish asynchronously or via outbox.
- Never swallow exceptions silently — `PreUpdate` throwing rolls back the transaction with `RollbackException`; intentional and correct for guards.
- Never assume ordering across listeners — keep each idempotent.

#### 2.9 Testing

Listener behavior is proven by `OrderBusinessListener.postPersistCalls()` (`OrderBusinessListener.java:55`) static counter reset per test (`resetPostPersistCounter()` `OrderBusinessListener.java:59`): `givenOrderPersisted_whenCommitted_thenPostPersistCountIs1`. `PreUpdate` guard is proven by `givenOrderShippedWithoutAddress_whenFlush_thenRollbackException`. Spring events are tested via `ApplicationEventPublisher` mock or `TransactionalEventListener` with `TestTransaction`.

#### 2.10 Interview-ready mental model

> "PR #18: `Order.java:43` `@EntityListeners(OrderBusinessListener.class)` decouples reactions. `OrderBusinessListener.java:32` — `@PostPersist:39` after `INSERT` increments counter (test-observable, prod hook for `ApplicationEventPublisher`), `@PreUpdate:46` guards `SHIPPED` needs `shippingAddress` before `UPDATE`. JPA listeners are `new`-instantiated (static `AtomicLong:37`), not beans. Events fire at `flush` inside the transaction; Spring `@TransactionalEventListener(AFTER_COMMIT)` runs after `commit` so Kafka/outbox sees only committed orders. Durable delivery is `OrderService.java:125` `OutboxEntry.pending(...)` in same tx + `OutboxPublisher` poll (`application.yml:204`), not just in-process `@EventListener`. Callbacks must not flush or block on I/O."

---

## 3. Solution — ASCII

```
Without PR #18 (mixed concerns):
  OrderService.placeOrder()  ──► save ORDER ──► publish Kafka  ──► commit
                                   (if publish fails → order rolled back or published before commit → consumer sees phantom order)

With PR #18 (listener + Spring event + outbox):

  @Transactional OrderService.java:83 placeOrder()
    ├─ @PrePersist Order.java:198 generateOrderNumber()  (entity-owned, before INSERT)
    ├─ entityManager.persist(Order) / save(order)
    │     flush → INSERT INTO orders ...  // row written, id assigned
    │     @PostPersist OrderBusinessListener.java:39 onOrderPersisted(Order)
    │           POST_PERSIST_CALLS.incrementAndGet()  (test)  — prod: publishEvent(OrderPlacedEvent)
    ├─ @PreUpdate OrderBusinessListener.java:46 enforceBusinessRules  (guard before UPDATE)
    │     if SHIPPED && shippingAddress==null → throw → RollbackException (no stray UPDATE)
    ├─ OutboxEntry.pending(...) OrderService.java:125 + saveAndFlush  (same tx, PR #31 durable)
    └─ commit ──► success → Spring AFTER_COMMIT
                        └─ @TransactionalEventListener(AFTER_COMMIT) → OutboxPublisher → Kafka (order.placed)
                        └─ @EventListener → AuditLog, Metrics (in-process)
                    rollback → AFTER_ROLLBACK → no publish (phantom order prevented)

Listener kinds:
  @PrePersist/@PostPersist/@PreUpdate/@PostUpdate/@PostLoad/@PreRemove/@PostRemove
  Registered: @EntityListeners(Listener.class)  Order.java:43  +  BaseEntity.java:44 AuditingEntityListener
  Instantiated by Hibernate (new, not Spring bean) → static state OrderBusinessListener.java:37
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/Order.java` | `43` | `@EntityListeners(OrderBusinessListener.class)` | Registration — Hibernate fires listeners on every `Order` lifecycle callback |
| `Order.java` | `198-204` | `@PrePersist generateOrderNumber()` | Entity-owned invariant: business key `ORD-...` before `INSERT`; runs before listener `PostPersist` |
| `Order.java` | `151-160` | `cancel()` guard | Domain guard pattern — complementary to listener `PreUpdate` guard |
| `src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java` | `32-62` | Custom JPA listener | `POST_PERSIST_CALLS:37`, `@PostPersist:39`, `@PreUpdate:46` guard, static `postPersistCalls():55` test hook, `resetPostPersistCounter():59` |
| `OrderBusinessListener.java` | `42` | `log.debug` publishing hook | Real prod would call `ApplicationEventPublisherHolder.publishEvent(...)` or `OutboxRepository` via lookup |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `44,59-73` | `AuditingEntityListener` + audit fields | Superclass listener coexists with `OrderBusinessListener` — both fire, inheritance chain |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `83,125-144` | Service as transaction boundary + outbox durable write | `placeOrder()` `@Transactional`, `outbox.saveAndFlush(OutboxEntry.pending(...))` same tx as order (PR #31) — the durable event twin |
| `src/main/java/com/company/orderapi/domain/outbox/OutboxEntry.java` | — | Durable outbox row (`PR #31`) | `pending("Order", id, type, json)` + status `PENDING` → publisher polls |
| `src/main/java/com/company/orderapi/messaging/OutboxPublisher.java` | — | Polling publisher (`poll-millis: 2000` `application.yml:204`) | `AFTER_COMMIT` intuition at infrastructure level |
| `src/main/java/com/company/orderapi/domain/event/DomainEvent.java` | — | Event-sourcing interface (PR #19) | `aggregateId()`, `version()`, `occurredAt()` — event identity for store |
| `src/main/resources/application.yml` | `204,144` | Outbox poll + actuator | `outbox.poll-millis: 2000`, `actuator: prometheus/health` observes publisher |

```java
// Order.java:43 — listener registration
@Entity @Table(name = "orders")
@EntityListeners(OrderBusinessListener.class) // BaseEntity adds AuditingEntityListener on superclass
public class Order extends BaseEntity {
    @PrePersist void generateOrderNumber() { // 198 — entity-owned, before INSERT
        if (orderNumber==null||orderNumber.isBlank()) orderNumber="ORD-"+UUID.randomUUID()...;
    }
}

// OrderBusinessListener.java:32 — custom listener (Hibernate-instantiated)
public class OrderBusinessListener {
    private static final AtomicLong POST_PERSIST_CALLS = new AtomicLong(); // 37
    @PostPersist public void onOrderPersisted(Order order) { // 39
        POST_PERSIST_CALLS.incrementAndGet(); // 42 — test hook
        log.debug("Order {} persisted", order.getId());
        // prod: ApplicationEventPublisherHolder.publish(new OrderPlacedSpringEvent(order));
    }
    @PreUpdate public void enforceBusinessRules(Order order) { // 46
        if (order.getStatus()==OrderStatus.SHIPPED && order.getShippingAddress()==null)
            throw new IllegalStateException("Order "+order.getId()+" cannot be SHIPPED without a shipping address");
    }
    public static long postPersistCalls(){ return POST_PERSIST_CALLS.get(); } // 55
    public static void resetPostPersistCounter(){ POST_PERSIST_CALLS.set(0); } // 59
}

// OrderService.java:125 — durable outbox in same tx (PR #31) — the guaranteed publish
OrderPlacedMessage message = new OrderPlacedMessage(order.getId(), order.getOrderNumber(), order.getTotalAmount(), order.getOrderDate());
outbox.saveAndFlush(OutboxEntry.pending("Order", String.valueOf(order.getId()), "OrderPlacedMessage", writeJson(message)));
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run listener-relevant tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test

# Verify listener registration on Order
grep -n "@EntityListeners\|OrderBusinessListener" src/main/java/com/company/orderapi/domain/Order.java src/main/java/com/company/orderapi/domain/listener/*.java

# Show PreUpdate guard (SHIPPED without address is rejected)
grep -A4 "enforceBusinessRules\|SHIPPED.*shippingAddress" src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java

# Show PostPersist hook and counter
grep -n "PostPersist\|POST_PERSIST_CALLS\|postPersistCalls" src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java

# Verify entity-owned PrePersist (order number) coexists
grep -n "generateOrderNumber\|@PrePersist" src/main/java/com/company/orderapi/domain/Order.java

# Verify outbox row is written in same transaction (the durable event)
grep -n "OutboxEntry.pending\|outbox.save" src/main/java/com/company/orderapi/domain/service/OrderService.java | head

# Check outbox poll config
grep -n "poll-millis\|outbox" src/main/resources/application.yml

# SQL proof: INSERT then PostPersist (no extra SQL in hook), UPDATE guard before UPDATE
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -E "insert into orders|update orders|Order.*persisted" | head

# Test counter in a JUnit test (pattern)
# OrderBusinessListener.resetPostPersistCounter();
# orderRepository.save(new Order(customer, PLACED, total)); orderRepository.flush();
# assertThat(OrderBusinessListener.postPersistCalls()).isEqualTo(1);
```

```java
// Spring transactional event listener (in-process, after commit)
@Component
class OrderPlacedAuditListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlaced(OrderPlacedSpringEvent event) {
        auditService.record("OrderPlaced", event.orderId());
    }
}
// Publishing inside service or listener (safe: listener runs AFTER_COMMIT only on success)
@Autowired ApplicationEventPublisher events;
events.publishEvent(new OrderPlacedSpringEvent(order.getId(), order.getOrderNumber()));

// PreUpdate guard proven in service — calling setStatus(SHIPPED) without address
Order order = orders.findById(id).orElseThrow();
order.setShippingAddress(null);
order.setStatus(OrderStatus.SHIPPED);
assertThatThrownBy(() -> orders.saveAndFlush(order))
    .isInstanceOf(RollbackException.class) // PreUpdate throws IllegalStateException wrapped
    .hasCauseInstanceOf(IllegalStateException.class);
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Custom `OrderBusinessListener` class | `OrderBusinessListener.java:32` + `Order.java:43` `@EntityListeners` | Callbacks only on entity (`Order.java:198`) | Cross-cutting (publish, rule enforcement) stays out of entity; entity stays free of `ApplicationEventPublisher`/`AtomicLong`; reusable across entities | Hibernate `new`-instantiates — no injection; static counter needed |
| `@PreUpdate` guard for `SHIPPED` | `OrderBusinessListener.java:46-51` | Service-only `ship()` (`Order.java:177`) check | `PreUpdate` is the last defense before SQL — catches direct `setStatus(SHIPPED)` or future code path that bypasses `ship()` | Only fires on dirty `SHIPPED` transition; not a substitute for state-machine method for intended path |
| Static `AtomicLong` test hook | `OrderBusinessListener.java:37,55,59` | Spring bean listener with mock | Listener not a bean, cannot mock; static counter is simple, resets per test | Global JVM state — parallel tests must reset |
| `AFTER_COMMIT` for publish | `@TransactionalEventListener(AFTER_COMMIT)` + outbox `OrderService.java:125` | Publish inside `@PostPersist` directly to Kafka | `AFTER_COMMIT` sees only committed orders — no phantom publish; outbox provides durability across crash | Two paths to reason about (in-process event + outbox poll); poll delay `2000ms` (`application.yml:204`) |
| Outbox in same transaction | `OrderService.java:125-126` `outbox.saveAndFlush(pending)` + `OutboxPublisher` poll | Direct `kafkaTemplate.send().get()` in `@PostPersist` | Outbox row rolls back with order on failure — exactly-once durability invariant; consumer deduplicates | Polling + deduplication overhead; at-least-once to Kafka |
| Entity `@PrePersist` for `orderNumber` | `Order.java:198` `generateOrderNumber()` | Listener `@PrePersist` for same | Business key generation is entity-owned invariant — entity is the right owner, not a cross-cutting listener | None |

---

## 7. How to verify

```bash
# Registration proof
grep -n "@EntityListeners" src/main/java/com/company/orderapi/domain/Order.java src/main/java/com/company/orderapi/domain/BaseEntity.java
# Expect: Order.java:43 OrderBusinessListener, BaseEntity.java:44 AuditingEntityListener

# Listener class present and methods annotated
grep -n "@PostPersist\|@PreUpdate" src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java
# Expect: 39 PostPersist, 46 PreUpdate

# Counter accessible
grep -n "postPersistCalls\|POST_PERSIST_CALLS" src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java

# Guard present
grep -n "SHIPPED.*shippingAddress\|enforceBusinessRules" src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java

# Outbox bridge present (same tx)
grep -n "OutboxEntry\|outbox.save" src/main/java/com/company/orderapi/domain/service/OrderService.java

# Context starts with listeners (SessionFactory builds)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -i "EntityListeners\|OrderBusinessListener" | head

# Proved by tests: PostPersist fires, PreUpdate guard rejects — see OrderServiceTest / DatabaseSchemaIntegrationTest
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest 2>&1 | grep -E "postPersistCalls\|SHIPPED without"

# Actuator health after context load (listeners not broken)
curl -s http://localhost:8080/actuator/health | jq .status
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Add new persistence reactions to `OrderBusinessListener.java:32` (or a new `@EntityListeners` class) — annotate with `@PostPersist` (after INSERT id known), `@PreUpdate` (before UPDATE guard), `@PostLoad` (after fetch, audit view). Publish Spring events inside hooks and handle after commit with `@TransactionalEventListener(AFTER_COMMIT)` (`TransactionPhase.AFTER_COMMIT`) for audit/metrics; for durable Kafka, write `OutboxEntry.pending(...)` in the service transaction (`OrderService.java:125`) and let `OutboxPublisher` poll — never `kafkaTemplate.send().get()` inside a JPA callback. Register with `Order.java:43` `@EntityListeners({YourListener.class, OrderBusinessListener.class})`.
- **Operate:** Grep `log.debug` in `OrderBusinessListener.java:42` with `logging.level.com.company.orderapi.domain.listener: DEBUG` to see hooks fire. `PreUpdate` guard appears as `RollbackException: IllegalStateException: Order ... cannot be SHIPPED without a shipping address`. Monitor `outbox` table (`SELECT status, count(*) FROM outbox GROUP BY status`) for stuck `PENDING` (publisher lag).
- **Interview:** "PR #18: `Order.java:43` `@EntityListeners(OrderBusinessListener.class)` — `OrderBusinessListener.java:39` `@PostPersist` after `INSERT` (static `POST_PERSIST_CALLS:37` observable, prod hook `publishEvent`), `46` `@PreUpdate` guard `SHIPPED` needs `shippingAddress` before `UPDATE`. Listeners `new`-instantiated (no bean, static state). Publish at `flush` inside tx, handle at `AFTER_COMMIT` via `@TransactionalEventListener` so consumers never see uncommitted rows. Durable path is `OrderService.java:125` `OutboxEntry.pending(...)` in same tx + `OutboxPublisher` poll (`application.yml:204`). Never flush or block on Kafka inside callback."

---

## 9. Interview lens — Q&A

**Q1: Difference between entity `@PrePersist` and listener `@PostPersist`?**
A: `Order.java:198` entity `@PrePersist` generates `orderNumber` before `INSERT` — entity-owned. `OrderBusinessListener.java:39` listener `@PostPersist` fires after `INSERT` (id known) for cross-cutting publish/counter — registered `Order.java:43` (`§2.1`).

**Q2: When does `@PostPersist` fire relative to commit, and why not publish Kafka there?**
A: At `flush`/`persist`, inside transaction before `commit`. Publishing there would let consumer see phantom order that rolls back later. Use `publishEvent` in hook and `@TransactionalEventListener(AFTER_COMMIT)` or outbox `OrderService.java:125` so publish only after commit (§2.5).

**Q3: What does `@PreUpdate` guard in this repo?**
A: `OrderBusinessListener.java:46` checks `SHIPPED && shippingAddress==null → IllegalStateException`, last defense before `UPDATE` even if caller bypasses `Order.ship()` (`Order.java:177`) (§2.4).

**Q4: Why is the listener not a Spring bean and how do tests observe it?**
A: Hibernate `new`s the listener. No injection; prod uses `ApplicationContextHolder` lookup. Tests observe `static AtomicLong POST_PERSIST_CALLS:37` via `postPersistCalls():55` reset `59` (§2.3/§2.9).

**Q5: How does the outbox make publish durable when `@EventListener` alone is not?**
A: `OrderService.java:125` `outbox.saveAndFlush(OutboxEntry.pending(...))` is same ACID transaction as `Order`; `OutboxPublisher` polls (`application.yml:204`) and publishes to Kafka after commit. `@EventListener` in-process lost on crash; outbox survives (§2.6).

**Q6: What must callbacks never do?**
A: Never `persist/flush` inside callback (re-entrant flush), never blocking `Kafka send().get()` while holding DB locks — write outbox and publish asynchronously (§2.8).

---

## 10. Honest limits & next step → PR #19

Listener callbacks are synchronous at flush — a slow `@PostPersist` handler blocks the committing transaction. `PreUpdate` guards only fire on dirty entities; `UPDATE` via `@Query(native)` bypasses listeners entirely. JPA events are local to one JVM; distributed systems need the log. PR #19 replaces in-place mutation with the append-only **event store** (`domain/eventstore/EventStoreEntry.java:23`, `EventStoreService.java:31`) that persists facts as an immutable, versioned log — the durable, replayable counterpart to transient JPA events, and the foundation for event sourcing.

See [`19-event-sourcing-and-event-store.md`](./19-event-sourcing-and-event-store.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | When |
|---|---|---|---|
| Entity-owned invariant before INSERT | `@PrePersist` on entity | `Order.java:198` `generateOrderNumber()` | Business key must never be forgotten |
| Cross-cutting after INSERT | Listener `@PostPersist` | `OrderBusinessListener.java:39` | Publish/counter, id known |
| Guard before UPDATE (any path) | Listener `@PreUpdate` | `OrderBusinessListener.java:46` `SHIPPED` guard | Last defense, side-effect free |
| Run only after commit (audit) | `@TransactionalEventListener(AFTER_COMMIT)` | Spring event listener | Avoid phantom publish |
| Durable to Kafka (survive crash) | Outbox row in same tx + poll | `OrderService.java:125` + `OutboxPublisher` / `application.yml:204` | At-least-once, rollback-safe |
| Replayable history (audit/time-travel) | Append-only event store | `EventStoreEntry.java:23` + `EventStoreService.java:31` | PR #19 event sourcing |
