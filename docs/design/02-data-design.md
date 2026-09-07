# 02 — Data Design

## 2.1 Conceptual domain model
- **Customer** 1—N **Address** (child owns `customer_id`).
- **Customer** 1—N **Order** (history; customer deletion restricted).
- **Order** 1—N **OrderItem** (product snapshot lines; `ALL, orphanRemoval`).
- **OrderItem** N—1 **Product** (RESTRICT: history must survive product changes).
- **Product** N—N **Category** via `product_categories`.
- **Order** 1—1 **Payment** (payment owns unique `order_id`).
- Supporting tables: `event_store`, `idempotency_keys`, `audit_log`, `outbox`.

## 2.2 Physical schema & ownership
| Table | Purpose | Notes |
|---|---|---|
| customers, addresses, orders, order_items, products, categories, product_categories, payments | domain | FK delete rules: child CASCADE, history RESTRICT, optional SET NULL |
| event_store | append-only event log | unique (aggregate_id, version) |
| idempotency_keys | retry replay | unique key; TTL cleanup planned (§9) |
| audit_log | compliance trail | no FK to customers so it survives erasure; no raw PII |
| outbox | reliable publish | status PENDING/PUBLISHED, attempts counter |

Schema is owned by **Liquibase** (`db/changelog/v1.0/01..08`). Hibernate runs
`ddl-auto: validate` so entity/DB drift fails fast in tests/CI. Golden rule:
never edit a shipped changeset (checksum protection) - add a new one.

## 2.3 Integrity & locking design
| Concern | Mechanism |
|---|---|
| Money | `NUMERIC(19,2)` ↔ `BigDecimal` (never float) |
| Stock decrement race | optimistic `@Version` → `OptimisticLockingFailureException` → bounded retry; optional `SELECT … FOR UPDATE` path for hot counters |
| Order placement atomicity | one `@Transactional` use-case; any failure rolls back order, stock, payment |
| Deletion of history | DB RESTRICT + `@PreRemove` business guard on customer |
| Deadlocks | Postgres detection mapped to retryable exception |

### Indexing
Every FK column is indexed (leading-column rule); `outbox(status,id)` and
`audit_log(customer_id)` carry explicit indexes. A schema test asserts table
count, FK rules and index coverage so these invariants cannot regress.

## 2.4 Retention & lifecycle
- Order history retained (no hard delete path through normal APIs); GDPR
  erasure anonymizes when retention applies (see §04).
- Audit log rows survive customer erasure (no FK); detail payloads are
  non-PII.
- Idempotency keys accumulate (cleanup job in forward plan). Outbox rows with
  permanent failures accumulate (alerting/dead-row handling in forward plan).

## 2.5 Data access patterns
- Read-heavy catalogue → **DTO cache** (Redis, TTL 10 min) with write-through
  eviction; entity L2 (Ehcache, JVM-wide) for selective entities in dev.
- Read models/projections for lists/dashboards; `default_batch_fetch_size=20`
  controls incidental lazy loads.
- Search/filter via Specifications; paging via Spring Data `Pageable`.

## 2.6 Schema evolution process
New column/table/index ⇒ new numbered changelog file + update schema tests +
`ddl-auto: validate` green. Renaming enums used in CHECK constraints is a
joint code+migration change.
