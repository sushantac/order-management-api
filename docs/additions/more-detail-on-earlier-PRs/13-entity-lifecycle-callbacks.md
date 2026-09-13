# 13. Entity Lifecycle Callbacks (PR #13)
> PR #13 — Entity Lifecycle Callbacks: `@PrePersist` `orderNumber`, `@PreUpdate`, `@PostLoad`, `@PreRemove`, callback order and `@Transient` state. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Jakarta JPA. See `README.md:1772` roadmap `| 13 | Entity Lifecycle Callbacks |`.
---

## 1. Purpose — what shipped

PR #13 delivers **Entity Lifecycle Callbacks** as a first-class, tested, documented building block. Four entity callbacks give every `INSERT`/update/load/remove a predictable hook without service-layer boilerplate: `Order.java:198` `@PrePersist generateOrderNumber()` (business key `ORD-XXXXXXXXXX`), `BaseEntity.java:109` `@PostLoad onPostLoad()` (inherited, `boolean postLoadFired` `BaseEntity.java:107` `@Transient`), `Payment.java:114` `@PreUpdate stampProcessedDate()` (invariant `paymentDate` on `PROCESSED`), `Customer.java:99` `@PreRemove assertRemovalAllowed()` (business delete-guard). Verified by `SELECT order_number FROM orders` and flush/load/remove tests in `DatabaseSchemaIntegrationTest.java:56`.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `Order.orderNumber` (`Order.java:64` `length=40`, unique in DB) was set at call sites — some services forgot it, some generated it outside the transaction, and there was no single source of truth. `Payment.paymentDate` was manually stamped in `OrderService.java:116` — easy to miss when updating status via a new code path (MCP tool `ShipOrderTool`, admin job). `Customer` could be deleted even with `orders` present — DB `CASCADE` would silently delete their history, violating the business rule that customer history is immutable. No in-memory flag to prove an entity was loaded from DB vs constructed in tests.

**After:** Business keys and invariants live on the entity: `@PrePersist generateOrderNumber()` (`Order.java:198`) runs before every `INSERT` if `orderNumber == null`; `@PreUpdate stampProcessedDate()` (`Payment.java:114`) ensures `paymentDate` on `PROCESSED` without caller cooperation; `@PreRemove` (`Customer.java:99`) throws before `DELETE` when orders exist — the DB would happily cascade but the domain refuses; `@PostLoad` (`BaseEntity.java:109`) sets `postLoadFired = true` (`@Transient` `BaseEntity.java:107`) inherited by all entities so tests can assert loaded-vs-transient without querying version.

### Theory — entity lifecycle callbacks from first principles (100+ lines)

#### 2.1 What "entity lifecycle" means

