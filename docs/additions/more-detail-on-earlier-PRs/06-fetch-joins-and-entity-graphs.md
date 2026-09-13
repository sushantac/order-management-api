# 06. Fetch Joins and Entity Graphs (PR #6)

> PR #6 — Fetch Joins and Entity Graphs. Stack: Java 21, Spring Boot, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers + Hibernate 6.6. See `README.md:1772` roadmap `| 6 | Fetch Joins and Entity Graphs |`.

---

## 1. Purpose — what shipped

PR #6 delivers **Fetch Joins and Entity Graphs** as a first-class, tested, documented building block. It fixes the N+1 problem introduced by `LAZY` (PR #5) with three equivalent per-query strategies, all on `CustomerRepository.java`: (1) JPQL `JOIN FETCH` (`CustomerRepository.java:61` `findAllWithAddressesJoinFetch`), (2) programmatic `@EntityGraph(attributePaths={"addresses"})` (`CustomerRepository.java:70` `findAllWithAddressesEntityGraph`), (3) reusable `@NamedEntityGraph` (`Customer.java:34` `name="Customer.addresses"` → `CustomerRepository.java:80` `findAllWithAddressesNamedEntityGraph`). All three load 50 customers + their addresses in *1 query* instead of N+1, with `distinct` fixing cartesian row duplication. Tests assert query counts via `hibernate.generate_statistics: true` (`application.yml:56`).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `customerRepository.findAll()` + loop `c.getAddresses().size()` = N+1: 1 `SELECT * FROM customers` + N `SELECT * FROM addresses WHERE customer_id=?`. With 50 customers → 51 queries; with 1000 → 1001. `show-sql: true` (`application.yml:44`) makes it visible but not fixed.

**After:** `findAllWithAddressesJoinFetch()` (`CustomerRepository.java:61`) does `SELECT DISTINCT c FROM Customer c JOIN FETCH c.addresses` → 1 query with join. Same result via `@EntityGraph` (`CustomerRepository.java:70`) or named graph (`Customer.java:34` + `CustomerRepository.java:80`). Tests assert `sessionMetrics.getPrepareStatementCount() == 1` for the fetch methods vs N+1 for plain `findAll()`.

### Theory — N+1 and its fixes from first principles

#### 2.1 N+1 defined (why LAZY implies it)

- **N+1 pattern:** 1 query to load N parent rows, then N *additional* queries (one per parent) to load a lazy association when traversed.

```
  Naïve (N+1):                          Tuned (1 query):
  ──────────                            ────────────────
  1  SELECT * FROM customers             1  SELECT DISTINCT c.*, a.* FROM customers c
        → 50 rows                           LEFT JOIN addresses a ON a.customer_id=c.id
  50 SELECT * FROM addresses                   → 50 customers + addresses in 1 result set
        WHERE customer_id = ?  ×50
  ── total 51 queries ──                ── total 1 query ──
```

- N+1 is not a bug in Hibernate — it is the *correct* consequence of LAZY: each lazy trigger is a separate `SELECT` because no join was requested. The fix is to *declare the fetch per query* when you know you will traverse.

#### 2.2 Fix 1 — JPQL `JOIN FETCH` (imperative, SQL-level control)

```java
// CustomerRepository.java:61 — the classic fix
@Query("select distinct c from Customer c join fetch c.addresses")
List<Customer> findAllWithAddressesJoinFetch();
```

- `JOIN FETCH` is *not* a filter (`JOIN` + `WHERE`); it is a *fetch instruction* — "join and mark `c.addresses` as initialized in the `PersistenceContext` so later `getAddresses()` does not trigger another SELECT."
- **Why `DISTINCT`:** joining a collection multiplies root rows (customer with 3 addresses → 3 rows SQL). Without `DISTINCT`, JPA returns duplicate `Customer` objects. `DISTINCT` here is *object-level deduplication* (Hibernate does it in memory, not `SELECT DISTINCT` in SQL — check the generated SQL). Alternatives: `Set<Customer>` or `LinkedHashSet` but `DISTINCT` is idiomatic.
- **SQL generated (check `org.hibernate.SQL`):**
  ```sql
  select distinct c1_0.id, c1_0.email, c1_0.full_name, a1_0.customer_id, a1_0.id, a1_0.city ...
  from customers c1_0
  join addresses a1_0 on a1_0.customer_id = c1_0.id
  ```
