# Order Management API

A **production-grade Order Management API**, built as a **learning journey**: one
pull request at a time, each PR teaching one concrete aspect of modern Java 21 /
Spring Boot API development (JPA mappings, cascading, fetch strategies, locking,
auditing, security, event-driven, Kubernetes, ...).

> **Status: PR #7 (Batch Fetching) — awaiting review.**
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
    │   │   └── domain/                       # JPA entities + enums
    │   │       ├── BaseEntity.java
    │   │       ├── Customer.java / Address.java / Order.java
    │   │       ├── OrderItem.java / Product.java / Category.java
    │   │       ├── Payment.java              # added PR #4 (Order 1:1)
    │   │       └── enums: AddressType / OrderStatus / PaymentMethod / PaymentStatus
    │   └── resources/
    │       ├── application.yml
    │       └── db/changelog/                 # Liquibase schema versioning
    │           ├── db.changelog-master.xml
    │           └── v1.0/
    │               └── 01_create_tables.sql  # 8 tables + FKs + indexes
    └── test/java/com/company/orderapi/
        ├── OrderManagementApiApplicationTests.java   # contextLoads smoke test
        └── integration/
            ├── DatabaseSchemaIntegrationTest.java    # verifies Liquibase output
            ├── JpaEntityMappingIntegrationTest.java  # FK write/read round-trips
            └── JpaCascadingIntegrationTest.java      # cascade behaviour (PR #4)
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
