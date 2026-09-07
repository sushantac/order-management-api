# 02 — JPA, Hibernate & PostgreSQL

## Mental model
Entity = row. Object graph = FK graph. **Owning side** holds the FK / join table;
**inverse side** (`mappedBy`) is just navigation. Hibernate issues SQL on flush/commit
(dirty checking) unless you flush explicitly.

## Relationship cheat
| Mapping | Owning side | Notes |
|---|---|---|
| `@ManyToOne` (+ `@JoinColumn`) | the child | default EAGER - set LAZY |
| `@OneToMany(mappedBy=...)` | the child's `@ManyToOne` | inverse, LAZY default |
| `@OneToOne` | holds FK (e.g. `Payment.order_id` unique) | inverse nullable can't be lazy-proxied |
| `@ManyToMany` | either side + `@JoinTable` | inverse uses `mappedBy` |

## Cascade & orphan removal (VERIFY from experience)
- Cascade is a *JPA operation fan-out*, independent of the DB FK rule.
- **`orphanRemoval=true` only deletes when the collection also cascades** (use `cascade = ALL, orphanRemoval = true`). Without cascade, removed children silently survive - a real bug this project hit.
- DB rules: child CASCADE, history RESTRICT, optional SET NULL - set them in the migration, not only in JPA.

## N+1 - the question they always ask
Symptom: N parents + N child queries.
Fixes, in order you should offer:
1. `JOIN FETCH` (JPQL) / `@EntityGraph(attributePaths)` - one query; `distinct` for collections.
2. Batch fetching (`@BatchSize` or `hibernate.default_batch_fetch_size`) - lazy loads collapse into `IN (...)` per owner; "cheap insurance".
3. Projections (interface/constructor) - fetch only columns you need.
Know when each fits: known access path → fetch; incidental lazy access → batching.

## Queries & projections
- JPQL (entities), native SQL (db-specific, aliases→getters), constructor expressions for aggregates (`new com...CustomerOrderTotal(...)`), Specifications for dynamic filters, `Pageable` slicing.
- Interface projection: Spring generates proxies - **column aliases must match getter names** (empirical: mismatch = silently null/error).

## Callbacks & auditing
- `@PrePersist` generate business keys (`order_number`), `@PreUpdate` enforce invariants, `@PreRemove` business delete-guard (customer with orders → refuse).
- Auditing: `@CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy` on a `BaseEntity` + `AuditorAware` = who/when on every row with zero service code.

## Migrations & schema validation
- Liquibase/Flyway own the schema; Hibernate `ddl-auto: validate` in tests/CI proves agreement.
- Never edit a shipped changeset (checksum). Add a new one.
- Every FK column indexed; choose delete rules deliberately; money = `NUMERIC`/`BigDecimal`.

## Tell me about...
**N+1 in a real codebase.** → "Order list pages fired a query per customer. We fixed the hot path with an `@EntityGraph`, and turned on `default_batch_fetch_size` so incidental lazy access stopped multiplying queries; a test counts statements to prevent regressions."

## Traps
- Serializing an entity with lazy associations outside a session → `LazyInitializationException`; cache DTOs, not entities.
- `open-in-view: false` is correct - never rely on lazy loading after the controller returns.
- Bidirectional sync: add both sides (`addOrder`) or the inverse collection is stale.

## Rapid Q&A
- Where does the FK live? → owning many-to-one side.
- EAGER or LAZY? → LAZY by default; fetch deliberately.
- L2 cache scope? → JVM-wide; shared infra = Redis app cache.
