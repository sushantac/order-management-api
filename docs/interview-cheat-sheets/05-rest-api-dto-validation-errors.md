# 05 — REST API Design: DTOs, Validation, Errors, Docs

## Mental model
Controllers are thin adapters. Services hold transactions/business rules.
DTO records cross the boundary; entities never do.

## Endpoint conventions (be able to rattle off)
- POST `/resources` → 201 + `Location` header (+ body = created resource).
- GET `/resources`, GET `/resources/{id}`, PUT (full replace), DELETE → 204.
- Pagination via `Pageable` (`?page=0&size=20&sort=field,asc`); filters via query
  params or Specifications; bulk ops get their own endpoint.
- Version URLs `/api/v1/...`; deprecated aliases advertise RFC 8594 `Deprecation`
  + `Sunset` headers before removal (no silent breakage).

## DTOs as records (Java 21)
- Immutable; validation annotations live on components; mapping centralized in a
  `*Mapper` so the API contract is reviewable in one file.
- Why not entities? No lazy proxies on the wire, no audit/version leakage,
  contract decoupled from persistence shape.

## Validation groups (strong interview point)
Same DTO, different rules per operation:
`@Validated(Create.class)` vs `@Validated(Update.class)`.
Example: `email` required for both; name length only enforced on Create. Cross-field
rules use a custom class-level constraint + `ConstraintValidator` (`@ValidStock`,
`@ValidOrderRequest`). Grouping avoids separate request DTOs per verb.

## Errors: RFC 7807 Problem Details (have the shape memorized)
```
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
{ "title": "Insufficient stock", "status": 409,
  "detail": "Insufficient stock for product 7 (available 2)",
  "code": "INSUFFICIENT_STOCK", "hint": "Reduce the quantity or restock." }
```
One `@RestControllerAdvice`; a Java 21 pattern-matching switch classifies exception
families → status/code/hint. **Clients branch on `code`, never message strings.**
Include 401/403 handlers explicitly or method-security denials become 500s (real bug).

## Idempotency keys (great differentiator)
`Idempotency-Key` header on risky writes (order placement): first request stores
(status, body) keyed by the key; retries with the same key **replay the stored
response** - no double charge/order. Table with a unique key; same transaction as
the write. Contrast: exactly-once is impractical, idempotent-at-least-once is real.

## Docs & contracts
- springdoc generates `/v3/api-docs` + Swagger UI from `@Operation`/`@ApiResponse`;
  declare security schemes (bearer + API key).
- Consumer-contract testing (Pact) records interactions; provider verifies them in
  CI so the API cannot silently break clients.

## Tell me about...
**Designing the order endpoint.** → "POST /api/v1/orders with an Idempotency-Key: the
service deducts stock, creates the order, charges the payment and writes the outbox in
ONE transaction; validation groups shape the request; every failure is a Problem
Detail with a stable code; docs generated from the annotations."

## Rapid Q&A
- PUT vs PATCH? → PUT full replacement, idempotent; PATCH partial.
- 400 vs 422? → 400 malformed/validation is fine; pick one convention and keep it.
- Why Problem Details? → uniform machine-readable errors + stable codes.
- Field filtering / resource inclusion (`?fields=&include=`) - nice-to-have demo of
  flexible APIs (customer `/view` endpoint).
- Bulk + idempotency conflict? → one key per element or per batch; document which.
