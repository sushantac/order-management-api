# 17. DTO Projections (PR #17)

> PR #17 — DTO Projections: interface-closed, class/constructor, and native projections; DTO vs entity tradeoffs. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Spring Data JPA, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 17 | DTO Projections |`.

---

## 1. Purpose — what shipped

PR #17 introduces **DTO projections** — repository methods that `SELECT` only the needed columns and map them directly to a lightweight DTO, never loading the full entity into the persistence context. Two concrete patterns shipped: **interface-based closed projection** `CustomerRepository.CustomerNameProjection` (`CustomerRepository.java:40-53`) with JPQL `select c.email as email, c.fullName as fullName`, and **class-based constructor projection** `CustomerOrderTotal` (`CustomerOrderTotal.java:15`, `OrderRepository.findCustomerOrderTotals` `OrderRepository.java:86-93`) via `select new com.company.orderapi.domain.repository.CustomerOrderTotal(...)` that carries an aggregate `sum(totalAmount)`. A third **native-SQL projection** `OrderRepository.CustomerSpend` (`OrderRepository.java:37-72`) demonstrates column-alias mapping for non-JPQL aggregates. All are read-only, bypass dirty checking, and are the fastest read path when the full entity graph is not needed.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Every read loaded the full entity graph. `orders.findAll()` loaded `Order` + `customer` lazy proxy + `items` (extra queries or joins) + `version/audit` — even when the dashboard only needed `email + sum(totalAmount)` per customer (`OrderRepository.java:86`). That triggered the persistence context: entities became managed, dirty-checked on every `flush()`, and held identity (`==`) — overhead proportional to column count × row count. For reference reads like `findAllCustomerNameProjections`, the API paid `SELECT *` for 10 columns to display 2.

**After:** `customerRepository.findAllCustomerNameProjections()` (`CustomerRepository.java:53`) emits `select c.email as email, c.fullName as fullName from Customer c` — only 2 columns, no entity instantiation, not managed. `orderRepository.findCustomerOrderTotals()` (`OrderRepository.java:86`) runs `select new CustomerOrderTotal(email, sum(totalAmount)) group by email` — aggregates without hydration. `orderRepository.findCustomerSpendNative()` (`OrderRepository.java:64`) pushes `SUM` into Postgres natively. Each is a single `SELECT` of minimal width, no persistence context participation.

### Theory — projections from first principles (100+ lines)

#### 2.1 Entities vs DTOs vs Projections — the cost of `SELECT *`

```
SELECT o FROM Order o  →  Hibernate: SELECT id, order_number, order_date, status, total_amount, version,
                                         created_at, updated_at, customer_id, ... FROM orders
                          → hydrates Order managed entity → L1 → dirty-check → identity map
                          Cost: N columns × N rows hydrated + managed overhead

SELECT email, sum(amount)  →  Hibernate: SELECT c.email, SUM(o.total_amount) ... GROUP BY c.email
                          → instantiates CustomerOrderTotal(String, BigDecimal) via constructor
                          → not managed, not dirty-checked, not cached
                          Cost: 2 columns × distinct(customers) + one object per row
```

Fetching 10k orders as entities to compute spend per customer loads 10k×`Order` + `Customer` proxies = ~80k column values, then sums in Java. Projection does `SUM` in Postgres and returns ~200 `CustomerOrderTotal` rows (one per email) — 50× less data transferred, 0 managed entities, no N+1.

#### 2.2 Interface projections (closed) — `CustomerNameProjection`

```java
// CustomerRepository.java:40-53
interface CustomerNameProjection { String getEmail(); String getFullName(); }

@Query("select c.email as email, c.fullName as fullName from Customer c order by c.fullName")
List<CustomerNameProjection> findAllCustomerNameProjections();
```

Mechanics: Spring Data Data JPA generates a JDK proxy for the interface at runtime. Hibernate executes the JPQL, reads `email, fullName` from `ResultSet`, and the proxy's `getEmail()`/`getFullName()` returns those values. Requirements:

- **Alias → getter:** `select c.email as email` alias must match `getEmail` (case `email`). `as` is mandatory for alias mapping.
- **Closed:** interface declares only getters for selected columns; Spring Data validates every getter has a matching alias. An open projection (`@Value("#{target.email}")`) would load the entity first — defeating the projection.
- **Not managed:** the proxy is not an entity; `EntityManager.contains(projection)` is false; no `UPDATE` will ever flush it.
- **Dynamic:** Spring Data can infer interface projections from method return type without `@Query`, but explicit `@Query` guarantees the `SELECT` stays minimal (derived query might `SELECT *` then project).

