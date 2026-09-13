# 14. Schema Generation and Validation (PR #14)
> PR #14 — Schema Generation and Validation: `ddl-auto` `validate` vs `update` vs `create` vs `create-drop` vs `none`, Liquibase as source of truth, catching drift at startup. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Liquibase, Testcontainers. See `README.md:1772` roadmap `| 14 | Schema Generation and Validation |`.
---

## 1. Purpose — what shipped

PR #14 delivers **Schema Generation and Validation** as a first-class, tested, documented building block. The single setting `spring.jpa.hibernate.ddl-auto: validate` (`application.yml:48`) is the project's schema contract: Hibernate never creates or alters tables, it only *proves* that the entity mappings (`BaseEntity.java:43`, `Order.java:43`, `Customer.java:37`) match the Liquibase-owned schema (`src/main/resources/db/changelog/`) at startup. `application-dev.yml:12` `ddl-auto: update` is the explicitly flagged dev-only exception (fast entity iteration on a throwaway dev DB); production (`application-prod.yml:12`) locks `validate`. Drift (entity removed a column but Liquibase not rolled back, wrong column length) fails fast with `SchemaManagementException` before any test or HTTP request runs, rather than corrupting data silently.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Two common failure modes. (1) `ddl-auto: update` in production: Hibernate silently adds/drops columns to match entities — a typo in `Order.orderNumber` (`@Column(length=40)` `Order.java:64`) that shortens the column would truncate `ORD-XXXXXXXXXX` data with no review, and Liquibase change history diverges from reality. (2) No validation at all (`ddl-auto: none`): entity says `@Column(name="order_number", length=40)` `Order.java:64` but Liquibase still has `order_number VARCHAR(30)` from an old changeset — insert fails at runtime with `DataIntegrityViolationException` ("value too long") instead of at startup, discovered only when that code path is tested.

**After:** `application.yml:48` `ddl-auto: validate` plus `application-dev.yml:12`/`application-prod.yml:12` profile overrides makes the contract explicit. Test and CI use the default `validate` — they run Liquibase via Testcontainers Postgres first, then Hibernate validates against the real schema. A mismatched column type/length/nullability is caught in the `SchemaManagementException` that aborts `ApplicationContext` load, failing the build in seconds with a message like `Table [orders] column [order_number] mismatched types (expected VARCHAR(40) found VARCHAR(30))`.

### Theory — `ddl-auto` from first principles (100+ lines)

#### 2.1 What `spring.jpa.hibernate.ddl-auto` is

At `EntityManagerFactory` creation, Hibernate inspects the entity metamodel (every `@Entity`, `@MappedSuperclass` `BaseEntity.java:43`, `@Column`, `@JoinColumn`, `@Table` `Order.java:42`, `@Version` `BaseEntity.java:53`, audit fields `BaseEntity.java:59`, `orderNumber` `Order.java:64`, `customer` FK `Order.java:46`, etc.) and *optionally* compares it to the live DB metadata (`information_schema.columns`, `pg_indexes`, `pg_constraint`). `ddl-auto` controls what Hibernate does with that comparison:

```
ddl-auto value    Hibernate action     DB mutation?   When useful
──────────────    ────────────────     ────────────   ──────────
none              do nothing           none            Liquibase/Flyway only; no check — drift found at runtime
validate          compare + abort if mismatch   none   Production, tests, CI — source-of-truth is migration scripts (THIS repo's default)
update            compare + ALTER to match entities  YES (ALTER TABLE)  Dev/throwaway only — fast entity iteration
create            DROP + CREATE from entities  YES (DROP/CREATE)   Tests that need blank schema (replaces create-drop)
create-drop       create on startup, drop on SessionFactory close  YES  Integration tests with in-mem DB (older Spring pattern; here Testcontainers uses Liquibase)
```

Source in Hibernate: `SchemaManagementTool`, `SchemaValidator` (for `validate`), `SchemaMigrator` (for `update`), `SchemaCreator`. Spring Boot sets it via `spring.jpa.hibernate.ddl-auto` → `AvailableSettings.HBM2DDL_AUTO`.

In this repo's three `application*.yml`:

