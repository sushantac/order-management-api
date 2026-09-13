# 09. Pessimistic Locking (PR #9)

> PR #9 — Pessimistic Locking with `SELECT FOR UPDATE` / `FOR SHARE`, deadlock handling, retry and lock-mode deep dive. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, `src/main/java/com/company/orderapi/...` + Liquibase. See `README.md:1772` roadmap `| 9 | Pessimistic Locking |`.

---

## 1. Purpose — what shipped

PR #9 delivers **Pessimistic Locking** as a first-class, tested, documented building block. Two repository methods in `ProductRepository.java:34/44` expose `@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE` and `@Lock(PESSIMISTIC_READ)` → `SELECT ... FOR SHARE` (PostgreSQL). `ProductInventoryService.java:22` wraps them in `@Transactional` services (`decrementStockPessimistic`, `peekStockPessimisticRead`, `moveStockPessimistic`), plus deadlock demonstration (`DeadlockLoserDataAccessException` + `@Retryable`) and a `holdMillis` blocking proof. Optimistic locking (PR #8 `@Version`) remains; pessimistic is the complementary strategy for hot rows where abort-and-retry is wasteful.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Only optimistic (`@Version` + `@Retryable` on `OrderService.java:77`). For a hot `Product(stock=1)` with 100 concurrent `placeOrder()` callers, optimistic retries burn 99 aborts + 99 retries — thundering herd, wasted DB round-trips, p99 latency spiked by retries. No way to say "I intend to write this row — let others wait" before decrementing. No shared-read lock for "peek stock and decide" without risking a write wedged in between.

**After:** `productRepository.findByIdForUpdate(id)` (`ProductRepository.java:34`) issues `SELECT ... FOR UPDATE` *inside* `ProductInventoryService.java:34` `@Transactional` — the row is exclusively locked until commit. Competing transactions *block* on the lock instead of aborting. `findByIdForShare` (`ProductRepository.java:44`) uses `FOR SHARE` — many readers can share the lock, writers block. `moveStockPessimistic` (`ProductInventoryService.java:95`) deliberately acquires two row locks in opposite orders to demonstrate the classic deadlock and shows `@Retryable(DeadlockLoserDataAccessException)` (`ProductInventoryService.java:91`) recovery.

### Theory — pessimistic locking from first principles (100+ lines)

#### 2.1 What "pessimistic" means

Optimistic: "conflicts are rare — read without locking, detect stale write at flush via `WHERE version=?`". Pessimistic: "conflicts are expected — lock the row *before* you read it, so no one else can change it until you commit". The lock is held for the duration of the transaction (from `SELECT FOR UPDATE` to `COMMIT/ROLLBACK`).

```
Optimistic (PR #8):                    Pessimistic (PR #9):
 read v5 ───────────► no lock          SELECT ... FOR UPDATE ──► row locked exclusively
 write WHERE v5 ──► 0 rows → abort     write (implicit version check) ──► must hold lock, so waits
 retry re-reads v6                      blocked thread resumes after holder commits
```

#### 2.2 PostgreSQL row-level locks — the lock matrix

PostgreSQL has four relevant row locks (from `pg_locks`, `FOR` syntax):

| SQL syntax | JPA `LockModeType` | PostgreSQL name | Conflicts with | Meaning |
|---|---|---|---|---|
| `FOR UPDATE` | `PESSIMISTIC_WRITE` | `ForUpdate` | `ForUpdate`, `ForShare`, `ForNoKeyUpdate` | Exclusive write lock — no reader or writer can acquire any `FOR` lock on this row |
| `FOR NO KEY UPDATE` | `PESSIMISTIC_WRITE` (variant) | `ForNoKeyUpdate` | `ForUpdate` | Weaker exclusive — allows `FOR SHARE` readers; Hibernate doesn't expose separately |
| `FOR SHARE` | `PESSIMISTIC_READ` | `ForShare` | `ForUpdate`, `ForNoKeyUpdate` | Shared read — multiple `FOR SHARE` holders allowed; writers (`FOR UPDATE`) block |
| `FOR KEY SHARE` | `PESSIMISTIC_READ` (variant) | `ForKeyShare` | `ForUpdate` only | Weakest shared — Hibernate maps to `FOR SHARE` |

In this repo:

```java
// ProductRepository.java:34 — exclusive, blocks everyone
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Product p where p.id = :id")
Optional<Product> findByIdForUpdate(Long id);
// SQL: SELECT ... FROM products WHERE id=? FOR UPDATE

// ProductRepository.java:44 — shared, blocks writers only
@Lock(LockModeType.PESSIMISTIC_READ)
@Query("select p from Product p where p.id = :id")
Optional<Product> findByIdForShare(Long id);
// SQL: SELECT ... FROM products WHERE id=? FOR SHARE
```

Key difference vs optimistic: the lock is taken *at SELECT time*, not at flush. By the time you `setStockQuantity(...)`, no one else holds a conflicting lock.

#### 2.3 Lock scope, duration and visibility

- **Scope:** row-level (not table). `SELECT ... FOR UPDATE WHERE id=1` only locks product `1`; product `2` is unaffected. Concurrency scales with row cardinality.
- **Duration:** from the `SELECT` to the *transaction end* (`commit`/`rollback`). In `ProductInventoryService.java:34` the `@Transactional` boundary owns the lock. Exiting the method commits and releases.
- **Isolation interaction:** at `READ COMMITTED` (the repo default, `application.yml:48`), a `FOR UPDATE` lock ensures your read is not overwritten before you write — you don't need `REPEATABLE READ`. At `SERIALIZABLE`, lock semantics change (conflicts become serialization failures instead of waits).
- **No extra column:** unlike optimistic's `version` (`BaseEntity.java:53`, `02_add_version_columns.sql`), pessimistic needs no schema change — locks live in PostgreSQL's `pg_locks` shared memory, visible via `SELECT * FROM pg_locks WHERE relation='products'::regclass`.

#### 2.4 `FOR SHARE` semantics and the read-only trap

`FOR SHARE` allows many readers to hold the lock simultaneously — useful for "peek and decide" reads:

```java
// ProductInventoryService.java:54 — peek without exclusive lock
@Transactional
public int peekStockPessimisticRead(Long productId) {
    Product p = productRepository.findByIdForShare(productId); // FOR SHARE
    return p.getStockQuantity(); // safe: no concurrent writer can sneak in
}
```

But PostgreSQL documents: **`FOR SHARE` refuses to run inside a `readOnly=true` transaction** (`cannot execute SELECT FOR SHARE in a read-only transaction`) because acquiring a lock is considered a write operation (it modifies `pg_locks`). So `peekStockPessimisticRead` is deliberately `@Transactional` (not `readOnly=true`) — see `ProductInventoryService.java:46` comment and `README.md:521`.

```
@Transactional(readOnly = true)  + SELECT FOR SHARE  → PostgreSQL ERROR
@Transactional                    + SELECT FOR SHARE  → OK (shared lock held to commit)
```

This surprises many developers who assume "I only read, so readOnly is fine". With pessimistic read locks, it is not.

#### 2.5 How Hibernate issues the lock

`@Lock(LockModeType.PESSIMISTIC_WRITE)` is applied by `QueryTranslator` → `LockOptions`. At execution:

1. Hibernate wraps the `SELECT` in `Dialect.getForUpdateString(LockOptions)` → PostgreSQL's `PostgreSQLDialect` appends ` for update` or ` for share`.
2. Optional `LockOptions.setTimeOut(LockOptions.WAIT_FOREVER)` is the default (block indefinitely). Variants `NO_WAIT` (`for update nowait`) and `SKIP_LOCKED` (`for update skip locked`) exist — not used in this PR but important for job queues.
3. The `PersistenceContext` marks the entity `LockMode.PESSIMISTIC_WRITE` so subsequent `lock()` calls in the same transaction don't re-issue SQL.

Lock timeout can be set per query: `@QueryHints(@QueryHint(name="jakarta.persistence.lock.timeout", value="1000"))` → PostgreSQL `FOR UPDATE NOWAIT` / `FOR UPDATE WAIT n`.

#### 2.6 `FOR UPDATE NOWAIT` vs `SKIP LOCKED` — alternatives not used here (but worth knowing)

