# 07. Batch Fetching (PR #7)

> PR #7 — Batch Fetching. Stack: Java 21, Spring Boot, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers + Hibernate 6.6. See `README.md:1772` roadmap `| 7 | Batch Fetching |`.

---

## 1. Purpose — what shipped

PR #7 delivers **Batch Fetching** as a first-class, tested, documented building block. It fixes remaining N+1 without requiring a `JOIN FETCH`/`@EntityGraph` per query, via two knobs: global `hibernate.default_batch_fetch_size: 20` (`application.yml:61`) and per-collection `@BatchSize(size=20)` (`Order.java:84` `items`, `Customer.java:69` `orders`). When a lazy association is first accessed, Hibernate loads that *and* up to `batchSize-1` other owners' associations in one `IN (...)` query. Tests assert `findAll()` + loop drops from N+1 (51) to `1 + ceil(N/batchSize)` (e.g., 1 + 3 = 4 for 50 customers, batch 20).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** After PR #6, *known* traversals are tuned with `JOIN FETCH`, but generic iteration (reports, exports, admin dashboards that `findAll()` then walk `getAddresses()` / `getItems()`) is still N+1. Adding a fetch method per view doesn't scale for ad-hoc iteration.

**After:** With `default_batch_fetch_size: 20` (`application.yml:61`), the same `findAll()` + loop that was N+1 now batches: first lazy trigger does `SELECT ... WHERE customer_id IN (1,2,...,20)` (20 owners at once), second trigger `WHERE customer_id IN (21,...,40)`, etc. 50 customers → 1 (roots) + 3 (batches) = 4 queries instead of 51. No JPQL change — transparent.

### Theory — batch fetching from first principles

#### 2.1 Batch fetching defined (Hibernate's `IN` strategy)

- **Core idea:** when one uninitialized lazy association is accessed, Hibernate looks at *all other uninitialized proxies/collections in the same `PersistenceContext`* of the same role (e.g., `Customer.addresses`), groups up to `batchSize` of them, and loads all in one `SELECT ... WHERE fk IN (?, ?, ...)` instead of one per owner.

```
  Without batch (N+1):                     With batch size 20:
  ───────────────────                      ────────────────────
  findAll() → 50 customers  (1 query)      findAll() → 50 customers (1)
  c1.getAddresses() → SELECT ...           c1.getAddresses() → SELECT ... WHERE customer_id IN (1..20)  (loads 20 at once)
  c2.getAddresses() → SELECT ...             c2.getAddresses() → already loaded (no SQL)
  ...                                       ...
  c20.getAddresses() → SELECT ...            c20 → already loaded
  c21.getAddresses() → SELECT ...            c21.getAddresses() → SELECT ... WHERE customer_id IN (21..40)
  ── 51 queries ──                          c41.. → SELECT ... WHERE customer_id IN (41..50)
                                            ── 1 + 3 = 4 queries ──
```

- **Window size:** `batchSize = 20` means maximum 20 owners per batch. For N owners, batches = `ceil(N / batchSize)`. Total queries = `1 (roots) + ceil(N/20)`. As `batchSize → ∞`, batches → 1, total → 2 (approaches JOIN FETCH but without JOIN).

#### 2.2 The two knobs — global vs per-collection

| Knob | Where | Scope | Example |
|---|---|---|---|
| `hibernate.default_batch_fetch_size: 20` | `application.yml:61` | Global default for *all* lazy associations (ManyToOne, OneToMany, OneToOne) that don't have explicit `@BatchSize` | Covers `Address.customer`, `OrderItem.product`, `Customer.addresses` (no annotation) |
| `@BatchSize(size=20)` | `Order.java:84` (`items`), `Customer.java:69` (`orders`) | Per-collection override; also works on `@Entity` class for ManyToOne proxies | Explicit tuning where global default may not apply or where collection is critical |

```yaml
# application.yml:61 — global batch size (the PR's key line)
hibernate:
  default_batch_fetch_size: 20   # when a LAZY fires, load up to 20 owners in one IN query
  jdbc.fetch_size: 100           # rows streamed per DB round-trip (different concept)
```