```yaml
# application.yml:48  — default (tests, CI, bare production)
jpa:
  hibernate.ddl-auto: validate   # Liquibase owns schema; Hibernate only proves agreement

# application-dev.yml:12 — dev profile only (docker-compose local DB)
jpa:
  hibernate.ddl-auto: update   # temporarily apply entity column tweaks without writing a changeset; DO NOT ship

# application-prod.yml:12 — prod profile
jpa:
  hibernate.ddl-auto: validate   # explicit repeat for safety; prod never mutates schema without a reviewed changeset
```

#### 2.2 Why `validate` is the correct default for production

Liquibase (`src/main/resources/db/changelog/db.changelog-master.xml`) is the sole schema author:

- Changesets are ordered (`01_create_tables.sql` → `02_add_version_columns.sql:15` → `03_add_audit_columns.sql` → `04_add_order_number.sql` → ... `09_create_mcp_tool_audit.sql`) and versioned, reviewable, rollbackable (`--rollback` in each file).
- `validate` guarantees that *the entities the code runs* match *the schema that Liquibase built*. Drift is caught at `ApplicationContext` startup, before the first test or first HTTP request — not at 2am when a customer hits a code path that touches the drifted column.
- If a developer changes `Order.java:64` `@Column(length=40)` to `length=30` without a new `ALTER TABLE` changeset, `validate` fails at next `./mvnw test`; `update` would silently `ALTER` the prod DB, truncating `ORD-XXXXXXXXXX` values without review.
- `validate` costs one metadata scan at startup (milliseconds) — no runtime cost. `update` costs an `ALTER` check per column on every startup, with risk of accidental mutation.

Rule (from `README.md:272`): *"`ddl-auto: validate` now has teeth — Hibernate fails startup if any entity annotation diverges from Liquibase."*

#### 2.3 What `validate` actually checks — and what it doesn't

`SchemaValidator` compares the metamodel to `DatabaseMetaData`/`information_schema`:

| Checked | Example | Fails when |
|---|---|---|
| Table exists | `orders` `Order.java:42` `@Table(name="orders")` | Changeset `01_create_tables.sql` missing or not yet applied |
| Column exists & type | `order_number` `Order.java:64` vs `04_add_order_number.sql` | Entity added field without changeset, or wrong `columnDefinition` |
| Column length / precision / scale | `@Column(length=40)` vs `VARCHAR(40)`; `price NUMERIC(19,2)` `Product.java:43` | Entity length shorter/longer than DB, or wrong `precision` |
| Nullability | `@Column(nullable=false)` `Order.java:50` vs `NOT NULL` in `01_create_tables.sql` | Entity says nullable, DB says not null (or vice versa for validation strictness) |
| FK existence | `@JoinColumn(name="customer_id")` `Order.java:47` vs FK constraint `01_create_tables.sql` | Join column name typo (`customerId` vs `customer_id`) |
| Index expectation | (Hibernate can validate some indexes via `@Index` but does not require them) | Usually warning, not failure — see note below |
| **Not checked** | Enums as strings, check constraints, row counts | `OrderStatus` `Order.java:53` `EnumType.STRING` matches `VARCHAR` length but valid values not validated; `CHECK (status IN (...))` in `01_create_tables.sql` not compared to enum literals |
| **Not checked** | `@PrePersist` business logic | `Order.java:198` `generateOrderNumber` logic is never validated — only the column mapping |

Thus `validate` catches structural drift but not semantic drift (enum literal `CANCELLED` removed from DB `CHECK` but still in code → runtime `ConstraintViolationException` on insert). Keep the DB `CHECK`/`ENUM` in sync with `OrderStatus.java` via a changeset per status change.

#### 2.4 `update` — what it does, why it's dangerous

When `application-dev.yml:12` is active:

```
validate would:  compare → abort on mismatch
update  does:  compare → generate ALTER TABLE to fix mismatch → execute via JDBC → proceed
  e.g., Order.java:64 length=40 but DB is VARCHAR(30) → ALTER TABLE orders ALTER COLUMN order_number TYPE VARCHAR(40)
```

`update` is convenient: adding `Order.notes @Column(length=500)` and restarting validates-and-fixes without writing `ALTER TABLE ... ADD COLUMN notes` changeset. But on a production DB:

