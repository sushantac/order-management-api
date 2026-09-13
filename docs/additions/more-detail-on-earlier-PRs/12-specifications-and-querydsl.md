# 12. Specifications and QueryDSL (PR #12)
> PR #12 — Dynamic, type-safe queries with JPA Specifications, `JpaSpecificationExecutor` and QueryDSL: predicates, Criteria API, composability vs string concatenation. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Spring Data JPA + (optional) QueryDSL. See `README.md:1772` roadmap `| 12 | Specifications and QueryDSL |`.
---

## 1. Purpose — what shipped

PR #12 delivers **Specifications and QueryDSL** as a first-class, tested, documented building block. `CustomerRepository.java:32` extends `JpaSpecificationExecutor<Customer>`; `CustomerSpecifications.java:21` provides three reusable, composable `Specification<Customer>` factories (`nameContains` `CustomerSpecifications.java:28`, `hasOrderStatus` `CustomerSpecifications.java:39`, `hasOrderTotalAtLeast` `CustomerSpecifications.java:53`) that build Criteria-API predicates (`cb.like`, `cb.equal`, `root.join`) with null-safe `cb.conjunction()` and `distinct(true)` dedup. Search screens compose with `where(...).and(...).or(...)` — zero JPQL string concatenation, zero native SQL, full type safety. The QueryDSL path (predicates from generated `QCustomer`) is documented as the type-safe DSL alternative. Proven by repository tests asserting filtered `findAll(spec)` counts.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** PR #11's SpEL `findByAmountRange` handles 2 optional filters; adding 3 more (name, status, total) would make the JPQL guard chain `is null or ...` unreadable and combinatorial. Adding `or` composition or conditional `JOIN`s (only join `orders` if status filter present) requires `if` branches concatenating JPQL — untyped, not parsed at startup, injection-adjacent. No programmatic predicate builder.

**After:** `CustomerSpecifications.java:21` expresses each filter as a `Specification<Customer>` lambda `(root, query, cb) -> Predicate`. Caller composes: `spec = Specification.where(nameContains(q)).and(hasOrderStatus(s)).and(hasOrderTotalAtLeast(min))`; `customerRepository.findAll(spec, Pageable)` executes one Criteria query with only the non-null joins/predicates. Adding a new filter = new static factory, no existing method changes. QueryDSL alternative generates `QCustomer.customer.fullName.containsIgnoreCase(...)`.

### Theory — Specifications & QueryDSL from first principles (100+ lines)

#### 2.1 Why string concatenation is the enemy

Queries assembled as strings share all the weaknesses of manual SQL:

```java
// Anti-pattern — manual JPQL assembly
String jpql = "SELECT c FROM Customer c WHERE 1=1 ";
if (name != null) jpql += "AND c.fullName LIKE '%" + name + "%' "; // injection if name contains '
if (status != null) jpql += "AND EXISTS (SELECT o FROM c.orders o WHERE o.status = :status) ";
Query q = em.createQuery(jpql);
```