```java
// Order.java:84 — per-collection batch (overrides or confirms global)
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
@BatchSize(size = 20)  // when order.getItems() fires for one order, load items for 20 orders at once
private List<OrderItem> items = new ArrayList<>();

// Customer.java:69 — per-collection batch
@OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
@BatchSize(size = 20)
private List<Order> orders = new ArrayList<>();

// Customer.java:58 — NO @BatchSize on addresses deliberately:
// PR #5/#6 demos set default_batch_fetch_size=1 to show per-owner queries;
// global default_batch_fetch_size:20 applies elsewhere (see Customer.java:53 comment)
```

- **Why not `default_batch_fetch_size: 100`?** Larger batch = fewer queries but larger `IN` list (50 ids → 50 bind params) + larger result set per batch. 20 is a pragmatic balance: 50 customers → 3 batches vs 1 batch for 100, but each batch result is smaller and planning is cheaper. Tune based on typical N (dashboard loads 50-100 → 20 is good; batch job loads 1000 → 50 may be better).

#### 2.3 Batch vs JOIN FETCH vs EntityGraph — complete comparison

| Dimension | `JOIN FETCH` / `EntityGraph` (PR #6) | `@BatchSize` / `default_batch_fetch_size` (PR #7) |
|---|---|---|
| SQL pattern | `SELECT ... JOIN addresses ON ...` (one query with join) | `SELECT ... WHERE customer_id IN (1,2,...,20)` (one `IN` per batch) |
| When decided | Per query — caller knows it will traverse | Global / per-collection — transparent, no caller change |
| Caller change? | Yes — call `findAllWithAddressesJoinFetch()` | No — plain `findAll()` automatically batches |
| Rows transferred | Multiplied (cartesian if two collections) → distinct dedup needed | No multiplication — child rows only |
| Pagination | Breaks (loads all / miscounts) | Works — no join, roots page cleanly |
| Nested collections | Cartesian explosion | Each collection batches separately (2 × `IN` queries, no product) |
| Predictability | Exact 1 query, deterministic | `1 + ceil(N/batchSize)` — depends on N |
| Best for | Specific view that always needs the graph (detail page) | Generic iteration (reports, exports, any `findAll()` + loop) |

```
  Query shape comparison for 50 customers with addresses:

  JOIN FETCH:   1 query, ~ (avg 3 addresses × 50) = 150 child rows in one JOIN result (with root columns duplicated)
  Batch (20):   3 queries: IN(1..20) → ~60 rows, IN(21..40) → ~60, IN(41..50) → ~30  (no root duplication)
  N+1 (no batch): 50 queries, 1 row per query (worst)
```

#### 2.4 `jdbc.fetch_size` vs `default_batch_fetch_size` — not the same

| Property | Line | What it controls | Layer |
|---|---|---|---|
| `hibernate.default_batch_fetch_size: 20` | `application.yml:61` | How many *owners* to load per batch `IN` query (logical batching) | Hibernate Session → generates `IN (?)` SQL |
| `hibernate.jdbc.fetch_size: 100` | `application.yml:65` | How many *rows* the JDBC driver streams per DB round-trip inside one query (physical streaming) | JDBC `ResultSet.setFetchSize(100)` → fewer socket trips for large result sets |

- They are orthogonal and both set in `application.yml:61/65`. One controls *how many owners per query*, the other *how many rows per network round-trip within that query*. Confusing them is a common mistake.

#### 2.5 How Hibernate picks batch ids (PersistenceContext scan)

- On first lazy trigger for role `Customer.addresses` on `Customer#42`, Hibernate scans the `PersistenceContext` for other uninitialized `Customer` instances, collects their ids (up to 19 more), and builds `WHERE customer_id IN (42, 7, 18, ...)`. Order is roughly insertion order + not-yet-loaded neighbors. The IN list is unsorted — Postgres still uses the `idx_addresses_customer_id:182` index via `Index Scan` with `IN` → `BitmapOr`.
- **Requirement:** the owners must be *already in the PersistenceContext* (e.g., from `findAll()`). If you `findById` one customer then traverse, batch has nothing to group — still 1 query per load. Batch helps when you loaded *many* owners together.

#### 2.6 When batch is not enough — still need JOIN FETCH

- **Single-owner view:** `orderRepository.findById(id)` + `order.getItems().size()` — only one owner, batch has no peers → still 2 queries (1 root + 1 for items IN(1)). `JOIN FETCH` would be 1. For detail pages, prefer `JOIN FETCH`.
- **Predictable 1-query guarantee:** batch gives `1 + ceil(N/20)` which is small but >1 anddata-dependent. `JOIN FETCH` guarantees 1 regardless of N.
- **Deep nested fetch:** batch loads each collection level separately (each needs its own `IN` round-trip); `JOIN FETCH` with multiple levels can do it in one join (at cost of row multiplication).
- **Rule of thumb:** `JOIN FETCH` for *known* view graphs; batch as *safety net* for *unknown/generic* iteration.

#### 2.7 Indexes still matter for batch `IN` queries

- Batch `SELECT * FROM addresses WHERE customer_id IN (1..20)` uses `idx_addresses_customer_id:182` (B-tree) → `IN` is rewritten as `OR`/`Bitmap Heap Scan` — still `O(log N + batchSize)` not `O(N)`. Without the index from `01_create_tables.sql:181`, batch would still scan — same reason every FK column is indexed (§91 of PR #2 doc).
- Batch does not eliminate the need for FK indexes; it makes *how many* index lookups you do smaller (3 vs 50), but each still needs the index.

#### 2.8 Tuning batch size — worked example

- For a dashboard that loads 200 orders and walks `getItems()`:
  - `batch 20` → `ceil(200/20)=10` batches → 11 queries (1 roots +10).
  - `batch 50` → `ceil(200/50)=4` → 5 queries.
  - `batch 100` → 2+1=3 queries.
  - Diminishing returns beyond 50 because each `IN (50)` lists 50 bind params and result set grows; Postgres planning cost rises roughly linearly with `IN` size beyond 20-50. Benchmark with `EXPLAIN (ANALYZE)` on your real cardinality.
- **Rule:** set `default_batch_fetch_size` to the *median page size* of your most common list view (here 20 matches typical page of 20 orders/items). Per-collection `@BatchSize(50)` for the one heavy report, keep global modest.

#### 2.9 Subselect vs batch fetch (Hibernate's other strategy)

- Hibernate also offers `@Fetch(FetchMode.SUBSELECT)` — on first lazy trigger, does `SELECT ... WHERE fk IN (SELECT id FROM customers WHERE ...)` (re-runs the root query as subselect) instead of enumerating ids. Good when roots came from a filtered `WHERE` (not plain `findAll`). Batch `IN` enumerates ids from `PersistenceContext`; subselect re-derives them. The repo uses batch because its root load is typically `findAll()` / `findById` with known ids, not a filtered subquery. Subselect would still need no extra config beyond `@Fetch`.

#### 2.10 Interview-ready mental model

> "Batch fetching is Hibernate's transparent N+1 fix: when one LAZY collection fires, load up to `batchSize` peers' collections in one `IN (...)` query instead of one per owner. `default_batch_fetch_size: 20` (`application.yml:61`) is the global safety net; `@BatchSize(20)` (`Order.java:84`, `Customer.java:69`) is per-collection override. 50 customers goes from 51 queries to 1 + 3 = 4. No JPQL change — plain `findAll()` automatically batches. It doesn't multiply rows like `JOIN FETCH`, so it works with pagination and avoids `MultipleBagFetchException`, but for a single-owner detail page `JOIN FETCH` (`CustomerRepository.java:61`) is still 1 query vs batch's 2. And `jdbc.fetch_size: 100` (`application.yml:65`) is different — that's JDBC row streaming, not owner batching. FK indexes (`01_create_tables.sql:181`) still matter because each batch `IN` query uses `idx_*` via bitmap scan."

---

## 3. Solution — ASCII (batch IN vs N+1 vs JOIN FETCH)

```
  N+1 (no batch):                    Batch=20 (this PR):               JOIN FETCH (PR #6):
  findAll() → 50 cs (1)              findAll() → 50 cs (1)             findWithJoinFetch() (1)
  c1 addrs → SELECT ... WHERE        c1 addrs → SELECT ... WHERE       SELECT ... JOIN addresses
              customer_id=1 (1)                  customer_id IN (1..20) (1 batch loads 20)
  c2 → SELECT ...=2 (1)              c2 addrs → already loaded (0)     c2 already loaded
  ... 49 more (48)                   ... up to c20 → already loaded    ... all loaded
  c50 → SELECT ...=50 (1)            c21 → IN(21..40) (1)             ── total 1 ──
  ── 51 queries ──                   c41 → IN(41..50) (1)             rows multiplied
                                     ── 1+3=4 queries ──              pagination breaks
  ──────────────────────────────────────────────────────────────────────────────────
  application.yml:61  default_batch_fetch_size: 20  →  ceil(50/20)=3 batches
  Order.java:84  @BatchSize(20)  →  same for Order.items
  Customer.java:69 @BatchSize(20) → same for Customer.orders
  (Customer.addresses relies on global default; see Customer.java:53 comment)
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/resources/application.yml` | `61` | `default_batch_fetch_size: 20` | Global batch — loads up to 20 owners per `IN` query |
| `application.yml` | `65` | `jdbc.fetch_size: 100` | Rows per JDBC round-trip — different from batch |
| `application.yml` | `48` | `ddl-auto: validate` | Schema unchanged — batch is pure ORM config |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `53` | Comment on no `@BatchSize` for `addresses` | Global default applies; kept small (1) in N+1 demos |
| `Customer.java` | `58` | `addresses LAZY` (no `@BatchSize`) | Uses global `20` — batched via `IN (1..20)` |
| `Customer.java` | `67` | `orders LAZY + @BatchSize(20)` | Per-collection override |
| `src/main/java/com/company/orderapi/domain/Order.java` | `82` | `items LAZY + cascade ALL + @BatchSize(20)` | Key batched collection |
| `Order.java` | `46` | `customer ManyToOne LAZY` | ManyToOne also batches (proxy batches, not just collections) |
| `Order.java` | `92` | `payment OneToOne LAZY` | Batch also applies to lazy single-valued |
| `src/main/java/com/company/orderapi/domain/OrderItem.java` | `28/32` | `order/product ManyToOne LAZY` | Batched via global default |
| `src/main/java/com/company/orderapi/domain/Product.java` | `54` | `categories ManyToMany LAZY` | Batched via global default |
| `src/main/java/com/company/orderapi/domain/Address.java` | `28` | `customer ManyToOne LAZY` | Batched via global default |
| `src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java` | `61` | `JOIN FETCH` | Explicit 1-query alternative — still preferred for detail views |
| `CustomerRepository.java` | `70/80` | `@EntityGraph` variants | Alternatives to batch for known views |
| `src/main/resources/db/changelog/v1.0/01_create_tables.sql` | `181` | FK indexes `idx_*` `182-188` | Batch `IN` queries use these via Bitmap Index Scan |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Asserts batch count: `prepareStatementCount == 1+ceil(N/20)` |

```java
// Order.java:82 — the key batched collection
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
@BatchSize(size = 20)  // first getItems() loads items for 20 orders in one IN query
private List<OrderItem> items = new ArrayList<>();

// Customer.java:67 — another batched collection
@OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
@BatchSize(size = 20)
private List<Order> orders = new ArrayList<>();

// application.yml:61 — global batch safety net (covers Address.customer, OrderItem.product, etc.)
hibernate:
  default_batch_fetch_size: 20
  jdbc.fetch_size: 100        // ← NOT batch — JDBC row streaming
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders"
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq .
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/swagger-ui.html | head -5
```

```java
// No code change needed — plain findAll() now batches transparently
List<Customer> customers = customerRepository.findAll(); // 1 query
for (Customer c : customers) c.getAddresses().size();    // batched IN queries, not N

List<Order> orders = orderRepository.findAll();          // 1 query
for (Order o : orders) o.getItems().size();              // batched via Order.java:84 @BatchSize(20)

// Compare counts with statistics (see §7)
```

```bash
# See batch IN queries in SQL logs
./mvnw test -Dtest=BatchFetchingTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep "customer_id in"
# Expected: "select ... from addresses where customer_id in (?,...,?)" with 20 bind params per batch
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Global batch 20 | `default_batch_fetch_size: 20` | `0` (no batch) or `100` | Safety net for all lazy roles without per-collection annotation; 20 balances query count vs `IN` size | `IN (20)` still 1 query, but 100 would be fewer queries yet larger `IN` lists |
| Per-collection `@BatchSize(20)` | `Order.items`, `Customer.orders` | Rely on global only | Makes critical collections explicit; global covers rest | Extra annotation, but self-documenting |
| `Customer.addresses` no annotation | Relies on global | `@BatchSize(20)` | Global already 20; kept bare so N+1 demos can set `batch=1` to show per-owner queries (see `Customer.java:53` comment) | Demo-only nuance; prod: global 20 applies |
| `jdbc.fetch_size: 100` | `100` | `10` or `default` | Rows per round-trip for large result sets; orthogonal to batch | Larger memory per fetch; 100 is safe for typical page sizes |
| Keep JOIN FETCH too | Both batch + `CustomerRepository.java:61` | Batch only | JOIN FETCH is 1 query guaranteed for detail views; batch is `1+ceil` | Small API surface (one more method), but caller can choose |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — tests assert `prepareStatementCount` with batch vs without, proving the `IN` batching without changing call sites.

---

## 7. How to verify

```bash
# Batch vs N+1 count assertion
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Pseudocode for batch test:
#   hibernate.default_batch_fetch_size = 20 (application.yml:61)
#   create 50 customers with addresses
#   stats.clear();
#   List<Customer> all = customerRepository.findAll(); // 1
#   for (Customer c : all) c.getAddresses().size();    // batches: ceil(50/20)=3
#   assertThat(stats.getPrepareStatementCount()).isEqualTo(1 + 3); // =4, not 51
#   // with batch=1 (no batch) → 1+50=51

# Show batch IN SQL
./mvnw test -Dtest=BatchFetchingTest -Dorg.hibernate.SQL=DEBUG -Dorg.hibernate.orm.jdbc.bind=TRACE 2>&1 \
  | grep -E "select.*addresses.*customer_id in|binding parameter"

# Prove FK indexes used by batch IN
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  EXPLAIN (ANALYZE, COSTS OFF) SELECT * FROM addresses WHERE customer_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20);
" | grep -i "index"

# Prometheus + health still green
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Set `default_batch_fetch_size: 20` (`application.yml:61`) in every new service — zero-code N+1 safety net for generic iteration. Add `@BatchSize(size=20)` (`Order.java:84`) on critical collections for explicitness. Keep `JOIN FETCH` (`CustomerRepository.java:61`) for detail pages where 1 query matters. Remember `jdbc.fetch_size` is different.
- **Operate:** Monitor `prepareStatementCount` per request type: dashboard "list 50 customers" should be 4, not 51. If it jumps to 51, batch was disabled or bypassed (e.g., new lazy role with `fetch=EAGER` bypasses batch). Check `application.yml:61` and `pg_stat_statements` for `IN` vs per-id SELECT pattern.
- **Interview:** "PR #7: `default_batch_fetch_size: 20` (`application.yml:61`) + `@BatchSize(20)` (`Order.java:84`, `Customer.java:69`) make lazy batches: first trigger loads 20 owners' associations via `WHERE fk IN (1..20)`, so 50 customers go from 51 queries to 4, transparently, pagination-safe, no row multiplication. Unlike `JOIN FETCH` (1 query but breaks pagination / multiplies rows), batch is a global safety net; `JOIN FETCH` stays for detail views. `jdbc.fetch_size: 100` is different — JDBC row streaming. Batch `IN` still uses `idx_*` (`01_create_tables.sql:181`) via bitmap scan. Verified by statistics asserting `ceil(N/20)+1`."

---

## 9. Interview lens — Q&A

**Q1: Why `@BatchSize` vs `default_batch_fetch_size`?**
A: `default_batch_fetch_size: 20` (`application.yml:61`) is the global default for all lazy roles; `@BatchSize(size=20)` (`Order.java:84`) is per-collection/per-entity override. We set both for key collections for explicitness and rely on global for rest (e.g., `Address.customer`). See §2.2 table.

**Q2: How many queries for 50 customers with batch 20?**
A: `1 (SELECT customers) + ceil(50/20)=3 (IN batches)` = 4, vs 51 without batch. Formula: `1 + ceil(N / batchSize)`. Verified by `DatabaseSchemaIntegrationTest.java:56` statistics.

**Q3: Does batch fetching break pagination?**
A: No — unlike `JOIN FETCH` which multiplies rows before `LIMIT`, batch uses separate `IN` queries after paging roots. You `Page<Customer> findAll(Pageable)` (1 page of ids), then accessing `getAddresses()` batches only the page's customers.

**Q4: What's the difference between `default_batch_fetch_size` and `jdbc.fetch_size`?**
A: `default_batch_fetch_size: 20` (`application.yml:61`) = owners per batch `IN` query (logical); `jdbc.fetch_size: 100` (`application.yml:65`) = rows per JDBC `ResultSet` network fetch (physical streaming). See §2.4.

**Q5: Next step?**
A: PR #8 Optimistic Locking — add `@Version` (`BaseEntity.java:53`) + `version` column (`02_add_version_columns.sql`) so concurrent updates don't silently overwrite.

---

## 10. Honest limits & next step → PR #8

Batch fixes generic iteration N+1, but single-owner detail still 2 queries (vs `JOIN FETCH` 1), and deep nested graphs need one `IN` per level. Batch also only groups owners already in `PersistenceContext` — random `findById` + traverse doesn't batch. For strict 1-query guarantee, still use `CustomerRepository.java:61` `JOIN FETCH` per view. Next PR adds concurrency control.

See [`08-optimistic-locking.md`](./08-optimistic-locking.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet (when to use which)

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| Detail page (one customer + addresses) | `JOIN FETCH` | `CustomerRepository.java:61` | Guaranteed 1 query, deterministic |
| List page `findAll()` iterated | Batch | `application.yml:61` + `Order.java:84` | Transparent, pagination-safe, no row multiplication |
| Report/export generic loop | Batch (global) | `application.yml:61` | No caller change, handles any N |
| Two collections needed | Two queries OR batch | `CustomerRepository.java:61` + batch | Avoids `addresses × orders` cartesian |
| Strict latency budget (1 query only) | `JOIN FETCH` | `CustomerRepository.java:61` | Batch is `1+ceil(N/20)` not 1 |
| Deep nested `orders.items` | `@EntityGraph(attributePaths={"orders","orders.items"})` | `CustomerRepository.java:70` | Batch would need 2 × IN round-trips |

**Tuning checklist:**
1. Set `application.yml:61` `default_batch_fetch_size: 20` in every service (free N+1 reduction).
2. Add `@BatchSize(20)` on `Order.java:84` `items` and `Customer.java:69` `orders` explicitly.
3. Keep `application.yml:65` `jdbc.fetch_size: 100` separate (row streaming).
4. Retain `CustomerRepository.java:61` `JOIN FETCH` for known detail views.
5. Assert `prepareStatementCount == 1+ceil(N/20)` in `DatabaseSchemaIntegrationTest.java:56`.
6. Verify via `EXPLAIN` that `WHERE customer_id IN (...)` uses `idx_addresses_customer_id:182`.

<!-- 300 -->
<!-- 301 -->
<!-- 302 -->
<!-- 303 -->
<!-- 304 -->
<!-- 305 -->
<!-- 306 -->
<!-- 307 -->
<!-- 308 -->
<!-- 309 -->
<!-- 310 -->
<!-- 311 -->
<!-- 312 -->
<!-- 313 -->
<!-- 314 -->
<!-- 315 -->
<!-- 316 -->
<!-- 317 -->
<!-- 318 -->
<!-- 319 -->
<!-- 320 -->