- **Tradeoff:** most explicit, flexible (can add `WHERE c.email = :email`), but JPQL string must be maintained. Good for complex predicates alongside fetch.

#### 2.3 Fix 2 — `@EntityGraph(attributePaths=...)` (declarative, per-method)

```java
// CustomerRepository.java:70 — no JPQL join needed
@EntityGraph(attributePaths = {"addresses"})
@Query("select distinct c from Customer c")
List<Customer> findAllWithAddressesEntityGraph();
```

- `@EntityGraph` declares *which associations to fetch* separate from the query. Hibernate rewrites the `SELECT` to add the joins automatically.
- `attributePaths = {"addresses"}` is a property path, not a column — can be nested: `{"addresses", "orders.items"}` for deeper graphs. No JPQL duplication when several methods share the same fetch.
- **SQL generated:** similar join to Fix 1, but Hibernate builds it from the graph metadata rather than your JPQL string. Good for simple queries where you mostly want to control fetching, not filtering.

#### 2.4 Fix 3 — `@NamedEntityGraph` (reusable, entity-level recipe)

```java
// Customer.java:34 — declared once on the entity
@NamedEntityGraph(name = "Customer.addresses",
        attributeNodes = @NamedAttributeNode("addresses"))
public class Customer extends BaseEntity { /* ... */ }

// CustomerRepository.java:80 — referenced by name
@EntityGraph("Customer.addresses")
@Query("select distinct c from Customer c")
List<Customer> findAllWithAddressesNamedEntityGraph();
```

- `@NamedEntityGraph` is the *reusable* variant: one declaration (`Customer.java:34`) can be referenced by multiple repository methods, services, or `EntityManager.find(id, hints)`. Changing the fetch recipe updates all callers.
- Useful when the same fetch graph (e.g., `Customer` with `addresses` + `orders`) is needed in many places. Naming also conveys intent: `"Customer.addresses"` is self-documenting.

#### 2.5 Three fixes compared — when to choose which

| Aspect | `JOIN FETCH` | `@EntityGraph(attributePaths)` | `@NamedEntityGraph` |
|---|---|---|---|
| Where declared | JPQL string (`@Query`) | Annotation on repo method | Entity class (`Customer.java:34`) + repo ref |
| Flexibility | Highest (JOIN type, WHERE, ORDER together) | Medium (path list) | Low (fixed graph, but reusable) |
| Reusability | Per-method | Per-method | Across methods/services |
| Nested paths | Via JPQL (`join fetch c.orders o join fetch o.items`) | `{"orders", "orders.items"}` | `attributeNodes` nesting |
| Best for | Complex filtering + fetch | Simple findAll + fetch | Shared fetch recipe across app |

All three generate the same *single* SQL with join and return fully initialized `addresses` collections — choose by reuse need and query complexity.

#### 2.6 Pagination + `JOIN FETCH` — the critical pitfall

- **Never paginate a `JOIN FETCH` collection query in memory.** `SELECT DISTINCT` + `JOIN FETCH` + `Pageable` causes Hibernate to either (a) load *all* rows and paginate in memory (warning `HHH000104`), or (b) produce wrong row counts because collection join multiplies rows before `LIMIT`.
- **Rule:** paginate on the *root* only, then batch-fetch collections separately, or use a subselect:

  ```java
  // Correct: paginate ids, then fetch with graph
  Page<Customer> page = customerRepository.findAll(Pageable.ofSize(20));
  // then for fetched ids, use findAllWithAddressesEntityGraphByIds(ids)
  // OR: @Query with native countQuery: see OrderRepository.java:78 findOrdersPaged
  ```

- `OrderRepository.java:78` `findOrdersPaged(Pageable)` shows non-fetch paginated query; adding `JOIN FETCH` there would break count query. The repo keeps pagination and collection fetch separate — a deliberate tradeoff.

#### 2.7 Cartesian product and `MultipleBagFetchException`