A JPA entity instance has 5 states (Hibernate's `EntityEntryStatus`):

```
Transient (new Order(...))     ──►  Managed (in PersistenceContext, after persist/merge/find)
   │                                      │    dirty-checked, versioned (BaseEntity.java:53)
   │ persist() / find()                   │ flush() → SQL
   │                                      ▼
                              Removed (scheduled for DELETE) ──► Deleted (committed)
                                      ▲        │ flush()
                                      └────────┘
                              Detached (PersistenceContext closed — outside transaction)
```

Callbacks are methods JPA invokes at well-defined transitions, regardless of which code path triggered the transition (REST controller, MCP tool, batch job, test). They are the entity-level counterpart to Spring's application events (`DomainEvent` `OrderStatusChangedEventProducer`) and to `AuditingEntityListener` (`BaseEntity.java:44` PR #10).

JPA defines 7 callbacks: `@PrePersist`, `@PostPersist`, `@PreUpdate`, `@PostUpdate`, `@PreRemove`, `@PostRemove`, `@PostLoad`. This repo uses 4; `@PostPersist`/`@PostUpdate` appear in `OrderBusinessListener.java:39/46` (PR #18) showing the listener alternative.

#### 2.2 `@PrePersist` — `Order.generateOrderNumber()` deep dive

```java
// Order.java:198
@PrePersist
void generateOrderNumber() {
    if (orderNumber == null || orderNumber.isBlank()) {
        orderNumber = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
```

- **When:** invoked once, just before the `INSERT` SQL for `Order` — after `persist(order)` but before `flush()`. If the transaction calls `persist` 3 times without flush, `generateOrderNumber` has fired 3 times but no SQL yet; actual `INSERT` is at commit flush.
- **Guard:** `if (orderNumber == null || isBlank())` makes it idempotent. Tests and seed scripts can set `orderNumber = "ORD-TEST-0001"` explicitly and the callback won't overwrite it.
- **Why on entity, not service:** placing it on the entity guarantees *every* insert path (`OrderService.java:83` `placeOrder`, any future MCP tool, batch import, test) generates a number — no caller can forget. `order_number` (`Order.java:64`) has `length=40` and a unique DB constraint (`04_add_order_number.sql`); the callback is the canonical generator, tests assert uniqueness via `SELECT distinct count(order_number) = count(*)`.
- **Generation:** `UUID.randomUUID()` is cheap and collision-resistant enough for 10-hex `ORD-3A7F9C2E1B`. For a stricter sequel, DB sequence or Snowflake ID would be ordered/monotonic but UUID covers the correctness guarantee without extra infrastructure.
- **Liquibase:** `04_add_order_number.sql` adds `order_number VARCHAR(40) UNIQUE` with a DB-level `UNIQUE` constraint. The callback ensures no `NULL` is inserted (which would satisfy `UNIQUE` vacuously in PostgreSQL — two `NULL` rows are not considered equal). First PR #13 startup migrates, then `ddl-auto: validate` (`application.yml:48`) confirms `Order.orderNumber` (`Order.java:64`) maps length 40 to that column.

Compared to auditing: `BaseEntity.java:44` `AuditingEntityListener` fills `createdAt/createdBy` on the same `PrePersist` phase via Spring Data's handler; `Order.generateOrderNumber()` is a separate, business-specific method on the same lifecycle event — they coexist as described in §2.6 of PR #10 doc.

#### 2.3 `@PreUpdate` — `Payment.stampProcessedDate()` deep dive

```java
// Payment.java:114
@PreUpdate
void stampProcessedDate() {
    if (status == PaymentStatus.PROCESSED && paymentDate == null) {
        paymentDate = LocalDateTime.now();
    }
}
```

- **When:** invoked before every `UPDATE` SQL for `Payment` where Hibernate's dirty check finds any field dirty. If only `transactionId` changes but status is still `PROCESSED` and `paymentDate` was already set, the guard `paymentDate == null` prevents re-stamping.
- **Trigger path:** `order.setPayment(payment); payment.setStatus(PROCESSED);` → entity managed → dirty → at flush, Hibernate fires `PreUpdateEvent` → `Payment.stampProcessedDate()` fills `paymentDate`.
- **Why not `OrderService`:** Without the callback, every call site that sets `status=PROCESSED` must remember to also set `paymentDate`. Adding a new path (e.g., `ConfirmOrderTool` via MCP `ConfirmOrderTool.java`) would be easy to forget — this invariants-on-entity pattern centralizes it. PR #18 `OrderBusinessListener.java:46` `@PreUpdate enforceBusinessRules` shows the listener variant for cross-cutting invariants (`SHIPPED` without `shippingAddress` → throw) that keeps the entity clean — similar purpose, different home.
- **Null invariant:** `paymentDate` is `@Column(name="payment_date")` nullable; `PENDING` → `null` is allowed, `PROCESSED` → not null is the business rule, enforced by the callback not by DB `NOT NULL`.

#### 2.4 `@PostLoad` — `BaseEntity.onPostLoad()` deep dive

```java
// BaseEntity.java:106-117
@Transient                                // NOT a column — in-memory flag only
private boolean postLoadFired;

@PostLoad
void onPostLoad() {
    this.postLoadFired = true;
    log.debug("{}#{} loaded from the database", getClass().getSimpleName(), getId());
}

public boolean isPostLoadFired() { return postLoadFired; } // BaseEntity.java:115
```

- **When:** after any load path succeeds: `findById`, `findAll`, `getReference`/`load`, JPQL/Criteria/Spec query returns, after `refresh`. NOT after `persist` (entity not yet loaded from DB).
- **Where declared:** `BaseEntity.java:109` (mapped superclass), so inherited by `Order`, `Customer`, `Product`, etc. Hibernate invokes inherited `@PostLoad` methods as well as entity-declared ones.
- **`@Transient` `BaseEntity.java:107`:** without it, Hibernate would try to map `postLoadFired` to a column `post_load_fired` and fail validation (`application.yml:48` `ddl-auto: validate`). `@Transient` (Jakarta) excludes it from column mapping — it is pure in-memory state, reset to `false` for each new instance, `true` after load.
- **Use:** tests/assertion only — distinguishes a managed entity loaded from DB (`postLoadFired==true`) from one constructed in test (`new Order(...); postLoadFired==false`). Also demonstrates the lightest lifecycle hook; production use would be to hydrate derived transient fields (e.g., formatted display name, computed total) after DB load.

Inheritance note: if both `BaseEntity` and a subclass declare `@PostLoad`, *both* fire, superclass first then subclass.

#### 2.5 `@PreRemove` — `Customer.assertRemovalAllowed()` deep dive

```java
// Customer.java:99
@PreRemove
void assertRemovalAllowed() {
    if (orders != null && !orders.isEmpty()) {
        throw new IllegalStateException(
            "Customer " + getId() + " cannot be removed: still has " + orders.size() + " order(s)");
    }
}
```

- **When:** before Hibernate executes `DELETE FROM customers WHERE id=?`. If the callback throws `IllegalStateException`, Hibernate wraps it and aborts the flush — `DELETE` never issued, transaction can be rolled back or recovered.
- **Business vs DB enforcement:** `orders.customer_id` FK is typically `ON DELETE CASCADE` / `ON DELETE RESTRICT` in `01_create_tables.sql` — the DB *would* happily cascade-delete the customer's orders (and via orders, `order_items`) or restrict depending on FK declaration. The callback is the *domain* decision: customer history is immutable business data, deleting a customer with live orders must be refused at the domain layer with a clear error, not silently cascaded and discovered later from missing audit trails.
- **Lazy trap:** `orders` (`Customer.java:67` `fetch=LAZY` + `@BatchSize(20)`) may not be loaded when `@PreRemove` fires. Calling `orders.size()` outside a transaction would lazy-load; inside `em.remove(customer)` within `@Transactional`, the collection is accessible. If it were uninitialized and the `PersistenceContext` were closed, size would throw `LazyInitializationException`.
- **Alternative:** could be a repository `deleteById` guard (`if (customerRepository.existsByOrdersNotEmpty(id)) throw`) — but placing it as a callback means *any* delete path (repository, `em.remove`, cascade from another entity, admin script) hits the same guard.

#### 2.6 Callback order and interaction with version/auditing

Execution order for an `Order` update (`order.setStatus(SHIPPED)`) that triggers flush:

```
Business call:   order.setStatus(SHIPPED);
Hibernate dirty: detects status change → schedules UPDATE
Callbacks:
  1. AuditingEntityListener.preUpdate  (BaseEntity.java:44) → sets updatedAt/updatedBy (via Spring's AuditingHandler, PR #10)
  2. Payment.preUpdate                 (Payment.java:114) if payment was also dirty
  3. Order.generateOrderNumber NOT fired (only PrePersist)
  4. OrderBusinessListener.preUpdate   (OrderBusinessListener.java:46) → enforces shipped-without-address invariant (PR #18)
  5. Hibernate version increment       (BaseEntity.java:53) → version = version+1
  6. SQL: UPDATE orders SET status=?, updated_at=?, updated_by=?, version=? WHERE id=? AND version=?
              ──── only dirty fields + version + updated* ────
  7. postUpdate listeners (OrderBusinessListener.postPersist is only on insert)
```

Within one flush, `PreUpdate` callbacks see `status == SHIPPED` already set; setting `paymentDate` there marks that field dirty too and Hibernate includes it in the same `UPDATE SET` — one SQL, not two. Throwing from any `PreUpdate` aborts the whole flush.

Ordering rule: `BaseEntity` superclass callbacks fire before subclass callbacks; multiple listener classes (`AuditingEntityListener`, `OrderBusinessListener`) are invoked in the order they appear in `@EntityListeners({...})`.

#### 2.7 `@Transient` vs `@Column` — the complete picture in BaseEntity

| Field | Annotation | Column | Lifecycle |
|---|---|---|---|
| `id` | `@Id` `@GeneratedValue(IDENTITY)` | `id BIGINT` (`BaseEntity.java:50`) | `IDENTITY` — written on insert by DB, not by `@PrePersist` |
| `version` | `@Version` | `version BIGINT NOT NULL DEFAULT 0` (`BaseEntity.java:53` / `02_add_version_columns.sql`) | Incremented on every `UPDATE`/`DELETE` |
| `createdAt` | `@CreatedDate` | `created_at TIMESTAMPTZ` (`BaseEntity.java:60`) | Set by `AuditingEntityListener` `PrePersist`, never updated (`updatable=false`) |
| `updatedAt` | `@LastModifiedDate` | `updated_at TIMESTAMPTZ` | `AuditingEntityListener` `PrePersist`/`PreUpdate` |
| `postLoadFired` | `@Transient` | **no column** (`BaseEntity.java:107`) | `PostLoad` only — in-memory proof of DB load |

#### 2.8 Entity callbacks vs entity listeners (`@EntityListeners`)

| Aspect | Callback on entity (`@PrePersist` on `Order.java:198`) | Listener (`OrderBusinessListener.java:32` `@EntityListeners`) |
|---|---|---|
| Location | Method on the entity class itself | Separate class, shared across entities |
| Spring bean? | Not needed — JPA invokes it directly | Listener is NOT a Spring bean either (Hibernate instantiates it) — cross-observation via static `AtomicLong` `POST_PERSIST_CALLS` `OrderBusinessListener.java:37` |
| Reuse | One entity only | One listener can handle many entity types (e.g., `@PreUpdate` on any entity) |
| Test observability | Check entity field (`order.getOrderNumber()!=null`, `postLoadFired`) | Static counter `postPersistCalls()` `OrderBusinessListener.java:55` |
| Typical use | Business key generation (`Order.generateOrderNumber`) | Cross-cutting `PostPersist` event publishing (PR #18 `onOrderPersisted`) |

They coexist — `Order` has `Order.java:198` `@PrePersist` plus `Order.java:43` `@EntityListeners(OrderBusinessListener.class)`. See PR #18 doc for the listener side.

#### 2.9 Pitfalls and limits

- **Exceptions are fatal:** throwing from `@PreRemove`/`@PreUpdate` kills the whole flush — the transaction must rollback or recover. Don't throw non-domain exceptions.
- **Don't call `persist`/`remove` from callbacks:** callback-triggered `persist` can recurse or produce `IllegalStateException` from the persistence provider.
- **`@PrePersist` not fired on `merge` of new entity:** `merge(new Order(...))` may copy state to a managed instance; `PrePersist` fires on that managed instance if it is new — behaviour is provider-dependent. Prefer `persist`.
- **`@PostLoad` not fired for projections:** interface/class projections (`CustomerNameProjection` `CustomerRepository.java:40`) and native `CustomerSpend` (`OrderRepository.java:37`) bypass the entity lifecycle — `postLoadFired` stays `false`.
- **Version still matters:** callbacks run before version increment — `generateOrderNumber` sees `version==0` for new rows; `@PreUpdate` sees `version==n` (old value) because increment happens after callbacks (see §2.6).

#### 2.10 Interview-ready mental model

> "`Order.java:198` `@PrePersist generateOrderNumber()` guarantees `Order.orderNumber` (`Order.java:64` `VARCHAR(40) UNIQUE`, `04_add_order_number.sql`) on every insert without callers remembering — `ORD-` + 10 hex from `UUID`, idempotent if already set. `Payment.java:114` `@PreUpdate stampProcessedDate()` ensures `paymentDate` when `PROCESSED` — enforced in the same `UPDATE` as the status change. `BaseEntity.java:109` `@PostLoad onPostLoad()` (inherited) sets `@Transient postLoadFired` (`BaseEntity.java:107`) so tests can prove load vs transient; `@Transient` means no column (`ddl-auto: validate` would fail otherwise). `Customer.java:99` `@PreRemove assertRemovalAllowed()` refuses `DELETE` when `orders` non-empty — DB would CASCADE but domain refuses. Execution: `AuditingEntityListener` (`BaseEntity.java:44` PR #10) → entity `@PreUpdate` callbacks → version increment (`BaseEntity.java:53` PR #8) → one `UPDATE ... SET ..., updated_at=?, version=? WHERE id=? AND version=?`. Entity callbacks vs listeners: `Order.java:198` is entity-local business invariants; `OrderBusinessListener.java:46` listener is cross-cutting (`PostPersist` event). Don't `persist`/`remove` from callbacks."

---

## 3. Solution — ASCII

```
Entity states & callback fire points:
  Transient  --persist()-->  Managed  --setStatus-->  dirty ──────────► PreUpdate fires ──► version++ ──► UPDATE SQL
      │                         │                         │                   │  Payment.java:114 stampProcessedDate
      │                         │ find*/query             │                   │  AuditingEntityListener preUpdate (BaseEntity.java:44)
      │                         │  ───────► PostLoad      │                   └── OrderBusinessListener.java:46 enforceBusinessRules
      │                         │       BaseEntity.java:109│
      │    @PrePersist          │                          └── PreRemove fires before DELETE
      │  Order.java:198         │                             Customer.java:99  ──► must have no orders
      │  generateOrderNumber()  │ flush() → INSERT (order_number filled)
      ▼                         ▼                         ▼
   detached                 PersistenceContext            removed

Order INSERT (first persist):
  OrderService.placeOrder() → new Order(customer,PLACED,total)
                            → order.addItem(item)
                            → orders.save(order)           // persist schedules INSERT
                            → flush @ commit
                              └► Order.generateOrderNumber()  (Order.java:198) fills ORD-XXXXXXXXXX if null
                                 + AuditingEntityListener    (BaseEntity.java:44) fills createdAt/updatedBy
                                 + BaseEntity.version = 0/1  (BaseEntity.java:53)
                                 → INSERT INTO orders (order_number, created_at, ..., version) VALUES ('ORD-...', ...)

Payment UPDATE:
  payment.setStatus(PROCESSED)
                            → dirty at next flush
                              └► Payment.stampProcessedDate()  (Payment.java:114) fills paymentDate if null
                                 + AuditingEntityListener     updatedAt
                                 → UPDATE payments SET status=?, payment_date=?, updated_at=?, version=? WHERE id=? AND version=?
Customer DELETE attempt:
  em.remove(customer) → Customer.assertRemovalAllowed() (Customer.java:99)
                         if orders not empty → throw IllegalStateException → DELETE never issued
```
---
## 4. How it is implemented — file map
| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/Order.java` | `43` | `@EntityListeners(OrderBusinessListener)` | Listener + entity callbacks coexist |
| `Order.java` | `64` | `orderNumber` | `@Column(length=40)` — uniqueness in `04_add_order_number.sql` |
| `Order.java` | `198-204` | `generateOrderNumber()` | `@PrePersist`, `UUID` 10 hex, idempotent guard `null/blank` check |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43-44` | `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener)` | Auditing (PR #10) + lifecycle inherited |
| `BaseEntity.java` | `53` | `@Version version` | Incremented after `PreUpdate` callbacks (see §2.6) |
| `BaseEntity.java` | `59-73` | `createdAt/updatedAt/createdBy/updatedBy` | Filled by `AuditingEntityListener` at same flush as business callbacks |
| `BaseEntity.java` | `106-117` | `postLoadFired` + `onPostLoad()` | `@Transient`, `@PostLoad`, inherited flag |
| `src/main/java/com/company/orderapi/domain/Payment.java` | `114-119` | `stampProcessedDate()` | `@PreUpdate`, `PROCESSED && paymentDate==null` → `now()` |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `99-106` | `assertRemovalAllowed()` | `@PreRemove`, throws if `orders` non-empty |
| `Customer.java` | `67-70` | `orders LAZY + @BatchSize(20)` | Lazy collection accessed in callback (works inside tx) |
| `src/main/java/com/company/orderapi/domain/listener/OrderBusinessListener.java` | `39/46` | `@PostPersist` / `@PreUpdate` | Listener-side lifecycle (PR #18) — `postPersistCalls()` counter, `enforceBusinessRules` |
| `src/main/resources/db/changelog/v1.0/04_add_order_number.sql` | — | `order_number VARCHAR(40) UNIQUE` | DB constraint matching `Order.java:64` + callback |
| `src/main/resources/db/changelog/v1.0/03_add_audit_columns.sql` | — | `created_at/updated_at` | Audit columns as context (coexist at same flush) |
| `src/main/resources/application.yml` | `48` | `ddl-auto: validate` | Catches missing `order_number` or `@Transient` misuse |
```java
// Order.java:198 — @PrePersist business key (the canonical PR #13 example)
@PrePersist
void generateOrderNumber() {
    if (orderNumber == null || orderNumber.isBlank()) {
        orderNumber = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
// Payment.java:114 — @PreUpdate invariant
@PreUpdate
void stampProcessedDate() {
    if (status == PaymentStatus.PROCESSED && paymentDate == null) {
        paymentDate = LocalDateTime.now();
    }
}
// BaseEntity.java:106-113 — @Transient flag + @PostLoad (inherited by all entities)
@Transient
private boolean postLoadFired;
@PostLoad
void onPostLoad() {
    this.postLoadFired = true;
    log.debug("{}#{} loaded from the database", getClass().getSimpleName(), getId());
}
// Customer.java:99 — @PreRemove business guard
@PreRemove
void assertRemovalAllowed() {
    if (orders != null && !orders.isEmpty())
        throw new IllegalStateException("Customer " + getId() + " cannot be removed: still has " + orders.size() + " order(s)");
}
// OrderBusinessListener.java:39 — listener-side @PostPersist (PR #18, for comparison)
@PostPersist public void onOrderPersisted(Order order) { POST_PERSIST_CALLS.incrementAndGet(); }
```
---
## 5. How to use the feature — copy-paste, runnable against `develop`
```bash
# Run lifecycle-callback tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test

# Verify order_number column + unique constraint
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders" | grep order_number
# Expect: order_number | character varying(40) | ... (UNIQUE index)

psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT indexname, indexdef FROM pg_indexes WHERE tablename='orders' AND indexdef LIKE '%order_number%';"
# Expect: UNIQUE btree (order_number)

# Show order_number generated by @PrePersist
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT id, order_number, created_at FROM orders ORDER BY id LIMIT 5;"
# Expect: order_number LIKE 'ORD-__________' (10 hex)

# Prove generateOrderNumber is idempotent — set one explicitly, save, check it wasn't overwritten
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  INSERT INTO orders (customer_id, order_date, status, total_amount, version, created_at, updated_at, created_by, updated_by, order_number)
  VALUES (1, now(), 'PLACED', 10.00, 0, now(), now(), 'system', 'system', 'ORD-MANUAL-001')
  RETURNING order_number;
"
# Expect: ORD-MANUAL-001 (not mutated by @PrePersist because not null)

# Payment paymentDate stamped on PROCESSED (via @PreUpdate)
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT id, status, payment_date FROM payments LIMIT 5;"

# Health
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq '.[0].orderNumber // .content[0].orderNumber'
```

```java
// Proving @PostLoad — managed vs transient
Order transientOrder = new Order(customer, OrderStatus.PLACED, total);
assertThat(transientOrder.isPostLoadFired()).isFalse(); // BaseEntity.java:115 @Transient, not fired

Order loaded = orderRepository.findById(persistedId).orElseThrow();
assertThat(loaded.isPostLoadFired()).isTrue(); // BaseEntity.java:109 fired after SELECT

// Proving @PreRemove guard
Customer withOrders = customerRepository.findById(customerWithOrdersId).orElseThrow();
assertThatThrownBy(() -> {
    customerRepository.delete(withOrders);
    customerRepository.flush(); // triggers PreRemove → IllegalStateException
}).hasCauseInstanceOf(IllegalStateException.class);

// Proving @PrePersist idempotency
Order manual = new Order(customer, OrderStatus.PLACED, total);
// Reflect or setter if available: manual.setOrderNumber("ORD-TEST-0001");
orderRepository.saveAndFlush(manual);
assertThat(manual.getOrderNumber()).isEqualTo("ORD-TEST-0001"); // not overwritten

// Proving Payment @PreUpdate
Payment pending = new Payment(order, amount, PaymentMethod.CREDIT_CARD);
paymentRepository.save(pending); // paymentDate null
pending.setStatus(PaymentStatus.PROCESSED);
entityManager.flush(); // triggers Payment.stampProcessedDate → paymentDate now non-null
assertThat(pending.getPaymentDate()).isNotNull();
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `@PrePersist` on entity | `Order.java:198` `generateOrderNumber()` | Service-layer `setOrderNumber` | Every code path covered, no caller forgets; transactional — generated number rolls back with failed insert | Slight test friction: must flush to trigger it; idempotent guard needed for manual numbers |
| `UUID` 10 hex | `UUID.randomUUID().substring(0,10)` | DB `SEQUENCE` or Snowflake | Zero infra, collision ~2^40 space, adequate for order volume | Not monotonic — order-number order != creation order ( `created_at` `BaseEntity.java:60` is ordering) |
| `if (null/blank)` guard | `Order.java:200` | Always generate | Allows seed/tests to set deterministic numbers, e.g., `ORD-MANUAL-001` | One branch — negligible |
| `@PreUpdate` on `Payment` | `Payment.java:114` `stampProcessedDate` | Manual `payment.setPaymentDate(now())` in service | Invariant centralized — new payment paths (MCP ship/confirm tools) cannot forget | Callback is hidden control flow — developers must discover it in entity, not service |
| `@PostLoad` on `BaseEntity` | `BaseEntity.java:109` inherited | Per-subclass `@PostLoad` | One flag, every entity — consistent | Every load pays one boolean set — trivial |
| `@Transient` boolean | `BaseEntity.java:107` no column | `@Column` | In-memory proof only; `@Transient` avoids `validate` failure (`application.yml:48`) | Not persisted — cannot query `postLoadFired` |
| `@PreRemove` business guard | `Customer.java:99` throw if orders non-empty | DB `ON DELETE RESTRICT` FK | Domain error message (`Customer 5 cannot be removed: still has 3 orders`) clearer than FK violation; works for soft-delete paths too | Lazy collection must be accessible (within tx) |
| Listeners alongside callbacks | `OrderBusinessListener.java:39/46` for `PostPersist`/`PreUpdate` | All on entity | Listener handles cross-cutting `PostPersist` event publishing (PR #18 outbox-style), entity handles its own invariants — separation | Hibernate not a Spring bean → listener uses static `AtomicLong` for tests |

---

## 7. How to verify

```bash
# Full lifecycle tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "order_number\|postload"

# order_number generated — every row has one, unique
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT count(*) AS total, count(DISTINCT order_number) AS distinct_numbers FROM orders;
"
# Expect: total = distinct_numbers, both >0, and no NULLs: SELECT count(*) FROM orders WHERE order_number IS NULL → 0

# @PrePersist idempotency — insert with explicit order_number retains it
./mvnw test -Dtest=OrderServiceTest#shouldNotOverwriteManualOrderNumber

# @PreUpdate — transition payment PENDING → PROCESSED stamps date
./mvnw test -Dtest=PaymentRepositoryTest#shouldStampPaymentDateOnProcessed

# @PostLoad — loaded entity has postLoadFired==true
./mvnw test -Dtest=DatabaseSchemaIntegrationTest#shouldFirePostLoadOnFind

# @PreRemove — deleting customer with orders throws
./mvnw test -Dtest=CustomerRepositoryTest#shouldRefuseToDeleteCustomerWithOrders

# OrderBusinessListener PostPersist counter (PR #18 listener proof)
./mvnw test -Dtest=OrderBusinessListenerTest#shouldIncrementPostPersistCounter

# Liquibase migrations applied
./mvnw liquibase:status 2>&1 | grep -E "04_add_order_number|03_add_audit"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders" | grep -E "order_number|created_at|version"

## 8. How this helps you on the job — build / operate / interview

- **Build:** Put mandatory invariants in callbacks: `@PrePersist` `Order.java:198` for business keys, `@PreUpdate` `Payment.java:114` for state invariants, `@PreRemove` `Customer.java:99` for delete-guards, `@PostLoad`+`@Transient` `BaseEntity.java:107/109` for transient flags.
- **Operate:** `SELECT order_number IS NULL` should be 0; alert on `IllegalStateException("still has ... orders")` `Customer.java:99`; watch `postLoadFired` `BaseEntity.java:112` logs.
- **Interview:** "PR #13: `Order.java:198` `@PrePersist` `ORD-`+10 hex idempotent, `Payment.java:114` `@PreUpdate` `paymentDate`, `BaseEntity.java:109` `@PostLoad`+`@Transient:107` inherited, `Customer.java:99` `@PreRemove` guard; callbacks→auditing→`version:53` in one `UPDATE`."

---

## 9. Interview lens — Q&A

**Q1: Why `@PrePersist` vs service?** A: `Order.java:198` hooks every insert path transactionally; guard preserves manual `ORD-MANUAL-001`; see §2.2.
**Q2: Why `@Transient` on `postLoadFired`?** A: `BaseEntity.java:107` no column, `ddl-auto:validate:48` would fail on missing `post_load_fired` column (§2.4/2.7).
**Q3: How callbacks interact with auditing/version?** A: `AuditingEntityListener:44` → entity `@PreUpdate` → `version++:53` → one `UPDATE ... SET ...,updated_at=?,version=? WHERE id=? AND version=?` (§2.6).

---

## 10. Honest limits & next step → PR #14

Callbacks are hidden control flow; `UUID` order_number not monotonic; `PostLoad` fires on every load amplifying N+1 if heavy. `PreRemove` lazy `orders` needs active tx. Next: PR #14 `ddl-auto validate` `application.yml:48` vs `update`/`create`, Liquibase source of truth.

See [`14-schema-generation-and-validation.md`](./14-schema-generation-and-validation.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| Business key required on every INSERT | `@PrePersist` on entity | `Order.java:198` | Every path covered, idempotent guard |
| Invariant on state transition (PROCESSED → date) | `@PreUpdate` on entity | `Payment.java:114` | New paths can't forget |
| Delete guard (business not DB) | `@PreRemove` | `Customer.java:99` | Domain error, not FK violation |
| In-memory flag for DB-loaded vs transient | `@PostLoad` + `@Transient` | `BaseEntity.java:107/109` | No column, inherited |
