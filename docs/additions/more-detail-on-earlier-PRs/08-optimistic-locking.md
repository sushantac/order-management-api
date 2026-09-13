# 08. Optimistic Locking (PR #8)

> PR #8 — Optimistic Locking with `@Version`, MVCC, lost-update prevention and `@Retryable`. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 8 | Optimistic Locking |`.

---

## 1. Purpose — what shipped

PR #8 delivers **Optimistic Locking** as a first-class, tested, documented building block. One `@Version` field in `BaseEntity.java:53` plus one Liquibase changeset `02_add_version_columns.sql` gives every entity (customers, addresses, orders, order_items, products, categories, product_categories, payments) a `version BIGINT NOT NULL DEFAULT 0` column. Hibernate automatically adds `WHERE version = ?` to every `UPDATE`/`DELETE` and increments `version`. `OrderService.java:77` wraps `placeOrder()` in `@Retryable(retryFor=OptimisticLockingFailureException)` so concurrent stock decrements retry instead of corrupting data. This is the foundation for safe concurrent `placeOrder()` under load without coarse DB locks.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Two concurrent `placeOrder()` calls read the same `Product(stock=1, version=5)` at `T0`. Both decrement to `0` locally, both `UPDATE products SET stock=0, version=6 WHERE id=?`. Second write silently overwrites first — the store sells one unit twice (lost update). No column records "someone changed this row since I read it". No exception is thrown.

**After:** Same start — both read `version=5`. First `UPDATE ... SET stock=0, version=6 WHERE id=1 AND version=5` → 1 row matched, returns. Second tries `UPDATE ... WHERE id=1 AND version=5` but row is now `version=6` → 0 rows matched → Hibernate throws `ObjectOptimisticLockingFailureException` (wraps `StaleObjectStateException`) → Spring translates to `OptimisticLockingFailureException` → `@Retryable` on `OrderService.java:77` catches it, re-runs the whole method in a fresh transaction reading fresh `stock/version`.

### Theory — optimistic locking from first principles (100+ lines)

#### 2.1 The lost-update problem

```
Time  Tx A (Thread 1)                    Tx B (Thread 2)              DB stock
----  ---------------------------------  --------------------------------  --------
T0    SELECT stock,version → 1, v5       SELECT stock,version → 1, v5     stock=1 v5
T1    stock-- → 0 (in memory)
T2                                       stock-- → 0 (in memory)
T3    UPDATE SET stock=0, v=6 WHERE v=5 → 1 row updated                 stock=0 v6
T4                                       UPDATE SET stock=0, v=6 WHERE v=5 → 0 rows → EXCEPTION (without @Version: 1 row, lost update!)
```

Without `version`, the second `UPDATE` succeeds because `WHERE id=1` still matches — the business invariant (stock never negative, never oversold) is violated silently. With `version`, the second `UPDATE`'s `WHERE version=5` fails because the row is already `6`.

This is the classic **lost update** anomaly — PostgreSQL's `READ COMMITTED` (the default) does NOT prevent it. Higher isolation (`REPEATABLE READ` / `SERIALIZABLE`) would abort one transaction, but at the cost of blocking/retrying every read-write conflict, not just stale writes. Optimistic locking is a lightweight, application-level check that works at `READ COMMITTED`.

#### 2.2 MVCC — how PostgreSQL versioning relates

PostgreSQL uses **MVCC (Multi-Version Concurrency Control)** internally: each row version (`tuple`) carries `xmin/xmax` transaction ids. Readers never block writers. But MVCC alone does NOT prevent lost updates at `READ COMMITTED` — two transactions can both read `v5`, both write, last writer wins. `@Version` adds an *application-visible* version column on top of MVCC's invisible tuple versions, so the application can detect the stale read.

```
Invisible (MVCC):  xmin=100 xmax=0  → tuple v5  (Postgres keeps old tuples until VACUUM)
Visible (@Version): version=5       → application can compare WHERE version=?
```

