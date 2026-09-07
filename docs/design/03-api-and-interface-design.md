# 03 — API & Interface Design

## 3.1 Interface principles
- REST over HTTPS, JSON; versioned under `/api/v1/...` (v2 for breaking
  change, coexistence with deprecation headers).
- Controllers thin; request/response **Java records (DTOs)**; one explicit
  mapper per aggregate (`OrderMapper` pattern). Entities never cross the wire.
- Idempotency headers on unsafe writes; ETag/If-Match on resources with
  optimistic concurrency needs.
- Machine-readable errors everywhere (Problem Details + stable codes).
- Generated OpenAPI at `/v3/api-docs` and Swagger UI as the live contract.

## 3.2 Resource surface
| Resource | Operations |
|---|---|
| /api/v1/orders | POST (idempotent single), POST /bulk, GET page, GET /{id} (+ETag), PATCH /{id} (JSON Patch replace /status), DELETE /{id} (If-Match) |
| /api/v1/products | GET page, GET /{id} (cached), POST, PUT /{id}, DELETE /{id}, GET /export.csv (flag) |
| /api/v1/categories | GET page, GET /{id}, POST, PUT /{id}, DELETE /{id} |
| /api/v1/customers | GET page, GET /{id}, GET /{id}/view (fields/include), GET /legacy (deprecated), POST, PUT /{id}, DELETE /{id} |
| GDPR | DELETE /api/v1/customers/{id}/data, GET /api/v1/customers/{id}/portability |
| Utility | GET /api/v1/features, GET /api/v1/messages/{key} (i18n) |
| Platform | /actuator/health(liveness/readiness), metrics, prometheus, info; /v3/api-docs; /swagger-ui.html |

## 3.3 Authorization (scope matrix - normative)
| Path family | Required |
|---|---|
| POST /orders (single) | `SCOPE_order_write` or API key |
| POST /orders/bulk, PATCH/DELETE /orders/{id} | `SCOPE_order_write` or API key (align in build; see §9 deviation) |
| GET /orders, products, categories, /customers list/get | `SCOPE_order_read` or API key |
| Write on products/categories/customers | `SCOPE_order_write` or API key (align; deviation noted) |
| Raw PII + portability | `SCOPE_pii_read` or API key |
| Erasure | `SCOPE_pii_write` or API key |
| health/info, api-docs, swagger | public |

Deviation note (known gap in as-built v1.0): not every endpoint carries its
matrix guard yet; matrix + tests are the remediation (roadmap P0 in
`docs/good-and-bad/03`).

## 3.4 Error model
Every failure is an RFC 7807 Problem Detail: `{status, title, detail, code,
hint}`. Clients branch on `code`. Catalog highlights: 400 VALIDATION_ERROR /
INVALID_ARGUMENT; 401 AUTHENTICATION_REQUIRED; 403 ACCESS_DENIED; 404
RESOURCE_NOT_FOUND; 409 DATA_CONFLICT / INSUFFICIENT_STOCK /
CONCURRENT_MODIFICATION / RESOURCE_IN_USE; 412 (If-Match); 429 RATE_LIMITED;
502 PAYMENT_FAILED; 500 INTERNAL_ERROR (last resort only).

## 3.5 Idempotency & concurrency control
- `Idempotency-Key` on order create: first request stores (status, body);
  same key replays. (Forward plan: apply to all unsafe writes + TTL cleanup.)
- `ETag: "v<version>"` from GET; DELETE requires matching `If-Match`
  (stale ⇒ 412) - optimistic concurrency at the HTTP layer over `@Version`.

## 3.6 Interface quality
- Validation: Bean Validation groups per operation + cross-field custom
  constraints (e.g. `@ValidStock`, `@ValidOrderRequest`).
- Pagination: Spring `Pageable` (`page`, `size`, `sort`).
- Deprecation: RFC 8594 `Deprecation` + `Sunset` headers on aliases.
- Correlation: `X-Correlation-Id` honoured/generated, echoed, and logged via
  MDC for end-to-end tracing of one request.
