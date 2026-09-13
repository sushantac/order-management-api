# 10. Auditing (PR #10)

> PR #10 — Auditing with `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`, `@MappedSuperclass`, `AuditorAware`, `AuditingEntityListener`. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Spring Data JPA. See `README.md:1772` roadmap `| 10 | Auditing |`.

---

## 1. Purpose — what shipped

PR #10 delivers **Auditing** as a first-class, tested, documented building block. Every entity now records *when* and *who* on every row without any service code: four columns on every table (`created_at`, `updated_at`, `created_by`, `updated_by`), four fields in `BaseEntity.java:59-73`, `AuditingEntityListener` registered via `@EntityListeners` (`BaseEntity.java:44`), and `JpaAuditingConfig.java:18` `@EnableJpaAuditing` + `AuditorAware<String>` (`JpaAuditingConfig.java:29`). A dedicated compliance trail (`AuditLog.java:25` + `07_create_audit_log.sql`) covers Gdpr-relevant actions separately. Verified by `SELECT created_at, created_by FROM orders` and `DatabaseSchemaIntegrationTest.java:56`.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** No `created_at`, no `updated_at`, no `created_by`. Debugging "who changed order 42 at 2am" requires digging through Kafka/outbox or DB backups. Compliance (audit trail, GDPR Art. 30 records of processing) is impossible. Services manually set `order.setCreatedAt(LocalDateTime.now())` inconsistently — some forget, some use `Instant`, some pass `null`.

**After:** `BaseEntity.java:59-73` declares four fields once; `AuditingEntityListener` populates them automatically on insert/update via JPA lifecycle callbacks. `JpaAuditingConfig.java:29` supplies the current user as `Optional.of("system")` until PR #26 (security) upgrades it to read `SecurityContext`. Every `INSERT` gets `created_at/created_by` + `updated_at/updated_by`; every `UPDATE` touches `updated_*` only (updatable=false on created).

### Theory — auditing from first principles (100+ lines)

#### 2.1 What "auditing" means in JPA

Two distinct meanings:

- **Entity auditing** (this PR): who/when touched *each row*, stored *on the row itself* (`BaseEntity.java:59`). Lightweight, in-row, OLTP-friendly — `SELECT created_at FROM orders`.
- **Envers / audit log** (out of scope for this PR): full *history table* per entity (`orders_aud`) recording every old value on every change. Heavy but gives time-travel queries. The repo does NOT use Envers; the dedicated `AuditLog` (`src/main/java/com/company/orderapi/domain/audit/AuditLog.java:25`) is the separate compliance trail for Gdpr actions, not a full Envers history.

This PR uses entity auditing — the minimum needed for traceability and incident response.

#### 2.2 The four annotations and where they fire

| Annotation | Line | Set when | Writable? | Listener |
|---|---|---|---|---|
| `@CreatedDate` | `BaseEntity.java:59` | `INSERT` only | `updatable=false` so never overwritten | `AuditingEntityListener` `@PrePersist` |
| `@LastModifiedDate` | `BaseEntity.java:63` | `INSERT` and every `UPDATE` | Always overwritten | `AuditingEntityListener` `@PrePersist` + `@PreUpdate` |
| `@CreatedBy` | `BaseEntity.java:67` | `INSERT` only | `updatable=false` | `AuditingEntityListener` `@PrePersist` |
| `@LastModifiedBy` | `BaseEntity.java:71` | `INSERT` and every `UPDATE` | Always overwritten | `AuditingEntityListener` `@PrePersist` + `@PreUpdate` |

DB columns mirror the lifecycle: `03_add_audit_columns.sql` adds `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`, `created_by VARCHAR(64) NOT NULL`, `updated_by VARCHAR(64) NOT NULL` with `DEFAULT` handling for pre-existing rows (similar to `02_add_version_columns.sql:15` `DEFAULT 0` for version).