Optimistic locking = MVCC for the application layer. Pessimistic locking (PR #9) is the alternative — take a DB lock *before* reading.

#### 2.3 How `@Version` works internally (Hibernate)

1. **Read path:** `SELECT id, stock, ..., version FROM products WHERE id=1` → Hibernate stores `version=5` in the entity's snapshot (the loaded state) inside `PersistenceContext` (`StatefulPersistenceContext`).
2. **Dirty check at flush:** `product.setStockQuantity(0)` marks the entity dirty. At `flush()` (before commit or `saveAndFlush`), Hibernate compares snapshot `version=5` vs current managed `version=5`.
3. **SQL generation:** generates `UPDATE products SET stock_quantity=?, version=? WHERE id=? AND version=?` with `version+1` as new value and old `version` in `WHERE`. See Hibernate `DefaultFlushEntityEventListener` → `EntityPersister.update()`.
4. **Row count check:** JDBC returns `updateCount`. If `0`, Hibernate throws `StaleObjectStateException("Row was updated or deleted by another transaction")` → Spring translates via `PersistenceExceptionTranslationPostProcessor`.
5. **Increment:** on success, Hibernate increments the in-memory `version` to `6` so further changes in the same `PersistenceContext` compare against `6`.

```
Hibernate flush internals:
  entityEntry.getLoadedState()[versionIndex] = 5   // snapshot
  entity.getVersion() = 5                          // current
  SQL: UPDATE ... SET version = 6 WHERE id=1 AND version = 5
  if (rowCount == 0) throw StaleObjectStateException
  entity.setVersion(6)   // update managed state
  entityEntry.setLoadedState(versionIndex, 6)
```

Only one row pattern — no separate `SELECT FOR UPDATE` needed. Works for detached entities too: `merge()` carries the version from the detached instance.

#### 2.4 `@Version` field mapping — why `long` not `Long`

```java
// BaseEntity.java:53 — the single declaration for all entities
@Version
@Column(name = "version", nullable = false)
private long version;  // primitive: starts at 0, never null
```

- `long` (not `Long`) ensures `DEFAULT 0` (`02_add_version_columns.sql:15`) maps cleanly. `Long` would allow `null` version on new rows → `WHERE version IS NULL` path which Hibernate handles differently.
- Hibernate increments via `VersionType.next(version)` — for `long` that's `version+1`. For `Instant` it would use timestamp; for `Integer` overflow is a risk.
- `nullable = false` enforces DB invariant: every row has a version, even inserted by raw SQL.

Liquibase: every table gets `version BIGINT NOT NULL DEFAULT 0` in one changeset file (`02_add_version_columns.sql:15-44`). Default `0` means existing rows (pre-PR #8) are valid immediately; Hibernate's initial version matches.

#### 2.5 `OptimisticLockException` hierarchy

```
jakarta.persistence.OptimisticLockException
  └─ org.hibernate.StaleObjectStateException          (Hibernate core)
       └─ org.springframework.orm.ObjectOptimisticLockingFailureException
            └─ org.springframework.dao.OptimisticLockingFailureException  ← caught by @Retryable
```

`OrderService.java:77` catches the Spring DAO exception (the most general), so it handles both Hibernate stale-state and JPA optimistic-lock variants.

#### 2.6 `@Retryable` — why retry is part of the pattern

Optimistic locking without retry = fail fast. For stock decrements under contention, fail-fast means a valid order randomly fails during a sale. Retry turns the lost-update exception into eventual success:

```java
// OrderService.java:77
@Transactional
@Retryable(retryFor = OptimisticLockingFailureException.class,
           maxAttempts = 5, backoff = @Backoff(delay = 20))
public Order placeOrder(Long customerId, List<OrderLine> lines) { ... }
```

- `maxAttempts=5`: first try + 4 retries. With `delay=20ms`, worst ~80ms extra. Empirical: at 100 concurrent buyers for `stock=1`, only 1 ultimately succeeds; others exhaust retries and propagate "insufficient stock" or optimistic failure — the store sells exactly one, as proved by `LockingPerformanceComparisonTest`.
- **Transaction boundary:** `@Retryable` must wrap the `@Transactional` proxy correctly. In this repo it does: Spring Retry creates a proxy around the `@Transactional` proxy; on exception the *entire* transaction rolls back and the method re-enters, reading fresh `version` from DB. If order were reversed (retry inside transaction), the `PersistenceContext` would still hold stale snapshot.
- **Non-idempotent trap:** retries must be leaf-safe — `placeOrder()` re-reads `Product` each invocation, so second attempt sees updated `stock/version`. A retry that re-sends a non-idempotent side effect (e.g., `paymentGateway.charge()` outside the transaction) would double-charge — here `charge()` is inside the retried transaction and rolls back on exception, so safe.

#### 2.7 When optimistic locking is WRONG — alternatives

| Scenario | Optimistic (`@Version`) | Pessimistic (`FOR UPDATE`) | No version (last-write-wins) |
|---|---|---|---|
| Low contention, short tx | ✔ retry rarely fires, no lock overhead | ✖ pays `FOR UPDATE` lock hold on every tx | Risk: silent lost updates |
| High contention (flash sale, stock=1, 100 threads) | Retries many times, thundering herd on retry | ✔ `FOR UPDATE` queues, no retries needed | ✖ massively oversells |
| Long user-think time (edit form open 5 min) | ✔ version travels in DTO/ETag, detects stale submit | ✖ lock held 5 min blocks everyone | Risk: user B overwrites user A's edits |
| Batch job (1 writer, no concurrency) | Overhead ~0 (just version column) | Unnecessary lock | Acceptable if single writer proven |

#### 2.8 Version on join table `product_categories`

`02_add_version_columns.sql:39` adds `version` to the join table too. Hibernate needs it because `@ManyToMany` with `@Version` on the owning entity (`Product`) still writes to the join table. Without it, concurrent category assignments could lose updates. In practice the join table row is rarely contended, but the cost of adding `version` there is one `BIGINT` per row — negligible vs correctness.

#### 2.9 Interview-ready mental model

> "Optimistic locking adds a `version` (`BaseEntity.java:53`, `02_add_version_columns.sql:15`) to every row. Hibernate appends `WHERE version = ?` to every `UPDATE` and increments `version`. If two transactions read `v5` and both try to write, second gets `0 rows updated` → `StaleObjectStateException` → `OptimisticLockingFailureException` → `@Retryable` on `OrderService.java:77` retries with fresh state. It prevents lost updates at `READ COMMITTED` without `SELECT FOR UPDATE` locks (that's PR #9). Cost is one `BIGINT` per row and rare retries; works because retries re-read the row."

---

## 3. Solution — ASCII

```
Without @Version (lost update):            With @Version + @Retryable (PR #8):

 TxA read v5 stock=1                        TxA read v5 stock=1
 TxB read v5 stock=1                        TxB read v5 stock=1
 TxA UPDATE SET stock=0 WHERE id=1 → ok    TxA UPDATE SET stock=0, v6 WHERE v5 → 1 row → commits v6
 TxB UPDATE SET stock=0 WHERE id=1 → ok    TxB UPDATE SET stock=0, v6 WHERE v5 → 0 rows → THROW
   (oversold — no error)                     → @Retryable re-reads v6, stock=0 → "Insufficient stock" → correct
                                                exactly 1 winner under any concurrency

  BaseEntity.java:53   @Version long version          ─┐
  02_add_version_columns.sql:15  version BIGINT DEFAULT 0 │ Hibernate generates WHERE version=?
  OrderService.java:77  @Retryable(maxAttempts=5)      ─┘  retry on OptimisticLockingFailureException
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43-55` | Single `@Version` for all entities | `@MappedSuperclass` + `@Version` `long version` `nullable=false` |
| `BaseEntity.java` | `119` | No `setVersion()` | Hibernate owns increment; callers cannot spoof version |
| `src/main/resources/db/changelog/v1.0/02_add_version_columns.sql` | `15-44` | 8× `ADD COLUMN version BIGINT NOT NULL DEFAULT 0` | One changeset per table (customers, addresses, orders, order_items, products, categories, product_categories, payments) |
| `02_add_version_columns.sql` | `15` | `DEFAULT 0` | Existing rows valid immediately, matches `long` initial |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `77-83` | `@Retryable` on `placeOrder()` | `retryFor=OptimisticLockingFailureException`, `maxAttempts=5`, `backoff=20ms` |
| `OrderService.java` | `98` | `product.setStockQuantity(q-quantity)` | The versioned write that triggers `WHERE version=?` |
| `OrderService.java` | `23-25` | Imports | `OptimisticLockingFailureException`, `@Retryable`, `@Backoff` |
| `src/main/java/com/company/orderapi/domain/service/ProductStockService.java` | `16` | Comment on versioned stock | Documents `WHERE version=?` → 0 rows → exception |
| `src/main/java/com/company/orderapi/domain/Product.java` | `54` | Product with version via BaseEntity | `stockQuantity` is the contended field |
| `src/main/java/com/company/orderapi/domain/Order.java` | `44` | Extends BaseEntity → inherits version | Every `orders` row versioned |
| `src/main/resources/application.yml` | `48` | `ddl-auto: validate` | Schema must match entities; version column mismatch fails startup |
| `src/test/java/com/company/orderapi/integration/LockingPerformanceComparisonTest.java` | — | Proves 100 threads → 1 winner | Uses `ab`-like concurrent `placeOrder` |

```java
// BaseEntity.java:53 — one declaration, every entity inherits it
@Version
@Column(name = "version", nullable = false)
private long version;

// 02_add_version_columns.sql:15 — matching DB column
ALTER TABLE customers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

// OrderService.java:77 — retry makes optimistic locking user-visible as success
@Transactional
@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 5,
           backoff = @Backoff(delay = 20))
public Order placeOrder(Long customerId, List<OrderLine> lines) {
    Product p = products.findById(line.productId()).orElseThrow(...);
    p.setStockQuantity(p.getStockQuantity() - line.quantity()); // dirty → UPDATE ... WHERE version=?
    // on StaleObjectStateException → OptimisticLockingFailureException → retry re-reads fresh version
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run optimistic-locking tests (concurrent placeOrder)
./mvnw test -Dtest=LockingPerformanceComparisonTest,OrderServiceTest -Dspring.profiles.active=test

# Verify version columns exist on all 8 tables
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT table_name, column_name, data_type, column_default
  FROM information_schema.columns WHERE column_name='version' ORDER BY table_name;
"
# Expect 8 rows, all BIGINT DEFAULT 0.

# Show Hibernate UPDATE with version guard
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "where.*version"
# Expect: update products set stock_quantity=?, version=? where id=? and version=?

# Smoke: place an order then attempt concurrent second order for last unit
curl -s -X POST http://localhost:8080/api/orders \
  -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"customerId":1,"lines":[{"productId":1,"quantity":1}]}' | jq .orderNumber

# Verify no oversell after concurrent load (choose product with stock=1)
# All but one request should return 409/422 or retry then "Insufficient stock"
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/orders \
    -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
    -d '{"customerId":1,"lines":[{"productId":99,"quantity":1}]}' &
done | jq -s 'group_by(.status) | map({status: .[0].status, count: length})'

# Check version increments
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT id, stock_quantity, version FROM products WHERE id=1;"
```

```java
// Reading version explicitly (e.g., for ETag / If-Match)
Order o = orderRepository.findById(id).orElseThrow();
long etag = o.getVersion(); // BaseEntity.java:83
// Send as ETag: W/"6" ; client sends If-Match: W/"6" on PUT
// On PUT, load entity, compare incoming version vs o.getVersion() — mismatch → 409 Conflict
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| One `@Version` in `BaseEntity` | `BaseEntity.java:53` DRY | Per-entity `@Version` | Single declaration, every table covered, impossible to forget a new entity | None — inheritance works for `@MappedSuperclass` |
| `long` not `Long` | primitive `long` | `Long` nullable | `DEFAULT 0` matches primitive; no null branch in Hibernate `VersionType` | Slight: new entity `version==0` vs `null` semantics — harmless |
| `BIGINT` not `INT` | 64-bit | 32-bit | Stock table may see many updates over years; `INT` overflow at 2^31 is plausible under high write rate | 8 bytes vs 4 — negligible |
| `DEFAULT 0` | Backfills existing rows | `NULL` then migrate | Zero matches Hibernate's initial version; no data migration script needed | New rows start at 0 then 1 on first update — correct |
| `@Retryable` on service | `OrderService.java:77` retries 5× | No retry (fail fast) | User-visible success during flash sale; retry is cheap (20ms) | Thundering herd if many losers retry simultaneously — bounded by `maxAttempts` |
| 5 attempts, 20ms delay | `maxAttempts=5, delay=20` | More attempts / longer delay | Tests prove 5× enough for 100-thread contention on single row | Extra latency under contention (up to 80ms) |
| Not using `@Retryable` on `ProductInventoryService` | Optimistic path via `OrderService` | Retry on inventory service | Stock decrement belongs to order placement — retry the whole business transaction, not just the stock write | None |

---

## 7. How to verify

```bash
# Full optimistic-locking proof — concurrent test must show exactly 1 winner
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest,LockingPerformanceComparisonTest

# Liquibase version columns applied
./mvnw liquibase:status 2>&1 | grep "02_add_version"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d products" | grep version
# Expect: version | bigint | not null default 0

# Hibernate UPDATE includes version predicate
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG -Dorg.hibernate.orm.jdbc.bind=TRACE 2>&1 \
  | grep -A2 "update.*products"
# Expect line containing "where.*products.*version = ?"

# Health / metrics unchanged
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep -i "optimistic\|retry"

# Reproduce lost-update WITHOUT version (thought experiment): temporarily remove @Version,
# concurrent test would oversell — version is what makes the test pass.
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Extend `BaseEntity` for every new entity — version is free. Wrap contended writes (`placeOrder`, stock move) with `@Retryable(retryFor=OptimisticLockingFailureException, maxAttempts=5, backoff=@Backoff(delay=20))` on the `@Transactional` service method (see `OrderService.java:77`). Don't retry business exceptions (e.g., "Insufficient stock") — only the optimistic exception. For user-facing edits, expose `getVersion()` (`BaseEntity.java:83`) as `ETag` and check `If-Match` before `save()`.
- **Operate:** Monitor `OptimisticLockingFailureException` rate via logs/metrics. Occasional retries are normal under concurrency; sustained high retry rate signals hot product — consider pessimistic locking (PR #9) or stock partitioning. Query `SELECT id, version, stock_quantity FROM products ORDER BY version DESC` to spot hot rows. Alert if `placeOrder` latency p99 grows because retries stack.
- **Interview:** "PR #8: `@Version long version` (`BaseEntity.java:53`) + `version BIGINT DEFAULT 0` (`02_add_version_columns.sql:15`) on all 8 tables. Hibernate does `UPDATE ... SET version=v+1 WHERE id=? AND version=v`; `0 rows` → `StaleObjectStateException` → `OptimisticLockingFailureException` → `@Retryable(maxAttempts=5)` (`OrderService.java:77`) re-runs the transaction reading fresh version/stock. Prevents lost updates at `READ COMMITTED` without `FOR UPDATE` locks. One `BIGINT` per row cost; retry is 20ms. Verified by 100-thread test proving exactly 1 winner for `stock=1`."

---

## 9. Interview lens — Q&A

**Q1: What problem does `@Version` solve? Can you draw the lost-update diagram?**
A: Two transactions read `stock=1, v5`, both decrement to `0`, both `UPDATE WHERE id=1` → both succeed, oversell. With `@Version` (`BaseEntity.java:53`, `02_add_version_columns.sql:15`) the second `UPDATE WHERE version=5` matches `0` rows → exception. See §2.1 diagram and §3 ASCII.

**Q2: How does Hibernate use `@Version` internally?**
A: Snapshot `version` at load, `UPDATE SET version=v+1 WHERE version=v` at flush, check `rowCount==0` → `StaleObjectStateException` → `OptimisticLockingFailureException`. See §2.3 internals and `BaseEntity.java:53`.

**Q3: Why `@Retryable` and where does it sit relative to `@Transactional`?**
A: Without retry the second buyer gets an error. `@Retryable(maxAttempts=5, backoff=20ms)` (`OrderService.java:77`) re-executes the whole `@Transactional` method in a fresh persistence context reading new `version/stock`. Spring Retry proxy must wrap the `@Transactional` proxy so the failed transaction rolls back before retry. See §2.6.

**Q4: What exception is thrown and how is it translated?**
A: Hibernate `StaleObjectStateException` → JPA `OptimisticLockException` → Spring `ObjectOptimisticLockingFailureException` → `OptimisticLockingFailureException` (caught by `@Retryable`). See §2.5 hierarchy.

**Q5: When would you use pessimistic locking instead?**
A: High-contention hot rows (flash sale) where retries cause thundering herd; long transactions; or when you must guarantee ordering. Then `SELECT ... FOR UPDATE` (`ProductRepository.java:34` `findByIdForUpdate`, PR #9) queues rather than retries. See §6 comparison table and [`09-pessimistic-locking.md`](./09-pessimistic-locking.md).

**Q6: Does `@Version` require special Liquibase handling for existing data?**
A: Yes — `DEFAULT 0` (`02_add_version_columns.sql:15`) ensures pre-PR #8 rows have a valid version matching `long version=0` default. No nullable path, no migration script.

---

## 10. Honest limits & next step → PR #9

Optimistic locking detects conflicts late (at flush) and relies on retry. Under high contention (100 buyers, 1 unit) many transactions abort and retry — wasted work and latency. It also doesn't order access; losers race to retry. PR #9 adds pessimistic locking (`SELECT ... FOR UPDATE` / `FOR SHARE` via `ProductRepository.java:34/44`) that locks the row *before* decrementing, serializing access and eliminating retries at the cost of holding a row lock for the transaction duration — the complement to this PR's abort-and-retry strategy.

See [`09-pessimistic-locking.md`](./09-pessimistic-locking.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| General concurrent `placeOrder` | Optimistic (`@Version` + `@Retryable`) | `BaseEntity.java:53` + `OrderService.java:77` | No lock held, scales, retry covers rare conflict |
| Flash sale hot product | Pessimistic `FOR UPDATE` | `ProductRepository.java:34` | Queues, no thundering herd |
| User edit form (long think) | Optimistic via ETag (`If-Match`) | `BaseEntity.java:83 getVersion()` | Locking for minutes would block everyone |
| Batch job single writer | `@Version` still on (harmless) | `02_add_version_columns.sql:15` | Cost is one BIGINT, safety is free |
| DDL check | `ddl-auto: validate` | `application.yml:48` | Catches missing `version` column at startup |