| Mode | SQL | Behavior on locked row |
|---|---|---|
| default (`WAIT_FOREVER`) | `FOR UPDATE` | Block until holder commits (used by `ProductRepository.java:34`) |
| `NO_WAIT` | `FOR UPDATE NOWAIT` | Throw `PessimisticLockException` immediately (fail fast) |
| `SKIP LOCKED` | `FOR UPDATE SKIP LOCKED` | Silently skip locked rows — returns only unlocked rows (great for job polling `SELECT ... LIMIT 1 FOR UPDATE SKIP LOCKED`) |

These matter for queue-like workloads but not for the single-row stock decrement in this PR.

#### 2.7 Deadlock — the classic two-row lock-order inversion

`ProductInventoryService.java:95` demonstrates the canonical deadlock:

```
Thread A:  lock(1) FOR UPDATE → sleep 150ms → lock(2) FOR UPDATE
Thread B:  lock(2) FOR UPDATE → sleep 150ms → lock(1) FOR UPDATE

Timeline:
 T0  A locks 1    B locks 2
 T1  A waits for 2 (held by B)     B waits for 1 (held by A)  → circular wait → DEADLOCK
 T2  PostgreSQL deadlock detector fires (every ~1s, checks wait-for graph)
     → picks victim (usually the transaction that waited longest or did least work)
     → ERROR: deadlock detected → JDBC SQLException → Spring DeadlockLoserDataAccessException
     → victim rolls back → survivor proceeds
```

Spring Retry (`ProductInventoryService.java:91`) catches the victim and re-runs:

```java
@Transactional
@Retryable(retryFor = {DeadlockLoserDataAccessException.class, CannotAcquireLockException.class},
           maxAttempts = 5, backoff = @Backoff(delay = 150))
public void moveStockPessimistic(Long fromId, Long toId, int qty) {
    Product from = productRepository.findByIdForUpdate(fromId); // lock 1
    sleep(150); // widens window (demo only — never sleep in prod tx!)
    Product to   = productRepository.findByIdForUpdate(toId);   // lock 2 → may deadlock
}
```

Fix in production: **consistent lock ordering** — always `ORDER BY id ASC` when locking multiple rows (`SELECT ... WHERE id IN (1,2) ORDER BY id FOR UPDATE`) or lock via `SELECT ... FOR UPDATE` in id-sorted order in Java. The `sleep(150)` is test-only to make the deadlock deterministic.

#### 2.8 Blocking demo — `decrementStockHoldingLock`

`ProductInventoryService.java:67` holds the lock for `holdMillis` so a test can assert that a competing `findByIdForUpdate` really blocks:

```
Thread A:  findByIdForUpdate(1) → holds lock, sleep 500ms → decrement → commit (releases)
Thread B:  findByIdForUpdate(1) → BLOCKED for ~500ms → then reads fresh stock
```

Without pessimistic locking, B would not block — it would read the old `stock=10` concurrently and both would decrement to 9 (lost update, caught only by `@Version` much later). With pessimistic, B's `SELECT` blocks on `pg_locks` until A's commit — serialized, correct, no retry needed.

#### 2.9 When pessimistic vs optimistic — the full matrix (adds to PR #8 §2.7)

| Dimension | Optimistic (`@Version` in `BaseEntity.java:53`) | Pessimistic (`FOR UPDATE` in `ProductRepository.java:34`) |
|---|---|---|
| Lock held | None (detect at flush) | Row lock from `SELECT` to `COMMIT` |
| Under low contention | 1 query, almost no retries, fastest | Pays lock acquisition + hold time |
| Under high contention (flash sale) | Many aborts + retries → thundering herd | Queue is natural — no retries, p99 lower |
| Long-held user edit (minutes) | Ideal — no lock blocks others | Terrible — blocks row for minutes |
| Requires schema change? | Yes — `version` column (`02_add_version_columns.sql`) | No — `pg_locks` only |
| Deadlock risk? | No (no locks) | Yes — opposite lock orders → victim + retry (`ProductInventoryService.java:91`) |
| `FOR SHARE` read? | Not applicable | Yes — many readers, writers block (`ProductRepository.java:44`) |
| Works offline / detached? | Yes — version travels in DTO/ETag | No — lock requires live transaction |
| Combined? | ✔ Both can coexist: pessimistic lock prevents concurrent read, version still detects detached stale updates | — |