```
Insert flow (AuditHandler → EntityListener):
  auditorAware.getCurrentAuditor() → Optional<String> "system"
  createdDate = now(), lastModifiedDate = now()
  createdBy   = "system", lastModifiedBy = "system"
  JPA: INSERT INTO orders (..., created_at, updated_at, created_by, updated_by, ...) VALUES (..., ?, ?, ?, ?, ...)

Update flow:
  lastModifiedDate = now()    // overwritten
  lastModifiedBy   = current auditor (may be different from createdBy)
  created*         unchanged (updatable=false skips them from SET)
  UPDATE orders SET total_amount=?, updated_at=?, updated_by=?, version=? WHERE id=? AND version=?
```

#### 2.3 How `AuditingEntityListener` works internally

```java
// BaseEntity.java:44
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity { ... }
```

Spring Data's `AuditingEntityListener` is a JPA entity listener (invoked by Hibernate, not by Spring directly) that delegates to `AuditingHandler`:

1. Hibernate calls `AuditingEntityListener.touchForCreate(entity)` from `PrePersistEventListener`.
2. Listener looks up `AuditingHandler` (a Spring bean) via the `BeanFactory` stored in `AuditingHandlerBeanPostProcessor`.
3. Handler reads `AuditorAware<String> auditorAware` (`JpaAuditingConfig.java:29`) and the `DateTimeProvider` (defaults to `LocalDateTime.now()`).
4. Using reflection + `AnnotationAuditingConfiguration`, it finds fields annotated with `@CreatedDate/@CreatedBy/...` on the entity class *and its `@MappedSuperclass` parents* (hence `BaseEntity` works) and sets them.

Critical: the listener only populates fields annotated with Spring Data annotations (`org.springframework.data.annotation.*`), *not* Jakarta annotations. The imports matter:

```java
// BaseEntity.java:14-17 — correct imports
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
```

Using `jakarta.persistence.*` equivalents would not be recognized by `AuditingHandler`.

#### 2.4 `@MappedSuperclass` — why not `@Entity` or `@Embeddable`

- `@MappedSuperclass` (`BaseEntity.java:43`) means "copy these column mappings into each subclass's table" — no `base_entity` table exists. Each `orders`, `products`, etc. table has its own `created_at` column.
- `@Entity` would create a real table + require `JOINED`/`SINGLE_TABLE` inheritance — wasteful and couples all tables.
- `@Embeddable` would require `@Embedded` per entity + a shared column prefix.
- `@MappedSuperclass` plus `AuditingEntityListener` means one declaration covers all 8+ tables, matches `02_add_version_columns.sql`/`03_add_audit_columns.sql` pattern where each table individually gets the columns.

The `version` field (`BaseEntity.java:53`) and auditing fields live together → both copied into every subclass table.

#### 2.5 `AuditorAware` — the "who" injection point

```java
// JpaAuditingConfig.java:18,29
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system"); // PR #10 placeholder
    }
}
```

