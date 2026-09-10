# Order Management API

A **production-grade Order Management API**, built as a **learning journey**: one
pull request at a time, each PR teaching one concrete aspect of modern Java 21 /
Spring Boot API development (JPA mappings, cascading, fetch strategies, locking,
auditing, security, event-driven, Kubernetes, ...).

> **Status: 🎉 All 35 learning PRs + bonus PRs #36 (MCP server), #37 (official MCP Spring SDK, Boot 3.4), #38 (RAG — `docs_search`), #39 (agentic tool-calling — `agentic_ask`), #40 (chat memory — multi-turn `agentic_ask`), #41 (guarded write — `cancel_order`), #42 (RAG productionization — pgvector + re-index + retrieval eval), #43 (hybrid retrieval — dense + Postgres full-text, RRF + MMR) and #44 (RAG eval gate, earned — real-model measurement, MMR off, gate 0.7) merged to `develop` — journey complete (final suite: 189 tests, 0 failures).**
> See [Learning Roadmap](#learning-roadmap) for the full 35-PR sequence (plus the #36–#44 AI bonuses).
>
> 📚 **Study guides:** [Learning document](docs/learnings/README.md) (concepts → real code)
> · [Interview cheat sheets](docs/interview-cheat-sheets/README.md) (rapid revision pack)
> · [Functional documentation](docs/business/README.md) (what each feature does, endpoint by endpoint)
> · [Good & bad retrospective](docs/good-and-bad/README.md) (honest self-review + fix roadmap)
> · [🔁 Redo blueprint](docs/next-time/README.md) (refined spec/instructions to run the journey again, better)
> · [Solution design](docs/design/README.md) (architecture & detailed design, as a lead designer would write it)
> · [🎯 Mastery toolkit](docs/others/README.md) (12-week plan, self-assessment, flashcards, labs, playbook)
> · [✅ Not-asked gaps](docs/not-asked/README.md) (evidence, security pack, diagrams/ADRs, runbooks, closure plan)

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
| Security   | Spring Security + OAuth2 RS    | JWT scopes + API keys (PR #26) |
| Privacy    | PII masking + GDPR endpoints   | Erasure/portability/audit (PR #27) |

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

Since PR #26 every other endpoint requires authentication. Machine/script clients
send the dev API key; interactive clients send an OAuth2 bearer JWT signed with
`app.security.jwt-secret` (scopes `order_read` / `order_write`):

```bash
curl -H "X-API-Key: dev-api-key-orderapi" http://localhost:8080/api/v1/customers?page=0\&size=5
curl -H "Authorization: Bearer <jwt>" http://localhost:8080/api/v1/customers?page=0\&size=5
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

## PR #19 — Event Sourcing & Event Store

**Aspect learned:** event sourcing basics — store FACTS (events) append-only and
rebuild state by replaying them, instead of only storing the current state.

### Deliverables
- [x] `DomainEvent` interface (aggregate id, version, occurred-at, type)
- [x] `OrderPlacedEvent`, `OrderConfirmedEvent` (immutable records)
- [x] `event_store` table (Liquibase): metadata + JSON payload, **UNIQUE
      (aggregate_id, version)**
- [x] `EventStoreEntry` entity + `EventStoreRepository`
- [x] `EventStoreService` (append, read history, version-duplicate guard)
- [x] `EventStoreIntegrationTest` — storage, ordered retrieval, duplicate rejection

### Key questions answered
1. **What is event sourcing?** Persist every state-changing fact, never overwrite.
   Current state = fold over the event history; you can replay, audit, and ask
   "what did the system look like at time T?".
2. **How is it different from traditional CRUD?** CRUD mutates/overwrites rows
   (the past is lost); event sourcing only ever INSERTs immutable events.
3. **Benefits?** Complete audit trail, temporal queries, decoupled projections
   and reliable integration events (the outbox later in the Kafka PR builds on
   this). Costs: eventual consistency & replay logic.
4. **Integrity mechanism:** the UNIQUE `(aggregate_id, version)` constraint
   guarantees each fact is appended exactly once (tested: duplicate append is
   rejected). Events are serialised as JSON; the payload column is TEXT to keep
   JPA/PostgreSQL casts simple (JSONB would need driver-level casts).

---

## PR #20 — Service Layer

**Aspect learned:** transaction management & ACID — business logic lives in
`@Transactional` services, and one failing step rolls back the entire unit of
work.

### Deliverables
- [x] `OrderService` (`@Transactional`) with `placeOrder(...)`
- [x] Stock deduction inside the order transaction (versioned)
- [x] `@Retryable` for optimistic-lock conflicts on hot products
- [x] `SimulatedPaymentGateway` with a **10% failure rate**
      (`PaymentGateway` interface → deterministic fake in tests)
- [x] `OrderServiceTest` proving success AND full rollback on payment failure

### Key questions answered
1. **What is ACID and why does it matter?** Atomicity, Consistency, Isolation,
   Durability. `placeOrder` = deduct stock + insert order/items + charge
   payment; if the charge fails, ALL of it must vanish (atomic) or customers get
   charged without stock.
2. **How does `@Transactional` work?** The proxy opens a DB transaction before
   the method and commits/rolls back after — a `RuntimeException` rolls back.
   Nested calls join the same transaction.
3. **Optimistic locking in the service?** Stock writes are versioned (PR #8);
   `@Retryable` re-runs the whole `placeOrder` in a fresh transaction on a
   conflict — the previous attempt was fully rolled back, so retry is safe.
4. **Verification:** the rollback test reads the **committed** database (tests
   are intentionally not `@Transactional`) and proves zero orders survived and
   the stock is untouched after a failed payment.

---

## PR #21 — DTOs (Java Records)

**Aspect learned:** separate the API contract from the domain model using Java
records.

### Deliverables
- [x] `OrderRequest`/`OrderResponse` (+ item records) with validation annotations
- [x] `CustomerRequest`/`CustomerResponse`, `ProductRequest`/`ProductResponse`
- [x] `OrderMapper` — explicit entity → DTO mapping

### Key questions answered
1. **Why DTOs instead of exposing entities?** Entities leak persistence
   internals (version, audit users, lazy proxies) and couple clients to the
   domain. DTOs are the stable, versionable API contract.
2. **Why Java records?** Immutable value carriers with `equals`/`hashCode`/
   `toString` for free — ideal for request/response payloads.
3. **How do you map entities to DTOs?** Explicitly (`OrderMapper`): one
   readable mapping per type, no reflection/magic, and the mapper doubles as
   documentation of exactly what the API exposes.

---

## PR #22 — REST Controllers

**Aspect learned:** REST API design — resourceful URLs, correct verbs/status
codes, and the payload controls (`fields`, `include`) that keep APIs flexible.

### Deliverables
- [x] `CustomerController`/`ProductController`/`CategoryController` CRUD
- [x] `OrderController`: POST `/orders`, GET list/detail, bulk
      POST `/orders/bulk`
- [x] Pagination + sorting (`Pageable`/`Sort`)
- [x] Field filtering `?fields=` & resource inclusion `?include=`
      (`GET /customers/{id}/view`)
- [x] ETag on order GET + `If-Match` preconditioned DELETE (412 on staleness)
- [x] PATCH via JSON Patch (`replace /status`)
- [x] `RestControllerIntegrationTest` (MockMvc, deterministic payment fake)

### Key questions answered
1. **REST principles?** Resources as nouns (`/customers`, `/orders/{id}`),
   verbs from HTTP methods, state in status codes, hypermedia in `Location`.
2. **Designing endpoints?** Collection (`GET/POST /orders`) vs item
   (`GET/PUT/PATCH/DELETE /orders/{id}`); actions become sub-resources or
   documents (bulk = `POST /orders/bulk`).
3. **HTTP methods → CRUD?** POST=create (201 + Location), GET=read,
   PUT=full replace, PATCH=partial, DELETE=remove (204). Conditional requests
   use ETag/If-Match to prevent lost updates (412 when stale).

---

## PR #23 — Validation

**Aspect learned:** Bean Validation — annotations, custom constraints, cross-field
rules and validation groups.

### Deliverables
- [x] `@NotNull` / `@Size` / `@Email` on the request DTOs
- [x] Custom constraint `@ValidStock` (class-level product sanity)
- [x] Custom cross-field constraint `@ValidOrderRequest` (duplicate products,
      quantity bounds)
- [x] Validation groups `Create` / `Update` (create stricter than update)
- [x] Controllers wired with `@Valid` / `@Validated(Create|Update.class)`
- [x] `ValidationIntegrationTest` — 400s for each rule

### Key questions answered
1. **How does Bean Validation work?** Constraints on request records are
   checked automatically by Spring MVC before the controller runs; violations
   produce 400 with field-level details.
2. **When custom validators?** When the rule spans multiple fields (whole
   `OrderRequest`), needs the whole object (stock sanity), or needs DB access —
   write a `ConstraintValidator` behind a custom annotation.
3. **What are validation groups for?** The SAME request type is validated
   differently per operation: creation requires `fullName.length() >= 2`, while
   an update (already-valid data) is lenient — proven by the test.

---

## PR #24 — Exception Handling & Idempotency

**Aspect learned:** RFC 7807 Problem Details and write idempotency via
`Idempotency-Key`.

### Deliverables
- [x] `GlobalExceptionHandler` (`@RestControllerAdvice`) producing RFC 7807
      `ProblemDetail`
- [x] Java 21 pattern-matching `switch` → error catalog `(status, code, hint)`
- [x] `idempotency_keys` table (Liquibase), `IdempotencyKeyRepository`,
      `IdempotencyService`
- [x] `Idempotency-Key` header on `POST /orders` — replays instead of re-executing
- [x] Tests: ProblemDetail shapes + retry scenarios (no duplicate orders)

### Key questions answered
1. **What is RFC 7807 Problem Details?** A standard error body
   (`type/title/status/detail` + extensions). Clients branch on `code` and read
   `hint` instead of string-matching ad-hoc messages.
2. **Why `@ControllerAdvice`?** One place converts every exception family to an
   HTTP response — controllers stay clean and the mapping is testable.
3. **What is idempotency and why does it matter?** Clients retry on timeouts;
   without idempotency a retried `POST /orders` double-charges. The client key
   makes retries replay the FIRST result (proven: same key → same body and
   order count unchanged; a second key creates a second order).

---

## PR #25 — OpenAPI Documentation

**Aspect learned:** document the API from code (springdoc), version it, and
deprecate it gracefully.

### Deliverables
- [x] `springdoc-openapi-starter-webmvc-ui` dependency
- [x] `OpenApiConfig` (title/version metadata) + `@Operation`/`@ApiResponse`
      annotations on controllers
- [x] Live spec at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`
- [x] Committed snapshot export: `docs/api/openapi.yaml`
- [x] Postman collection export: `docs/postman/order-management-api.postman_collection.json`
- [x] API versioning strategy (v1/v2 coexistence) + `Deprecation`/`Sunset`
      headers on the deprecated alias (`GET /api/v1/customers/legacy`)
- [x] `OpenApiIntegrationTest`

### Key questions answered
1. **Why document APIs?** The spec is the contract clients build against —
   machine-readable docs keep it truthful and enable codegen/tooling.
2. **What are OpenAPI & Swagger?** OpenAPI = the spec format; Swagger UI = the
   interactive explorer springdoc generates from controllers/annotations.
3. **How to generate docs from code?** springdoc introspects Spring MVC at
   runtime (`/v3/api-docs`) and renders the UI — annotations enrich the
   auto-detected model. No hand-maintained docs to rot.
4. **Versioning & deprecation strategy:** URLs stay `/api/v1/...`; breaking
   changes live under `/api/v2/...` so both can coexist. Deprecated endpoints
   advertise RFC 8594 `Deprecation` + `Sunset` headers before removal.

## PR #26 — Security (OAuth2 & JWT)

**Aspect learned:** turn a Spring Boot API into an OAuth2 *resource server* that
validates JWTs, maps scopes to authorities and protects endpoints declaratively.

### Deliverables
- [x] `spring-boot-starter-oauth2-resource-server` + `spring-security-test` deps
- [x] `SecurityConfig` - stateless resource server (JWT decoder, CORS, HSTS/CSP
      security headers, permit-list for health + OpenAPI docs)
- [x] `SecurityProperties` binds `app.security.*` (master on/off switch for tests,
      HS256 secret, dev API key, CORS origins)
- [x] `JwtAuthenticationConverter` maps the OAuth2 `scope` claim → `SCOPE_*`
      authorities; `ApiKeyAuthenticationFilter` accepts `X-API-Key` for machine
      clients (`ROLE_API_KEY`)
- [x] `@PreAuthorize` guards: create order requires `order_write`, read customer
      requires `order_read` (disabled when `app.security.enabled=false`)
- [x] `GlobalExceptionHandler` maps security failures to RFC 7807 bodies
      (401 `AUTHENTICATION_REQUIRED`, 403 `ACCESS_DENIED`)
- [x] `SecurityIntegrationTest` (no token → 401, wrong scope → 403, full flow,
      API key, CORS preflight, security headers) - **6 tests**
- [x] OpenAPI declares `bearer-jwt` + `api-key` security schemes

### Key questions answered
1. **What does a resource server do?** It never issues tokens - it *validates*
   bearer JWTs it receives, using the issuer's key material, and decides which
   requests to admit. The API stays decoupled from the IdP.
2. **Why scopes → `SCOPE_*` authorities?** Spring Security's
   `hasAuthority('SCOPE_order_write')` idiom is the OAuth2 convention; the JWT
   `scope` claim becomes real `GrantedAuthority`s via the converter.
3. **HS256 vs asymmetric JWT in production?** Here we sign locally with a shared
   secret (learning/dev). A real deployment validates RS256 signatures against
   the IdP's published JWKS and pins `iss`/`aud`.
4. **How are method rules tested?** Each existing MockMvc test sets
   `app.security.enabled=false` (kept off so all 77 pre-security tests still run
   unchanged); the new dedicated test turns security **on** and drives it with a
   locally-signed token and the API key.

## PR #27 — PII & GDPR

**Aspect learned:** personal data is protected at every boundary - in logs, in
API responses, and by GDPR subject-rights endpoints backed by an audit trail.

### Deliverables
- [x] `PiiMasker` + `SensitiveDataSerializer` (Jackson) - DTO fields tagged
      `@MaskedPii` are masked for callers WITHOUT the privileged `pii_read`
      scope / API key (`PiiAccessDecider`)
- [x] `PiiRedactionFilter` - request/response bodies are masked BEFORE they
      reach the logs (log sink policy, independent of caller rights)
- [x] GDPR right to erasure: `DELETE /api/v1/customers/{id}/data` - DELETES a
      customer without history, ANONYMIZES one whose orders must be retained
      (Art. 17(3)); idempotent
- [x] GDPR portability: `GET /api/v1/customers/{id}/portability` - full
      machine-readable JSON export (Art. 20)
- [x] Compliance audit trail: new `audit_log` table (Liquibase #07) records
      WHO did WHAT to WHICH customer - even after the customer row is erased;
      details never contain raw PII
- [x] PCI-DSS: payments table proven (by test) to store no cardholder data;
      `Payment`/`PaymentMethod` document the tokenised, out-of-scope design
- [x] GDPR actions require `pii_write`/`pii_read` scopes (403 for everyday
      scopes); every action is audited
- [x] Tests: unit (masking/redaction rules, serializer scope logic) + 6-test
      `GdprPiiIntegrationTest` + schema tests - **all green**

### Key questions answered
1. **What is PII and why mask it in responses?** Names/e-mails/phones are
   personal data (GDPR). The default read scope (`order_read`) gets a masked
   view; only a deliberately stronger `pii_read` scope (or the machine key)
   sees raw values - least privilege per field class.
2. **Why redact logs separately?** A support engineer with log access is NOT an
   authorised data consumer. Logs must be safe for everyone who may read them,
   so bodies are scrubbed regardless of the caller's own access rights.
3. **DELETE vs anonymise (Art. 17)?** The right to erasure yields to legal
   retention obligations. Customers with order history are anonymised (fields
   overwritten with `erased-<id>@erased.invalid`), which keeps history usable
   while unlinking the person.
4. **Why an audit trail with no FK?** Accountability (Art. 5(2)) requires
   records of processing. The audit row must survive a physical erasure, hence
   no FK and no PII inside the detail - GDPR applies to the audit trail too.
5. **What does PCI-DSS compliant payment handling look like here?** The API
   never sees a card number: the payment gateway returns only a transaction
   reference, and the schema is *test-enforced* to never add cardholder columns.

## PR #28 — Caching (Redis)

**Aspect learned:** cache-aside with Spring's cache abstraction and Redis - where
caching pays, how invalidation keeps it correct, and how to observe it.

### Deliverables
- [x] Redis 7 service in `docker-compose.yml` (AOF persistence + healthcheck)
- [x] `spring-boot-starter-data-redis` + `RedisConfig` (`@EnableCaching`)
- [x] `ProductCatalogueService` - `@Cacheable` on product retrieval (DTOs,
      never entities), `@CacheEvict(allEntries)` on catalogue writes
- [x] Stock writers (order placement + the locking services) call
      `ProductCatalogueService.evict(id)` so cached stock never goes stale
- [x] TTL config: `spring.cache.redis.time-to-live: 10m` + key prefix in the
      dev/prod profiles (default profile stays `simple` so tests need no Redis)
- [x] Cache hit/miss metrics: `RedisCacheMetrics` publishes Redis server
      `keyspace_hits`/`keyspace_misses` as Micrometer gauges
- [x] `CachingRedisIntegrationTest` (4 tests, real Testcontainers Redis): miss →
      populate → hit, write eviction, delete eviction, TTL applied, stock
      freshness after order placement, metric gauges present

### Key questions answered
1. **What is caching and why use it?** Repeat reads (a hot product page) hit a
   fast in-memory/remote store instead of the DB. Cache the *read model* (the
   `ProductResponse` DTO) - entities carry session/lazy state and must not leave
   the persistence context.
2. **What is cache-aside?** On a read: check cache; on hit return, on miss load
   from the DB and populate the cache with a TTL. Spring expresses it as
   `@Cacheable` - the method body only runs on a miss.
3. **How do you invalidate cache?** Writes that change cached state must evict
   it. Catalogue writes evict all entries; stock changes made by the order and
   locking services evict the single product entry - otherwise a cached stock
   level silently oversells the next buyer.
4. **How do you know it works?** Redis counts every key lookup
   (`keyspace_hits`/`keyspace_misses`); publishing those as Micrometer gauges
   makes cache effectiveness visible on `/actuator/metrics` and in dashboards.

## PR #29 — Resilience Patterns

**Aspect learned:** protect the API from a flaky dependency and from noisy
clients - circuit breaker, retry, bulkhead and rate limiting with Resilience4j.

### Deliverables
- [x] `resilience4j-spring-boot3` + `spring-boot-starter-aop` dependencies
- [x] Circuit breaker on the payment gateway - opens after repeated failures or
      SLOW calls (latency chaos), fast-fails while open, recovers in half-open
- [x] Retry with EXPONENTIAL backoff (200ms → 400ms → ...) INSIDE the breaker
- [x] Thread-pool bulkhead isolates gateway work on its own small pool
      (1 core / 2 max / queue 5), so slow charges cannot starve the DB threads
- [x] Per-API-key rate limiting (`ApiKeyRateLimiterFilter`) with
      `X-RateLimit-Remaining`, `X-RateLimit-Reset` and `Retry-After` headers
- [x] Chaos testing: deterministic failure + latency injection drives the real
      Resilience4j stack (`ResilienceChaosIntegrationTest`)
- [x] Load test script: `scripts/k6-load-test.js` (with thresholds)
- [x] `RateLimitIntegrationTest` (per-key buckets, 429 behaviour)

### Key questions answered
1. **What is a circuit breaker?** A state machine (CLOSED → OPEN → HALF_OPEN)
   in front of a dependency call. Too many failures/slow calls trip it OPEN and
   subsequent calls fail *fast* instead of queueing on a sick provider; after a
   wait it lets one probe through and closes again on success.
2. **How does rate limiting work?** A client takes a permit from a bucket that
   refills every window (here: 60s). We bucket **per API key**, so one noisy
   client cannot exhaust another's quota; the standard headers tell clients how
   many permits remain and when to retry.
3. **What is a bulkhead?** Named after ship compartments: a failure in one
   compartment cannot sink the ship. Gateway calls run on their own small thread
   pool (thread-pool bulkhead), so a payment backlog cannot consume the whole
   app server. Retry sits *inside* the circuit breaker so each logical request
   is one breaker record - attempts retry within it.

## PR #30 — Virtual Threads & Concurrency

**Aspect learned:** Java 21 virtual threads let a server hold thousands of
concurrent operations cheaply - plus the locks (local and distributed) that
keep shared state safe while they run.

### Deliverables
- [x] `spring.threads.virtual.enabled: true` - the web server is configured to
      serve requests on virtual threads (setting asserted in tests)
- [x] 100 simultaneous "buy the last unit" requests over virtual threads -
      exactly ONE winner (`VirtualThreadsIntegrationTest`)
- [x] Structured fan-out (`DashboardService`): three independent DB aggregates
      run concurrently on a virtual-thread executor and join before returning
      (structured-concurrency idiom; `StructuredTaskScope` is its JDK 22+ form)
- [x] Distributed lock with Redisson (`DistributedLockService`): mutual
      exclusion across threads AND instances, lease-based crash recovery
- [x] `ReentrantLock` replacing `synchronized`: `FairSequenceAllocator`
      (fair, explicit, reliable release) proven by a 100×100 unit test
- [x] Performance/concurrency comparison tests live alongside the lock tests

### Key questions answered
1. **What are virtual threads?** Threads scheduled by the JVM onto a few carrier
   platform threads. A blocking call parks the virtual thread (cost ~KB) instead
   of an OS thread (~MB), so "one thread per request" finally scales to very
   high concurrency - and code stays simple and synchronous.
2. **Structured concurrency / fan-out?** Independent subtasks (counts, sums) run
   in parallel and ALL must finish before the operation returns; no orphan work
   leaks past the scope. We model it with try-with-resources on a virtual-thread
   executor; JDK 22's `StructuredTaskScope` is the same idea built in.
3. **Local vs distributed locks?** `synchronized`/`ReentrantLock` only protect
   one JVM. When multiple instances share state, a Redis lock (Redisson) is the
   coordination point - and a lease means a crashed holder can never deadlock
   the system (verified by the crash-recovery test).
4. **Why ReentrantLock over synchronized?** Explicit lock/unlock with `finally`,
   fairness control, tryLock - behaviours a `synchronized` block cannot express.
   Virtual threads make lock CONTENTION visible again, so picking the right lock
   granularity matters more than ever.

## PR #31 — Kafka (Event-Driven)

**Aspect learned:** decouple producers from consumers with a message broker,
publish *reliably* via a transactional outbox, and consume with explicit
offsets + dead-letter handling.

### Deliverables
- [x] Kafka broker (KRaft, no ZooKeeper) in `docker-compose.yml`
- [x] Transactional OUTBOX: `outbox` table (Liquibase #08) written in the SAME
      transaction as the order (`OrderService.placeOrder`)
- [x] Polling publisher (`OutboxPublisher` + `@Scheduled`): claims PENDING rows
      with `FOR UPDATE SKIP LOCKED`, publishes, marks PUBLISHED only after the
      broker acks - at-least-once; failed rows retry with an attempt counter
- [x] `OrderEventProducer` (KafkaTemplate, JSON `OrderPlacedMessage` - no PII)
- [x] `OrderEventConsumer` with MANUAL offset management (`Acknowledgment`) and
      defensive validation
- [x] Dead-letter topic: poison messages (e.g. missing total) are rejected,
      never acked, and routed to `order-events.DLT` by a `DefaultErrorHandler`
- [x] Integration tests with `@EmbeddedKafka`: outbox→broker→consumer round
      trip, PUBLISHED state, DLT routing
- [x] `app.kafka.enabled` master switch - dev/prod talk to the broker, the 100+
      non-Kafka test contexts never touch one

### Key questions answered
1. **What is event-driven architecture?** Producers publish facts ("order
   placed") to a broker; consumers react in their own time. The order service
   no longer calls downstream systems directly - decoupling + scaling come
   from the topic in between.
2. **Why the outbox pattern?** Kafka cannot join the DB transaction. Writing
   the event to an outbox table in the SAME transaction gives atomicity (event
   exists iff the order exists); the publisher then relays it to Kafka - the
   standard reliable-publish pattern.
3. **Delivery semantics?** This pipeline is at-least-once: a crashed publisher
   or a missing ack redelivers. That is why consumers must be idempotent and
   why manual acks + a dead-letter topic matter - poison never stalls the
   group.
4. **Schema note:** the payload is a JSON record (dependency-light, keeps the
   learning repo easy to run); switching to Avro/Protobuf + Schema Registry is
   a serializer/dependency swap behind the same topic contract.

## PR #32 — Observability

**Aspect learned:** logs, metrics and traces - the three pillars that tell you
what a running system is actually doing.

### Deliverables
- [x] Structured JSON logging with Logback (`logback-spring.xml`; prod profile
      emits one JSON object per line with MDC fields)
- [x] MDC correlation/trace IDs: `CorrelationIdFilter` honours/creates
      `X-Correlation-Id`, echoes it, and puts it in every log line's MDC
- [x] Micrometer metrics + `@Timed` (`TimedAspect` bean; `order.place` and
      `product.get` timers with p95 percentiles)
- [x] Prometheus export: `PrometheusMeterRegistry` + `/actuator/prometheus`
- [x] OpenTelemetry tracing + Jaeger exporter (OTLP) - disabled by default,
      enabled in prod; `docker-compose` runs Jaeger all-in-one
- [x] Custom health indicator (`AppInfoHealthIndicator`) merged into
      `/actuator/health`
- [x] SLO definitions: `docs/slo/order-api-slo.md`
- [x] AlertManager rules: `docs/monitoring/prometheus/alerts.yml`

### Key questions answered
1. **What is structured logging?** One JSON object per event instead of free
   text - every field (level, logger, message, MDC correlationId) is
   queryable by log aggregators. Dev keeps human-readable logs; prod switches
   to JSON by Spring profile.
2. **Why distributed tracing?** Logs tie events to a *single service*; a trace
   spans services. OpenTelemetry propagates the trace context across HTTP and
   exports spans to Jaeger, where one order request becomes one waterfall.
3. **SLIs vs SLOs vs SLAs?** An SLI measures (p95 latency, success rate); an
   SLO is the target over time (≥99.5% success, p95 < 500 ms); an SLA is the
   contractual promise built on SLOs. Alert rules (docs/monitoring) page on
   budget burn, not on single flaky minutes.

## PR #34 — CI/CD & GitOps

**Aspect learned:** every change is tested automatically, and every promotion to
a cluster is a reviewable commit - not a button.

### Deliverables
- [x] GitHub Actions CI (`.github/workflows/ci.yml`): full Testcontainers suite on
      every PR to `develop` (JDK 21, Maven cache)
- [x] Promotion workflow (`.github/workflows/promote.yml`): manual dispatch that
      bumps the overlay image tag and opens the env promotion PR
- [x] GitOps model documented (`docs/gitops/gitops.md`) + ArgoCD `Application`
      manifest (`k8s/argocd/order-api.yaml`) - env branches → ArgoCD sync
- [x] Kustomize overlays per environment (from PR #33) used as the promotion target
- [x] Sealed Secrets flow for encrypted-in-git secrets (docs + samples from PR #33)
- [x] Feature flags (`FeatureFlags` bean + `/api/v1/features`, `app.features.*`)
- [x] Contract testing with Pact: `docs/contracts/` (example pact JSON + flow doc)

### Key questions answered
1. **What is GitOps?** The cluster's desired state lives in git; an operator
   (ArgoCD) converges reality to it. Deploy = merge a commit. Rollback = revert.
2. **How do you handle secrets in git?** Encrypt them. A SealedSecret is
   decrypted only by the cluster controller, so the manifest is safe to commit;
   plaintext secrets never enter the repo.
3. **What is progressive delivery?** Ship in stages (test → uat → staging →
   prod) watching SLOs at each step; feature flags let a capability ride along
   dark until it is safe to switch on.

## PR #35 — Enterprise Features (optional slice)

**Aspect learned:** the features enterprises ask for next - and which are worth
building vs documenting as a design decision.

### Deliverables (implemented)
- [x] Internationalization: `i18n/messages*.properties` + `MessageSource` +
      `/api/v1/messages/{key}` resolving by `Accept-Language` (de/en tested)
- [x] CSV export endpoint `GET /api/v1/products/export.csv`, gated behind the
      `csvExport` feature flag
- [x] Feature flags (PR #34) now drive a real endpoint switch
- [x] Enterprise design notes (`docs/enterprise/enterprise.md`): multi-tenancy
      (discriminator vs schema-per-tenant), developer portal / API-key self-
      service, scheduled jobs & background processing strategy, DB backup
      strategy with point-in-time recovery and restore drills

### Key questions answered
1. **What is multi-tenancy?** Serving many customers from one app. Discriminator
   column = cheap, shared schema; schema-per-tenant = strongest isolation,
   highest ops cost. We document both and pick discriminator + row-level
   filtering for this product, with schema-per-tenant as the upgrade path.
2. **How do you internationalize?** Never embed user-visible strings in code -
   keys + resource bundles, resolved by locale (Accept-Language). This slice
   proves the plumbing; real products add per-field validation messages next.
3. **Progressive delivery recap:** flags (PR #34) + staged promotion (PR #33/34)
   mean a capability can ship dark, then light gradually - watching the SLOs
   from PR #32 the whole way.

### Learning journey complete
35 learning PRs, one concept each, all merged to `develop` with tests green at
every step. Final suite: **120 tests, 0 failures.**

---

## PR #36 (bonus) — Model Context Protocol (MCP) Server

> Requested after the journey as an extra: let an AI assistant *use* the API.

**Aspect learned:** MCP — how an assistant discovers and calls tools over
JSON-RPC, and the security mindset of exposing a read-only AI surface.

### Deliverables (implemented)
- [x] `POST /mcp` — minimal spec-conformant MCP server (JSON-only mode of the
      Streamable HTTP transport): `initialize`, `ping`, `tools/list`,
      `tools/call`, notifications, and JSON-RPC batch requests
- [x] Read-only tools behind one interface (`McpTool`): `api_health`
      (DB-free probe), `product_search` (catalogue by name), `order_status`
      (order id → number/status/total/date)
- [x] **PII boundary**: `order_status` never touches the (lazy) customer
      association — an assistant physically cannot pull customer data through
      the tools
- [x] Real Postgres integration tests (`McpControllerIntegrationTest`):
      handshake, tool discovery/calls, PII guarantee, JSON-RPC `-32601`/
      `-32602` errors, unknown-order tool errors, notifications + batches
- [x] Business guide: `docs/business/11-mcp-ai-integration.md`

### Design decision
The repo was pinned to Spring Boot 3.2 (Spring 6.1) at that time, while the
official MCP Spring modules require Spring 6.2+. Rather than upgrade the stack
mid-sequence, PR #36 implemented the JSON-RPC wire subset directly and
documented the upgrade path. **PR #37 below does that upgrade and replaces this
hand-rolled transport with the official `mcp-spring-webmvc` SDK** — same tools,
same PII boundary, protocol correctness owned by the SDK.

### Key questions answered
1. **Why MCP instead of more REST endpoints?** Assistants are best served by a
   *discoverable* surface: they read `tools/list` and adapt. The API keeps one
   auth story (Bearer/API key) and one concept of "who may do what".
2. **Why read-only?** An assistant can be a great observer and a dangerous
   writer. Read-only tools give value now; write tools need confirmation +
   side-effect discipline (idempotency, outbox, audit) later.
3. **Is it production-grade MCP?** The subset is spec-conformant and tested,
   with honest limits (no resources/prompts/SSE) documented rather than hidden.

---

## PR #37 (bonus) — Official MCP Spring SDK (Spring Boot 3.4 upgrade)

> Follow-up to PR #36: replace the hand-rolled JSON-RPC subset with the
> **official MCP Java SDK** once the stack allowed it.

**Aspect learned:** when a capability needs a newer framework minor, upgrade the
platform deliberately — and how much correctness the official SDK buys you once
you do (protocol negotiation, JSON-RPC, notifications, error contracts).

### What changed
- [x] **Spring Boot 3.2.1 → 3.4.1** (Spring Framework 6.2.1) — the version the
      official `mcp-spring-webmvc` module is built against (Spring 6.2+ minimum)
- [x] MCP server re-implemented on `io.modelcontextprotocol.sdk:0.18.4`:
      `WebMvcStatelessServerTransport` (`POST /mcp`) + `McpServer.sync(...)` +
      per-tool `SyncToolSpecification`s. Same read-only tools, same PII boundary
- [x] ~200 lines of hand-rolled JSON-RPC deleted — the SDK now owns the protocol
      (`initialize`, `tools/list`, `tools/call`, notifications, errors)
- [x] Jackson bridge (`mcp-json-jackson2`) feeds the SDK the Spring-managed
      `ObjectMapper`
- [x] Tests now drive the app with the **official MCP Java client** against a
      real Tomcat port + real Postgres (`McpServerSdkIntegrationTest`, 7 tests)
- [x] Boot-3.4 collateral fixes: Micrometer Prometheus classes moved to
      `io.micrometer.prometheusmetrics`; springdoc 2.3.0 → 2.7.0 (2.3.0 returns
      500 on `/v3/api-docs` under Boot 3.4)

### Key questions answered
1. **Why upgrade the whole platform for one dependency?** Spring Boot versions
   are a bundle — you cannot move one framework jar to 6.2 without aligning the
   rest. The jump 3.2 → 3.4 was the smallest supported step that reached
   Spring 6.2.1, and it is exactly what the MCP Spring module pins.
2. **What did the official SDK give us?** Protocol version negotiation, JSON-RPC
   2.0 framing, `tools/list` + `tools/call` handling, notifications and a
   conformance-tested stateless Streamable-HTTP transport — all verified by
   driving the server with the official client instead of hand-written curl.
3. **Honest limits that remain:** tools-only (no resources/prompts), no JSON-RPC
   batches, no sessions/SSE. Sessionful Claude Desktop-style clients are an
   upgrade path (swap in `WebMvcStreamableServerTransportProvider`), not a bug.

---

## PR #38 (bonus) — RAG: the API answers questions about itself (`docs_search`)

> Follow-up to PR #37: MCP let an assistant *call into* the API; RAG turns the
> direction around — the API now *answers questions* about its own
> documentation. Retrieval-Augmented Generation over the 66-file `docs/` corpus.

**Aspect learned:** the full RAG pipeline (ingest → chunk → embed → vector
store → similarity search → grounded generation), the opt-in pattern for AI
features, and why Spring AI auto-configuration breaks tests unless explicitly
disabled.

### What changed
- [x] **RAG pipeline** (`com.company.orderapi.rag`): `DocumentIngestionService`
      loads `classpath:docs/**/*.md` on startup, chunks with
      `TokenTextSplitter(800, 200)`, embeds via **Ollama `nomic-embed-text`**
      into an in-memory `SimpleVectorStore`
- [x] **Generation**: `RagService` does top-k similarity search
      (`app.rag.top-k`, default 5) and asks **DeepSeek** (`deepseek-v4-flash`)
      to answer using only the retrieved chunks — each chunk attributed with
      `[Source: <file>]`. No chunks matched → helpful "not found" message and
      **no LLM call at all** (never invents answers)
- [x] **MCP integration**: auto-discovered read-only tool `docs_search(question)`
      on the existing `/mcp` server (`AbstractMcpReadOnlyTool` like
      `product_search`/`order_status`) — no new endpoint, no write surface
- [x] **Fully opt-in**: all RAG beans gated on `app.rag.enabled=true` (the `rag`
      Spring profile); the default app needs no Ollama, no API key, no vector
      store. `application-rag.yml` hard-requires `DEEPSEEK_API_KEY`
- [x] **Spring AI 1.0.0** via BOM: `spring-ai-starter-model-deepseek`,
      `spring-ai-starter-model-ollama`, `spring-ai-vector-store`; `docs/` added
      as a classpath resource
- [x] **Test collateral (kept)**: DeepSeek's eager auto-config throws without an
      API key, so every `@SpringBootTest` now disables AI autoconfig
      (`spring.ai.model.chat=none` + `spring.ai.model.embedding=none`); Mockito
      needs `-Dnet.bytebuddy.experimental=true` on the test JVM (local Java 25)
- [x] **8 new pure unit tests** (mocked vector store/model, real corpus):
      grounding (prompt cites `Source:`), idempotent ingest, no-chunks honesty

### Design decision
Two models, two jobs: **Ollama** (local, free) does *embeddings* while
**DeepSeek** (hosted) does *chat generation* — DeepSeek has no first-class
embeddings API and the split is cheap/fast/quality-balanced. The vector store is
**in-memory** (`SimpleVectorStore`) because 66 files / ≈118 chunks is tiny; the
`VectorStore` interface is where you'd plug PGVector/Redis for a huge corpus.
Teach guide: `docs/additions/01-rag-and-docs-search.md`.

### Key questions answered
1. **Why RAG instead of one big `grep`?** Lexical search misses meaning
   ("concurrency guard" vs "locking"); embeddings retrieve by *meaning*, and the
   LLM writes a grounded, source-attributed answer only from the retrieved chunks.
2. **Why fully gated behind a profile?** The default context must stay
   unchanged: no Ollama, no API key, no eager model beans. But the
   *auto-configuration* still boots them eagerly — that is why tests must opt
   out with `spring.ai.model.*=none`.
3. **How is hallucination controlled?** The system prompt forbids knowledge
   outside the cited chunks, every answer carries `[Source: ...]` attribution,
   and with zero relevant chunks the LLM is never called at all.

---

## PR #39 (bonus) — Agentic tool-calling: the API decides (`agentic_ask`)

> Follow-up to PR #38: MCP/RAG let an assistant call the API and ask it about
> itself. This PR gives the API's own DeepSeek chat model the same read-only
> tools as **functions it decides to call** — an agent that chains
> `product_search` + `order_status` + `docs_search` to answer one task.

**Aspect learned:** function calling / agentic loops — how a model advertises,
picks and chains real methods, how to expose it safely, and how Spring AI's
`ChatClient` actually carries tools to the model (decompiled, not assumed).

### What changed
- [x] **`com.company.orderapi.agent`**: `AgentToolSet` exposes the four existing
      read-only MCP tools as `@Tool`-annotated functions (same `product_search` /
      `order_status` / `docs_search` / `api_health` names, same PII boundary);
      `AgentConfig` turns them into a `ToolCallbackProvider`; `AgentService`
      builds a `ChatClient` with a system prompt ("call a tool, don't guess,
      chain calls, never claim customer data") + the tool callbacks as
      `DefaultToolCallingChatOptions`
- [x] **One code path, two protocols**: `AbstractMcpReadOnlyTool.execute(...)`
      is now the shared invocation contract — MCP `tools/call` and agent
      function calling both delegate to the same read-only tools
- [x] **`agentic_ask` MCP tool**: the agent as just another read-only tool on
      `/mcp` — one `task`, multi-step reasoning happens server-side
- [x] **Real gotcha fixed + locked by a test**: Spring AI 1.0.0 merges tool
      callbacks into the generated `Prompt` only when `ChatOptions` is already a
      `ToolCallingChatOptions` (verified by decompiling
      `DefaultChatClientUtils`). `.defaultToolCallbacks(...)` alone silently
      drops them; the agent embeds them in `.defaultOptions(...)` and the test
      captures the real `Prompt` and asserts the four tools are in
      `getToolCallbacks()`
- [x] **9 new pure unit tests** (`AgentToolSetTest`, `AgentServiceTest`):
      registration under MCP names, delegation, the PII guarantee (order result
      never contains the customer email), blank-task rejection, system-prompt
      wiring, and the tools-reach-the-model proof

### Key questions answered
1. **Agent vs MCP?** Complementary: MCP is the transport a caller uses to reach
   the tools; function calling is the loop a model runs *inside* a chat. Here
   the tools are identical — the same beans, invoked through one `execute`.
2. **How is it safe?** Structurally: the agent can only call read-only,
   PII-free tools that physically cannot touch the customer association; there
   is no write tool; auth is unchanged; no memory is retained between tasks.
3. **Why embed tools in ChatOptions instead of registering them?** See the
   gotcha above — the framework's merge is conditional, and the unit test that
   captures the Prompt is the guard.

---

## PR #40 (bonus) — Chat memory: the agent remembers (`agentic_ask` + `conversationId`)

> Follow-up to PR #39: the agent worked, but every call was stateless — turn 2
> forgot turn 1. This PR gives it **scoped multi-turn memory**: pass the same
> `conversationId` across calls and previous questions *and the agent's answers*
> are fed back into the model's context via a message-window memory advisor.

**Aspect learned:** LLM chat memory — Spring AI's `ChatMemory` +
`MessageChatMemoryAdvisor` (before/after hooks that prepend history and store
the reply), a bounded per-conversation message window, and — the real gotcha —
how a conversation id actually reaches the advisor in Spring AI 1.0.0: there is
**no `.context()`** on the request spec; values flow via
`AdvisorSpec.param(...)` → `advisorParams` → request context
(`ChatMemory.CONVERSATION_ID` key). Older docs examples don't compile against it.

### What changed
- [x] **`app.agent.memory.max-messages` `ChatMemory` bean**: a 20-message
      sliding window (`MessageWindowChatMemory`, in-memory, `SystemMessage`-aware
      eviction) — swap the repository for Redis/DB persistence later
- [x] **`MessageChatMemoryAdvisor`** wired on the agent's `ChatClient`
      (prepends prior turns, stores the assistant reply)
- [x] **`ask(task, conversationId)`** — same id = multi-turn conversation;
      blank/missing id = fresh random per-call id, **stateless by default**
      (the PR #39 guarantee is preserved); system prompt grown to tell the model
      it may use remembered context
- [x] **`agentic_ask` gains optional `conversationId`** in its MCP schema and
      passes it straight through
- [x] **3 new pure unit tests with real memory plumbing**: context carried
      across turns in one conversation (turn 2's prompt contains turn 1's
      Q *and* A), conversations isolated, blank ids stay stateless

### Key questions answered
1. **How does an LLM "remember"?** The stored history is prepended to the prompt,
   not persisted in the model — an advisor does it for you (prompt-frame memory).
2. **How do conversations stay isolated?** Every conversation is keyed by the
   caller's `conversationId`; absent ids get a random per-call id, so memory is
   strictly opt-in and never leaks between calls.
3. **What was the version-specific trap?** `.context()` doesn't exist on the
   1.0.0 request spec — you set advisor params per request. Doc'd in
   `docs/additions/03-chat-memory.md` with the test that captures the real
   second-turn prompt.

---

## PR #41 (bonus) — Guarded write tool: `cancel_order` (the first mutation)

> Follow-up to PRs #38–#40: every tool so far was read-only — safety came from
> "nothing can change." This PR adds the first **write** capability on the MCP
> server (`cancel_order`) and makes the guard rails the point: feature-gated
> off by default, per-call `confirmed=true`, service-level `order_write`
> authorization, domain state-machine rules, audit logging, PII-free replies —
> and, the biggest lesson, the **agent still can never call it**.

**Aspect learned:** how to add a mutating capability to an LLM surface without
lowering its security bar — the difference between an *ergonomic* rail
(confirmation flag) and the *real* boundary (service-layer `@PreAuthorize` +
domain state machine), and how to make the armed capability structurally
unreachable by the model via a separate tool hierarchy.

### What changed
- [x] **`app.mcp.write-tool.enabled` gate** — `CancelOrderTool` (and every
      future write tool) is a `@Bean` only when the property is `true`; the
      default app advertises read-only tools only (`McpServerSdkIntegrationTest`
      asserts `cancel_order` is absent)
- [x] **`AbstractMcpWriteTool`** — a *separate* base class (not a subclass of
      the read base), so the auto-discovery (`List<AbstractMcpReadOnlyTool>` +
      `List<AbstractMcpWriteTool>`) can reason about writes by type; agent tool
      collection physically excludes them
- [x] **`cancel_order`** — schema requires `orderId` + `confirmed`; anything but
      `confirmed=true` refuses ("…confirmed must be exactly true…"); success
      returns order number + new status only (never customer PII) and writes a
      structured `mcp.cancel-order` audit line
- [x] **`OrderService.cancelOrder(Long)`** — `@Transactional`,
      `@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')")`,
      `@Timed("order.cancel")`; unknown id → `IllegalArgumentException`
- [x] **`Order.cancel()`** — domain state machine: PLACED/CONFIRMED → CANCELLED;
      already-cancelled and shipped/delivered orders are refused
- [x] **17 new tests** — `OrderStatusTransitionTest` (5, pure domain),
      `CancelOrderToolTest` (4, tool logic incl. "never touches the service
      without confirmation"), `AgentToolSetTest` (agent surface stays
      strictly read-only), `OrderServiceTest` (+4, committed DB semantics),
      `McpServerWriteToolIntegrationTest` (3, full MCP round-trip with the gate
      enabled)

### Key questions answered
1. **How do you let an LLM surface mutate state safely?** Rails, in order:
   gate so the capability doesn't exist by default; keep write tools in a
   hierarchy the agent's tool collection never sees; require an explicit
   confirmation argument; enforce the real authorization in the service layer;
   keep the domain rules on the entity; audit every success; return no PII.
2. **Why is the confirmation flag "not security"?** It's an ergonomic guard
   against accidental calls (absent/`false`/string `"true"` all refuse). The
   boundary is the `@PreAuthorize` scope/API-key check plus the state machine;
   the gate and agent-separation minimize the surface in the first place.
3. **Why a second tool base class?** So "write" is a type, not a naming
   convention — the MCP server merges both lists by type and the agent
   `AgentToolSet` test asserts no write-name ever appears in its surface.

---

## PR #42 (bonus) — RAG productionization: persistent pgvector + re-index trigger + retrieval eval

> Follow-up to PR #38: RAG's two honest open boxes were "in-memory vector store
> … scale path: PGVector" and "docs indexed once at startup … a tiny admin
> trigger is the obvious next increment." This PR closes both — vectors now live
> in pgvector on the app's own Postgres, re-indexing is *content-addressed and
> incremental* and triggerable via a guarded `reindex_docs` write tool — and then
> adds the part almost nobody learns: **measuring retrieval quality** against a
> golden question set, report-only by default and a deploy gate once earned.

**Aspect learned:** that RAG's production concerns are three different problems —
persistence (a `VectorStore` bean swap, no code change), freshness (deterministic
ids + content hashes make re-indexing idempotent and cheap), and *measurement*
(a hit-rate@k harness that makes retrieval quality a number before it's a gate).

### What changed
- [x] **`PgVectorStore` replaces `SimpleVectorStore`** — same `VectorStore`
      interface, `initializeSchema(true)` creates the `vector` extension +
      `vector_store` table + HNSW index idempotently on the app's own Postgres;
      plain `spring-ai-pgvector-store` library (not the starter) so no eager
      auto-config bean leaks into non-RAG contexts
- [x] **Content-addressed incremental re-index** — deterministic chunk ids
      (`UUID.nameUUIDFromBytes(source:index)`), sha-256 `content-hash` metadata,
      unchanged files skipped, edited files deleted + re-added exactly once,
      removed sources dropped; sources keyed by *relative path* (basenames are
      not unique here — eleven `README.md`s) with idempotency locked in by tests
- [x] **`reindex_docs` guarded write tool** — PR #41 rails: `app.mcp.write-tool.enabled`
      + RAG-on conditionals, required `confirmed=true`, side effects only through
      `DocumentIngestionService.reindex()`, count-only reply, `mcp.reindex-docs`
      audit; the agent still can never call it
- [x] **Retrieval-eval harness** — `rag/eval/golden-questions.json` (12 goldens),
      `RagRetrievalEvaluator` (hit-rate@k, top-1 accuracy, precision@k),
      `RagEvalRunner` logs the full HIT/MISS report at startup with RAG on;
      `app.rag.eval.min-hit-rate` defaults to `0` (report-only) and gates deploys
      once raised
- [x] **19 new tests** — `DocumentIngestionServiceTest` (rewritten, in-memory
      `VectorIndexStore` twin; idempotency/change/removal), 3 eval semantics,
      4 runner-gate, 4 tool-guard, and `PgVectorRagIntegrationTest` (6) against
      a real `pgvector/pgvector:pg16` container asserting rows, 768-dim vectors,
      HNSW, idempotent row counts, stale-chunk replacement on edits, retrieval
      from Postgres and a well-formed eval over the real goldens

### Key questions answered
1. **Why did re-indexing break the first time?** Basenames aren't unique (eleven
   `README.md`) — deterministic ids derived from them collided, so "unchanged"
   could never be proven. Relative-path source keys + a test that asserts a
   second `reindex()` is a zero-chunk no-op fixed and locked it.
2. **Why not Redis/a vector DB?** The `VectorStore` seam made the swap a bean.
   postgres was already there, ops unchanged, and one database answers SQL and
   similarity queries.
3. **When is a hit-rate gate safe?** Only after real retrieval quality has been
   measured once — which is exactly why `min-hit-rate=0` is the default and the
   hash-embedded tests are plumbing-only (they can prove the harness, never
   shoulder the threshold).

## PR #43 (bonus) — Hybrid retrieval: dense + Postgres full-text (RRF + MMR)

> PR #42 made retrieval *measurable* and the measurement exposed the honest
> weakness of a dense-only retriever: embeddings are bad at exact vocabulary.
> Tool ids, error codes, identifiers (`reindex_docs`, `cancel_order`) — a chunk
> can be semantically "about" a topic and still never surface for the query that
> names it exactly. This PR makes retrieval hybrid: cosine with BM25-style
> Postgres full-text, fused by Reciprocal Rank Fusion and re-ranked for topical
> diversity by MMR — with the #42 eval harness as the before/after ruler.

**Aspect learned:** hybrid retrieval is *not* "avg the two scores" — cosine and
`ts_rank` are incomparable units, so the fusion must be rank-based (RRF,
`1 / (rrfK + rank)`), and after fusion a relevance-maximizer tends to hand all
top-k slots to one dominant topic, which is where MMR's *diversity penalty*
(lambda) earns its keep.

### What changed
- [x] **`RetrievalEngine` seam** — `retrieve(query, topK)`, wiring decided by
      `app.rag.retrieval-mode` (`DENSE` = pre-#43 behaviour, `HYBRID` default);
      `RagService` and the #42 eval harness now retrieve through it unchanged
- [x] **`LexicalRetrievalEngine`** — Postgres full-text over the *same*
      `vector_store` table: `to_tsvector('english', content) @@
      plainto_tsquery(?,…)`, scored `ts_rank`, lazy/idempotent GIN index behind
      an `AtomicBoolean` so startup order never matters
- [x] **`HybridRetrievalEngine`** — each engine queried at `4× topK` (min 20),
      fused by RRF (`rrf-k=60`), MMR-reranked when enabled
      (`retrieval.mmr-enabled`, `retrieval.mmr-lambda` clamped to `[0,1]`);
      fusion dedups across lists by the #42 content-hash `Document` ids
- [x] **5 new + 2 updated tests** — `HybridRetrievalEngineTest` (RRF ordering
      verified against hand-computed scores, MMR proven at both λ extremes,
      widened candidate pool), `PgVectorRagIntegrationTest` grows lexical
      term-containment ("outbox" → chunks that actually contain it) and the
      default hybrid wiring; `RagServiceTest`/`RagEvalRunnerTest` moved to the
      engine / new `RagProperties` shape

### Key questions answered
1. **Why not blend scores?** Cosine and `ts_rank` have no shared scale; RRF
   consumes only *ranks*, which are unit-free and transferable — no magic per
   corpus weight.
2. **Why would dense alone miss?** The query names `reindex_docs`; the relevant
   chunk describes it in prose. Embeddings track *meaning*, full-text tracks
   *occurrence* — the two only ever agree for most queries, and "most" is
   exactly the gap fusion closes.
3. **When can the #42 hit-rate gate be raised?** After this PR, dense vs hybrid
   is measurable on the same goldens by toggling `retrieval-mode` — fix the
   retriever *first*, then set `min-hit-rate`, never before.

## PR #44 (bonus) — The retrieval-eval gate, earned: real-model measurement

> PRs #42/#43 built the harness (goldens → hit-rate@k, report-only gate) and a
> second retriever (hybrid full-text) — but the gate was still `0` because
> nobody had *measured* the number. This PR runs the harness for the first time
> with the real model over the real corpus. It failed immediately and usefully:
> the eval ran **before** ingestion and measured an empty store; then MMR
> (shipped on in #43) scored *worse* than RRF-only — 50% vs 75% hit-rate@5 —
> on single-topic goldens. Four latent startup bugs in the rag profile surfaced
> and were fixed, and the gate was only then set to a defensible `0.7` — earned,
> never predicted.

**Aspect learned:** a deploy gate and a retriever both need a *measured runway*
before they mean anything — errors flood in as soon as you run what you built,
and a threshold is only honest after the table exists. The single most valuable
artifact is the measured table:

| retrieval-mode | hit-rate@5 | top-1 accuracy | precision@5 |
|---|---|---|---|
| `DENSE` (baseline) | 75.0% | 41.7% | 15.0% |
| `HYBRID` + RRF (`mmr-enabled: false`) | 75.0% | **50.0%** | 15.0% |
| `HYBRID` + RRF + MMR (λ=0.5) | 50.0% | 41.7% | 10.0% |

### What changed
- [x] **Eval-before-ingest ordering bug fixed** — `RagEvalRunner` was an
      `ApplicationRunner` (fires before `ApplicationReadyEvent` listeners), so
      it measured an *empty* store; both are now ordered ready-event listeners
      (`@Order(HIGHEST_PRECEDENCE)` ingest, `LOWEST_PRECEDENCE` eval)
- [x] **Run-the-gate chrome (4 latent bugs)** — explicit `spring.ai.model.chat:
      deepseek` (two ChatModel beans fought), `@Lazy` ChatModel in `RagService`
      (agent↔ChatModel tool-callback cycle failed startup), compose Postgres →
      `pgvector/pgvector:pg16` (not a single rag-profile boot had ever worked)
- [x] **MMR shipped default flipped OFF** — measured damage, not opinion:
      λ=0.5 diversity penalty costs 25pts of hit-rate@5 on single-topic goldens;
      stays opt-in (`app.rag.retrieval.mmr-enabled`)
- [x] **Persisted eval report** — `app.rag.eval.report-location`
      (`target/rag-eval-report.json`): timestamp, retrieval-mode, hit-rate/top-1/
      precision@k + full per-question HIT/MISS trail (a fancier log that
      survives rotation); written as `RagEvalReportSnapshot`
- [x] **Earned gate activated** — `app.rag.eval.min-hit-rate: 0.7` in the rag
      profile (measured 75%, one notch below), goldens untouched; verified both
      directions with the real model: default profile passes, forcing `0.9`
      fails startup with `RAG retrieval eval gate FAILED`
- [x] **2 new + 2 updated tests** — runner report-write/snapshot (self-contained,
      temp-dir, hash-backed), plus eval/RAG suites adjusted for the new
      `report-location` property and listener shape; suite 189 green

### Key questions answered
1. **Why does MMR lose here?** Recall-tier metrics like hit-rate@k already get
   diversity from top-5; MMR's penalty only re-orders single-topic answers out
   of the list. It's an opt-in knob for genuinely multi-topic questions now.
2. **Why keep the goldens fixed?** Editing them until the retriever passes would
   be teaching to the test — the anti-pattern the discipline exists to block.
   Two "misses" actually retrieve *identical-content twin files* in another
   folder; the report trail records that honestly.
3. **Haven't #38–#43 always been runnable?** No — that's the lesson. The rag
   profile never booted end-to-end before this PR; four bugs (§ What changed)
   prove "gated, report-only" and "actually runnable" are different states.

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
| 18 | JPA Events & Listeners | 36 (bonus) | MCP Server (AI Integration) |
| 37 (bonus) | Official MCP Spring SDK on Boot 3.4 (Spring 6.2) | 38 (bonus) | RAG — `docs_search` (DeepSeek + Ollama) |
| 39 (bonus) | Agentic tool-calling — `agentic_ask` | 40 (bonus) | Chat memory — multi-turn `agentic_ask` |
| 41 (bonus) | Guarded write — `cancel_order` | 42 (bonus) | RAG productionization — pgvector + re-index + retrieval eval |
| 43 (bonus) | Hybrid retrieval — dense + Postgres full-text (RRF + MMR) | 44 (bonus) | RAG eval gate, earned — real-model measurement, MMR off, gate 0.7 |
| 45 (next, bonus) | Open — your choice | | |

---

*Built one pull request at a time — each teaching one API development aspect.*

## PR #33 — Docker & Kubernetes

**Aspect learned:** ship the app as a container and run it on Kubernetes - probes,
resources, scaling, TLS, secrets and per-environment overlays.

### Deliverables
- [x] Multi-stage `Dockerfile` (Maven build -> slim JRE runtime, non-root user)
- [x] `docker-compose.yml` now runs EVERYTHING: postgres + redis + kafka + jaeger + `api` (health-gated)
- [x] `k8s/base`: Deployment (resources/limits, non-root, liveness/readiness/startup probes), Service, ConfigMap, HPA, Ingress with cert-manager TLS
- [x] Kustomize overlays: `dev`, `test`, `uat`, `staging`, `prod` (prefixes, replicas, image tags, active Spring profile)
- [x] Secrets: plaintext DEV sample + SEALED secret sample + `docs/k8s/deploy.md`
- [x] Graceful shutdown (`server.shutdown: graceful`, 20s drain) + probe endpoints (`/actuator/health/liveness|readiness`)

### Key questions answered
1. **What is containerization?** One immutable artifact (image) runs identically on a laptop and in prod; the multi-stage build keeps the image small and dependency-free.
2. **Why Kubernetes?** Declarative ops: desired state in git (`deployment.yaml`), the cluster converges to it. Probes tell it when a pod is alive (`liveness`), ready (`readiness`) or still starting (`startup`).
3. **Probes:** readiness gates traffic, liveness restarts a wedged pod, startup prevents kill-during-cold-start. Probes must use their OWN endpoints - readiness failing must never restart a healthy-but-busy pod (that is why we split them).

---