Rule: **optimistic by default**, pessimistic for hot rows / must-serialize / `FOR SHARE` peeks. They coexist — `OrderService.java:77` uses optimistic for `placeOrder`, `ProductInventoryService.java:34` uses pessimistic for inventory moves; both tables have `version` columns.

#### 2.10 Interview-ready mental model

> "Pessimistic locking locks the row *before* you read it: `SELECT ... FOR UPDATE` (`ProductRepository.java:34`, `PESSIMISTIC_WRITE`) takes an exclusive row lock held to commit, so competitors block instead of aborting — great for hot-stock contenders where optimistic retries waste work. `SELECT ... FOR SHARE` (`ProductRepository.java:44`, `PESSIMISTIC_READ`) allows many readers but blocks writers; it cannot run in a `readOnly` transaction because taking a lock is a write. Locks live in `pg_locks`, not in a `version` column (`BaseEntity.java:53` is for optimistic). Deadlocks happen when two transactions lock rows in opposite orders (`ProductInventoryService.java:95`); PostgreSQL picks a victim → `DeadlockLoserDataAccessException` → `@Retryable(maxAttempts=5)` re-runs the whole `@Transactional` method. Fix deadlocks with consistent `ORDER BY id` lock ordering. Pessimistic costs lock hold time; optimistic costs retries — choose based on contention."

---

## 3. Solution — ASCII

```
Optimistic (no lock):              Pessimistic FOR UPDATE (this PR):        FOR SHARE:

 TxA read v5                        TxA SELECT FOR UPDATE  → locks row      TxA SELECT FOR SHARE → shared lock
 TxB read v5                        TxB SELECT FOR UPDATE  → BLOCKS         TxB SELECT FOR SHARE → also holds (readers share)
 TxA update WHERE v5 → ok          TxA decrements, commits → releases       TxC SELECT FOR UPDATE → BLOCKS (writer waits)
 TxB update WHERE v5 → 0 rows      TxB wakes, reads fresh, decrements      Readers both commit → TxC proceeds
  → abort + @Retryable               → serialized, no abort needed             → FOR SHARE is for "peek safely"

 Deadlock (moveStockPessimistic:95):
  A: lock(1) → wait for 2          PostgreSQL wait-for graph detects cycle
  B: lock(2) → wait for 1  ──────► picks victim → DeadlockLoserDataAccessException
                                   → @Retryable retries victim → second try succeeds (rows now free)

 ProductRepository.java:34  @Lock(PESSIMISTIC_WRITE)  → FOR UPDATE (exclusive)
 ProductRepository.java:44  @Lock(PESSIMISTIC_READ)   → FOR SHARE  (shared, blocks writers)
 ProductInventoryService.java:91  @Retryable(deadlock) → retries victim 5×
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/repository/ProductRepository.java` | `34-36` | `findByIdForUpdate` | `@Lock(PESSIMISTIC_WRITE)` + `@Query` → `SELECT ... FOR UPDATE` |
| `ProductRepository.java` | `44-46` | `findByIdForShare` | `@Lock(PESSIMISTIC_READ)` → PostgreSQL `FOR SHARE` |
| `ProductRepository.java` | `20-24` | Javadoc on lock variants | Documents `FOR SHARE` vs `FOR UPDATE` choice |
| `src/main/java/com/company/orderapi/domain/service/ProductInventoryService.java` | `22` | Service header | Explains optimistic vs pessimistic choice |
| `ProductInventoryService.java` | `34-43` | `decrementStockPessimistic` | `findByIdForUpdate` → `setStockQuantity` → `catalogue.evict` inside `@Transactional` |
| `ProductInventoryService.java` | `54-58` | `peekStockPessimisticRead` | `findByIdForShare` shared read — comment on "NOT readOnly" trap |
| `ProductInventoryService.java` | `67-76` | `decrementStockHoldingLock` | Holds lock `sleep(holdMillis)` to prove blocking |
| `ProductInventoryService.java` | `91-109` | `moveStockPessimistic` | Two `FOR UPDATE` locks + `sleep(150)` → deadlock demo + `@Retryable(DeadlockLoser...)` |
| `ProductInventoryService.java` | `91` | `@Retryable` on deadlock | `retryFor={DeadlockLoserDataAccessException, CannotAcquireLockException}` `maxAttempts=5` |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `53` | `@Version` still present | Pessimistic supplements optimistic — version column still incremented under lock |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `77` | Optimistic retry | Complement — pessimistic path avoids this retry under high contention |
| `src/main/resources/application.yml` | `48` | `ddl-auto: validate` | No schema change — locks need no new column |
| `src/main/resources/db/changelog/v1.0/02_add_version_columns.sql` | — | Version columns | Pessimistic doesn't need them, but they coexist (version still bumps) |