- Fetching *two* collections in one query (`JOIN FETCH c.addresses` + `JOIN FETCH c.orders`) multiplies rows: `addresses × orders` per customer. Result set explodes; Hibernate may throw `MultipleBagFetchException: cannot simultaneously fetch multiple bags` for two `List` collections.
- **Fixes:** fetch one collection per query, or map one as `Set` (`LinkedHashSet`), or use `@BatchSize` (PR #7) as fallback, or two queries: `findAllWithJoinFetchAddresses` + `findAllWithJoinFetchOrders`. The repo keeps each fetch method single-collection for this reason (`CustomerRepository.java:61/70/80` each fetch only `addresses`).

```
  customer with 3 addresses + 4 orders — double JOIN FETCH:
  row = customer × address_i × order_j = 3 × 4 = 12 rows per customer
  50 customers → 600 rows transferred, most duplicated
  ── Better: two queries (3 + 4 rows) or @BatchSize (PR #7) ──
```

#### 2.8 How EntityGraph/JOIN FETCH differ from batch fetching (PR #7)

| Question | `JOIN FETCH` / `EntityGraph` | `@BatchSize` / `default_batch_fetch_size` |
|---|---|---|
| When decided | Per query (caller knows it will traverse) | Global or per-collection (`Customer.java:67` `@BatchSize(20)`) |
| SQL pattern | One query with `JOIN` | `IN (?, ?, ...)` per batch (`WHERE customer_id IN (1,2,..,20)`) |
| Best when | Loading graph for a specific view (e.g., customer detail page) | Iterating many owners whose collections will be touched (e.g., report) |
| Pagination | Breaks (see §2.6) | Works (no join) |
| Complexity | Explicit per method | Implicit, tuned by `application.yml:61` |

#### 2.9 Second-level cache interaction with fetch graphs

- `@Cacheable` (`Product.java` has `@Cache(usage=READ_WRITE)`) does not replace fetch tuning. Even when `Product` rows are cached, `Customer → addresses` JOIN FETCH still matters for uncached associations. Mixing `@Cache` + `EntityGraph` gives: first request warms both DB and cache, later requests may serve `Product` from L2 while still joining `addresses` per `CustomerRepository.java:61`.
- Hibernate second-level cache (`application.yml:68` `use_second_level_cache: true`) only caches entities loaded by id or query-cache; `JOIN FETCH` queries bypass query-cache by default — fine for list views where cache invalidation cost would outweigh benefit. See PR #16 doc for details.

#### 2.10 Interview-ready mental model

> "LAZY causes N+1: 1 query for roots, N for collections. Fix per-use-case with a fetch strategy, not EAGER. Three equivalent fixes: `JOIN FETCH` in JPQL (`CustomerRepository.java:61`) — imperative, most flexible, needs `DISTINCT`; `@EntityGraph(attributePaths)` (`CustomerRepository.java:70`) — declarative path list, no JPQL join; `@NamedEntityGraph` (`Customer.java:34`) — reusable recipe referenced by name. All three produce one JOIN query vs N+1. `DISTINCT` dedups the multiplied rows. Don't paginate a collection `JOIN FETCH` — it loads everything or miscounts. Don't fetch two bags in one query — cartesian product and `MultipleBagFetchException`. Those cases are where PR #7's `IN`-based batch fetching wins."

---

## 3. Solution — ASCII (naïve vs tuned)

```
  NAïVE (N+1) = CustomerRepository.findAll()  + loop
  ───────────────────────────────────────────────────
  Java:  findAll()                    SQL: SELECT * FROM customers  (1)
           │ for c in customers                for each customer (N times):
           └── c.getAddresses().size()  ──LAZY──▶ SELECT * FROM addresses WHERE customer_id=? (N)

  TUNED (1 query) = findAllWithAddressesJoinFetch / EntityGraph
  ─────────────────────────────────────────────────────────────────
  Java:  findAllWithAddressesJoinFetch()     ──one call──▶   SQL: SELECT DISTINCT c.*, a.*
           │                                                 FROM customers c JOIN addresses a
           └── c.getAddresses().size()  ──already loaded──▶  ON a.customer_id=c.id  (1)
                                                                 no LAZY trigger

  Alternative tunings (same result):
   ─ EntityGraph(attributePaths={"addresses"})  CustomerRepository.java:70
   ─ @NamedEntityGraph("Customer.addresses")    Customer.java:34 → CustomerRepository.java:80
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/Customer.java` | `34` | `@NamedEntityGraph("Customer.addresses")` | Reusable fetch recipe: `attributeNodes=@NamedAttributeNode("addresses")` |
| `Customer.java` | `58` | `addresses LAZY` | Target of all three fetch methods |
| `Customer.java` | `67` | `orders LAZY + @BatchSize(20)` | Not fetched here (single-collection rule) |
| `src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java` | `46` | `findByEmail` | No fetch — proves plain query still LAZY |
| `CustomerRepository.java` | `61` | `findAllWithAddressesJoinFetch()` | Fix 1: `SELECT DISTINCT c JOIN FETCH c.addresses` |
| `CustomerRepository.java` | `70` | `findAllWithAddressesEntityGraph()` | Fix 2: `@EntityGraph(attributePaths={"addresses"})` |
| `CustomerRepository.java` | `80` | `findAllWithAddressesNamedEntityGraph()` | Fix 3: `@EntityGraph("Customer.addresses")` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `46` | `customer LAZY` | Similar tuning would apply for Order graphs |
| `src/main/resources/application.yml` | `42` | `open-in-view: false` | Fetch must happen inside TX |
| `application.yml` | `44` | `show-sql: true` | See 1 vs N+1 in logs |
| `application.yml` | `56` | `generate_statistics: true` | Asserts query count: 1 vs 51 |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Asserts `JOIN FETCH` loads without extra SELECT |
| `src/main/java/com/company/orderapi/domain/repository/OrderRepository.java` | `78` | `findOrdersPaged(Pageable)` | Pagination without JOIN FETCH — deliberate separation |

```java
// CustomerRepository.java:61 — Fix 1: imperative JOIN FETCH
@Query("select distinct c from Customer c join fetch c.addresses")
List<Customer> findAllWithAddressesJoinFetch();

// CustomerRepository.java:70 — Fix 2: declarative attributePaths
@EntityGraph(attributePaths = {"addresses"})
@Query("select distinct c from Customer c")
List<Customer> findAllWithAddressesEntityGraph();

// CustomerRepository.java:80 — Fix 3: named graph from entity
@EntityGraph("Customer.addresses")
@Query("select distinct c from Customer c")
List<Customer> findAllWithAddressesNamedEntityGraph();

// Customer.java:34 — the named graph definition
@NamedEntityGraph(name = "Customer.addresses",
        attributeNodes = @NamedAttributeNode("addresses"))
public class Customer extends BaseEntity { ... }
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d customers"
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq .
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/swagger-ui.html | head -5
```

```java
// In a service @Transactional: compare naive vs tuned
// Naïve N+1 (51 queries for 50 customers)
List<Customer> all = customerRepository.findAll();
for (Customer c : all) c.getAddresses().size(); // N lazy triggers

// Tuned — any of the three gives 1 query
List<Customer> tuned = customerRepository.findAllWithAddressesJoinFetch();
for (Customer c : tuned) c.getAddresses().size(); // no SQL — already loaded
assert Hibernate.isInitialized(tuned.get(0).getAddresses()); // true

// Same via EntityGraph variants
customerRepository.findAllWithAddressesEntityGraph();
customerRepository.findAllWithAddressesNamedEntityGraph();
```

```bash
# Prove query count via statistics
./mvnw test -Dtest=CustomerFetchTest -Dhibernate.generate_statistics=true 2>&1 \
  | grep -E "Session Metrics|prepareStatement"
# Naïve:  prepareStatementCount=51
# Tuned:  prepareStatementCount=1
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen |
|---|---|---|---|
| Three fetch strategies | All three on same repo | Single JOIN FETCH | Demonstrates tradeoffs; each fits different reuse/complexity |
| `DISTINCT` in JPQL | Yes | `Set` return type | Dedupes multiplied rows from collection join; idiomatic |
| Fetch only one collection per query | `addresses` only per method | `addresses` + `orders` together | Avoids cartesian `3×4=12` rows/customer + `MultipleBagFetchException` |
| No pagination with JOIN FETCH | Paginated `findOrdersPaged` has no fetch | `JOIN FETCH` + `Pageable` | Would load all rows or miscount — keep separate |
| Global fallback | `@BatchSize` (PR #7) for other cases | EAGER | Batch is `IN` query, no join, pagination-safe |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — tests assert both correctness (data loaded) and performance (query count == 1 vs N+1).

---

## 7. How to verify

```bash
# Query-count assertions (statistics)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Test pseudocode:
#   SessionStatistics stats = sessionFactory.getStatistics();
#   stats.clear(); customerRepo.findAllWithAddressesJoinFetch();
#   assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
#   // vs naive findAll() + loop → N+1

# Show real SQL: 1 JOIN vs N SELECT
./mvnw test -Dtest=CustomerFetchTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep "select.*from"
# Tuned: one "select distinct ... join addresses"
# Naïve: one "select ... from customers" + N "select ... from addresses where customer_id=?"

# Verify DISTINCT dedupes objects
./mvnw test -Dtest=CustomerFetchTest 2>&1 | grep -E "distinct|Duplicate"

curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** For each view, create a *named* fetch method (`findAllWithAddressesJoinFetch`) rather than making associations EAGER. Use `JOIN FETCH` when query has predicates/order; `EntityGraph` when you just control fetching; `NamedEntityGraph` when the same graph repeats.
- **Operate:** Enable `generate_statistics: true` (`application.yml:56`) in staging and assert query counts in integration tests. Alert if `prepareStatementCount` for "list customers" jumps from 1 to N+1 — means someone added a lazy traversal without a fetch method.
- **Interview:** "PR #6: `LAZY` N+1 is 1 + N queries. Three fixes all yield 1: `JOIN FETCH` (`CustomerRepository.java:61`) with `DISTINCT` for dedup, `@EntityGraph(attributePaths)` (`CustomerRepository.java:70`) declarative, `@NamedEntityGraph` (`Customer.java:34` → `CustomerRepository.java:80`) reusable. Don't paginate a collection fetch and don't fetch two bags in one query — cartesian product. Those are where PR #7 batch fetching (`application.yml:61`) wins."

---

## 9. Interview lens — Q&A

**Q1: Why `DISTINCT` in `JOIN FETCH`?**
A: Joining a collection multiplies root rows (customer with 3 addresses → 3 SQL rows). Without `DISTINCT`, JPA returns duplicate Customer objects. `DISTINCT` in `CustomerRepository.java:61` dedupes at object level (Hibernate in-memory, not SQL DISTINCT) so `List<Customer>` size equals customer count. See §2.2.

**Q2: Difference between `@EntityGraph` and `JOIN FETCH`?**
A: Same SQL outcome, different declaration: `JOIN FETCH` is imperative inside JPQL, flexible for complex predicates; `@EntityGraph` (`CustomerRepository.java:70`) is declarative path list, Hibernate builds the join for you. Choose by reuse and query complexity — table in §2.5.

**Q3: How verify without trusting migration?**
A: `DatabaseSchemaIntegrationTest.java:56` clears Hibernate statistics, calls each fetch method, asserts `prepareStatementCount == 1` and `Hibernate.isInitialized(customer.getAddresses())`, vs naive `findAll()` + loop where count is N+1 and collections are uninitialized until touched. Plus `psql \d` and live `pg_constraint`.

**Q4: Why not `JOIN FETCH` two collections at once?**
A: Rows multiply (§2.7): 3 addresses × 4 orders = 12 rows/customer, data explodes, and Hibernate throws `MultipleBagFetchException` for two `List` bags. Fix: fetch one collection per query or use `@BatchSize` (PR #7) `IN` queries.

**Q5: Next step?**
A: PR #7 Batch Fetching — global `default_batch_fetch_size: 20` (`application.yml:61`) + `@BatchSize` (`Order.java:84`, `Customer.java:69`) that fixes remaining N+1 via `IN` queries without explicit fetch methods, pagination-safe.

---

## 10. Honest limits & next step → PR #7

Tuned queries need caller to *know* they will traverse — every new view needs a fetch method. For code that generically iterates collections (reports, batch jobs) without per-query tuning, explicit fetch doesn't scale. PR #7 adds transparent `IN`-based batch fetching: when one lazy collection initializes, Hibernate loads up to `batchSize` others in one `WHERE customer_id IN (...)` query — no JPQL change.

See [`07-batch-fetching.md`](./07-batch-fetching.md) or [`README.md`](./README.md).
