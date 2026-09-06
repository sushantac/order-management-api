# Order Management API

A **production-grade Order Management API**, built as a **learning journey**: one
pull request at a time, each PR teaching one concrete aspect of modern Java 21 /
Spring Boot API development (JPA mappings, cascading, fetch strategies, locking,
auditing, security, event-driven, Kubernetes, ...).

> **Status: PR #18 (JPA Events & Listeners) — merged ✅ (next: PR #19)**
> See [Learning Roadmap](#learning-roadmap) for the full 35-PR sequence.

---

## Tech Stack

| Category   | Technology                     | Purpose                    |
|------------|--------------------------------|----------------------------|
| Language   | Java 21 LTS                    | Records, pattern matching, virtual threads |
| Framework  | Spring Boot 3.2.1              | Application framework      |
| Build      | Maven 3.9+ (wrapped via mvnw)  | Dependency management      |
| Database   | PostgreSQL 16                  | Primary + event store (from PR #2) |
| Migration  | Liquibase                      | Schema versioning (PR #2)  |
| API        | REST (GraphQL later)           | Dual API approach          |

---

## Project Structure (after PR #4)

```
order-management-api/
├── pom.xml                                   # Spring Boot 3.2.1 + Java 21
├── mvnw / mvnw.cmd                           # Maven wrapper
├── docker-compose.yml                        # PostgreSQL 16 (dev)
├── .gitignore
├── README.md
└── src/
    ├── main/
    │   ├── java/com/company/orderapi/
    │   │   ├── OrderManagementApiApplication.java
    │   │   ├── config/RetryConfig.java        # @EnableRetry (PR #8)
    │   │   ├── domain/                       # JPA entities + enums
    │   │   │   ├── BaseEntity.java           # id + @Version (PR #8)
    │   │   │   ├── Customer.java / Address.java / Order.java
    │   │   │   ├── OrderItem.java / Product.java / Category.java
    │   │   │   ├── Payment.java              # added PR #4 (Order 1:1)
    │   │   │   ├── repository/               # Spring Data repositories
    │   │   │   │   ├── CustomerRepository.java (PR #6) / ProductRepository.java (PR #8)
    │   │   │   ├── service/ProductStockService.java  # @Retryable stock (PR #8)
    │   │   │   └── enums: AddressType / OrderStatus / PaymentMethod / PaymentStatus
    │   └── resources/
    │       ├── application.yml
    │       └── db/changelog/                 # Liquibase schema versioning
    │           ├── db.changelog-master.xml
    │           └── v1.0/
    │               ├── 01_create_tables.sql        # 8 tables + FKs + indexes
    │               └── 02_add_version_columns.sql  # @Version columns (PR #8)
    └── test/java/com/company/orderapi/
        ├── OrderManagementApiApplicationTests.java   # contextLoads smoke test
        └── integration/
            ├── DatabaseSchemaIntegrationTest.java    # verifies Liquibase output
            ├── JpaEntityMappingIntegrationTest.java  # FK write/read round-trips
            ├── JpaCascadingIntegrationTest.java      # cascade behaviour (PR #4)
            ├── FetchTypeIntegrationTest.java         # LAZY semantics (PR #5)
            ├── NPlusOneDemoTest.java                 # N+1 fixes (PR #6)
            ├── BatchFetchIntegrationTest.java        # batch fetching (PR #7)
            └── OptimisticLockingTest.java            # 100 concurrent updates (PR #8)
```

Future PRs extend this into the spec's target package tree:
`domain/` (entities, value objects, events), `api/` (REST/GraphQL controllers,
DTOs), `security/`, `infrastructure/` (persistence, messaging, resilience),
and `observability/`.

---

## Prerequisites

- **JDK 21+** (build targets Java 21 bytecode via `maven.compiler.release`,
  so a newer JDK also works for local development)
- **Docker** (PostgreSQL for dev; integration tests spin up real Postgres via
  Testcontainers — no local install needed)

Start the local database (from the project root):

```bash
docker compose up -d         # PostgreSQL 16 on localhost:5432 (orderdb/order/order)
```

No local Maven install is required — use the checked-in wrapper:

```bash
./mvnw clean test        # compile + run all tests (Liquibase runs in Testcontainers)
./mvnw spring-boot:run   # start the API on http://localhost:8080
```

Verify it is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ... "db":{"status":"UP"}, ...}
```

---

## PR #1 — Project Setup

**Aspect learned:** Spring Boot project structure & dependency management.

### Key questions answered

1. **How does a Spring Boot project structure work?**
   - Convention-over-configuration: `src/main/java` for sources,
     `src/main/resources` for configuration, `src/test/java` for tests.
   - `@SpringBootApplication` = component scanning + auto-configuration +
     configuration bootstrap, all from one class.
   - `application.yml` externalizes configuration; auto-configuration can be
     reasoned about and, when needed, explicitly excluded.

2. **What are the key dependencies for a JPA project?**
   - `spring-boot-starter-data-jpa` → Hibernate + Spring Data JPA.
   - PostgreSQL driver (runtime) → the JDBC target database.
   - `liquibase-core` → versioned, reviewable schema changes.
   - Plus the always-needed `web`, `validation`, `actuator` and `test` starters.
   - The Spring Boot **parent POM acts as a BOM** — we never pin third-party
     versions manually.

3. **How does the Maven wrapper work?**
   - `mvnw`/`mvnw.cmd` bootstrap the Maven version declared in
     `.mvn/wrapper/maven-wrapper.properties`, downloading it on first use.
   - Every developer and the CI server get the *exact same* Maven version —
     no "works on my machine".

### Key decisions

- **Java 21 bytecode even on newer JDKs** via `<maven.compiler.release>21</maven.compiler.release>`.
- **DB auto-configuration excluded in PR #1** (and commented in `application.yml`)
  so the app boots and the `contextLoads` smoke test passes *without* a database.
  PR #2 introduces PostgreSQL + Liquibase and removes those exclusions.
- **No Lombok** — per coding standards we prefer plain, readable Java.
- **No speculative dependencies** — starters (security, cache, Kafka, etc.)
  are added in the PR that teaches them.

---

## PR #2 — Database Schema with Foreign Keys (Liquibase)

**Aspect learned:** database design, foreign keys, and referential integrity.

### Deliverables in this PR
- `docker-compose.yml` — PostgreSQL 16 for local development.
- Liquibase changelog (`db.changelog-master.xml` + `v1.0/01_create_tables.sql`)
  creating all **8 tables** with **database-level foreign keys**:
  - `customers` → `addresses`, `orders`, `order_items`, `payments` chains
  - `product_categories` join table (`products` ↔ `categories`)
- Explicit **indexes on every FK column** (`idx_*`), with the two columns
  already covered by PK/UNIQUE constraints deliberately left un-indexed.
- Integration tests asserting the *actual* Liquibase result against a real
  PostgreSQL 16 (Testcontainers) — not just that the app starts.

### Key questions answered

1. **Why define foreign keys at the database level vs. only in JPA?**
   JPA annotations are *application-level* hints — Hibernate only honours them
   when *it* writes SQL. Hand-written SQL, scripts and bugs elsewhere can
   happily create orphans. A database FK is enforced by PostgreSQL for **every**
   client, making the DB the last line of defence for data integrity.

2. **What is the difference between CASCADE, RESTRICT and SET NULL?**
   All three decide what happens when a parent row is deleted:
   - `ON DELETE CASCADE` — children are deleted with the parent
     (customer → orders/addresses/payments; order → order_items).
   - `ON DELETE RESTRICT` — the delete is **refused** while children exist
     (product/category referenced by order history cannot disappear).
   - `ON DELETE SET NULL` — the child survives; the reference is nulled
     (an order keeps existing when one of its snapshot addresses is deleted).

3. **Why index foreign-key columns?**
   FK columns are used in joins and in the delete-time integrity check on the
   parent. Without an index, PostgreSQL scans the whole child table for each
   deleted/updated parent row — a classic slow-production-query source. This is
   verified by `DatabaseSchemaIntegrationTest.everyForeignKeyColumnIsIndexed`.

### Key decisions
- **Liquibase owns the schema; Hibernate is `validate`-only.** The schema is
  reproducible from git in every environment (dev/CI/prod) — schema-as-code.
- **`BIGINT GENERATED BY DEFAULT AS IDENTITY`** for surrogate keys — modern
  standard SQL, no `BIGSERIAL` legacy.
- **UNIQUE constraints double as indexes** (`customers.email`, `payments.order_id`,
  composite `product_categories` PK) so no redundant index is created.
- **PR #1's temporary DB auto-config exclusions were removed** — the app is now
  truly database-backed; tests run against a disposable real Postgres via
  `@ServiceConnection`.

---

## PR #3 — JPA Entities with Foreign Key Mappings

**Aspect learned:** mapping Java objects to foreign keys — `@ManyToOne` +
`@JoinColumn`, `@OneToMany(mappedBy = ...)`, `orphanRemoval`, and `@JoinTable`.

### Deliverables in this PR
- `BaseEntity` (`@MappedSuperclass`) — one shared identity convention
  (`BIGINT GENERATED BY DEFAULT AS IDENTITY` → `GenerationType.IDENTITY`).
- All core entities mapped to the PR #2 schema: `Customer`, `Address`, `Order`,
  `OrderItem`, `Product`, `Category` (+ `AddressType`/`OrderStatus` enums).
- Every mapping from the spec checklist:
  - `Customer.addresses` ↔ `Address.customer` — **bidirectional**, owning side
    `@ManyToOne @JoinColumn(name = "customer_id")` on Address
  - `Customer.orders` ↔ `Order.customer` — bidirectional, **`mappedBy`**
  - `Order.items` ↔ `OrderItem.order` — `@OneToMany(mappedBy = "order")` with
    **`orphanRemoval = true`**
  - `Order.shippingAddress`/`billingAddress` — nullable `@ManyToOne`
    `@JoinColumn(shipping_address_id / billing_address_id)`
  - `OrderItem.product` — `@ManyToOne @JoinColumn(product_id)`
  - `Product.categories` — `@ManyToMany @JoinTable(name = "product_categories")`
  - `Category.products` — inverse side via `mappedBy = "categories"`
- Integration tests (`JpaEntityMappingIntegrationTest`) proving Hibernate
  **writes and reads the FK columns** on a real Postgres — flush entities, then
  assert the on-disk FK values and reload through the inverse sides.

### Key questions answered

1. **What is the difference between `@JoinColumn` and `mappedBy`?**
   `@JoinColumn` declares *who owns the foreign key column* (the many-to-one
   side). `mappedBy` on the opposite collection says *"I am the inverse — look
   at that field on the other side"*. Only the owning side writes the FK; this
   avoids two conflicting writers for one column. The DB still has exactly one
   `customer_id` — JPA just decides which object graph controls it.

2. **Why use `orphanRemoval = true`?**
   It expresses the invariant "an OrderItem cannot exist without its Order".
   Removing an item from `order.items` should DELETE the row instead of leaving
   an orphan. Verified during this PR: Hibernate only issues that DELETE when
   the association also carries a cascade — `CascadeType.ALL` arrives in
   **PR #4** with the cascade behaviour tests.

3. **What is the `@JoinTable` for a many-to-many relationship?**
   Relational databases cannot express *n..n* directly, so JPA uses an
   intermediate table. `@JoinTable` names it (`product_categories`) and declares
   the two FK columns (`product_id` = owning side, `category_id` = inverse),
   matching the Liquibase schema 1:1. The owning side writes the join rows; the
   inverse side only reads.

### Key decisions
- **Bidirectional Customer ↔ Address** (Address owns the FK) instead of the
  literal `@OneToMany(@JoinColumn)` unidirectional form: the schema declares
  `addresses.customer_id NOT NULL`, and the unidirectional form makes Hibernate
  insert-then-update the FK — impossible against a NOT NULL column. This is a
  real-world example of "let the database shape your JPA mapping".
- **`Money` fields are `BigDecimal` with `precision = 19, scale = 2`**, exactly
  matching `NUMERIC(19,2)` — never floating point for money.
- **Text columns mapped with `columnDefinition = "text"`** to match the schema.
- **`ddl-auto: validate` now has teeth**: Hibernate fails startup if any entity
  mapping disagrees with the Liquibase schema — the DB stays the source of truth.

---

## PR #4 — Cascading Strategies

**Aspect learned:** `CascadeType` — which operations should propagate from a
parent entity through its relationships, and how to choose per relationship.

### Deliverables in this PR (spec checklist)
- [x] `CascadeType.PERSIST` on Customer → Addresses
- [x] `CascadeType.ALL` on Order → OrderItems  (activates the orphan-removal DELETE)
- [x] `CascadeType.ALL` on Order → Payment  (new `Payment` entity, Order 1:1)
- [x] `CascadeType.MERGE` on Product → Categories
- [x] `CascadeType.PERSIST` on Customer → Orders
- [x] `JpaCascadingIntegrationTest` — cascade behaviour verified on real Postgres

### Key questions answered

1. **What is JPA cascading and why use it?**
   Cascading forwards an EntityManager operation (persist/merge/remove/refresh/
   detach) from a parent to its associated entities. Used deliberately, it turns
   "persist the whole new order graph" into one `persist(customer)`. Used
   blindly (`ALL` everywhere), it makes deletes/merges unpredictable — hence
   per-relationship, per-spec choices.

2. **What are the different `CascadeType` options?**
   - `PERSIST` — new children are saved with the parent.
   - `MERGE` — detached children are re-attached when the parent is merged.
   - `REMOVE` — children are deleted with the parent.
   - `ALL` — every operation above (and refresh/detach) propagates.
   - plus `REFRESH` / `DETACH` and the separate `orphanRemoval` flag.

3. **When should you use `CascadeType.ALL` vs. specific types?**
   `ALL` only where the child's lifecycle is *owned* by the parent — Order
   items/payment can't outlive their order. `PERSIST` alone on Customer →
   Addresses/Orders keeps deletions local (customers are not deleted by accident
   through a graph op). `MERGE` on Product → Categories matches the many-to-many
   semantics: categories are shared, so we merge references but never cascade
   removes into shared data.

### Key decisions
- **Payment entity introduced here** because the spec's cascade list requires
  `Order → Payment`; it maps the `payments` table from PR #2 (Order owns the
  1:1 inverse, `Payment.order` carries the unique `order_id`).
- **`orphanRemoval` now works**: PR #3 empirically showed Hibernate issues the
  orphan DELETE only once the association carries a cascade — `ALL` on
  Order→OrderItems provides it, and `JpaCascadingIntegrationTest` proves the
  rows are deleted.
- **Tests flush only the root** and assert child rows appeared on disk — the
  strongest proof a cascade fires.

---

## PR #5 — Fetch Types (LAZY vs EAGER)

**Aspect learned:** fetch strategies — when JPA loads a related entity/collection
relative to its owner, and why production code defaults to LAZY.

### Deliverables in this PR
- [x] Explicit `FetchType.LAZY` on every association (spec checklist):
  Customer→Addresses, Order→OrderItems, Order→Customer, Product→Categories,
  OrderItem→Product (+ the rest of the graph for consistency)
- [x] Hibernate SQL logging for fetch behaviour (test-scoped `show-sql`)
- [x] `FetchTypeIntegrationTest` — LAZY vs EAGER differences proven with
  `PersistenceUnitUtil.isLoaded` and real JDBC statement counts

### Key questions answered

1. **What is the difference between LAZY and EAGER?**
   EAGER loads the association in the *same* query that loads the owner;
   LAZY defers it until the association is actually touched (its own SELECT).
   EAGER sounds convenient but composes terribly — one "load customer" can
   silently become a deep graph of joins/selects you never asked for.

2. **Why default to LAZY in production?**
   You load what you need, when you need it — and, crucially, you *choose*
   explicitly (via fetch joins / entity graphs, PR #6) what gets loaded per
   use case. LAZY also keeps sessions short and prevents whole-graph pulls.

3. **What is the N+1 problem and how does fetch type affect it?**
   Naive LAZY traversal issues 1 query for the list + N queries for N children
   (demonstrated & counted in the test: exactly `1 + customers.size()`).
   EAGER would move the same explosion into the initial query. Neither is the
   fix — deliberate fetching (next PR) is.

---

## PR #6 — Fetch Joins & Entity Graphs (N+1 Fix)

**Aspect learned:** fixing the N+1 problem with `JOIN FETCH` and `@EntityGraph`.

### Deliverables in this PR
- [x] Naive N+1 demo path (`CustomerRepository.findAll()` + lazy traversal)
- [x] `JOIN FETCH` solution in JPQL (`findAllWithAddressesJoinFetch`)
- [x] `@EntityGraph(attributePaths = {"addresses"})` solution
- [x] `@NamedEntityGraph("Customer.addresses")` on the entity + named reference
- [x] Hibernate query counting via `Statistics`
- [x] `NPlusOneDemoTest` — before/after, with hard numbers

### Key questions answered

1. **What is the N+1 problem and how do you identify it?**
   1 query fetches N parent rows; traversing a lazy association adds N more.
   Identified by counting real statements (Hibernate `Statistics`) — the demo
   test proves `1 + N = 5` statements for 4 customers with addresses.

2. **What is the difference between `JOIN FETCH` and `@EntityGraph`?**
   `JOIN FETCH` is imperative and lives *in the JPQL* — you change SQL by hand.
   `@EntityGraph` is declarative metadata that Spring Data applies to the query;
   the same repository method stays clean and the fetch recipe is reusable.
   `@NamedEntityGraph` pushes the recipe onto the entity so many queries reuse it.

3. **When to use `@EntityGraph` vs `JOIN FETCH`?**
   Use `@EntityGraph`/`@NamedEntityGraph` for reusable, per-use-case fetch
   recipes on Spring Data queries; keep `JOIN FETCH` when you need full JPQL
   control (filtering/joins beyond fetching). Both collapsed N+1 to **1**
   statement in the tests.

---

## PR #7 — Batch Fetching

**Aspect learned:** batch fetching — load many lazy associations in one
`in (...)` query instead of one query per owner.

### Deliverables in this PR
- [x] `@BatchSize(size = 20)` on the key collections (Customer addresses/orders,
      Order items)
- [x] `hibernate.default_batch_fetch_size = 20` (global safety net)
- [x] `hibernate.jdbc.fetch_size = 100` (ResultSet streaming)
- [x] `BatchFetchIntegrationTest` — performance comparison with real counts

### Key questions answered

1. **What is batch fetching?**
   When a lazy association must be loaded, Hibernate doesn't limit itself to the
   single owner you touched — it loads the same association for every other
   owner already in the persistence context, using one parameter-array query
   (`customer_id = any (?)`). 8 customers → 1 query instead of 8.

2. **How does `@BatchSize` work?**
   It scopes that behaviour per association (collection or entity): at most N
   owners per round trip. The global
   `hibernate.default_batch_fetch_size` applies everywhere else; explicit
   `@BatchSize` overrides it for a specific role.

3. **Batch fetching vs `JOIN FETCH`?**
   `JOIN FETCH`/`@EntityGraph` are for queries where you KNOW the graph up front.
   Batch fetching is the safety net for *lazy* traversal you didn't (or can't)
   plan — it turns accidental N+1 into few round trips without changing any JPQL.
   Prefer explicit fetching for hot paths; keep batch fetching on as the default
   backstop.

### Bonus finding (worth remembering)
Loading an `Order` triggers its optional inverse `@OneToOne payment`: Hibernate
cannot lazy-proxy a *nullable* one-to-one, so it checks the payments table per
order. The batch test isolates collection batching (addresses) for this reason —
a real-world trap to remember when modelling 1:1s.

---

## PR #8 — Optimistic Locking

**Aspect learned:** `@Version` + optimistic locking — prevent lost updates in
concurrent scenarios without holding database locks.

### Deliverables in this PR
- [x] `@Version` on **every** entity (declared once in `BaseEntity`)
- [x] `version` column added to **all 8 tables** (Liquibase `02_add_version_columns.sql`)
- [x] `@Retryable` (+ `@Backoff`) for `OptimisticLockingFailureException`
      (`spring-retry` + `starter-aop`, `RetryConfig`, `ProductStockService`)
- [x] `OptimisticLockingTest` — **100 concurrent** stock decrements, zero lost updates

### Key questions answered

1. **What is optimistic locking and how does it work?**
   Every row carries a `version`. An UPDATE includes
   `WHERE id = ? AND version = <value the transaction read>` and increments
   version. If another transaction committed first, 0 rows match → the write is
   rejected. No locks are held while the transaction works — conflicts are
   *detected at write time*, not prevented up front.

2. **Why use `@Version`?**
   One annotation in `BaseEntity` gives every entity a safe concurrent-update
   guarantee. Compare that with hand-rolled `SELECT ... FOR UPDATE` (pessimistic
   locking, PR #9): optimistic locking scales better for read-heavy workloads
   and never blocks readers.

3. **What is the difference between optimistic and pessimistic locking?**
   Optimistic assumes conflicts are rare: verify-then-write, retry on failure.
   Pessimistic assumes conflicts are likely: lock the row up front and exclude
   everyone else until commit. Optimistic = better concurrency + deadlock-free,
   at the cost of occasional retries on hot rows — which `@Retryable` absorbs.

### Key decisions
- **One `@Version` in `BaseEntity`** rather than on each entity — DRY, and the
  version column exists in every table because BaseEntity drives all of them.
- **Liquibase adds `version BIGINT NOT NULL DEFAULT 0`** to the existing tables —
  never edit the original changesets; a new one migrates the schema.
- **Retry, don't serialise**: the 100-worker test succeeds because only a few
  calls collide per moment and `@Retryable(maxAttempts = 50)` re-reads the
  newest version. Business rejections (insufficient stock) are NOT retried.

---

## PR #9 — Pessimistic Locking

**Aspect learned:** `@Lock` and pessimistic locking — taking real database row
locks so competing transactions **wait instead of abort**.

### Deliverables in this PR
- [x] `@Lock(PESSIMISTIC_WRITE)` repository method (`findByIdForUpdate`
      → `SELECT … FOR UPDATE`)
- [x] `@Lock(PESSIMISTIC_READ)` for read operations (`findByIdForShare`
      → PostgreSQL `SELECT … FOR SHARE`)
- [x] `ProductInventoryService` — lock-based stock ops + a `moveStockPessimistic`
      method that demonstrates **deadlock detection & `@Retryable` resolution**
- [x] `PessimisticLockingIntegrationTest` — writer blocking, share locks, deadlock
- [x] `LockingPerformanceComparisonTest` — optimistic vs pessimistic numbers

### Key questions answered

1. **What is pessimistic locking and when to use it?**
   The row is locked the moment it is read (`FOR UPDATE`). Any competing
   transaction queues until the lock holder commits — no aborted work, no
   retries needed. Use it when contention on a row is high enough that
   optimistic retries cost more than the lock wait.

2. **What are the `LockModeType` options?**
   `PESSIMISTIC_WRITE` = exclusive (`FOR UPDATE`); `PESSIMISTIC_READ` = shared
   (`FOR SHARE`) — many readers, no writers; plus the optimistic family
   (`OPTIMISTIC`, `OPTIMISTIC_FORCE_INCREMENT`) and `NONE`.

3. **Trade-offs vs optimistic locking?**
   Measured in this PR on 40 contended decrements of one row:
   **optimistic ≈ 115 ms, pessimistic ≈ 26 ms** (pessimistic wins at high
   contention because no work is wasted on retries). Optimistic wins at low
   contention — it takes no locks and never blocks readers. Rule of thumb:
   optimistic for read-heavy & low-conflict, pessimistic for hot, write-heavy
   rows; the benchmark logs both every run.

### Key decisions & findings
- **Real deadlock demonstrated**: two transactions locking the same two rows in
  opposite order → PostgreSQL aborts one with `40P01` (surfaced as
  `DeadlockLoserDataAccessException`) → `@Retryable(maxAttempts=5)` re-runs it
  to completion. Retrying deadlocks is safe **because** the victim transaction
  was fully rolled back.
- **`FOR SHARE` cannot run in a read-only transaction** — PostgreSQL rejects it
  (`cannot execute SELECT FOR SHARE in a read-only transaction`); the peek
  service method is therefore a normal (writable-capable) transaction.
- **Locks live until commit** — the blocking test holds a lock inside the
  transaction for 600 ms and proves a second writer waits ≥ the remainder.

---

## PR #10 — Auditing

**Aspect learned:** `@CreatedDate` / `@LastModifiedDate` / `@CreatedBy` /
`@LastModifiedBy` + `AuditorAware` — traceability for every data change with
zero hand-written timestamp code.

### Deliverables
- [x] `@EnableJpaAuditing` configuration (`JpaAuditingConfig`)
- [x] `AuditorAware<String>` bean (returns the current user)
- [x] Audit fields on every entity (one declaration in `BaseEntity`,
      `@EntityListeners(AuditingEntityListener.class)`)
- [x] Liquibase `03_add_audit_columns.sql` — `created_at/updated_at/created_by/
      updated_by` on the 7 entity tables
- [x] `JpaAuditingIntegrationTest` — persist & update semantics verified

### Key questions answered
1. **How does JPA auditing work?** Spring Data's `AuditingEntityListener`
   (registered on the mapped superclass) fills the `@*Date`/`@*By` fields on
   `@PrePersist`/`@PreUpdate` using the `AuditingHandler`, which asks the
   `AuditorAware` bean "who is the current user?".
2. **Why audit?** created/updated + who — the minimum for compliance,
   debugging and GDPR/audit trails, without sprinkling timestamps through
   services.
3. **How to inject the current user?** The `AuditorAware` bean is the single
   seam — today it returns `"system"`; PR #26 (security) swaps it for the
   authenticated principal.

### Key decisions
- **Declared once in `BaseEntity`** (like `@Version`) so every entity inherits
  auditing automatically.
- **`created_*` are `updatable = false`** — insert-only, enforced both at the
  JPA and (column semantics) level.
- **No audit columns on the pure join table** `product_categories` — it has no
  entity and therefore no auditor.

---

## PR #11 — Custom Queries

**Aspect learned:** `@Query` — JPQL, native SQL, pagination and SpEL-driven
dynamic filters.

### Deliverables
- [x] `OrderRepository` with the four `@Query` flavours
- [x] Complex JPQL (predicates + ordering) — `findRecentOrdersByCustomer`
- [x] Native SQL with interface projection — `findCustomerSpendNative`
      (`GROUP BY` + `SUM`, alias → getter)
- [x] `@Query` + `Pageable`/`Page` — `findOrdersPaged`
- [x] SpEL expressions (`:#{#range.minTotal()}`) with null-safe optional
      predicates — `findByAmountRange`
- [x] `CustomQueryIntegrationTest` covering every query style

### Key questions answered
1. **When to use `@Query` vs. method naming?** Method naming is great for simple
   lookups; `@Query` takes over the moment you need joins, aggregates, native
   SQL, or anything a method name cannot express (or should not: very long
   derived names are unreadable).
2. **What is JPQL and how is it different from SQL?** JPQL queries the *entity
   model* (`o.customer.id`, `o.totalAmount`) and is database-agnostic; SQL
   queries tables/columns and is dialect-specific. Prefer JPQL; reach for native
   SQL only for DB-specific power (here: `GROUP BY`/`SUM` projection demo).
3. **When to use native SQL vs. JPQL?** Native SQL for aggregation/reporting or
   vendor features; JPQL for everything object-graph related. Native projection
   gotcha hit during this PR: interface-projection getters bind to **column
   aliases**, so `total_spent` did NOT bind to `getTotalSpent()` until aliased
   as `totalSpent`.
4. **Bonus (SpEL):** `:#{#range.minTotal()}` dereferences a method-argument
   object inside JPQL; paired with `is null or ...` guards it makes predicates
   optional — one method, four filter combinations, zero SQL concatenation.

---

## PR #12 — Specifications & QueryDSL

**Aspect learned:** JPA `Specification` — dynamic, type-safe, composable queries.

### Deliverables
- [x] `JpaSpecificationExecutor<Customer>` on `CustomerRepository`
- [x] `CustomerSpecifications` — reusable predicate builders (name contains,
      has order status, has order total ≥ …)
- [x] Composition (`where(...).and(...)`) + `count(Specification)`
- [x] `SpecificationIntegrationTest` — 4 dynamic-query tests on real Postgres

### Key questions answered
1. **What is a JPA Specification?** A functional interface that turns a
   `CriteriaBuilder` query into one `Predicate` — i.e. a type-safe, reusable
   WHERE clause as data.
2. **When to use Specifications?** Whenever the filter set is dynamic (search
   screens, admin lists). Composing at runtime is safer and more readable than
   concatenating JPQL/SQL strings.
3. **How to build dynamic queries?** Small static `Specification` factories
   (null-safe: a missing filter contributes `conjunction()`, never an error),
   combined by the caller with `.and()`/`.or()` and executed via
   `findAll(spec)`/`count(spec)`.
4. **Gotcha handled:** specifications that JOIN collections return duplicates —
   the join specs call `query.distinct(true)` (guarded for count queries).

---

## PR #13 — Entity Lifecycle Callbacks

**Aspect learned:** `@PrePersist` / `@PreUpdate` / `@PreRemove` / `@PostLoad` —
hooks that run around the persistence lifecycle inside the entity itself.

### Deliverables
- [x] `@PrePersist` on Order — generates the `order_number` (new Liquibase
      column, unique index) exactly once, never re-generated on update
- [x] `@PreUpdate` on Payment — stamps `payment_date` when a payment becomes
      `PROCESSED`
- [x] `@PreRemove` on Customer — refuses deletion while orders exist
- [x] `@PostLoad` on `BaseEntity` — log + observable load flag
- [x] `LifecycleCallbackIntegrationTest` — all four callbacks verified

### Key questions answered
1. **What are JPA lifecycle callbacks?** Annotations on entity methods that
   Hibernate invokes at well-defined moments: `@PrePersist`/`@PostPersist`
   (insert), `@PreUpdate`/`@PostUpdate` (update), `@PreRemove`/`@PostRemove`
   (delete), `@PostLoad` (read).
2. **Order of execution?** Pre-callbacks run inside the same transaction,
   before the statement; the entity listener and the entity's own callbacks
   both run (`@EntityListeners` (auditing) coexists with callbacks on
   `BaseEntity`). There is no guaranteed relative order between listener and
   callback — never make them depend on each other.
3. **`@PrePersist` vs `@PreUpdate`?** PrePersist fires only on insert (generate
   the order number once); PreUpdate fires on every change (stamp "processed
   at"). Doing them in the wrong callback either misses updates or re-runs
   one-time logic.
4. **Gotcha surfaced:** the DB's `ON DELETE CASCADE` would silently delete a
   customer's orders — the `@PreRemove` callback is the *business* rule on top.

---

## PR #14 — Schema Generation & Validation

**Aspect learned:** `spring.jpa.hibernate.ddl-auto` options and why the schema
should never be self-managed in production.

### Deliverables
- [x] `ddl-auto: validate` in production (`application-prod.yml` + default)
- [x] `ddl-auto: update` in development (`application-dev.yml`, clearly flagged)
- [x] `SchemaValidationIntegrationTest` — entity model ⇄ real schema contract
- [x] Liquibase schema validation (startup + existing `DatabaseSchemaIntegrationTest`)

### Key questions answered
1. **What is `ddl-auto` and what options exist?** `none` (do nothing),
   `validate` (fail if entities ≠ DB), `update` (add missing objects),
   `create`/`create-drop` (drop & recreate; testing only).
2. **Why `validate` in production?** Drift is caught at startup, before any
   request — no surprise DDL, no divergence between app and DB. Because
   Liquibase owns the schema, `validate` is a free integrity gate.
3. **What is the danger of `update` in production?** Hibernate only *adds*
   things it knows about: it never drops stale columns, can generate wrong DDL
   for non-trivial changes (renames look like drop+add), and two app versions
   can fight over the schema mid-deploy.
4. **How we use profiles:** default & `prod` = `validate`; `dev` = `update`
   (throwaway local databases only). Tests always run `validate`.

---

## PR #15 — SQL Logging & Debugging

**Aspect learned:** see exactly what JPA generates — statements, bind values,
comments and statistics.

### Deliverables
- [x] `spring.jpa.show-sql=true` + `hibernate.format_sql=true`
- [x] `hibernate.use_sql_comments=true` (each statement tagged with its code path)
- [x] `hibernate.generate_statistics=true`
- [x] Logging levels: `org.hibernate.SQL=DEBUG`,
      `org.hibernate.orm.jdbc.bind=TRACE`, `org.hibernate.stat=DEBUG`
- [x] All of it **off in `application-prod.yml`**
- [x] `SqlLoggingIntegrationTest` — properties + live statistics assertions

### Key questions answered
1. **How to log SQL generated by JPA?** `show-sql` (goes through the logger,
   honors level) or the `org.hibernate.SQL` logger at DEBUG; combine with
   `format_sql` and `use_sql_comments` to see the originating repository/query.
2. **How to log parameter bindings?** `org.hibernate.orm.jdbc.bind=TRACE`
   prints every bind value — essential when a "valid" SQL surprises you.
3. **How to get query statistics?** `generate_statistics=true` + the
   `org.hibernate.stat` logger; programmatically via
   `SessionFactory.getStatistics()` (used by our N+1/batch/locking tests).
4. **Production stance:** verbose SQL/bind TRACE and statistics are dev tools —
   `application-prod.yml` disables them all to keep logs small and overhead low.

---

## PR #16 — Second Level Cache

**Aspect learned:** JPA second-level cache — reuse entities ACROSS persistence
contexts (Hibernate JCache + Ehcache).

### Deliverables
- [x] `hibernate.cache.use_second_level_cache=true` +
      `region.factory_class=jcache` (Ehcache provider)
- [x] `@Cacheable` + `@Cache(READ_WRITE)` on `Product`
- [x] Selective mode (`jakarta.persistence.sharedCache.mode=ENABLE_SELECTIVE`)
- [x] `SecondLevelCacheIntegrationTest` — hits/misses, write-through refresh

### Key questions answered
1. **Second-level vs first-level cache?** L1 is per-EntityManager (default, not
   shareable); L2 lives in the SessionFactory and survives across persistence
   contexts — that is what makes a second `findById` in a NEW session a cache
   hit with **zero JDBC** (proven by statement counter).
2. **How is it configured?** A JCache provider (`hibernate-jcache` + Ehcache) +
   enabling the cache + marking entities `@Cacheable`; concurrency strategy
   `READ_WRITE` for mutable, versioned data.
3. **When to use it?** Read-heavy, rarely-mutated entities (product catalogue!).
   Avoid caching hot write rows (inventory counters) — the tests here still
   passed for the optimistic-lock benchmark, but per-entity judgement is key.
4. **Learned while testing:** `READ_WRITE` is **write-through** — an update
   *refreshes* the cache entry at commit, so the next read is a hit carrying the
   new state (not a stale read, no extra SELECT).
5. **Test isolation:** Ehcache's default cache manager is JVM-wide and SHARED
   across Spring test contexts, so cached ids can leak between test classes.
   Every integration test therefore runs in its own context (unique
   `@TestPropertySource`) with L2 **disabled** — only
   `SecondLevelCacheIntegrationTest` re-enables it, in isolation.

---

## PR #17 — DTO Projections

**Aspect learned:** fetch only what you need — JPA projections beat loading whole
entities for read/list views.

### Deliverables
- [x] Interface-based projection (`CustomerRepository.CustomerNameProjection`)
- [x] Class-based projection via JPQL constructor expression (`CustomerOrderTotal`,
      `select new …`)
- [x] `@Query` returning projections (incl. an aggregation `SUM` a plain
      interface cannot express)
- [x] `ProjectionIntegrationTest` incl. entity-vs-projection equivalence check

### Key questions answered
1. **What are JPA projections?** Queries whose result rows are NOT entities —
   interface proxies (Spring Data) or plain classes built by a constructor
   expression — carrying only the selected columns.
2. **Why projections vs entities?** Entities drag the full row + lazy graph
   semantics + audit/version state. Projections stay lean for lists/reports and
   never risk `LazyInitializationException` outside a transaction.
3. **Interface vs class projections?** Interfaces are convenient when the data
   is simple column selection; class (constructor) expressions are needed for
   expressions/aggregates (`GROUP BY`, `SUM`) and give you a real, testable type.

---

## PR #18 — JPA Events & Listeners

**Aspect learned:** `@EntityListeners` — external classes reacting to entity
lifecycle events, decoupled from both the entity and the service layer.

### Deliverables
- [x] `AuditingEntityListener` for auditing (already on `BaseEntity`)
- [x] Custom `OrderBusinessListener` for business rules
- [x] `@PostPersist` event-publishing hook on Order (observable counter)
- [x] `EntityListenerIntegrationTest` verifying listener behaviour + composition

### Key questions answered
1. **What are entity listeners?** Plain classes with `@PrePersist`/`@PostLoad`/…
   methods, registered via `@EntityListeners`. They run alongside lifecycle
   callbacks and other listeners.
2. **When to use `@EntityListeners`?** When the reaction is a cross-cutting
   concern you do not want inside the entity: event publishing (PostPersist),
   validation on update (PreUpdate), logging. Reuse one listener across
   entities instead of copying callbacks.
3. **Listeners vs lifecycle callbacks?** Callbacks live inside the entity class
   (order number generation stays there); listeners externalize shared concerns
   and keep the entity lean. They compose — this PR proves the inherited
   auditing listener AND the custom Order listener both fire on one insert.

---

## Learning Roadmap

| # | Aspect | # | Aspect |
|---|--------|---|--------|
| 1 | Project Setup | 19 | Event Sourcing & Event Store |
| 2 | Database Schema with Foreign Keys (Liquibase) | 20 | Service Layer |
| 3 | JPA Entities with Foreign Key Mappings | 21 | DTOs (Java Records) |
| 4 | Cascading Strategies | 22 | REST Controllers |
| 5 | Fetch Types | 23 | Validation |
| 6 | Fetch Joins & Entity Graphs (N+1 Fix) | 24 | Exception Handling & Idempotency |
| 7 | Batch Fetching | 25 | OpenAPI Documentation |
| 8 | Optimistic Locking | 26 | Security (OAuth2 & JWT) |
| 9 | Pessimistic Locking | 27 | PII & GDPR |
| 10 | Auditing | 28 | Caching (Redis) |
| 11 | Custom Queries | 29 | Resilience Patterns |
| 12 | Specifications & QueryDSL | 30 | Virtual Threads & Concurrency |
| 13 | Entity Lifecycle Callbacks | 31 | Kafka (Event-Driven) |
| 14 | Schema Generation & Validation | 32 | Observability |
| 15 | SQL Logging & Debugging | 33 | Docker & Kubernetes |
| 16 | Second Level Cache | 34 | CI/CD & GitOps |
| 17 | DTO Projections | 35 | Enterprise Features (Optional) |
| 18 | JPA Events & Listeners | | |

---

*Built one pull request at a time — each teaching one API development aspect.*