```java
// ProductRepository.java:34 — exclusive write lock
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Product p where p.id = :id")
Optional<Product> findByIdForUpdate(@Param("id") Long id);

// ProductRepository.java:44 — shared read lock (PostgreSQL: FOR SHARE)
@Lock(LockModeType.PESSIMISTIC_READ)
@Query("select p from Product p where p.id = :id")
Optional<Product> findByIdForShare(@Param("id") Long id);

// ProductInventoryService.java:34 — pessimistic write inside a transaction
@Transactional
public void decrementStockPessimistic(Long productId, int quantity) {
    Product p = productRepository.findByIdForUpdate(productId).orElseThrow(...); // FOR UPDATE
    if (p.getStockQuantity() < quantity) throw new IllegalStateException(...);
    p.setStockQuantity(p.getStockQuantity() - quantity); // no extra SQL — entity is managed, flush does UPDATE ... WHERE version=?
}

// ProductInventoryService.java:91 — deadlock demo + retry
@Transactional
@Retryable(retryFor = {DeadlockLoserDataAccessException.class, CannotAcquireLockException.class},
           maxAttempts = 5, backoff = @Backoff(delay = 150))
public void moveStockPessimistic(Long fromId, Long toId, int quantity) { ... }
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run pessimistic-locking tests
./mvnw test -Dtest=LockingPerformanceComparisonTest,ProductInventoryServiceTest -Dspring.profiles.active=test

# Verify lock SQL is generated (FOR UPDATE / FOR SHARE)
./mvnw test -Dtest=ProductInventoryServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "for update\|for share"
# Expect: "select ... from products where id=? for update"  and  "for share"

# Smoke: call the pessimistic decrement via any integration test or manual call
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT id, stock_quantity, version FROM products LIMIT 3;"

# Blocking proof — run two concurrent decrements on same product (one should wait)
# (covered by ProductInventoryServiceTest: decrementStockHoldingLock blocks)

# Deadlock demo — run moveStock two ways concurrently (covered by dedicated test)
# Expect: one transaction gets DeadlockLoserDataAccessException, @Retryable re-runs and succeeds

# Observe pg_locks while a long lock is held (in psql during sleep)
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT locktype, database, relation::regclass, mode, granted, pid
  FROM pg_locks WHERE relation='products'::regclass;
"
# Expect rows with mode RowShareLock / RowExclusiveLock, granted=t/f

# Health and metrics (deadlock counter if instrumented)
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep -i "lock\|deadlock"
```

```java
// Choosing lock in application code
// Hot stock decrement — exclusive, serialize writers
productInventoryService.decrementStockPessimistic(productId, qty);

// Peek stock safely — shared lock lets multiple readers proceed, writers queue
int stock = productInventoryService.peekStockPessimisticRead(productId);

// Moving stock between products — must lock both rows in consistent order to avoid deadlock
// Fix: always sort ids ascending before locking
List<Long> ids = Stream.of(fromId, toId).sorted().toList();
for (Long id : ids) productRepository.findByIdForUpdate(id);
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `PESSIMISTIC_WRITE` for decrement | `ProductRepository.java:34` `FOR UPDATE` | Optimistic `@Version` only | Hot row: queue beats retry storm at flash-sale scale | Lock hold = transaction duration → must keep tx short |
| `PESSIMISTIC_READ` for peek | `ProductRepository.java:44` `FOR SHARE` | Plain `findById` (no lock) | "Read and decide" must not race a concurrent writer between read and decision | Slight — still acquires `pg_locks` shared lock |
| `@Transactional` (not readOnly) on peek | `ProductInventoryService.java:54` non-readOnly | `readOnly=true` | PostgreSQL forbids `FOR SHARE` inside readOnly → would error | Write-set is still read-only in practice; Hibernate still flushes check |
| `WAIT_FOREVER` default | `FOR UPDATE` blocks | `NOWAIT` or `SKIP LOCKED` | Simplicity — stock decrement should wait, not fail/skip | Contention can queue; long queue → latency |
| Deadlock retry | `@Retryable(DeadlockLoser...)` `ProductInventoryService.java:91` | No retry, propagate error | Victim can safely re-run from scratch reading fresh locks | Max 5 attempts × 150ms backoff → up to 600ms extra |
| Sleep in `moveStockPessimistic` | `sleep(150)` `ProductInventoryService.java:98` demo only | No sleep | Widens race window so deadlock is deterministic in test | Never in prod — real code should minimize lock hold, not extend it |
| Keep `@Version` alongside pessimistic | `BaseEntity.java:53` still present | Remove version | Version still catches stale detached updates even when pessimistic isn't used; low cost | One `BIGINT` per row, version still incremented on flush |

---

## 7. How to verify

```bash
# Run the full suite proving pessimistic + deadlock + blocking
./mvnw test -Dtest=ProductInventoryServiceTest,LockingPerformanceComparisonTest