- It can widen/narrow columns, add columns, add foreign keys — but it *never drops* columns (orphaned columns accumulate).
- It can truncate data when narrowing a column.
- It doesn't produce a reviewable changeset — DBA / code review sees nothing, `git log` has no record of the schema change.
- It races with concurrent deployments (two pods both try `ALTER`).
- It cannot express data migrations (backfill `version DEFAULT 0` `02_add_version_columns.sql:15` or `NOT NULL` addition with existing rows) — those require Liquibase's `DEFAULT`/`UPDATE` choreography.

Hence the repo rule: `update` only in `application-dev.yml:12` for throwaway local docker-compose Postgres; every permanent change ships as a Liquibase changeset (see `db.changelog-master.xml:30` `PR #13` comment).

#### 2.5 `create` / `create-drop` — when they appear

- `create`: `DROP TABLE IF EXISTS orders; CREATE TABLE orders (...)` — destroys data, rebuilds from entities. Historically used for `@DataJpaTest` with H2; here integration tests use Testcontainers + Liquibase (`DatabaseSchemaIntegrationTest.java:56`) so not the default.
- `create-drop`: same as `create` but drops again when `SessionFactory` closes — even more ephemeral, for unit-test-only `SessionFactory`. Also not the default here because Testcontainers Postgres persists Liquibase changes across test classes for realism.
- Relationship to `validate`: neither validates against Liquibase — they *replace* the schema. Useful when you don't have migrations; harmful when migrations contain non-entity DDL (indexes, `product_categories` join, `audit_log` non-entity table `07_create_audit_log.sql`, `outbox` `08_create_outbox.sql`) that entities alone don't capture.

#### 2.6 Liquibase as source of truth — how it fits `validate`

Ordering guarantee for tests and app boot (Testcontainers path):

```
Docker: PostgreSQL 16 started (Testcontainers)
    ↓
Liquibase: apply db.changelog-master.xml (01→09) → schema built with all columns, FKs, indexes, version DEFAULT 0
    ↓
Hibernate: ddl-auto=validate (application.yml:48) → read information_schema → compare every @Entity/@Column → pass/fail
    ↓
Tests/Actuator health → DatabaseSchemaIntegrationTest.java:56 proves tables/columns exist via \d / information_schema
```

For production (docker-compose → ECS/K8s):

```
App pod starts with spring.liquibase.enabled=true (application.yml:38)
  → Liquibase checks DATABASECHANGELOG table, applies any unapplied changesets (e.g., 04_add_order_number.sql)
  → then Hibernate validate
  → then health probes liveness/readiness (application.yml:149-152) → K8s marks pod ready
```

Rollback (`--rollback` in each changeset, e.g., `02_add_version_columns.sql:16` `DROP COLUMN version`) is exercised via `liquibase:rollback` if a changeset causes validate to fail post-deploy — validate's error message names the mismatched column, so the fix changeset is obvious.

Profile matrix in this repo:

| Profile | Liquibase | ddl-auto | DB | Why |
|---|---|---|---|---|
| `test` (default, `DatabaseSchemaIntegrationTest`) | Testcontainers runs Liquibase | `validate` (`application.yml:48`) | Temporary Postgres container | Real schema + real validation on every test run |
| `dev` (`application-dev.yml`) | Enabled, but `update` may get there first | `update` (`application-dev.yml:12`) | `docker-compose.yml` Postgres `orderdb` | Fast iteration: edit entity, restart, DB updated without changeset |
| `prod` (`application-prod.yml`) | Enabled | `validate` (`application-prod.yml:12`) | Managed Postgres (RDS/Aurora) | No silent ALTER, reviewable changesets only |

#### 2.7 Drift — how it happens and how `validate` surfaces it

Common drift scenarios caught by `validate`:

- **Entity added column without changeset:** `Order.java` add `private String notes;` → validate: `Missing column notes in table orders` (`SchemaManagementException`).
- **Type mismatch:** `Product.java:46` `stockQuantity int` but column `stock_quantity BIGINT` after a botched changeset → `Wrong column type in orders ...`.
- **FK join column typo:** `@JoinColumn(name="custemer_id")` → `Column custemer_id not found` (the FK `customer_id:182` from PR #2 doc actually exists as `customer_id`).
- **Length narrowing forgotten:** entity `length=30` but DB `VARCHAR(40)` — `validate` may pass (Hibernate lenient when DB is wider) but `update` would not narrow; semantic drift remains — review via `information_schema`.

Validation message example on failure:

```
org.hibernate.tool.schema.management.exception.SchemaManagementException:
  Schema-validation: wrong column type encountered in column [order_number] in table [orders];
  found [varchar(30) (Types#VARCHAR)], but expecting [varchar(40) (Types#VARCHAR)]
```

This names `table`, `column`, found vs expected types — immediately points to the missing `ALTER TABLE orders ALTER COLUMN order_number TYPE VARCHAR(40)` changeset.

#### 2.8 `@Column`, `@JoinColumn`, `@GeneratedValue(IDENTITY)` and `validate`

Validation mapping:

- `Order.java:42` `@Table(name="orders")` → validates against `orders` table.
- `Order.java:46` `@ManyToOne` + `@JoinColumn(name="customer_id", nullable=false)` → validates FK column `customer_id` + index `idx_orders_customer_id:182` (see `01_create_tables.sql:182`).
- `BaseEntity.java:50` `@GeneratedValue(IDENTITY)` → validates `BIGINT GENERATED BY DEFAULT AS IDENTITY` vs Postgres `SERIAL`/`BIGSERIAL`; Liquibase uses `GENERATED BY DEFAULT AS IDENTITY` (PR #2), not a sequence — `validate` checks identity vs sequence choice.
- `Order.java:57` `@Column(name="total_amount", precision=19, scale=2)` → validates `NUMERIC(19,2)` (money is NEVER float — see `Order.java:38` note).
- `Order.java:64` `@Column(name="order_number", length=40)` → `VARCHAR(40)`.
- `BaseEntity.java:53` `@Version @Column(name="version")` → `version BIGINT NOT NULL DEFAULT 0` from `02_add_version_columns.sql:15`.

#### 2.9 Testing strategy

`DatabaseSchemaIntegrationTest.java:56` is the dedicated "schema is what we declared" suite. It runs under default `validate` (not `update`), so the test itself proves `validate`: if Liquibase and entities diverged, the `ApplicationContext` would never load. Additional checks:

- Smoke: `SELECT column_name FROM information_schema.columns WHERE table_name='orders' ORDER BY ordinal_position` vs entity field list.
- Liquibase status: `./mvnw liquibase:status` should show no unapplied changesets when entities match HEAD.
- Prod path smoke: `./mvnw -Pprod spring-boot:run` (or `-Dspring.profiles.active=prod`) with `ddl-auto: validate` `application-prod.yml:12` against a DB migrated to HEAD should start with no `SchemaManagementException`.

#### 2.10 Interview-ready mental model

> "`spring.jpa.hibernate.ddl-auto` (`application.yml:48` `validate`) is the schema contract: `none` does nothing, `validate` compares entities to `information_schema` and `SchemaManagementException` fails startup on any column/table/type/nullable mismatch, `update` auto-`ALTER`s (dev-only `application-dev.yml:12`), `create`/`create-drop` drop and recreate (not used here because Testcontainers+Liquibase are the realism). `validate` is the default (tests, CI, prod `application-prod.yml:12`) because Liquibase (`db.changelog-master.xml` `01_create_tables.sql` → `02_add_version_columns.sql` → `03_add_audit_columns.sql` → `04_add_order_number.sql` ...) is the sole author — entities must match migrations, not mutate them. Drift (wrong `order_number` length `Order.java:64`, missing FK `Order.java:47`) fails at context load, not at 2am. Production never uses `update` — it silently `ALTER`s, can't drop columns, races deploys, lacks review. Tests use Testcontainers → Liquibase → `validate` → `DatabaseSchemaIntegrationTest.java:56` proving tables via `\\d` / `information_schema`. Cost is one metadata scan at startup."

---

## 3. Solution — ASCII

```
ddl-auto lifecycle (this repo):

 App starts with ApplicationContext
   │
   ├─ Liquibase (application.yml:38 enabled=true)
   │   reads db.changelog-master.xml  01→09  (01_create_tables, 02_version:15, 03_audit, 04_order_number, ...)
   │   applies any unapplied changesets to DATABASECHANGELOG → real schema built
   │
   └─ Hibernate EntityManagerFactory (ddl-auto from profile)
        │
        ├── validate (application.yml:48 default, prod: application-prod.yml:12)
        │     compare entity metamodel (@Entity Order.java:43, BaseEntity.java:43, @Column length 40 Order.java:64,
        │            version BaseEntity.java:53, audit BaseEntity.java:59, FK Order.java:47 ...) to information_schema
        │     ├─ match → start, probes ready (application.yml:149-152)
        │     └─ mismatch → SchemaManagementException("wrong column type in orders.order_number ...") → fail fast
        │
        ├── update (application-dev.yml:12 dev only)
        │     compare → ALTER TABLE orders ALTER COLUMN ... — mutates prod if misconfigured — DO NOT use in prod
        │
        └── create / create-drop
              DROP TABLE orders; CREATE TABLE orders (...) — destroys data, replaces migrations — not used here
 Drifting scenario caught by validate:
   Developer edits Order.java:64 length=40 → 30 without changeset
     validate: found VARCHAR(40), expecting VARCHAR(30) → fail startup with message naming orders.order_number
       → fix: new changeset ALTER TABLE orders ALTER COLUMN order_number TYPE VARCHAR(30) and revert entity, or keep 40
     update:  would ALTER TYPE VARCHAR(30) silently on prod — truncates data — NOT reviewed
 Profile matrix:
  test  (default): Liquibase (Testcontainers) → validate  (application.yml:48)  → DatabaseSchemaIntegrationTest.java:56
  dev:             Liquibase + update (application-dev.yml:12, local docker-compose, throwaway DB)
  prod:            Liquibase → validate (application-prod.yml:12, never ALTER without changeset)
```
---
## 4. How it is implemented — file map
| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/resources/application.yml` | `37-39` | `liquibase.enabled=true` + `change-log` | Source of truth for schema |
| `application.yml` | `41-48` | `jpa: ddl-auto: validate` + comments | "Liquibase owns the schema; Hibernate only confirms match" |
| `src/main/resources/application-dev.yml` | `12` | `ddl-auto: update` | Dev-only, flagged for throwaway DB |
| `src/main/resources/application-prod.yml` | `12` | `ddl-auto: validate` | Prod lock — never ALTER without changeset |
| `src/main/resources/db/changelog/db.changelog-master.xml` | `30` | Includes all changesets | `01→09` ordered, comment `PR #13 order_number` |
| `db/changelog/v1.0/01_create_tables.sql` | — | Base tables + FKs + indexes `182-188` | 8 tables, FK `orders.customer_id`, index `idx_orders_customer_id:182` |
| `db/changelog/v1.0/02_add_version_columns.sql` | `15-44` | Per-table `version BIGINT NOT NULL DEFAULT 0` | 8× `ALTER ADD COLUMN version` (matches `BaseEntity.java:53`) |
| `db/changelog/v1.0/03_add_audit_columns.sql` | — | Per-table audit columns | `created_at/updated_at/created_by/updated_by` (matches `BaseEntity.java:59-73`) |
| `db/changelog/v1.0/04_add_order_number.sql` | — | `order_number VARCHAR(40) UNIQUE` | Matches `Order.java:64` + callback `Order.java:198` (PR #13) |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43-53` | `@MappedSuperclass` + `@Version` | Validated column `version` |
| `BaseEntity.java` | `59-73` | Audit fields | Validated `created_at/updated_at/created_by/updated_by` |
| `BaseEntity.java` | `107` | `@Transient postLoadFired` | Excluded from validation (no column) |
| `src/main/java/com/company/orderapi/domain/Order.java` | `42/64/46` | `@Table`, `orderNumber length=40`, FK | Key validated columns |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `37` | `@Table(name="customers")` | Validated table |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Integration proof | Runs under `validate` — itself proves agreement |
```yaml
# application.yml:48 — the schema contract (default for tests/CI)
jpa:
  open-in-view: false
  hibernate.ddl-auto: validate  # Liquibase owns schema; Hibernate only confirms match
# application-dev.yml:12 — dev-only exception (throwaway docker-compose DB)
jpa:
  hibernate.ddl-auto: update  # fast iteration, never use in prod
# application-prod.yml:12 — explicit prod lock
jpa:
  hibernate.ddl-auto: validate
# Liquibase — sole author (db.changelog-master.xml)
spring:
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.xml
```
```java
// BaseEntity.java:43-53 — validated across every table
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version @Column(name = "version", nullable = false) private long version;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    // ... plus updatedAt/By, postLoadFired @Transient (BaseEntity.java:107 excluded)
}
// Order.java:42,64,46 — examples of validated mappings
@Entity @Table(name = "orders")
public class Order extends BaseEntity {
    @Column(name = "order_number", length = 40) private String orderNumber; // 04_add_order_number.sql
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "customer_id", nullable = false) private Customer customer; // 01_create_tables.sql
}
```
---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run schema-validation tests (uses Testcontainers + Liquibase + validate)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test
# If entities vs schema diverge → SchemaManagementException at context load, build fails immediately.

# Verify ddl-auto settings per profile
grep -rn "ddl-auto" src/main/resources/application*.yml
# Expect: application.yml:48 validate, application-dev.yml:12 update, application-prod.yml:12 validate

# Check Liquibase changelog
grep -n "include file" src/main/resources/db/changelog/db.changelog-master.xml | head -20

# Liquibase status — pending changesets should be zero on HEAD
./mvnw liquibase:status -Dliquibase.url="jdbc:postgresql://localhost:5432/orderdb" \
  -Dliquibase.username=order -Dliquibase.password=order 2>&1 | grep -E "changesets|PENDING"

# Show live schema — entity columns should match \d output
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders" | grep -E "order_number|version|created_at|total_amount|customer_id"
# Expect: order_number VARCHAR(40), version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ, customer_id BIGINT NOT NULL FK

psql "postgresql://order:order@localhost:5432/orderdb" -c "\d products" | grep -E "version|stock_quantity"

# Verify validate catches drift — temporary experiment: change Order.length to 99, restart
sed -n '64p' src/main/java/com/company/orderapi/domain/Order.java  # should be length=40
# Edit to length=99 then:
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -A3 "SchemaManagementException\|wrong column type"
# Expect failure naming orders.order_number. Revert immediately.

# Health probes require validate passed (probes not marked ready until EMF built)
curl -s http://localhost:8080/actuator/health/liveness | jq .status
curl -s http://localhost:8080/actuator/health/readiness | jq .status

# Prod path smoke (requires prod DB reachable or Testcontainers with prod profile)
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod 2>&1 | grep -E "validate|SchemaManagement|started"

# All tables exist
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name;
"
```

```java
// Intentionally drift to prove validate catches it (in a throwaway branch)
// Change Order.java:64 length=40 → length=99 without a changeset → restart with validate → SchemaManagementException
// Fix = add: db/changelog/v1.0/10_alter_order_number_length.sql → ALTER TABLE orders ALTER COLUMN order_number TYPE VARCHAR(99)
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `validate` as default (`application.yml:48`) | Fail-fast on mismatch | `update` (auto-fix) or `none` (ignore) | Liquibase is source of truth; mismatches fail before any request, not at 2am | One metadata scan per startup (ms); does not hide drift |
| `update` only in `application-dev.yml:12` | Dev throwaway only | `update` everywhere | Fast `edit entity → restart → ALTER applied` without writing changeset; local docker-compose DB is disposable | Risk if developer runs prod with dev profile — mitigated by `application-prod.yml:12` explicit `validate` |
| `@Transient` excluded from `validate` | `BaseEntity.java:107` `postLoadFired` | `@Column` | In-memory flag has no column — correct to exclude; validate would otherwise expect `post_load_fired` column | None — transient not queryable |
| No `@Index` annotations for FK indexes | Liquibase `01_create_tables.sql:182-188` `CREATE INDEX idx_*` | `@Table(indexes=@Index(...))` Hibernate-managed | Liquibase indexes are explicit, named (`idx_orders_customer_id:182`), reviewable; `validate` tolerates extra indexes, doesn't require them | Small Hibernate warning about missing index is acceptable — index still used |
| Testcontainers + Liquibase for tests | `DatabaseSchemaIntegrationTest.java:56` against real Postgres | H2 + `create-drop` | Real dialect (Postgres `IDENTITY`, `TIMESTAMPTZ`, enum lengths, `VARCHAR` semantics) — validate against real `information_schema`, not H2 emulation | Testcontainers cold start ~seconds; pay once per suite (reuse) |
| One Liquibase changeset per concern | `02_version` PR #8, `03_audit` PR #10, `04_order_number` PR #13 | One giant `init.sql` | Each PR's schema delta is reviewable (`git log -- db/changelog/...`), rollbackable (`--rollback` per file), and maps to one doc (`08-*.md`, `10-*.md`, `13-*.md`) | More files, but `db.changelog-master.xml:30` includes them in order |

---

## 7. How to verify

```bash
# Core validation proof — test suite starts under validate against real Postgres
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Failure would be: SchemaManagementException at ApplicationContext load before any assertion.

# Liquibase applied all changesets including PR #13-#14 deltas
./mvnw liquibase:status 2>&1 | grep -E "is up to date|pending"
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT id, author, filename FROM DATABASECHANGELOG ORDER BY orderexecuted;"

# Schema matches entities — spot check via information_schema
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
  FROM information_schema.columns
  WHERE table_name IN ('orders','products','customers')
  ORDER BY table_name, ordinal_position;
"

# ddl-auto profile split verified
grep -A2 "ddl-auto" src/main/resources/application.yml         # validate
grep -A2 "ddl-auto" src/main/resources/application-dev.yml     # update
grep -A2 "ddl-auto" src/main/resources/application-prod.yml    # validate

# Negative test — prove mismatch would be caught (branch experiment)
# Temporarily change Order.java:64 length=40 → 99 and run:
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -A2 "SchemaManagementException"

# Health / observability — EMF built means validate passed
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep jvm_

# Config sources (dev vs prod)
## 8. How this helps you on the job — build / operate / interview

- **Build:** Every schema change = Liquibase changeset in `db/changelog/` + `db.changelog-master.xml`; keep `application.yml:48` `validate` default; only `application-dev.yml:12` `update` on throwaway docker-compose; `@Transient:107` for non-columns.
- **Operate:** `validate` fails with `SchemaManagementException("wrong column type in orders.order_number")` naming drift; alert on `DATABASECHANGELOGLOCK`; never deploy with `update` in prod.
- **Interview:** "PR #14: `application.yml:48` `validate` (check+fail), `update:dev:12` (auto-ALTER dev-only), `create`/`create-drop` (rebuild), Liquibase `01→09` sole author, `validate` checks table/column/type/length/nullable/FK, catches `Order.java:64` length drift at startup."

---

## 9. Interview lens — Q&A

**Q1: What does each `ddl-auto` do?** A: `none` no-op; `validate:48` compare+fail; `update:dev:12` auto-ALTER dev-only; `create` drop+create (§2.1).
**Q2: What does `validate` miss?** A: Checks type/length/FK (`Order.java:42/64/47`) but misses enum `CHECK` literals and business logic `Order.java:198` (§2.3).
**Q3: How to fix drift `validate` caught?** A: Message names `table.column` (§2.7) → new changeset `ALTER TABLE ...`, not entity edit to match wrong column.

---

## 10. Honest limits & next step → PR #15

`validate` is structural not semantic (enum literals, replica lag); `update` in dev weakens contract if forgotten; `DATABASECHANGELOG` history grows. Next: PR #15 SQL logging `application.yml:52/54/175` `format_sql/use_sql_comments/statistics`.

See [`15-sql-logging-and-debugging.md`](./15-sql-logging-and-debugging.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| New column / FK / index | Liquibase changeset + `validate` | `db.changelog-master.xml` → `application.yml:48` | Reviewable, rollbackable, fails fast on drift |
| Fast add column locally before changeset | `application-dev.yml:12` `update` on throwaway DB | `application-dev.yml:12` | No `ALTER` to write yet; don't merge without changeset |
| Tests / CI | Testcontainers + Liquibase → `validate` | `DatabaseSchemaIntegrationTest.java:56` | Real Postgres, real `information_schema` |
| In-memory field not a column | `@Transient` | `BaseEntity.java:107` | Excluded from `validate` |
| Prod deploy | `validate` (`application-prod.yml:12`), Liquibase runs first | `application-prod.yml:12` + `application.yml:38` | Never `update` in prod |