Problems: typo `"fullNmae"` only fails at runtime, `name` not escaped, `EXISTS` vs `JOIN` choice is stringly-typed, no IDE navigation to `Customer.fullName`, `ORDER BY`/`distinct` handled ad-hoc, pagination (`count` query) must be written separately. SpEL (§2.4 of PR #11 doc) fixes the `is null or ...` ugliness for 2-4 filters, but still one monolithic JPQL string per method — hard to compose `or` or conditional joins.

Specifications fix all of these by building a Criteria-API predicate tree programmatically.

#### 2.2 `Specification<T>` — the functional interface

```java
// Spring Data's functional contract
@FunctionalInterface
public interface Specification<T> {
    Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
```

`Root<T>` = the `FROM Customer c` alias (typed). `CriteriaBuilder cb` = factory for `Predicate`s (`cb.equal`, `cb.like`, `cb.greaterThanOrEqualTo`, `cb.conjunction()` = always-true, `cb.disjunction()` = always-false). `CriteriaQuery<?> query` = the overall query (allows `query.distinct(true)`, subqueries). Return `null` means "no predicate" (same as `conjunction`).

`JpaSpecificationExecutor<Customer>` (`CustomerRepository.java:32`) adds executor methods:

```
Page<Customer> findAll(Specification<Customer> spec)
List<Customer>  findAll(Specification<Customer> spec)
List<Customer>  findAll(Specification<Customer> spec, Sort sort)
long            count(Specification<Customer> spec)
boolean         exists(Specification<Customer> spec)
Optional<Customer> findOne(Specification<Customer> spec)
```

Implementation delegates to `CriteriaQuery` via `EntityManager` — no JPQL string ever exists at runtime.

#### 2.3 The three factories in `CustomerSpecifications` — line by line

**`nameContains` — simple attribute predicate, null-safe:**

```java
// CustomerSpecifications.java:28
public static Specification<Customer> nameContains(String fragment) {
    return (root, query, cb) -> {
        if (fragment == null || fragment.isBlank()) return cb.conjunction(); // no filter → WHERE 1=1
        String pattern = "%" + fragment.toLowerCase() + "%";
        return cb.like(cb.lower(root.get("fullName")), pattern); // LOWER(full_name) LIKE %fragment%
    };
}
```

- `root.get("fullName")` references `Customer.fullName` (`Customer.java:42` → column `full_name`). Stringly-typed key is the weakness of JPA Criteria; QueryDSL (§2.7) fixes it with generated `QCustomer.customer.fullName`.
- `cb.lower` + `fragment.toLowerCase()` makes it case-insensitive, using PostgreSQL `lower()` function.
- `cb.conjunction()` for `null/blank` means caller can always compose `where(nameContains(q)).and(other)` without null-checking the returned spec — `conjunction` is identity for `AND` (true).

**`hasOrderStatus` — join + distinct:**

```java
// CustomerSpecifications.java:39
public static Specification<Customer> hasOrderStatus(OrderStatus status) {
    return (root, query, cb) -> {
        if (status == null) return cb.conjunction();
        if (Long.class != query.getResultType()) query.distinct(true); // don't duplicate customers with many matching orders
        Join<Customer, Order> orders = root.join("orders"); // INNER JOIN orders ON orders.customer_id = customers.id
        return cb.equal(orders.get("status"), status);      // orders.status = ?
    };
}
```

- `root.join("orders")` navigates `Customer.orders` (`Customer.java:67` `@OneToMany(mappedBy="customer")`) — Hibernate renders the FK join condition from `@JoinColumn` mapping, just like JPQL `o.customer.id`.
- `query.distinct(true)` deduplicates: without it, a customer with 3 `PLACED` orders would appear 3 times (3 joined rows). `distinct` is skipped when result type is `Long` (i.e., `count(spec)` query) because `count(distinct ...)` semantics differ and Postgres handles `count` differently.
- Conditional join: only joins `orders` when `status != null` — unlike JPQL `JOIN FETCH` (`CustomerRepository.java:61`) which always joins, Specs only incur the join when needed (cheaper when filter not applied).

**`hasOrderTotalAtLeast` — numeric predicate on joined entity:**

```java
// CustomerSpecifications.java:53
public static Specification<Customer> hasOrderTotalAtLeast(BigDecimal minTotal) {
    return (root, query, cb) -> {
        if (minTotal == null) return cb.conjunction();
        if (Long.class != query.getResultType()) query.distinct(true);
        Join<Customer, Order> orders = root.join("orders");
        return cb.greaterThanOrEqualTo(orders.get("totalAmount"), minTotal);
    };
}
```

- `cb.greaterThanOrEqualTo(orders.get("totalAmount"), minTotal)` compares `orders.total_amount >= ?`. `totalAmount` maps to `Order.totalAmount` (`Order.java:57` `@Column(name="total_amount")`).

#### 2.4 Composability — `where` / `and` / `or`

Caller builds the predicate tree declaratively:

```java
Specification<Customer> spec = Specification.where(CustomerSpecifications.nameContains("ann"))
        .and(CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED))
        .and(CustomerSpecifications.hasOrderTotalAtLeast(BigDecimal.valueOf(100)));

Page<Customer> page = customerRepository.findAll(spec, PageRequest.of(0, 20, Sort.by("fullName")));
long total = customerRepository.count(spec);
boolean exists = customerRepository.exists(spec);
```

Each factory is a pure function `String/Enum/BigDecimal -> Specification`. `where(null)` returns a no-op spec that `and(...)` can chain off of — so `null` fragment/status/min all collapse to `conjunction` and contribute no SQL. Adding a new filter:

```java
// new factory
public static Specification<Customer> phoneStartsWith(String prefix) { ... }
// caller just adds .and(phoneStartsWith(prefix))
```

No existing JPQL method signature changes — contrast with SpEL `findByAmountRange` where adding a field requires editing the one `@Query` string.

`or` composition works identically: `where(nameContains("ann")).or(hasOrderStatus(PLACED))` renders `WHERE lower(full_name) LIKE ? OR exists...`.

#### 2.5 `distinct(true)` — why and when

A `JOIN` multiplies rows before `WHERE`: one customer with 3 matching orders produces 3 rows with the same `customers.id`. `SELECT DISTINCT` collapses them to one entity. Hibernate then hydrates one `Customer` instance per `customers.id`.

```sql
-- Without distinct (3 rows for customer 5 with 3 PLACED orders):
SELECT c.* FROM customers c JOIN orders o ON o.customer_id=c.id WHERE o.status='PLACED'
-- c5 appears 3 times → customerRepository.findAll(spec) would return 3 copies of Customer#5 (unless hydrated with distinct)

-- With distinct:
SELECT DISTINCT c.* FROM customers c JOIN orders o ON ...
-- c5 appears once
```

Guard `Long.class != query.getResultType()` (`CustomerSpecifications.java:44/58`): `count(spec)` renders `SELECT count(*) ...` — `COUNT(DISTINCT c.*)` is not valid SQL and `COUNT(DISTINCT c.id)` semantics differ (and is slower). So distinct is only enabled for entity-result queries.

#### 2.6 Pagination with Specifications

`findAll(spec, PageRequest.of(0,20))` does two SQL round-trips:

1. `SELECT count(c) FROM customers c JOIN ... WHERE <spec>` → total count.
2. `SELECT DISTINCT c.* FROM customers c JOIN ... WHERE <spec> ORDER BY full_name LIMIT 20 OFFSET 0` → page.

Spring Data derives both from the same `Specification` tree — only the `CriteriaQuery` result type differs (`Long` vs `Customer`). The `Page<Customer>` return gives `page.getTotalElements()` (from count) + the slice. Under the hood: `SimpleJpaRepository.getCountQuery(spec)` + `getQuery(spec, pageable, sort)`.

Caution: `JOIN` + `DISTINCT` + `ORDER BY` + `LIMIT` can be slower than `JOIN FETCH` for large tables — verify with `EXPLAIN (ANALYZE, BUFFERS)` that `idx_orders_customer_id:182` is used for the join.

#### 2.7 QueryDSL — the type-safe alternative (not the primary in this repo)

QueryDSL generates a metamodel class `QCustomer` at compile time (`target/generated-sources/java/.../QCustomer.java`) via annotation processing (`querydsl-apt`). Usage:

```java
// Requires querydsl-jpa + apt on the classpath (not asserted here)
QCustomer c = QCustomer.customer;
QOrder   o = QOrder.order;
Predicate p = c.fullName.containsIgnoreCase("ann")
              .and(c.orders.any().status.eq(OrderStatus.PLACED));
List<Customer> results = queryFactory.selectFrom(c).where(p).fetch();
```

Advantages over `Specification`:

| Aspect | Specification (`CustomerSpecifications.java:21`) | QueryDSL (`QCustomer`) |
|---|---|---|
| Metamodel | Stringly-typed `root.get("fullName")` | Generated `QCustomer.customer.fullName` — compile-time checked, IDE autocomplete |
| Query shape | Predicate only — Spring executes via `findAll(spec)` | Full fluent API: `.selectFrom().join().where().orderBy().limit()` — more SQL-like control |
| Likeliness of typo | Runtime `IllegalArgumentException` if attribute name wrong | Compile-time error |
| Dependency | Spring Data JPA only (no codegen) | `querydsl-apt` codegen + `querydsl-jpa` at runtime |
| Composition | `spec.and(other)` chain | `BooleanBuilder` / `Predicate.and()` chain |

This repo teaches Specifications because they require no codegen and are the Spring-idiomatic default; QueryDSL is documented for teams that want compile-time attribute safety (especially useful for deeply nested paths like `o.customer.addresses.city`).

#### 2.8 When to use SpEL `@Query` vs Specifications vs QueryDSL

| Filters | Pattern | File:line |
|---|---|---|
| 0-1 predicate, fixed | Derived (`findByEmail`) | `CustomerRepository.java:46` |
| 2-4 optional, `and` only | SpEL `:#{#range...}` `OrderRepository.java:101` | One `@Query`, guards `is null or ...` |
| 5+ optional, `and`/`or`/conditional `JOIN` | Specifications | `CustomerSpecifications.java:21` `where().and()` |
| Need compile-time attribute check | QueryDSL `QCustomer` | Generated metamodel |
| Report `GROUP BY` / window | Native `@Query` | `OrderRepository.java:64` |
#### 2.9 Interview-ready mental model
> "`JpaSpecificationExecutor<Customer>` (`CustomerRepository.java:32`) lets `findAll(Specification)` execute a Criteria-API predicate tree. Each factory in `CustomerSpecifications.java:21` is `value -> (root,query,cb) -> Predicate`: `nameContains` (`CustomerSpecifications.java:28`) does `cb.like(cb.lower(root.get(\"fullName\")), %fragment%)`, `hasOrderStatus` (`CustomerSpecifications.java:39`) does `root.join(\"orders\")` → `cb.equal(orders.get(\"status\"), status)` plus `query.distinct(true)` (dedup, skipped for `count` queries), `hasOrderTotalAtLeast` (`CustomerSpecifications.java:53`) similar with `greaterThanOrEqualTo`. `null` → `cb.conjunction()` so callers can `where(nameContains(q)).and(hasOrderStatus(s)).and(...)` with no null checks; new filter = new factory, no signature change. Pagination `findAll(spec, PageRequest)` does `COUNT` + `SELECT DISTINCT ... LIMIT/OFFSET`. QueryDSL is the type-safe alternative with `QCustomer.customer.fullName.containsIgnoreCase(...)` via generated metamodel — compile-time safe vs Specification's stringly-typed `root.get(\"...\")`. Use SpEL `:#{#range...}` (`OrderRepository.java:101`) for 2-4 filters, Specifications for 5+."
---
## 3. Solution — ASCII
```
SpEL @Query (PR #11, 2 filters):              Specifications (PR #12, 5+ filters):
 record AmountRange(min,max)                    CustomerSpecifications.java:21
 @Query("... where                               nameContains(String)    → cb.like(lower(fullName), %frag%)
   (:#{#range.min} is null                      hasOrderStatus(status)  → join("orders").get("status") = ?
     or total>=:#{#range.min})                   hasOrderTotalAtLeast(min) → join("orders").totalAmount >= ?
   and (:#{#range.max} is null                  // null → cb.conjunction() (no filter)
     or total<=:#{#range.max})")                // query.distinct(true) dedup (not for count)
 one JPQL string, 4 combos                      // each is pure factory: value → Specification
 Compose (caller):
  spec = where(nameContains("ann"))
          .and(hasOrderStatus(PLACED))
          .and(hasOrderTotalAtLeast(100.00))
            ↓ Spring translates
  CriteriaQuery<Customer>  →  SELECT DISTINCT c.* FROM customers c
                               JOIN orders o ON o.customer_id=c.id
                               WHERE lower(c.full_name) LIKE ? AND o.status=? AND o.total_amount>=?
                               ORDER BY full_name LIMIT 20 OFFSET 0
                            + SELECT count(c) FROM ... WHERE ... (for Page totalElements)
 QueryDSL alternative:
  QCustomer c = QCustomer.customer;
  c.fullName.containsIgnoreCase("ann").and(c.orders.any().status.eq(PLACED))
  // compile-time checked QCustomer.customer.fullName — not stringly-typed root.get("fullName")
  queryFactory.selectFrom(c).where(predicate).fetch();
 CustomerRepository.java:32  extends JpaSpecificationExecutor<Customer>  → findAll(spec), count(spec), exists(spec)
```
---
## 4. How it is implemented — file map
| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java` | `32-33` | `JpaSpecificationExecutor` | `extends ... JpaSpecificationExecutor<Customer>` adds `findAll(spec)`, `count(spec)` |
| `CustomerRepository.java` | `46` | `findByEmail` | Derived query — simple case, no spec needed |
| `src/main/java/com/company/orderapi/domain/repository/CustomerSpecifications.java` | `21` | Class header | "Reusable, composable" — each method returns one predicate builder |
| `CustomerSpecifications.java` | `28-36` | `nameContains` | `cb.like(cb.lower(root.get("fullName")), %fragment%)`, blank → `conjunction()` |
| `CustomerSpecifications.java` | `39-49` | `hasOrderStatus` | `root.join("orders")`, `cb.equal(orders.get("status"), status)`, `query.distinct(true)` except `Long` |
| `CustomerSpecifications.java` | `53-64` | `hasOrderTotalAtLeast` | `cb.greaterThanOrEqualTo(orders.get("totalAmount"), minTotal)`, same distinct guard |
| `CustomerSpecifications.java` | `44/58` | `Long.class != query.getResultType()` | Skips distinct on `count()` query |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `42/46/67` | `fullName`, `email`, `orders` | Attributes referenced by `root.get(...)` / `root.join("orders")` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `53/57` | `status`, `totalAmount` | Attributes referenced via `orders.get("status")` join |
| `src/main/java/com/company/orderapi/domain/repository/OrderRepository.java` | `29/101` | SpEL alternative | `AmountRange` + `:#{#range}` — PR #11 approach for few filters |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `53/59` | `version` / audit | Returned entities carry version/audit |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Asserts filtered counts via specs |

```java
// CustomerSpecifications.java:28 — name substring, null-safe
public static Specification<Customer> nameContains(String fragment) {
    return (root, query, cb) -> {
        if (fragment == null || fragment.isBlank()) return cb.conjunction();
        String pattern = "%" + fragment.toLowerCase() + "%";
        return cb.like(cb.lower(root.get("fullName")), pattern);
    };
}

// CustomerSpecifications.java:39 — join + status predicate + distinct
public static Specification<Customer> hasOrderStatus(OrderStatus status) {
    return (root, query, cb) -> {
        if (status == null) return cb.conjunction();
        if (Long.class != query.getResultType()) query.distinct(true);
        Join<Customer, Order> orders = root.join("orders");
        return cb.equal(orders.get("status"), status);
    };
}

// Caller composition
Specification<Customer> spec = Specification.where(CustomerSpecifications.nameContains("ann"))
        .and(CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED));
Page<Customer> page = customerRepository.findAll(spec, PageRequest.of(0, 20));

// QueryDSL alternative (compile-time safe, QCustomer generated)
QCustomer c = QCustomer.customer;
Predicate p = c.fullName.containsIgnoreCase("ann").and(c.orders.any().status.eq(OrderStatus.PLACED));
List<Customer> results = queryFactory.selectFrom(c).where(p).fetch();
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run specification tests
./mvnw test -Dtest=CustomerRepositoryTest,DatabaseSchemaIntegrationTest -Dspring.profiles.active=test

# Show CustomerSpecifications factories
grep -n "public static Specification" src/main/java/com/company/orderapi/domain/repository/CustomerSpecifications.java

# Verify JpaSpecificationExecutor on CustomerRepository
grep -n "JpaSpecificationExecutor" src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java

# Health
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

```java
// One filter — name substring
List<Customer> anns = customerRepository.findAll(CustomerSpecifications.nameContains("ann"));

// Two filters — "ann" AND has PLACED order
Specification<Customer> spec = Specification.where(CustomerSpecifications.nameContains("ann"))
        .and(CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED));