# Prove FOR UPDATE SQL is issued
./mvnw test -Dtest=ProductInventoryServiceTest -Dorg.hibernate.SQL=DEBUG -Dorg.hibernate.orm.jdbc.bind=TRACE 2>&1 \
  | grep -E "select.*products.*for (update|share)"

# Prove blocking: second FOR UPDATE waits until first commits
# (test uses decrementStockHoldingLock: thread A sleeps 500ms, thread B blocked ~500ms)
./mvnw test -Dtest=ProductInventoryServiceTest#shouldBlockSecondForUpdateUntilFirstCommits

# Prove deadlock detection + retry: opposite-order moveStock eventually succeeds
./mvnw test -Dtest=ProductInventoryServiceTest#shouldRetryDeadlockVictim

# Negative: FOR SHARE in readOnly transaction must fail (PostgreSQL rule)
# Try: @Transactional(readOnly=true) + findByIdForShare → expect PSQLException "cannot execute FOR SHARE in read-only tx"

# Live pg_locks inspection (run while a long lock is held)
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT pid, mode, granted FROM pg_locks WHERE relation='products'::regclass;
"

# Schema unchanged — no new columns for pessimistic
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d products" | grep -i "version\|lock"
# Expect: only 'version' from PR #8, no lock column (locks are in pg_locks memory)

# Health
curl -s http://localhost:8080/actuator/health | jq .components.db
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** For hot-row writes (`stock`, `balance`, `quota`) prefer `findByIdForUpdate` (`ProductRepository.java:34`) inside a short `@Transactional` (`ProductInventoryService.java:34`). For shared reads that must not race a writer, use `findByIdForShare` (`ProductRepository.java:44`) and remember it must NOT be `readOnly=true`. When locking multiple rows, sort ids ascending before issuing `SELECT FOR UPDATE` to avoid deadlocks; or `SELECT ... WHERE id IN (:ids) ORDER BY id FOR UPDATE`. Wrap multi-row moves with `@Retryable(DeadlockLoser...)` (`ProductInventoryService.java:91`) as a safety net, but fix ordering first — retry is a bandage.
- **Operate:** Monitor `pg_stat_activity.wait_event_type = 'Lock'` and `pg_locks` for long-held `FOR UPDATE` — a slow `@Transactional` method holding a lock starves writers. Set `statement_timeout` / `lock_timeout` per service account so blocked `FOR UPDATE` doesn't hang forever. Track `DeadlockLoserDataAccessException` rate — occasional is normal, rising means lock-order bug. Use `EXPLAIN (ANALYZE, BUFFERS) SELECT ... FOR UPDATE` — it still needs `idx_products_id` (primary key index).
- **Interview:** "PR #9: `ProductRepository.java:34` `PESSIMISTIC_WRITE` → `SELECT ... FOR UPDATE` (exclusive, blocks readers+writers to commit) and `ProductRepository.java:44` `PESSIMISTIC_READ` → `FOR SHARE` (shared, blocks only writers; cannot be `readOnly`). `ProductInventoryService.java:34/54/95` shows decrement (exclusive), peek (shared), and deadlock demo (`DeadlockLoserDataAccessException` + `@Retryable(maxAttempts=5)`). Locks live in `pg_locks`, not a column (`BaseEntity.java:53` version is for optimistic; pessimistic needs no schema change). Always lock multiple rows in consistent `ORDER BY id` order to avoid deadlock. Use pessimistic for hot rows where optimistic retries waste work."

