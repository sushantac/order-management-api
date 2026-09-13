# 11. Custom Queries (PR #11)
> PR #11 — Custom Queries with `@Query` JPQL vs native SQL, SpEL `:#{#param}`, projections (interface vs class vs DTO) and pagination. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Spring Data JPA. See `README.md:1772` roadmap `| 11 | Custom Queries |`.
---
## 1. Purpose — what shipped
PR #11 delivers **Custom Queries** as a first-class, tested, documented building block. `OrderRepository.java:26-106` showcases every production query flavour on one repository: complex JPQL joins/ordering (`findRecentOrdersByCustomer` `OrderRepository.java:48`), native SQL with an interface projection (`findCustomerSpendNative` `OrderRepository.java:64`), pagination via `@Query` + `Pageable` (`findOrdersPaged` `OrderRepository.java:78`), class-based constructor projection with aggregates (`findCustomerOrderTotals` `OrderRepository.java:86`), and SpEL-driven dynamic filters (`findByAmountRange` `OrderRepository.java:101` `:#{#range.minTotal()}`) where `null` means "no filter". Also demonstrates `CustomerRepository.java:40` interface projection alternative. Proven by `DatabaseSchemaIntegrationTest.java:56` and repository tests.
---
## 2. Problem — before/after + Theory (first principles)
**Before:** Only derived queries (`findByEmail` `CustomerRepository.java:46`) or `findAll()` + batch fetching (PR #7). No way to express "recent orders for customer X with status Y and amount ≥ Z", no native SQL for reports, no pagination contract, no lightweight projection to avoid loading full entities. SpEL dynamic filtering would have been string-concatenated JPQL — injection risk.
**After:** One `@Query`-annotated method per query flavour, each type-safe and testable. JPQL is portable and entity-oriented; native SQL is dialect-specific but can use PostgreSQL features; SpEL `:#{#range.minTotal()}` lets one method cover four filter combinations with `is null or ...` guards; record `AmountRange` (`OrderRepository.java:29`) groups parameters; `CustomerSpend` projection shows native mapping via aliases; `CustomerOrderTotal` shows JPQL constructor.
### Theory — `@Query` from first principles (100+ lines)
#### 2.1 JPQL vs native SQL vs derived queries

| Dimension | Derived (`findByEmail`) | JPQL (`@Query select o from Order o`) | Native (`@Query nativeQuery=true`) |
|---|---|---|---|
| Language | Method-name DSL parsed by Spring Data | JPQL (entity + association names) → translated by Hibernate to SQL | Raw SQL as-is, passed to JDBC |
| Navigates via | Property path (`findByCustomerEmail`) | Association path `o.customer.id` (`OrderRepository.java:50`) | Table/column names `orders.customer_id` (`OrderRepository.java:65`) |
| Portability | ✔ | ✔ (JPQL is dialect-agnostic) | ✖ PostgreSQL-specific (`GROUP BY`, window functions, `jsonb`) |
| Selected columns | Full entity always | Full entity or projection (class/interface) | Any columns — mapped by aliases → getters |
| `ORDER BY` | `Sort` param or `OrderBy` suffix | Explicit `order by o.orderDate desc` (`OrderRepository.java:53`) | `ORDER BY totalSpent DESC` (`OrderRepository.java:70`) |
| Use when | Simple predicate, no custom SQL | Multi-predicate, join, ordering, projection | Reports, `GROUP BY`, DB-specific functions |

Example contrast from the same repo:

```java
// Derived — no @Query at all
Optional<Customer> findByEmail(String email); // CustomerRepository.java:46

// JPQL — entity names, join via association path
@Query("select o from Order o where o.customer.id = :customerId and o.status = :status ...") // OrderRepository.java:48

// Native — table names, aliases must match interface getters
@Query(value = "SELECT c.email AS email, SUM(o.total_amount) AS totalSpent FROM orders ...", nativeQuery = true) // OrderRepository.java:64
```

JPQL wins for entity graphs (fetches, lazy handling); native wins for analytics where `GROUP BY` / `HAVING` / `CTE` are needed and full entities would be wasteful.

#### 2.2 `@Query` anatomy — JPQL path, `nativeQuery`, binding

```java
// OrderRepository.java:48 — canonical JPQL
@Query("""
        select o from Order o
        where o.customer.id = :customerId
          and o.status = :status
          and o.totalAmount >= :minAmount
        order by o.orderDate desc
        """)
List<Order> findRecentOrdersByCustomer(@Param("customerId") Long customerId,
                                       @Param("status") OrderStatus status,
                                       @Param("minAmount") BigDecimal minAmount);
```

- `select o from Order o` — `Order` is *entity* name (`Order.java:43` `@Entity`), not table name `orders` (`Order.java:42` `@Table(name="orders")`). JPQL parser resolves it via the metamodel. `o.customer.id` traverses `Order.customer` (`Order.java:46` `@ManyToOne`) → uses the FK column `customer_id` without an explicit `join`.
- `nativeQuery = true` flag makes Hibernate skip JPQL parsing and send the string verbatim to PostgreSQL. Aliases must exactly match projection getter names (case-insensitive for PostgreSQL, but Spring's mapping is strict): `SELECT c.email AS email` → `CustomerSpend.getEmail()` (`OrderRepository.java:37`).
- `@Param` binds by name. Without it, `@Query` uses positional `?1, ?2`. For SpEL `:#{#range.minTotal()}` the `@Param("range")` name is required because SpEL references the parameter object by its bound name.

#### 2.3 JPQL `nativeQuery = false` (default) — what's generated

Hibernate translates JPQL to SQL at startup (`QueryTranslator`). For `findRecentOrdersByCustomer`:

```
JPQL: select o from Order o where o.customer.id = :customerId and o.status = :status
SQL:  select o.id, o.version, o.created_at, ..., o.customer_id, o.order_number
      from orders o where o.customer_id=? and o.status=? and o.total_amount>=? order by o.order_date desc
```

Columns are expanded from the entity mapping (`Order.java:46-94`). The `version` and audit columns (`BaseEntity.java:53/59`) are selected too — JPQL returns managed entities, not raw rows. Native queries do not expand — you pick columns explicitly.

#### 2.4 SpEL in `@Query` — dynamic filters without concatenation

The hard problem: one method that handles all four combinations `(min, max) = (null,null), (value,null), (null,value), (value,value)`. String concatenation would be:

```java
// Anti-pattern — DO NOT DO:
String jpql = "select o from Order o where 1=1 ";
if (min != null) jpql += "and o.totalAmount >= :min ";
if (max != null) jpql += "and o.totalAmount <= :max ";
// → untyped, injection if misused, not parsed at startup
```

SpEL solves it declaratively in one `@Query` (`OrderRepository.java:101`):

```java
record AmountRange(BigDecimal minTotal, BigDecimal maxTotal) {} // OrderRepository.java:29

@Query("""
        select o from Order o
        where (:#{#range.minTotal()} is null or o.totalAmount >= :#{#range.minTotal()})
          and (:#{#range.maxTotal()} is null or o.totalAmount <= :#{#range.maxTotal()})
        """)
List<Order> findByAmountRange(@Param("range") AmountRange range);
```

- `:#{#range.minTotal()}` — Spring evaluates `range.minTotal()` via SpEL at invocation, binds the result as a named param (`?` placeholder). If `minTotal` is `null`, the `is null` branch short-circuits and the inequality is not evaluated (safe). If non-null, both branches run — second compares `o.totalAmount >= :boundValue`.
- `AmountRange` as `record` (`OrderRepository.java:29`) makes the parameter object immutable and destructurable; method signature stays one param instead of `min,max`.
- Two predicates (min, max) × nullable → 4 behaviours with zero code branches. Adding a third filter (e.g., `status`) would add `and (:#{#filter.status()} is null or o.status = :#{#filter.status()})`.
- SpEL expressions are parsed at startup, so typos like `:#{#range.minTotl()}` fail fast at context load, not at runtime call.

Limitation: SpEL guards (`is null or ...`) produce slightly verbose SQL (`? is null or total_amount >= ?`) but Postgres optimizes `NULL is null` → true cheaply. For many filters, Specifications (PR #12) are a more compositional alternative (see `CustomerSpecifications.java:28`); SpEL is lighter for 2-4 optional params.

#### 2.5 Projections — interface vs class vs DTO

PR #11 + PR #17 together show three projection strategies:

**Interface projection (open/closed):**

```java
// OrderRepository.java:37 — native-SQL side
interface CustomerSpend { String getEmail(); BigDecimal getTotalSpent(); }
// aliases: SELECT c.email AS email, SUM(...) AS totalSpent — must match getEmail/getTotalSpent

// CustomerRepository.java:40 — JPQL side
interface CustomerNameProjection { String getEmail(); String getFullName(); }
@Query("select c.email as email, c.fullName as fullName from Customer c ...")
List<CustomerNameProjection> findAllCustomerNameProjections();
```

Spring generates a proxy implementing the interface; getters are fed from column aliases. Only the listed columns are fetched — the full entity is NOT loaded, so version/audit columns are excluded. Interface projections are ideal for read-only reports; they are not managed entities (no dirty checking, no `@Version` increment).

**Class projection (constructor expression):**

```java
// OrderRepository.java:86
@Query("select new com.company.orderapi.domain.repository.CustomerOrderTotal(o.customer.email, sum(o.totalAmount)) from Order o group by o.customer.email")
List<CustomerOrderTotal> findCustomerOrderTotals();
```

`new CustomerOrderTotal(...)` (`CustomerOrderTotal.java` constructor) is instantiated per row via JPQL `NEW`. Unlike interface projection, the class is a normal Java type (can have aggregates, can be `record`). Requires fully-qualified class name in JPQL. Good for grouped aggregates where interface projection cannot hold `sum(...)`.

**DTO projection (PR #21) and `record`:**

Class projections pair naturally with Java `record`s (`OrderRepository.java:29` `AmountRange`) for parameter objects and with response DTOs (PR #21) for API output — see §10.

Comparison:

| Aspect | Interface (`CustomerNameProjection`) | Class (`CustomerOrderTotal`) | Entity (`Order`) |
|---|---|---|---|
| Loaded columns | Only listed aliases | Only constructor args | All entity columns + version + audit |
| Managed? | No (read-only proxy) | No (DTO) | Yes (dirty-checked, versioned `BaseEntity.java:53`) |
| `GROUP BY / SUM` | Yes — via matching alias types | Yes — constructor can take aggregate | No aggregation |
| Reusable | Repeat query per projection | Same JPQL shared via `new ...` | Full graph |

#### 2.6 Pagination with `@Query`

```java
// OrderRepository.java:78
@Query("select o from Order o")
Page<Order> findOrdersPaged(Pageable pageable);
```

- `Pageable` carries `page`, `size`, `sort` (maps to `LIMIT/OFFSET` + `ORDER BY` in SQL; dialect renders as PostgreSQL `limit ? offset ?`).
- Return `Page<Order>` gives `totalElements`, `totalPages`, `hasNext` — Spring runs a *count query* derived from the JPQL (`select count(o) from Order o`) automatically. For complex queries where count derivation fails, supply `@Query(countQuery="select count(o) ...")`.
- `Page<Order>` via JPQL loads managed entities (with version/audit), so subsequent `order.setStatus(...)` is dirty-checked and flushed with `WHERE version=?` (`BaseEntity.java:53`).
- Native pagination: must write `countQuery` manually (`@Query(value="... from orders ...", countQuery="select count(*) from orders ...", nativeQuery=true)`).

#### 2.7 `totalRevenue()` — simple JPQL aggregation

```java
// OrderRepository.java:33
@Query("select coalesce(sum(o.totalAmount), 0) from Order o")
BigDecimal totalRevenue();
```

`coalesce(...,0)` handles empty table (no orders → `NULL` → `0`). Maps to `SELECT coalesce(sum(total_amount), 0) FROM orders`. Demonstrates that `@Query` is not only for `find*` — aggregations, `exists`, `count`, `void` bulk updates all use it.

#### 2.8 When to choose which `@Query` flavour — decision matrix

| Query shape | Chosen flavour | File:line |
|---|---|---|
| One-predicate, no custom SQL | Derived (`findByEmail`) | `CustomerRepository.java:46` |
| Multi-predicate + join + order by (entity result) | JPQL `@Query` | `OrderRepository.java:48` |
| Report with `GROUP BY`, DB fn (`SUM`, `jsonb`, window) | Native `@Query(nativeQuery=true)` | `OrderRepository.java:64` |
| 2-4 optional filters, one method | SpEL `:#{#range...}` | `OrderRepository.java:101` |
| Read-only lightweight response (avoid entity load) | Interface projection | `OrderRepository.java:37` / `CustomerRepository.java:40` |
| Grouped aggregate row | Class projection `new ...` | `OrderRepository.java:86` |
| Paged list | `@Query` + `Pageable` → `Page` | `OrderRepository.java:78` |
| Many optional filters (5+) composed | Specifications (PR #12) | `CustomerSpecifications.java:28` |

#### 2.9 Interview-ready mental model

> "Spring Data's `@Query` has three flavours: JPQL (`OrderRepository.java:48` `select o from Order o` — entity names, `o.customer.id` traverses `Order.java:46` `@ManyToOne`, generates `SELECT ... FROM orders WHERE customer_id=?`), native (`OrderRepository.java:64` `nativeQuery=true` — raw `SELECT ... FROM orders JOIN customers ... GROUP BY`, aliases must match `CustomerSpend` getters `OrderRepository.java:37`), and SpEL (`OrderRepository.java:101` `:#{#range.minTotal()}` `is null or ...`) where `AmountRange` `record` (`OrderRepository.java:29`) gives one method covering four `null`/value filter combinations without string concatenation. Projections: interface (`CustomerRepository.java:40`, `OrderRepository.java:37`) is a proxy with only listed columns; class (`OrderRepository.java:86` `new CustomerOrderTotal(...)`) carries aggregates; `Page` (`OrderRepository.java:78`) adds `LIMIT/OFFSET` + auto count query. Native is for reports, JPQL for graphs, SpEL for few dynamic filters, Specifications (PR #12) for many."

---

## 3. Solution — ASCII

```
Derived (no @Query):                   JPQL @Query (entity graph):           Native @Query (report):

 CustomerRepository                     OrderRepository.java:48               OrderRepository.java:64
 findByEmail(String)                    @Query("select o from Order o          @Query(value="SELECT c.email AS email,
   ↓ derived to                           where o.customer.id=:id               SUM(o.total_amount) AS totalSpent
 SELECT * FROM customers                 and o.status=:status                    FROM orders o JOIN customers c
 WHERE email=?                           and o.totalAmount>=:min                 GROUP BY c.email
                                             order by orderDate desc")           ORDER BY totalSpent DESC",
                                         List<Order>                            nativeQuery=true)
                                        → managed entities                      List<CustomerSpend>  // aliases → getters
                                          (version BaseEntity.java:53)            // CustomerSpend.java:37

 SpEL dynamic filter (OrderRepository.java:101):          Pagination (OrderRepository.java:78):

  record AmountRange(min,max)                               @Query("select o from Order o")
  @Query("select o from Order o                               Page<Order> findOrdersPaged(Pageable p)
   where (:#{#range.minTotal()} is null                      → LIMIT ? OFFSET ? + auto COUNT query
          or o.totalAmount>=:#{#range.minTotal()})             Page.totalElements / totalPages / hasNext
         and (:#{#range.maxTotal()} is null
          or o.totalAmount<=:#{#range.maxTotal()})")
  List<Order> findByAmountRange(AmountRange)  // one method, 4 filter combos, no string concatenation

 Projections hierarchy:
  Order entity (full) ──→ SELECT all cols + version + audit (BaseEntity.java:53/59)
  Class new CustomerOrderTotal(...) ──→ SELECT email, sum(amount) GROUP BY (OrderRepository.java:86)
  Interface CustomerSpend/CustomerName ──→ SELECT only aliases (OrderRepository.java:37, CustomerRepository.java:40)
                                           proxy not managed, read-only
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/repository/OrderRepository.java` | `26` | Repository header | "Every flavour of `@Query` on one repo" |
| `OrderRepository.java` | `29` | `AmountRange` record | Groups min/max for SpEL — one param instead of two |
| `OrderRepository.java` | `33` | `totalRevenue()` | JPQL `coalesce(sum(...),0)` — empty-table safe |
| `OrderRepository.java` | `37-41` | `CustomerSpend` interface | `getEmail()` / `getTotalSpent()` — aliases must match |
| `OrderRepository.java` | `48-58` | `findRecentOrdersByCustomer` | JPQL with `o.customer.id` association nav + `order by orderDate desc` |
| `OrderRepository.java` | `64-72` | `findCustomerSpendNative` | Native SQL `JOIN customers` + `GROUP BY email` + `ORDER BY totalSpent DESC` |
| `OrderRepository.java` | `78-79` | `findOrdersPaged` | `@Query` + `Pageable` → `Page` (LIMIT/OFFSET + count) |
| `OrderRepository.java` | `86-93` | `findCustomerOrderTotals` | Class projection via `new CustomerOrderTotal(...)` + `group by email` |
| `OrderRepository.java` | `101-106` | `findByAmountRange` | SpEL `:#{#range.minTotal()}` `is null or ...` guards; `AmountRange` param |
| `src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java` | `40-52` | Interface projection | `CustomerNameProjection` + JPQL `select c.email as email, c.fullName as fullName` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `46` | `customer` association | `o.customer.id` in JPQL traverses this `@ManyToOne` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `65` | `orderNumber` | Not selected by native `CustomerSpend` — only entity queries include it |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `53/59` | `version` / `createdAt` | JPQL-loaded `Order` carries them; projections do not |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Asserts `@Query` methods return correctly against Testcontainers DB |

```java
// OrderRepository.java:48 — JPQL with association navigation
@Query("""
        select o from Order o
        where o.customer.id = :customerId
          and o.status = :status
          and o.totalAmount >= :minAmount
        order by o.orderDate desc
        """)
List<Order> findRecentOrdersByCustomer(@Param("customerId") Long customerId,
                                       @Param("status") OrderStatus status,
                                       @Param("minAmount") BigDecimal minAmount);

// OrderRepository.java:64 — native with alias → projection
@Query(value = """
        SELECT c.email AS email, SUM(o.total_amount) AS totalSpent
        FROM orders o JOIN customers c ON c.id = o.customer_id
        GROUP BY c.email ORDER BY totalSpent DESC
        """, nativeQuery = true)
List<CustomerSpend> findCustomerSpendNative();

// OrderRepository.java:101 — SpEL dynamic filter
@Query("""
        select o from Order o
        where (:#{#range.minTotal()} is null or o.totalAmount >= :#{#range.minTotal()})
          and (:#{#range.maxTotal()} is null or o.totalAmount <= :#{#range.maxTotal()})
        """)
List<Order> findByAmountRange(@Param("range") AmountRange range);
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run custom-query tests
./mvnw test -Dtest=OrderRepositoryTest,CustomerRepositoryTest,DatabaseSchemaIntegrationTest -Dspring.profiles.active=test

# Verify @Query methods count (should find 5+ @Query in OrderRepository)
grep -n "@Query" src/main/java/com/company/orderapi/domain/repository/OrderRepository.java

# Show SQL for each flavour
./mvnw test -Dtest=OrderRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -E "select.*from (orders|customers)" | head -20
# Expect: JPQL → SELECT with entity cols + WHERE customer_id=? and status=? ; native → SELECT email, SUM(...) GROUP BY

# Smoke API — recent orders for customer 1 (if seeded)
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT id, total_amount, status, order_date FROM orders WHERE customer_id=1 AND total_amount>=100 ORDER BY order_date DESC LIMIT 5;
"

# Health
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq .
```

```java
// JPQL with three predicates
List<Order> recent = orderRepository.findRecentOrdersByCustomer(customerId, OrderStatus.PLACED, BigDecimal.valueOf(100));

// Native report — customer spend ranking
List<OrderRepository.CustomerSpend> spend = orderRepository.findCustomerSpendNative();
spend.forEach(s -> log.info("{} spent {}", s.getEmail(), s.getTotalSpent()));

// Class projection with aggregate
List<CustomerOrderTotal> totals = orderRepository.findCustomerOrderTotals();

// Pagination — page 2 of orders, 20 per page, sorted by orderDate desc
Page<Order> page = orderRepository.findOrdersPaged(PageRequest.of(1, 20, Sort.by("orderDate").descending()));
long total = page.getTotalElements(); // auto count query

// SpEL dynamic filter — one method, four filter combos
orderRepository.findByAmountRange(new OrderRepository.AmountRange(null, null));           // all orders
orderRepository.findByAmountRange(new OrderRepository.AmountRange(BigDecimal.TEN, null)); // min only
orderRepository.findByAmountRange(new OrderRepository.AmountRange(null, BigDecimal.valueOf(500))); // max only
orderRepository.findByAmountRange(new OrderRepository.AmountRange(BigDecimal.TEN, BigDecimal.valueOf(500))); // both

// Interface projection — only email + fullName, no entity load
List<CustomerRepository.CustomerNameProjection> names = customerRepository.findAllCustomerNameProjections();
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| JPQL for entity graph queries | `OrderRepository.java:48` | Native SQL with manual mapping | Entity names, associations (`o.customer.id`), portability, managed results with version (`BaseEntity.java:53`) | Cannot use PostgreSQL-specific `jsonb` / window functions |
| Native for report | `OrderRepository.java:64` `GROUP BY` | JPQL `NEW` or two queries + java grouping | One SQL with `SUM`/`GROUP BY`/`ORDER BY` is the natural DB strength; aliases → interface proxy is lean | PostgreSQL-specific, not portable; needs alias→getter match |
| SpEL `:#{#range...}` for 2 filters | `OrderRepository.java:101` record `AmountRange` | String-concatenated JPQL or 4 separate methods | One method, no branch, parsed at startup, `null` means "no filter" safely | SQL contains `? is null or ...` — slightly verbose, Postgres optimizes it |
| `record AmountRange` | `OrderRepository.java:29` | Two params `min, max` | Single param object destructured by SpEL; immutable | Extra type (but self-documenting) |
| Interface projection | `CustomerRepository.java:40` / `OrderRepository.java:37` | Always return entity | Only needed columns fetched; no managed context cost; good for read-only UI | Not updatable, no version increment, `@Version` not available |
| Class projection `new ...` | `OrderRepository.java:86` | Interface projection for aggregation | Class can take `sum(...)` aggregate cleanly | FQCN in JPQL is noisy |
| `Page<Order>` from `@Query` | `OrderRepository.java:78` | `findAll(Pageable)` derived | Custom ordering/predicates where derived method cannot express them | Auto count query may be expensive on large table → supply `countQuery` hint if needed |

---

## 7. How to verify

```bash
# Repository tests — all @Query methods against Testcontainers Postgres
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderRepositoryTest

# Show generated SQL per flavour
./mvnw test -Dtest=OrderRepositoryTest -Dorg.hibernate.SQL=DEBUG -Dorg.hibernate.orm.jdbc.bind=TRACE 2>&1 \
  | grep -E "select.*order|join.*customer|group by" | head -20

# Verify aliases match projection getters (if alias wrong, Spring throws PropertyReferenceException at context load)
# Intentionally rename AS email → AS foo → startup fails "Could not extract ColumnReference"

# Pagination count query visible
./mvnw test -Dtest=OrderRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "count.*order"

# SpEL null handling — call with AmountRange(null,null) vs (10, 500), assert row counts
./mvnw test -Dtest=OrderRepositoryTest#shouldFindByAmountRange_NullMeansNoFilter

# Schema — orders table columns used by @Query match entity mappings
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders" | grep -E "customer_id|total_amount|status|order_date"

# Health
curl -s http://localhost:8080/actuator/health | jq .components.db
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Use JPQL (`@Query("select o from Order o where o.customer.id = :id")` `OrderRepository.java:48`) when you need managed entities and association navigation; native (`nativeQuery=true` `OrderRepository.java:64`) for reports with `GROUP BY`/`SUM`/window functions where loading entities wastes work. For 2-4 optional filters, prefer SpEL `:#{#range.field()}` with `is null or ...` (`OrderRepository.java:101`) and a `record` param (`OrderRepository.java:29`) over string concatenation — one method covers all combos. Return `interface` projections (`CustomerRepository.java:40`) for read-only lean responses; `new Class(...)` (`OrderRepository.java:86`) when the row carries aggregates. Always validate alias→getter match at startup.
- **Operate:** Monitor slow `@Query` methods via Micrometer timers on repositories; `EXPLAIN (ANALYZE, BUFFERS) SELECT ... FROM orders WHERE customer_id=1 AND status='PLACED'` should show `idx_orders_customer_id` (`01_create_tables.sql:182`) usage. Alert on `PessimisticLockException` if a `@Query` uses `@Lock` (PR #9) and holds too long. `findOrdersPaged` count query is the hidden cost on large tables — watch `totalElements` latency. Audit which `@Query` are native — they couple you to PostgreSQL (`nativeQuery=true` `OrderRepository.java:64`).
- **Interview:** "PR #11: `OrderRepository.java:26-106` shows five `@Query` flavours: JPQL (`findRecentOrdersByCustomer` `OrderRepository.java:48` — `o.customer.id` association nav), native (`findCustomerSpendNative` `OrderRepository.java:64` — `JOIN customers GROUP BY` + interface `CustomerSpend` `OrderRepository.java:37` alias→getter), pagination (`findOrdersPaged` `OrderRepository.java:78` `Pageable`+auto count), class `new CustomerOrderTotal` (`OrderRepository.java:86` aggregate), SpEL (`findByAmountRange` `OrderRepository.java:101` `:#{#range.minTotal()} is null or ...` via `record AmountRange` `OrderRepository.java:29` covering 4 filter combos). Interface (`CustomerRepository.java:40`) vs class (`OrderRepository.java:86`) vs entity trade-offs in §2.5. Tested by `DatabaseSchemaIntegrationTest.java:56`."
---

## 9. Interview lens — Q&A

**Q1: When choose JPQL vs native `@Query`?** A: JPQL (`OrderRepository.java:48`) for managed entities, association navigation; native (`OrderRepository.java:64` `nativeQuery=true`) for `GROUP BY`/`SUM`/DB-specific features where a report doesn't need entities. See §2.1 table.

**Q2: What is `:#{#range.minTotal()}` and why not concatenate JPQL?** A: SpEL (`OrderRepository.java:101`) evaluates `range.minTotal()` to a bind param; guard `is null or ...` makes `null` mean "no filter", so one method covers 4 combos. Concatenation is injection-risky and untyped; SpEL is parsed at startup. See §2.4.

**Q3: Difference between interface and class projections?** A: Interface (`CustomerSpend` `OrderRepository.java:37`) is a Spring proxy not managed, read-only. Class (`new CustomerOrderTotal` `OrderRepository.java:86`) is constructed via JPQL `NEW` and can carry aggregates. See §2.5.

**Q4: How does pagination with `@Query` work?** A: `Pageable` (`OrderRepository.java:78`) renders `LIMIT/OFFSET`; `Page` derives a `count` query (`select count(o)`) for `totalElements`. Supply `countQuery` if derivation fails. See §2.6.

---

## 10. Honest limits & next step → PR #12

SpEL `is null or ...` (§2.4) produces slightly heavier SQL; many optional filters (5+) become unwieldy — Specifications (`CustomerSpecifications.java:28` + `JpaSpecificationExecutor` `CustomerRepository.java:32`) and QueryDSL are better there. Native queries (`OrderRepository.java:64`) tie reports to PostgreSQL. Projections are read-only — updates still need managed entities. Next, PR #12 delivers dynamic, composable queries.

See [`12-specifications-and-querydsl.md`](./12-specifications-and-querydsl.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| Simple predicate | Derived `findByEmail` | `CustomerRepository.java:46` | Zero annotation |
| Multi-predicate + join | JPQL `@Query` | `OrderRepository.java:48` | Entity nav, portable |
| Analytics / GROUP BY | Native `@Query` | `OrderRepository.java:64` | DB aggregation strength |
| 2-4 optional filters, one method | SpEL + `record` | `OrderRepository.java:101` + `29` | No concatenation, 4 combos |
| Lean read-only row | Interface projection | `CustomerRepository.java:40` | Only selected cols fetched |
| Row with `SUM/GROUP BY` | Class `new ...` | `OrderRepository.java:86` | Constructor takes aggregate |
| Paged list | `@Query` + `Pageable` | `OrderRepository.java:78` | `LIMIT/OFFSET` + count |
| 5+ composable filters | Specifications | `CustomerSpecifications.java:28` | `where(...).and(...)` chain |