Use for: simple `SELECT` of a few entity columns where no aggregation or expression is needed.

#### 2.3 Class / constructor projections — `CustomerOrderTotal`

```java
// CustomerOrderTotal.java:15 — plain class with constructor
public class CustomerOrderTotal {
    private final String email; private final BigDecimal totalSpent;
    public CustomerOrderTotal(String email, BigDecimal totalSpent) { ... }
}

// OrderRepository.java:86-93
@Query("select new com.company.orderapi.domain.repository.CustomerOrderTotal(o.customer.email, sum(o.totalAmount)) from Order o group by o.customer.email order by o.customer.email")
List<CustomerOrderTotal> findCustomerOrderTotals();
```

Mechanics: JPQL `select new FQN(args)` tells Hibernate to call that constructor with the `SELECT` expressions. Hibernate reads `email` and `sum(...)` from `ResultSet` and `new`s the object — no proxy, a real type-safe instance. Advantages over interface:

- Supports aggregates (`sum`, `count`, `avg`), expressions (`coalesce`, `case when`), and DTO nesting in constructor args.
- Concrete type → usable in `Map`, `Stream`, `collect`, JSON response without proxy serialization issues.
- `record` also works (PR #21 uses records as DTOs with same mechanism), but PR #17 uses a class because `record` canonical constructor must match `SELECT` order exactly.

Limitation: constructor expression cannot `SELECT` an entity and scalar together — use interface or `Tuple` for mixed.

#### 2.4 Native projections — `CustomerSpend`

```java
// OrderRepository.java:37-72
interface CustomerSpend { String getEmail(); BigDecimal getTotalSpent(); }
@Query(value = "SELECT c.email AS email, SUM(o.total_amount) AS totalSpent FROM orders o JOIN customers c ... GROUP BY c.email ...", nativeQuery = true)
List<CustomerSpend> findCustomerSpendNative();
```

Mechanics: `nativeQuery=true` bypasses JPQL translation — raw SQL on Postgres with `o.total_amount` column names. Spring Data maps `AS totalSpent` → `getTotalSpent()` via alias. Needed when:

- Postgres-specific syntax (`WINDOW`, `LATERAL`, `jsonb` operators), or
- Legacy `orders`/`customers` table names / column names that don't match entity path `o.customer.email` navigation.

Cost: native SQL is dialect-locked; switching to another DB breaks it. JPQL constructor (`OrderRepository.java:86`) is portable; prefer it unless native is required.

#### 2.5 What projections bypass — persistence context, dirty checking, L2

```
Entity load:   ResultSet → hydrate → managed entity → L1 (StatefulPersistenceContext) → snapshot → dirty-check@flush → L2 put (if @Cacheable)
Projection:    ResultSet → proxy / new DTO → NOT in L1, no snapshot, no dirty-check, no L2
```

Consequences:

- Projections never trigger `flush()` work — faster commits.
- Projections bypass L2 (`Product.java:33` cached entity still served from L2 on `findById`, but `findCustomerOrderTotals` always hits DB — correct, because aggregates are up-to-the-moment).
- `EntityGraph` / `JOIN FETCH` irrelevant — projections already minimal.
- Lazy loading absent — projection getters never fire an extra `SELECT`.

#### 2.6 Interface vs class vs record — decision space

| Dimension | Interface closed (`CustomerRepository.java:40`) | Class (`CustomerOrderTotal.java:15`) | Record (`OrderResponse.java:16` as DTO) |
|---|---|---|---|
| Aggregates / expressions | Limited (no `sum` mapping without alias) | ✔ (`sum(totalAmount)`) | ✔ (same as class) |
| Type safety / IDE | Proxy, getters only | Real class, constructor-checked | Record components enforced |
| Serialization (Jackson) | Proxy may expose `target` internals | Plain POJO, clean JSON | Record auto-serialized by Jackson 2.12+ |
| Dynamic projection (`findById` returning `Projection` per caller) | ✔ `repository.findById(id, Projection.class)` | ✖ fixed JPQL | ✖ fixed JPQL |
| `nativeQuery` | ✔ alias → getter | ✖ JPQL `new` only | ✖ JPQL `new` only |

PR #17 uses interface for `email/fullName` (no aggregate) and class for `email/sum` (aggregate). Records arrive in PR #21 for API DTOs (same mechanism, nicer syntax).

#### 2.7 DTO vs entity — when NOT to project

Projecting is wrong when the caller will mutate and persist. `OrderService.placeOrder()` (`OrderService.java:91`) loads `Product` as an entity, decrements `stockQuantity`, and Hibernate's dirty-check flushes `UPDATE ... WHERE version=5`. A `ProductProjection` with `stockQuantity` copy would not flush — the decrement would be lost. Rule: read-only reports/dropdowns/lists → projection; write path → entity.

#### 2.8 Pagination interaction

`OrderRepository.findOrdersPaged(Pageable)` (`OrderRepository.java:78-79`) returns `Page<Order>` as entities because updates may follow paging. Mixing projections with `Pageable` works: `@Query("select c.email as email, c.fullName as fullName from Customer c") Page<CustomerNameProjection> findNamePage(Pageable p)` yields `Page` of proxies with total count — efficient for large reference tables.

#### 2.9 Interview-ready mental model

> "PR #17: `CustomerRepository.java:40-53` `CustomerNameProjection` — interface proxy, `select c.email as email, c.fullName as fullName`, closed (alias=getter), no entity, no L1. `OrderRepository.java:86` `select new CustomerOrderTotal(email, sum(totalAmount))` — class constructor projection for aggregates, `CustomerOrderTotal.java:15`, `GROUP BY email`. `OrderRepository.java:37-72` native `CustomerSpend` maps `AS totalSpent → getTotalSpent()`. All bypass persistence context/dirty-check/L2, minimal `SELECT` width, `SUM` in Postgres not Java. Use projections for read-only lists/reports; entities for mutating paths like `OrderService.java:91`."

---

## 3. Solution — ASCII

```
Full entity read (before PR #17):
  Repo findAll() → SELECT id, order_number, order_date, status, total_amount,
                            version, created_at, updated_at, customer_id, shipping_address_id, ...
                   FROM orders  (10 columns × 10k rows → hydrate 10k managed Order + proxies)
                   → L1, snapshot, dirty-check on flush — even for a read-only report

Projection (PR #17):
  CustomerNameProjection  CustomerRepository.java:53
    findAllCustomerNameProjections() → SELECT c.email AS email, c.fullName AS fullName FROM customers c
                                      → JDK proxy implements CustomerNameProjection → getEmail/getFullName
                                      → NOT in persistence context, 2 columns only

  CustomerOrderTotal      OrderRepository.java:86  + CustomerOrderTotal.java:15
    findCustomerOrderTotals() → SELECT new CustomerOrderTotal(o.customer.email, SUM(o.totalAmount))
                                FROM orders o GROUP BY o.customer.email
                             → new CustomerOrderTotal(email, total) per distinct customer
                             → aggregate SUM computed in Postgres, ~200 rows not 10k

  CustomerSpend (native)  OrderRepository.java:64
    findCustomerSpendNative() → SELECT c.email AS email, SUM(o.total_amount) AS totalSpent  -- nativeQuery true
                                FROM orders o JOIN customers c ON ... GROUP BY c.email
                             → interface proxy CustomerSpend getTotalSpent() ← alias totalSpent
                             → dialect-locked, use only for Postgres-specific SQL

Layering:
  Read-only list/report → Projection (minimal width, no L1, no dirty check)  ← PR #17
  Mutating path (placeOrder) → Entity (Product.java, Order.java:42, managed)  ← OrderService.java:91
  Cached read (getProduct) → Spring @Cacheable DTO (ProductCatalogueService.java:50)  ← PR #28
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java` | `40-53` | Interface closed projection | `CustomerNameProjection` getters `getEmail`/`getFullName`; JPQL `select c.email as email, c.fullName as fullName` aliases must match getters |
| `src/main/java/com/company/orderapi/domain/repository/CustomerOrderTotal.java` | `15-32` | Class constructor DTO | `CustomerOrderTotal(String email, BigDecimal totalSpent)` constructor args match `OrderRepository.java:87-88` `new ...` order/type |
| `src/main/java/com/company/orderapi/domain/repository/OrderRepository.java` | `86-93` | Class projection via JPQL `select new ...` | `CustomerOrderTotal` with `sum(o.totalAmount)` + `group by o.customer.email` |
| `OrderRepository.java` | `37-72` | Native interface projection + complex JPQL + `Pageable` + SpEL | `CustomerSpend` native `AS totalSpent`, `findRecentOrdersByCustomer:55` JPQL, `findOrdersPaged:78` pagination, `findByAmountRange:101` SpEL — all projection/query flavours on one repo |
| `OrderRepository.java` | `29,78` | `AmountRange` record param, pagination | Shows projection coexistence with other query styles |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `37` | Entity source of `CustomerNameProjection` columns | `@Column(name="email")`, `fullName` mapped — projection aliases correspond |
| `src/main/java/com/company/orderapi/domain/Order.java` | `42,57` | Entity source for `sum(totalAmount)` | `@Column(name="total_amount", precision=19, scale=2)` `totalAmount` aggregated |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Integration proof | Context loads with projections; queries validated against Testcontainers Postgres |
| `src/main/java/com/company/orderapi/api/dto/OrderResponse.java` | `16` | Record DTO (PR #21) — future of class projections | Shows record equivalence to `CustomerOrderTotal` class |

```java
// CustomerRepository.java:40-53 — interface closed projection
interface CustomerNameProjection { String getEmail(); String getFullName(); }
@Query("select c.email as email, c.fullName as fullName from Customer c order by c.fullName")
List<CustomerNameProjection> findAllCustomerNameProjections(); // only 2 columns fetched

// CustomerOrderTotal.java:15 — class constructor target
public class CustomerOrderTotal {
    private final String email; private final BigDecimal totalSpent;
    public CustomerOrderTotal(String email, BigDecimal totalSpent) { this.email=email; this.totalSpent=totalSpent; }
}
// OrderRepository.java:86-93 — constructor expression
@Query("select new com.company.orderapi.domain.repository.CustomerOrderTotal(o.customer.email, sum(o.totalAmount)) from Order o group by o.customer.email order by o.customer.email")
List<CustomerOrderTotal> findCustomerOrderTotals(); // aggregate in DB, minimal rows

// OrderRepository.java:64 — native alias → getter
@Query(value = "SELECT c.email AS email, SUM(o.total_amount) AS totalSpent FROM orders o JOIN customers c ON c.id=o.customer_id GROUP BY c.email ORDER BY totalSpent DESC", nativeQuery=true)
List<CustomerSpend> findCustomerSpendNative(); // AS totalSpent must match getTotalSpent()
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run projection-backed repository tests
./mvnw test -Dtest=CustomerRepositoryTest,OrderRepositoryTest -Dspring.profiles.active=test

# Verify only 2 columns fetched (interface projection) — SQL log from PR #15
./mvnw test -Dtest=CustomerRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep "customer.*email.*fullName\|CustomerNameProjection"
# Expect: select c1_0.email, c1_0.full_name ...

# Verify constructor projection uses SUM in SQL, not Java
./mvnw test -Dtest=OrderRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "sum.*total_amount.*group by"

# Verify native projection column aliases
grep -A6 "CustomerSpend\|totalSpent" src/main/java/com/company/orderapi/domain/repository/OrderRepository.java

# Show interface vs class vs native on one repo
grep -n "interface.*Projection\|class.*Total\|nativeQuery" src/main/java/com/company/orderapi/domain/repository/*.java

# Prove entities NOT used for projections (no dirty-check overhead)
# Check that CustomerNameProjection methods return proxy, not Customer entity
./mvnw test -Dtest=CustomerRepositoryTest -Dorg.hibernate.stat=DEBUG 2>&1 | grep -E "entities fetched|collections fetched"
# Expect: counts lower than full entity findAll

# Smoke via psql equivalents (what projections push into Postgres)
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT email, full_name FROM customers ORDER BY full_name;"
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT c.email, SUM(o.total_amount) FROM orders o JOIN customers c ON c.id=o.customer_id GROUP BY c.email ORDER BY c.email;"

# Check record DTO equivalence (PR #21)
grep -n "record.*Response\|record.*Request" src/main/java/com/company/orderapi/api/dto/*.java | head
```

```java
// Interface projection usage
List<CustomerRepository.CustomerNameProjection> names = customerRepository.findAllCustomerNameProjections();
String email = names.get(0).getEmail(); // no Customer entity loaded, proxy getter

// Class projection usage — aggregate
List<CustomerOrderTotal> totals = orderRepository.findCustomerOrderTotals();
BigDecimal spend = totals.stream().filter(t -> t.getEmail().equals("alice@example.com"))
                         .map(CustomerOrderTotal::getTotalSpent).findFirst().orElse(BigDecimal.ZERO);

// Native projection usage
List<OrderRepository.CustomerSpend> spends = orderRepository.findCustomerSpendNative();
spends.forEach(s -> log.info("{} spent {}", s.getEmail(), s.getTotalSpent()));

// Dynamic interface projection (call-site decides projection)
interface EmailOnly { String getEmail(); }
Optional<EmailOnly> emailOnly = customerRepository.findById(1L, EmailOnly.class); // Spring Data dynamic
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Interface for `email/fullName` | `CustomerRepository.java:40` closed interface | Class/record for same 2 columns | No constructor to maintain; alias-checked closed projection validates at startup; Spring Data dynamic friendly | Proxy object — watch Jackson serialization (unwraps fine, but `instanceof Customer` false) |
| Class for `email/sum(totalAmount)` | `CustomerOrderTotal.java:15` + `select new` | Interface with `@Value` SpEL | Aggregates/expressions cannot map to interface getter without `new`; class constructor is explicit and type-safe | FQN in JPQL tightly coupled to `CustomerOrderTotal` package — rename breaks query string |
| Native `CustomerSpend` for `SUM(total_amount)` | `OrderRepository.java:64` `nativeQuery=true` alias mapping | JPQL constructor | Demonstrates alias → getter (`AS totalSpent → getTotalSpent()`) and Postgres-specific aggregation; needed for `JOIN ... GROUP BY` native shape | Dialect-locked; entity path navigation (`o.customer.email`) lost |
| Class not record for PR #17 aggregate | `class CustomerOrderTotal` | `record CustomerOrderTotal(String email, BigDecimal totalSpent)` | PR #17 predates PR #21 record DTO adoption; class shows JPQL `new` works without record; records arrive in `OrderResponse.java:16` | Verbose getters; records would be terser |
| Not caching projection | No `@Cacheable` on projection methods | Cache projection DTOs | Aggregates are up-to-the-moment (`sum` after each `placeOrder`); stale totals would misreport spend; cache would need precise invalidation per order | Each call hits DB — but SELECT is minimal width + one query |
| Using JPQL vs derived query | `@Query` with explicit `select` | Derived `findByEmail` projection | Derived `findByEmail` might load `Customer` then project; explicit `@Query` guarantees `SELECT email, fullName` only, proven by `org.hibernate.SQL` (`application.yml:175`) | JPQL string is manual — not refactor-safe |

---

## 7. How to verify

```bash
# Files exist and aliases match getters
grep -n "getEmail\|getFullName\|getTotalSpent" src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java src/main/java/com/company/orderapi/domain/repository/OrderRepository.java src/main/java/com/company/orderapi/domain/repository/CustomerOrderTotal.java

# Constructor projection FQN matches class package
grep -n "select new" src/main/java/com/company/orderapi/domain/repository/OrderRepository.java
# Expect: select new com.company.orderapi.domain.repository.CustomerOrderTotal

# Native aliases match getters
grep -A2 "AS email\|AS totalSpent" src/main/java/com/company/orderapi/domain/repository/OrderRepository.java
# Expect: AS email, AS totalSpent with matching getEmail/getTotalSpent

# SQL proof: projections emit narrow SELECT + SUM/GROUP BY
./mvnw test -Dtest=CustomerRepositoryTest,OrderRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -E "select.*email|sum\(|group by" | head

# Tests pass (Testcontainers + Liquibase + validate)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,CustomerRepositoryTest,OrderRepositoryTest
# Failure would be SchemaManagementException (validate) or alias mismatch exception

# Jackson serialization of interface proxy (if exposed via API)
curl -s http://localhost:8080/api/customers/names -H "X-API-KEY: dev-api-key" | jq .  # if endpoint exists
# Alternative: verify in test that ObjectMapper serializes CustomerNameProjection

# Check not managed: EntityManager.contains check in test
# See DatabaseSchemaIntegrationTest pattern: entityManager.contains(projection) should be false
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** For every read-only list (dropdown, autocomplete, spend report) add an interface or constructor projection (`CustomerRepository.java:40`, `OrderRepository.java:86`) instead of `findAll()` + Java loop. Alias must equal getter (`as email → getEmail()`); constructor arg order must match `CustomerOrderTotal.java:20` signature or Hibernate throws `QueryException: could not instantiate class`. Keep mutating paths (`OrderService.java:91`) on entities — projections returned from a `placeOrder` transaction would not flush mutations.
- **Operate:** Projections are `SELECT` minimal width — index `idx_customers_email` serves `CustomerNameProjection` without touching `orders`. Aggregates (`findCustomerOrderTotals`) run `SUM` in Postgres; add `CREATE INDEX idx_orders_customer_id_total_amount` if `GROUP BY` scans are slow (`EXPLAIN ANALYZE` will show `GroupAggregate` vs `HashAggregate`). No dirty-check flush cost, so GC pressure lower.
- **Interview:** "PR #17: `CustomerRepository.java:40-53` interface closed projection — `select c.email as email` alias → `getEmail()` JDK proxy, 2 columns, not managed, no L1. `CustomerOrderTotal.java:15` + `OrderRepository.java:86` class constructor projection — `select new CustomerOrderTotal(email, sum(totalAmount)) group by email` aggregates in DB, ~200 rows vs 10k entities. Native `OrderRepository.java:37-72` `CustomerSpend` maps `AS totalSpent → getTotalSpent()`, dialect-locked. Use projections for read-only; entities for writes (`OrderService.java:91` `setStockQuantity`). Verified by `org.hibernate.SQL` (`application.yml:175`) narrow SELECT + `sum/group by`."

---

## 9. Interview lens — Q&A

**Q1: What makes interface projections "closed" and what alias rule applies?**
A: Closed means every getter maps to a `SELECT` alias (`CustomerRepository.java:49` `c.email as email` → `getEmail()`) and no `target` access — Spring Data validates at startup. Alias must match getter name exactly (§2.2).

**Q2: How does `select new CustomerOrderTotal(...)` work?**
A: JPQL constructor expression (`OrderRepository.java:86`) — Hibernate calls `CustomerOrderTotal.java:20` constructor with the `SELECT` expressions in declared order. FQN required, types must match (§2.3).

**Q3: When do you choose class vs interface projection?**
A: Interface for simple column picks (`CustomerRepository.java:40`); class/record for aggregates/expressions (`OrderRepository.java:86` `sum(totalAmount)`) where constructor args carry the computation (§2.6).

**Q4: Do projections participate in L1, dirty checking, or L2?**
A: No — they are not managed entities (`EntityManager.contains` false), no snapshot, no `flush()` update, not stored in L2 (`Product.java:33` L2 only caches managed entities) (§2.5).

**Q5: How do native alias projections map `SUM` to a getter?**
A: `SELECT c.email AS email, SUM(o.total_amount) AS totalSpent` (`OrderRepository.java:64`) → Spring Data maps `AS totalSpent` to `CustomerSpend.getTotalSpent()` by alias. Native bypasses JPQL, so column names are Postgres-specific (§2.4).

**Q6: Show a case where projecting would be wrong.**
A: `OrderService.placeOrder()` (`OrderService.java:98`) decrements `Product.stockQuantity` and relies on managed entity dirty-check + `@Version` (`BaseEntity.java:53`) `WHERE version=5` to flush the UPDATE. A `ProductProjection` returning a copy would not flush — stock decrement lost (§2.7).

---

## 10. Honest limits & next step → PR #18

JPQL `select new` FQN strings are brittle to refactors (rename `CustomerOrderTotal` breaks query at runtime, not compile time — `CriteriaQuery` tuple is safer but verbose). Interface proxy serialization leaks `target` in some Jackson versions if not unwrapped. Large projections still allocate DTOs per row — pagination (`findNamePage(Pageable)`) bounds memory. Next: PR #18 moves from querying data to reacting to writes — JPA lifecycle hooks (`Order.java:43` `@EntityListeners`, `OrderBusinessListener.java:32`) and Spring `ApplicationEvent` decouple side effects from the transaction that fired them.

See [`18-jpa-events-and-listeners.md`](./18-jpa-events-and-listeners.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Projection | File:line | Why |
|---|---|---|---|
| Simple column picks, no aggregate | Interface closed | `CustomerRepository.java:40` | Alias-checked, dynamic, no constructor |
| Aggregate / expression (`sum`, `case`) | Class constructor `select new` | `CustomerOrderTotal.java:15` + `OrderRepository.java:86` | Constructor carries `sum(totalAmount)` |
| Postgres-specific SQL (`jsonb`, `WINDOW`) | Native alias interface | `OrderRepository.java:37-72` | `AS alias → getter`, dialect-locked |
| Read-only report → API | Record DTO (PR #21) via mapper | `OrderResponse.java:16` | Record + Jackson auto-serialization |
| Mutating write (`stock--`) | Entity, not projection | `Product.java` via `OrderService.java:91` | Dirty-check + `@Version` flush |