List<Customer> filtered = customerRepository.findAll(spec);

// Three filters — AND all three
Specification<Customer> fullSpec = Specification
        .where(CustomerSpecifications.nameContains("ann"))
        .and(CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED))
        .and(CustomerSpecifications.hasOrderTotalAtLeast(BigDecimal.valueOf(200)));
Page<Customer> paged = customerRepository.findAll(fullSpec, PageRequest.of(0, 20, Sort.by("fullName")));

// Count with spec (distinct skipped internally)
long count = customerRepository.count(fullSpec);

// Exists with spec
boolean any = customerRepository.exists(CustomerSpecifications.hasOrderStatus(OrderStatus.CANCELLED));

// OR composition
Specification<Customer> orSpec = CustomerSpecifications.nameContains("ann")
        .or(CustomerSpecifications.hasOrderStatus(OrderStatus.PLACED));

// Null-safe: null/blank predicates become "WHERE 1=1" — no predicate added
customerRepository.findAll(CustomerSpecifications.nameContains(null)); // finds all customers
customerRepository.findAll(CustomerSpecifications.hasOrderStatus(null)); // finds all customers (no join)

// Loopback — same pattern as SpEL AmountRange, but composable for many filters
// See OrderRepository.java:29 AmountRange + OrderRepository.java:101 SpEL for 2-filter alternative
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Specifications as primary dynamic query | `CustomerSpecifications.java:21` + `JpaSpecificationExecutor` | String-assembled JPQL or many `findBy*` derived methods | Composable, typed predicate tree, zero string concat, `and`/`or` chain, pagination for free | Stringly-typed `root.get("fullName")` — typo is runtime error (QueryDSL fixes at compile time) |
| `cb.conjunction()` for null/blank | `CustomerSpecifications.java:31/42/56` | Return `null` (also means "no predicate") | Explicit identity for `AND`, self-documenting; Spring docs prefer `conjunction()` | None — `null` would also work but is less explicit |
| `query.distinct(true)` with `Long` guard | `CustomerSpecifications.java:44/58` | Always distinct or never | Dedup for entity queries (join multiplies rows), skip for `count` (invalid SQL for count) | Extra check per factory; without it, count query would fail or be wrong |
| `INNER JOIN` via `root.join(...)` | `CustomerSpecifications.java:47` | `LEFT JOIN` or `EXISTS` subquery | `INNER` is correct for "has at least one" semantics — customer with no orders must not match status filter | None — left join would include customers with no orders incorrectly for `hasOrderStatus` |
| QueryDSL documented as alternative | Not primary, taught as option | Specifications only | QueryDSL needs `querydsl-apt` codegen; Specifications need only Spring Data — lower barrier. Show QueryDSL for teams wanting compile-time safety (`QCustomer.customer.fullName`) | Two mental models; document §2.7 comparison |
| Separate `CustomerSpecifications` class | Utility class with static factories | Specification per repository method | Reusable across controllers/services, testable in isolation, open for extension (add new factory without touching existing) | Extra class file — trivial |