---

## 9. Interview lens — Q&A

**Q1: Difference between `FOR UPDATE` and `FOR SHARE`?**
A: `FOR UPDATE` (`PESSIMISTIC_WRITE`, `ProductRepository.java:34`) exclusive — no other `FOR` lock may coexist. `FOR SHARE` (`PESSIMISTIC_READ`, `ProductRepository.java:44`) shared — multiple holders allowed, only writers (`FOR UPDATE`) block. Use `FOR UPDATE` when you will write the row; `FOR SHARE` when you only peek and decide.

**Q2: Why can't `FOR SHARE` be `readOnly=true`?**
A: PostgreSQL treats acquiring a lock as a write to `pg_locks`, so it rejects `FOR SHARE` inside `readOnly` transactions (`cannot execute SELECT FOR SHARE in a read-only transaction`). Hence `ProductInventoryService.java:54` is plain `@Transactional` — see `README.md:521`.

**Q3: What is a deadlock in this context and how is it recovered?**
A: `ProductInventoryService.java:95` locks `(A then B)` while a concurrent transaction locks `(B then A)` → circular wait → PostgreSQL aborts victim → `DeadlockLoserDataAccessException` → `@Retryable(maxAttempts=5, backoff=150ms)` (`ProductInventoryService.java:91`) re-runs the victim in a fresh transaction. Fix root cause with ascending `ORDER BY id` lock order.

**Q4: How do you prove pessimistic actually blocks?**
A: `ProductInventoryService.java:67` `decrementStockHoldingLock` sleeps `holdMillis` while holding `FOR UPDATE`; test measures that second `findByIdForUpdate` wait time ≈ `holdMillis` — proves blocking via `pg_locks`, not stale-version check.

**Q5: When choose pessimistic over optimistic?**
A: High contention hot rows (flash sale) where retries cause thundering herd — pessimistic queues instead. Short, contended stock decrements. Not for long user-think edits (minutes) — optimistic via `ETag`/`If-Match` (`BaseEntity.java:83`) is better. See §2.9 matrix and [`08-optimistic-locking.md`](./08-optimistic-locking.md).

**Q6: Do you need `version` column when using `FOR UPDATE`?**
A: Not strictly — pessimistic doesn't check `version` to detect conflicts. But `BaseEntity.java:53` `@Version` remains; under `FOR UPDATE` Hibernate still increments `version` on flush (`02_add_version_columns.sql`), catching any stale detached updates that bypass `FOR UPDATE`. Low cost, complementary safety.

---

## 10. Honest limits & next step → PR #10

Pessimistic locking serializes — throughput on a single hot product is bounded by "one transaction's lock hold time" (DB round-trips + business logic inside `@Transactional`). A slow downstream call (e.g., `paymentGateway.charge()` inside the same `FOR UPDATE` transaction) amplifies contention. It also doesn't travel offline: detached edits (mobile form open for hours) cannot hold a DB lock. Next, PR #10 adds auditing — every row records `createdAt/updatedAt/createdBy/updatedBy` (`BaseEntity.java:59-73`) via `AuditingEntityListener` and `AuditorAware` (`JpaAuditingConfig.java:29`) — orthogonal to locking but essential for tracing who touched which version of which row when.

See [`10-auditing.md`](./10-auditing.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| Hot stock decrement (flash sale) | `FOR UPDATE` | `ProductRepository.java:34` + `ProductInventoryService.java:34` | Queue beats retry storm |
| Peek stock safely | `FOR SHARE` | `ProductRepository.java:44` + `ProductInventoryService.java:54` | Many readers, writers block; not readOnly |
| Two-row move | Sorted locks + retry | `ProductInventoryService.java:95` (demo) | Ascending id order avoids deadlock; retry is backup |
| Single reader no contention | Plain `findById` | `ProductRepository.java` `JpaRepository` | No lock overhead |
| Offline edit (ETag) | Optimistic `@Version` | `BaseEntity.java:53` + `83` | Version travels in DTO, checked on write |
| Long user-think | Optimistic, not pessimistic | `BaseEntity.java:53` | Minutes-long lock would block everyone |
