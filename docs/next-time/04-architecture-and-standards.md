# 04 — Architecture & Standards (decide once, then enforce)

These are the approved-by-default contracts for the re-run. Treat them like
ADRs 0001-0008: a later PR may change one, but only with an explicit ADR
explaining why. Layering and the error contract are enforced by tests.

## ADR 0001 — Packaging & dependency rules
```
com.company.orderapi
├── domain          # entities/value objects/domain events; NO Spring imports in
│                   # entities; NEVER imports api.*, messaging.*, config.*
├── application     # use-cases + ports (services, @Transactional) - depends on
│                   # domain + ports only
├── infrastructure  # persistence (repos/entities), messaging, cache, config,
│                   # observability glue - implements application ports
└── api             # controllers + DTOs + mappers (the only layer that imports
                    # application + DTOs)
```
Rule: `domain` must not import `api.dto`/`messaging`. Service methods return
domain types or application-level DTOs; controllers map to API DTOs at the
edge. **Enforced with ArchUnit** (`LayerDependencyTest`) from PR #1. If a read
model is cached, cache the mapped API/read DTO, not an entity.

## ADR 0002 — Error contract
- Every failure is RFC 7807 Problem Details with a stable catalog `code`.
- Handler precedence (most specific wins):
  - `MethodArgumentNotValidException`, `ConstraintViolationException` → 400 VALIDATION_ERROR
  - security → 401 AUTHENTICATION_REQUIRED / 403 ACCESS_DENIED (explicit)
  - domain exceptions → 404/409 family with codes (INSUFFICIENT_STOCK,
    CONCURRENT_MODIFICATION, DATA_CONFLICT, RESOURCE_IN_USE, RESOURCE_NOT_FOUND)
  - payment → 502 PAYMENT_FAILED
  - framework 4xx must stay 4xx: `NoResourceFoundException`→404,
    `HttpRequestMethodNotSupportedException`→405,
    `HttpMediaTypeNotSupportedException`→415
  - a LAST `Exception` handler exists but only maps genuinely unexpected bugs
    to 500 INTERNAL_ERROR
- Regression test: `GET /does-not-exist` returns 404 (never 500).

## ADR 0003 — Scope matrix (write before controllers; enforce with tests)
Endpoint table lives in `docs/business/...` AND in a test (`ScopeMatrixTest`)
that walks every mapped route with positive + negative tokens:

| Method+Path family | Minimum authority |
|---|---|
| POST /orders (single) | SCOPE_order_write or ROLE_API_KEY |
| POST /orders/bulk | SCOPE_order_write or ROLE_API_KEY |
| PATCH/DELETE /orders/{id} | SCOPE_order_write or ROLE_API_KEY |
| GET /orders*, products, categories | SCOPE_order_read or ROLE_API_KEY |
| POST/PUT/DELETE products, categories, customers | SCOPE_order_write or ROLE_API_KEY |
| GET /customers* (non-PII) | SCOPE_order_read or ROLE_API_KEY |
| GET PII (raw), /portability | SCOPE_pii_read or ROLE_API_KEY |
| DELETE /customers/{id}/data | SCOPE_pii_write or ROLE_API_KEY |
| /actuator/health|info, /v3/api-docs, /swagger-ui | public |
| /actuator/metrics|prometheus, /actuator/health/{liveness,readiness} | ops (configurable) |

Security disabled (`app.security.enabled=false`) exists ONLY for tests.

## ADR 0004 — Idempotency policy
- One `IdempotencyService` (table with unique key + TTL cleanup job).
- Applied to every unsafe write from PR #20 on: order create/bulk, and
  update/delete paths that carry side effects.
- Semantics: first request executes and stores (status, body, method, path);
  retries replay. Same key + same method/path ⇒ replay; new key ⇒ new op.

## ADR 0005 — State transitions
Statuses declare a transition map in one domain object, e.g.:
```
PLACED -> {CONFIRMED, CANCELLED}
CONFIRMED -> {SHIPPED, CANCELLED}
SHIPPED -> {DELIVERED, CANCELLED}
DELIVERED -> {}
CANCELLED -> {}
```
Services/PATCH validate via the map (unknown transitions → 409). Ship a
`canTransition(from, to)` unit-test suite.

## ADR 0006 — Cache policy
- Cache the read/DTO projection only (never entities with lazy state).
- Key by id (aggregate read) or query signature (lists) with TTL.
- Invalidation rule: ANY writer that can change what a cached read returns must
  evict the affected keys *in the same unit of work that commits the change*.
- Stock writers outside the catalogue evict the product key. Prove with an
  eviction-audit integration test. Track hit/miss gauges.

## ADR 0007 — Messaging & outbox
- Publishing to a broker never happens inside the business transaction except
  via the outbox row (same tx).
- Payloads carry no PII (consumers needing details call the API).
- Consumers: idempotent (dedupe by event id), manual ack after processing,
  DLT for poison after a bounded retry budget.
- Outbox rows: attempts + max threshold → alert; never retried forever.

## ADR 0008 — Tenancy boundary (decide in PR #35, seed in PR #1)
State the default: single-tenant for the learning build; the domain keeps a
`tenantId`-shaped seam documented (column reserved) so multi-tenancy is
additive. Revisit with an ADR before claiming production multi-tenancy.