---

## 7. How to verify

```bash
# Full suite — specs filter correctly via Testcontainers
./mvnw test -Dtest=CustomerRepositoryTest,DatabaseSchemaIntegrationTest

# Prove null → no filter (conjunction)
./mvnw test -Dtest=CustomerRepositoryTest#shouldFindAllWhenNameContainsNull

# Prove composition: nameContains AND hasOrderStatus narrows results
./mvnw test -Dtest=CustomerRepositoryTest#shouldComposeSpecifications

# Prove distinct — customer with 3 PLACED orders appears once not three times
./mvnw test -Dtest=CustomerRepositoryTest#shouldNotDuplicateCustomersWithMultipleMatchingOrders

# Prove pagination with spec
./mvnw test -Dtest=CustomerRepositoryTest#shouldPageWithSpecification

# Show generated SQL — specs render as CriteriaQuery → SQL with JOIN and DISTINCT
./mvnw test -Dtest=CustomerRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 \
  | grep -E "select distinct.*customer|join.*orders|where.*fullName"
# Expect: SELECT DISTINCT customers... FROM customers JOIN orders ON ... WHERE lower(full_name) LIKE ?

## 8. How this helps you on the job — build / operate / interview

- **Build:** One `Specification` factory per filter (`CustomerSpecifications.java:28/39/53`), `null→conjunction`, compose `where().and().or()`, `findAll(spec,Pageable)`; QueryDSL `QCustomer` when compile-time safety matters.
- **Operate:** `findAll(spec,PageRequest)` costs `COUNT` + `SELECT DISTINCT`; `EXPLAIN` join uses `idx_orders_customer_id:182`; `exists(spec)` cheaper than `count>0`.
- **Interview:** "PR #12: `CustomerRepository.java:32` `JpaSpecificationExecutor`, `CustomerSpecifications.java:21` factories (`nameContains:28` `cb.like`, `hasOrderStatus:39` `join+distinct`, `hasOrderTotalAtLeast:53`), `null→conjunction`, `where().and()`, pagination `COUNT+SELECT DISTINCT`, QueryDSL `QCustomer` compile-safe alternative."

---

## 9. Interview lens — Q&A

**Q1: Why Specifications over strings?** A: Typed `Predicate` tree via `CriteriaBuilder` (`CustomerSpecifications.java:21`), composable `and/or`, startup-safe; SpEL `OrderRepository.java:101` handles 2-4 filters, Specs handle 5+.
**Q2: What does `query.distinct(true)` guard do?** A: Dedup join-multiplied customers (`CustomerSpecifications.java:44`), skipped for `Long` `count` (`COUNT DISTINCT` invalid).
**Q3: How pagination works?** A: `findAll(spec,PageRequest)` → `SELECT count` + `SELECT DISTINCT ... LIMIT/OFFSET` from same spec tree (§2.6).

---

## 10. Honest limits & next step → PR #13

Criteria is verbose vs JPQL for fixed queries; `root.get("fullName")` stringly-typed (QueryDSL fixes at cost of codegen); `DISTINCT+JOIN+ORDER BY` needs proper indexes (`01_create_tables.sql:182`). Next: PR #13 entity lifecycle callbacks `@PrePersist` `Order.java:198`, `@PreUpdate` `Payment.java:114`, `@PostLoad` `BaseEntity.java:109`.

See [`13-entity-lifecycle-callbacks.md`](./13-entity-lifecycle-callbacks.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| 0-1 fixed predicate | Derived `findByEmail` | `CustomerRepository.java:46` | No annotation |
| 2-4 `and` optional, one method | SpEL `:#{#range}` | `OrderRepository.java:101` | Guard `is null or ...` minimal |
| 5+ optional, `and`/`or`/conditional join | Specifications | `CustomerSpecifications.java:21` | `where().and().or()` chain |
| Compile-time safety on attribute name | QueryDSL `QCustomer` | `QCustomer.customer.fullName` | No string key |