- `@EnableJpaAuditing` (`JpaAuditingConfig.java:18`) registers `AuditingHandler` + `AuditingHandlerBeanPostProcessor` + enables the `AuditingEntityListener` bridge. Without it, `@CreatedDate` fields remain `null` → `NOT NULL` constraint fails on insert.
- `AuditorAware<String>` is a functional interface `Optional<String> getCurrentAuditor()`. `Optional.empty()` → audit columns become `null` (and if `nullable=false` would fail — so always return a value; `"system"` is the PR #10 safe fallback).
- Until PR #26 (security), no `SecurityContext` exists — `Optional.of("system")` guarantees every row has a `created_by` even in integration tests that run without auth. PR #26 upgrades it to:
  ```java
  return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                       .map(Authentication::getName).or(() -> Optional.of("system"));
  ```
  This reads the OAuth2/JWT principal or API-key subject.

#### 2.6 `AuditingEntityListener` vs raw `@PrePersist`/`@PreUpdate`

| Aspect | `AuditingEntityListener` + annotations | Raw `@PrePersist`/`@PreUpdate` (e.g., `Order.java:198` `generateOrderNumber`) |
|---|---|---|
| `who` | Reads `AuditorAware` bean | No access to Spring beans (listener is not a Spring bean) — would need static holder |
| `when` | Uses Spring `DateTimeProvider` (testable, mockable) | `LocalDateTime.now()` hardcoded |
| Coverage | One declaration on `BaseEntity.java:44` → every entity | Must annotate each entity individually |
| Testability | Auditing can be disabled per test via `auditingEnabled=false` profile | Always fires unless you skip the callback |
| Limitation | Only Spring Data annotations | Can run arbitrary business rules (see `OrderBusinessListener.java:46` `@PreUpdate` guard) |

They coexist: `BaseEntity.java:44` uses `AuditingEntityListener` for auditing; `Order.java:198` `@PrePersist generateOrderNumber()` and `Payment.java:114` `@PreUpdate stampProcessedDate()` handle business fields. JPA allows multiple `@EntityListeners` on one entity.

#### 2.7 Transactions and auditing timing

Auditing callbacks run *at flush time*, inside the transaction. Sequence for `orders.save(order)` inside `OrderService.java:108` `@Transactional`:

1. Business logic sets `order.totalAmount = ...`.
2. `flush()` triggered by commit (or `saveAndFlush`).
3. Hibernate fires `PreInsertEvent` → `AuditingEntityListener` → fills `createdAt/updatedAt/createdBy/updatedBy` on the entity instance + includes them in the `INSERT` SQL.
4. `INSERT` executes with audit columns as bind params.
5. `PostInsertEvent` fires (not used for auditing).

On `UPDATE`, similar but only `updatedAt/updatedBy` are dirty-checked and included in `SET`. `createdAt/createdBy` excluded because `updatable=false`.

#### 2.8 The compliance `AuditLog` — auditing vs. event trail

`AuditLog.java:25` (`audit_log` table, `07_create_audit_log.sql`) is a *separate* concern:

- No foreign key to `customers` — rows survive `GdprService` erasure that deletes the customer. `customerId BIGINT NOT NULL` is denormalized, not FK-constrained.
- Append-mostly — never updated, only inserted — `AuditLog.of(customerId, action, actor, detail)` (`AuditLog.java:61`) is the factory.
- `occurredAt` stamped by `AuditLog.java:50` `@PrePersist stampTime()` (a plain JPA callback, not the `AuditingEntityListener`), because `AuditLog` does NOT extend `BaseEntity`.
- `detail` is sanitized JSON that never contains raw PII — GDPR applies to the audit trail itself.

Contrast with `BaseEntity` auditing which *is* on the row and is overwritten on each update — `AuditLog` is the durable, append-only compliance record; `BaseEntity` fields are the lightweight "last writer wins" OLTP trace.

#### 2.9 Null and default handling

- Fields are `nullable=false` (`BaseEntity.java:60/64/68/72` `nullable=false`) — DB enforces presence. This is correct: an unaudited row is a bug, fail fast.
- `updatable=false` on `created_at/created_by` prevents accidental overwrite on update; Hibernate excludes them from `SET`.
- If `auditorAware` returns `empty()`, `AuditingHandler` leaves `createdBy` as `null` → insert violates `NOT NULL` and fails — which is why PR #10 uses `Optional.of("system")` rather than `empty()`.

#### 2.10 Interview-ready mental model

> "`@MappedSuperclass BaseEntity.java:43` declares `createdAt/updatedAt/createdBy/updatedBy` (`BaseEntity.java:59-73`) with `@CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy`. `@EntityListeners(AuditingEntityListener.class)` (`BaseEntity.java:44`) plus `@EnableJpaAuditing` (`JpaAuditingConfig.java:18`) registers `AuditingHandler`; `AuditorAware<String>` (`JpaAuditingConfig.java:29`) supplies the current user (`Optional.of(\"system\")` until PR #26 maps it to `SecurityContext`). On `INSERT` all four are filled; on `UPDATE` only `updated*` change (`updatable=false` on `created*`). Each table gets its own columns via `03_add_audit_columns.sql` — no `base_entity` table. The separate `AuditLog.java:25` (`audit_log` table) is the append-only Gdpr compliance trail (no FK, never erased). Coexists with `@PrePersist generateOrderNumber` (`Order.java:198`) — auditing listener handles who/when, business callbacks handle order numbers."

---

## 3. Solution — ASCII

```
Without auditing:                        With auditing (PR #10):

  INSERT INTO orders (...)                INSERT INTO orders (..., created_at, updated_at, created_by, updated_by)
  VALUES (...)   -- no who/when           VALUES (..., now(), now(), 'system', 'system')  ← AuditingEntityListener fills them
                                          -- BaseEntity.java:59-73  +  JpaAuditingConfig.java:29

  UPDATE orders SET total=?               UPDATE orders SET total=?, updated_at=now(), updated_by='alice', version=v+1
  -- updated_at/updated_by missing         -- AuditingEntityListener overwrites updated* only
                                          -- created_at/created_by excluded (updatable=false)

  Who touched order 42?                   SELECT created_at, created_by, updated_at, updated_by FROM orders WHERE id=42
  → cannot tell without app logs           → 2024-03-10 14:02 | system | 2024-03-11 09:15 | alice  (answer in the row)

  Layers:
   @MappedSuperclass BaseEntity.java:43
     ├─ @Version long version              (PR #8)
     ├─ @CreatedDate LocalDateTime createdAt  (PR #10)  ─┐ copied into every subclass table
     ├─ @LastModifiedDate LocalDateTime updatedAt          │ no base_entity table
     ├─ @CreatedBy String createdBy                        │
     └─ @LastModifiedBy String updatedBy                  ─┘
   @EntityListeners(AuditingEntityListener.class)  BaseEntity.java:44
   @EnableJpaAuditing + AuditorAware  JpaAuditingConfig.java:18/29
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43-44` | `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener)` | Single inheritance point for `version` (PR #8) + auditing (PR #10) |
| `BaseEntity.java` | `59-73` | Four audit fields | `createdAt` `CreatedDate` `updatable=false`, `updatedAt` `LastModifiedDate`, `createdBy` `CreatedBy` `updatable=false`, `updatedBy` `LastModifiedBy` |
| `BaseEntity.java` | `87-101` | Read-only getters | No setters — `AuditingEntityListener` owns them, just like `version` has no setter (`BaseEntity.java:119`) |
| `src/main/java/com/company/orderapi/config/JpaAuditingConfig.java` | `18` | `@EnableJpaAuditing` | Registers `AuditingHandler` — without it, annotations do nothing |
| `JpaAuditingConfig.java` | `29` | `AuditorAware<String> auditorAware()` | `Optional.of("system")` placeholder until PR #26 reads `SecurityContext` |
| `src/main/resources/db/changelog/v1.0/03_add_audit_columns.sql` | — | 8× `ADD COLUMN` for audit | `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`, `created_by VARCHAR(64) NOT NULL`, `updated_by VARCHAR(64) NOT NULL` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `44/198` | Inherits auditing + own `@PrePersist` | `AuditingEntityListener` fills dates/users; `generateOrderNumber()` fills `order_number` — two listeners coexist |
| `src/main/java/com/company/orderapi/domain/audit/AuditLog.java` | `25` | Compliance append-only trail | Separate table (`audit_log`), no FK, `AuditLog.java:50` `@PrePersist stampTime()`, not extending `BaseEntity` |
| `src/main/java/com/company/orderapi/domain/Payment.java` | `114` | `Payment` own `@PreUpdate` | Shows raw JPA callback alongside Spring Data auditing — different purpose |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `37` | Inherits auditing | `Customer extends BaseEntity` — audit columns on `customers` table too |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `108-118` | `placeOrder` transaction | Audit columns filled at flush inside this `@Transactional` — see §2.7 timing |
| `src/main/resources/application.yml` | `48` | `ddl-auto: validate` | Catches missing `created_at` column at startup if Liquibase not applied |

```java
// BaseEntity.java:59-73 — the four audit fields (one declaration covers 8+ tables)
@CreatedDate
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;

@LastModifiedDate
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;

@CreatedBy
@Column(name = "created_by", nullable = false, updatable = false, length = 64)
private String createdBy;

@LastModifiedBy
@Column(name = "updated_by", nullable = false, length = 64)
private String updatedBy;

// JpaAuditingConfig.java:18,29 — enable + supply auditor
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system"); // PR #26 → reads SecurityContext
    }
}

// AuditLog.java:61 — compliance trail factory (append-only)
AuditLog.of(customerId, "CUSTOMER_ERASED", actor, "{\"mode\":\"gdpr\"}");
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run auditing tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest -Dspring.profiles.active=test

# Verify audit columns exist on all audited tables
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT table_name, column_name, data_type
  FROM information_schema.columns
  WHERE column_name IN ('created_at','updated_at','created_by','updated_by')
  ORDER BY table_name, column_name;
"
# Expect 4 columns × ~8 tables = ~32 rows.

# Show audit values after placing an order
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT id, order_number, created_at, updated_at, created_by, updated_by, version
  FROM orders ORDER BY created_at DESC LIMIT 3;
"
# Expect created_at ≈ now(), created_by='system' (until PR #26), updated_at = created_at on insert.

# Prove updated* changes on update, created* does not
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  UPDATE orders SET status='CANCELLED' WHERE id=1 RETURNING
    created_at, updated_at, created_by, updated_by;
"
# Expect updated_at > created_at, updated_by = current auditor; created_* unchanged.

# Show Hibernate INSERT includes audit bind params
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "created_at\|created_by"

# Compliance trail (separate from entity auditing)
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT action, actor, occurred_at FROM audit_log ORDER BY occurred_at DESC LIMIT 5;"

# Health
curl -s http://localhost:8080/actuator/health | jq .status
curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

```java
// Reading audit info in application code
Order order = orderRepository.findById(id).orElseThrow();
order.getCreatedAt(); // BaseEntity.java:87 — Instant of INSERT
order.getUpdatedAt(); // BaseEntity.java:91 — Instant of last UPDATE
order.getCreatedBy(); // BaseEntity.java:95 — who inserted
order.getUpdatedBy(); // BaseEntity.java:99 — who last updated
// No setters — auditing is owned by the listener
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `@MappedSuperclass` | `BaseEntity.java:43` | `@Entity` inheritance (`JOINED`) or `@Embeddable` | One table per entity, no join, DRY — matches version column strategy | No polymorphic queries over `BaseEntity` (not needed) |
| `AuditingEntityListener` | Spring Data listener | Raw `@PrePersist/@PreUpdate` per entity | One config, `AuditorAware` bean access, `updatable=false` support, testable `DateTimeProvider` | Requires `@EnableJpaAuditing` (`JpaAuditingConfig.java:18`) — easy to forget |
| `Optional.of("system")` | `JpaAuditingConfig.java:29` always returns value | `Optional.empty()` → `NULL` | `nullable=false` columns demand a value; "system" is explicit attribution for pre-auth code and tests | Until PR #26, real user not recorded — honest placeholder |
| `LocalDateTime` | `BaseEntity.java:60` | `Instant` or `OffsetDateTime` | Matches `TIMESTAMPTZ` mapping + existing `orderDate` (`Order.java:51` `LocalDateTime`) | Timezone handled by DB (`TIMESTAMPTZ`); app assumes system zone |
| Separate `AuditLog` | `AuditLog.java:25` compliance table | Envers or only entity auditing | `AuditLog` survives Gdpr erasure (no FK), never updated — entity auditing is overwritten on each update so history is lost without it | Second table + manual `AuditLog.of(...)` call per Gdpr action |
| `updatable=false` on `created*` | `BaseEntity.java:60/68` | Allow update | Prevents accidental `order.setCreatedAt(...)` from overwriting creation trace | None — update would be a bug |

---

## 7. How to verify

```bash
# Schema — audit columns present on every audited table
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders" | grep -E "created_at|updated_at|created_by|updated_by"
# Expect 4 lines with TIMESTAMPTZ / VARCHAR(64) NOT NULL

psql "postgresql://order:order@localhost:5432/orderdb" -c "\d customers" | grep -E "created_at|updated_at"
# Expect same 4 columns on customers

# Listener integration — INSERT populates all four, UPDATE only updated*
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -i "created_"
# Expect INSERT ... (created_at, updated_at, created_by, updated_by) VALUES (?, ?, ?, ?)
# and UPDATE ... SET updated_at=?, updated_by=?, version=? WHERE ... (no created_at)

# Assert in Java
# After placeOrder(): order.getCreatedAt()!=null && order.getCreatedBy().equals("system")
# After order.setStatus(CANCELLED) + flush: updatedAt > createdAt

# Health and metrics unchanged
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/actuator/prometheus | grep jvm_

# Liquibase status — 03_add_audit_columns applied
./mvnw liquibase:status 2>&1 | grep "03_add_audit"

# Failure mode: remove @EnableJpaAuditing → insert fails NOT NULL on created_at (proves listener is required)
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Extend `BaseEntity` for every new entity — auditing is automatic. Never call `setCreatedAt`/`setCreatedBy`; they have no setters and are owned by `AuditingEntityListener` (`BaseEntity.java:44`). When you need business timestamps (`orderNumber` `Order.java:198`, `paymentDate` `Payment.java:114`), use raw `@PrePersist/@PreUpdate` callbacks alongside the Spring Data listener — they coexist. For Gdpr-sensitive actions, also write an `AuditLog.of(...)` (`AuditLog.java:61`) — entity auditing's `updatedBy` only shows last writer, not full history.
- **Operate:** Answer "who changed row X when" with `SELECT created_at, created_by, updated_at, updated_by FROM {table} WHERE id=?` — no log aggregation needed. Alert on `created_by='system'` in production after PR #26 — means `SecurityContext` wasn't populated (anonymous access). Use `updated_at` for stale-data checks and incremental ETL watermarks (`WHERE updated_at > last_sync`). `audit_log` table (`07_create_audit_log.sql`) is the compliance source of truth — back it up separately, never truncate.
- **Interview:** "PR #10: `BaseEntity.java:59-73` `@CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy` with `AuditingEntityListener` (`BaseEntity.java:44`) and `@EnableJpaAuditing` + `AuditorAware<String>` (`JpaAuditingConfig.java:18/29` → `Optional.of(\"system\")` until PR #26 reads `SecurityContext`). `@MappedSuperclass` (`BaseEntity.java:43`) copies columns into each subclass table (`03_add_audit_columns.sql`). `INSERT` fills all four; `UPDATE` only `updated*` (`updatable=false` on `created*`). Coexists with `@PrePersist generateOrderNumber` (`Order.java:198`). Separate `AuditLog.java:25` is append-only Gdpr trail (no FK). Verified by `SELECT created_at, created_by FROM orders` and `DatabaseSchemaIntegrationTest.java:56`."

---

## 9. Interview lens — Q&A

**Q1: What does `@EnableJpaAuditing` do? What happens if you forget it?**
A: `JpaAuditingConfig.java:18` registers `AuditingHandler` + the bridge that lets `AuditingEntityListener` (`BaseEntity.java:44`) resolve `AuditorAware`. Without it, `@CreatedDate`/`@CreatedBy` fields stay `null` → insert violates `NOT NULL` on `created_at` (`03_add_audit_columns.sql`). See §2.5.

**Q2: Where does "who" come from and how does it evolve from PR #10 to PR #26?**
A: `AuditorAware<String>` (`JpaAuditingConfig.java:29`) returns `Optional.of("system")` in PR #10 so tests and unauthenticated code never produce `null`. PR #26 upgrades it to read `SecurityContext.getAuthentication().getName()` → real OAuth2 subject or API-key client. See §2.5.

**Q3: Why `@MappedSuperclass` not `@Entity` inheritance?**
A: `@MappedSuperclass` (`BaseEntity.java:43`) copies columns into each subclass table — no `base_entity` table, no joins. `@Entity` inheritance would create a shared parent table. `version` (`BaseEntity.java:53`, `02_add_version_columns.sql`) and auditing reuse the same pattern. See §2.4.

**Q4: How do `@CreatedDate` vs raw `@PrePersist` differ?**
A: `@CreatedDate` (`BaseEntity.java:59`) is filled by `AuditingEntityListener` via Spring's `AuditingHandler` (injects `AuditorAware`, testable clock, handles `updatable=false`). Raw `@PrePersist` (`Order.java:198` `generateOrderNumber`, `AuditLog.java:50` `stampTime`) is plain JPA with no bean access. They coexist — auditing handles who/when, callbacks handle business keys. See §2.6.

**Q5: Why is `AuditLog` separate from `@CreatedBy`?**
A: Entity auditing (`BaseEntity.java:59`) is overwritten (`updatedBy` = last writer only), fine for OLTP. `AuditLog.java:25` is append-only, no FK, survives erasure, never updated — needed for Gdpr compliance where full history matters. See §2.8.

**Q6: What are `nullable=false` and `updatable=false` protecting?**
A: `nullable=false` (`BaseEntity.java:60`) enforces that every row has audit data (fail fast on missing listener). `updatable=false` (`BaseEntity.java:60/68`) excludes `created*` from `UPDATE SET`, so creation trace is immutable. See §2.9.

---

## 10. Honest limits & next step → PR #11

Entity auditing records *last* writer only — it does not keep a history of all edits. For full time-travel (every old value), you'd need Envers or manual history tables. `created_at` uses `LocalDateTime` so timezone is implicit in `TIMESTAMPTZ` storage; a stricter service would use `Instant`. `AuditorAware` returns `"system"` until PR #26 — attribution is coarse during the PR #10-#25 window. The separate `AuditLog` covers compliance history, but the row-level audit itself is still last-write-wins. Next, PR #11 adds custom queries (`@Query` JPQL/native/SpEL, projections in `OrderRepository.java:26-106`) — the patterns that finally make repositories more than `findById`.

See [`11-custom-queries.md`](./11-custom-queries.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| Every new entity needs who/when | Extend `BaseEntity` | `BaseEntity.java:43` | Auditing automatic, no per-entity config |
| Business key on insert | Raw `@PrePersist` | `Order.java:198` | `AuditingEntityListener` can't generate order numbers |
| Compliance history | `AuditLog.of(...)` | `AuditLog.java:61` | Append-only, no FK, survives erasure |
| "Who last touched row X?" | `SELECT updated_by FROM {table}` | `BaseEntity.java:71` | OLTP answer in the row |
| "Full history of row X?" | Envers or `audit_log` query | `AuditLog.java:25` | Entity auditing alone overwrites |
| Disable auditing per test | `@DataJpaTest(includeFilters... exclude)` or `AuditingHandler` mock | `JpaAuditingConfig.java:29` | `AuditorAware` is a bean — replace it in test context |
