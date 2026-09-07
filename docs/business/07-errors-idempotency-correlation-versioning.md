# 07 — Errors, Idempotency, Correlation & Versioning

## Error format (all failures)

Every error is an RFC 7807 Problem Details document:

```json
{
  "type": "about:blank",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "Insufficient stock for product 7 (available 2)",
  "code": "INSUFFICIENT_STOCK",
  "hint": "Reduce the quantity or restock the product."
}
```

**Contract for clients: branch on `code`, never on the `detail` text.**

### Error code catalog

| HTTP | `code` | Typical cause / meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bean Validation or cross-field rule failed |
| 400 | `INVALID_ARGUMENT` | Malformed value / unsupported patch op |
| 401 | `AUTHENTICATION_REQUIRED` | Missing/invalid token or API key |
| 403 | `ACCESS_DENIED` | Authenticated but missing the required scope |
| 404 | `RESOURCE_NOT_FOUND` | Unknown id (`detail` names it) |
| 409 | `DATA_CONFLICT` | Duplicate unique value (e.g. customer e-mail) |
| 409 | `INSUFFICIENT_STOCK` | Not enough stock to fulfil an order |
| 409 | `CONCURRENT_MODIFICATION` | Optimistic-lock conflict survived retries |
| 409 | `RESOURCE_IN_USE` | Deleting a customer who still has orders |
| 412 | *(empty)* | DELETE with a stale/missing `If-Match` ETag |
| 429 | `RATE_LIMITED` | Per-key rate limit exceeded |
| 500 | `INTERNAL_ERROR` | Unexpected failure (bug or infra) |
| 502 | `PAYMENT_FAILED` | Payment gateway declined/unavailable after retries |

## Idempotency

Purpose: make retries of **order placement** safe (a client that times out and
retries must not create two orders / charge twice).

How to use:
- Generate a UUID once per logical order attempt.
- Send it on every retry: `Idempotency-Key: <uuid>`.
- First request executes and the API stores the response keyed by that UUID.
- Any later request with the same key **replays the stored response** (same
  status/body) without executing the order again.

Notes:
- Supported today on `POST /api/v1/orders`.
- The stored key is unique; retries with the same key on the same endpoint
  always replay. Use a NEW key for a genuinely different order.

## Correlation ids

Every request can (and should) carry a correlation id so support can trace one
operation across every log line and service hop:

```
X-Correlation-Id: <your-id>     // optional; a UUID is generated if absent
```
The API echoes the value (generated or provided) in the response header and
includes it in all logs of that request via MDC. Propagate the same header to
downstream systems to correlate across services.

## Versioning & deprecation

- Current version lives under `/api/v1/**`.
- Breaking changes belong in `/api/v2/**` (co-existence, no hard cut-overs).
- Deprecated endpoints keep working but advertise removal:
  - `Deprecation: true`
  - `Sunset: <date>` (planned removal)
  - Example: `GET /api/v1/customers/legacy` is the deprecated alias of the
    customers list.

## Interactive docs
- OpenAPI spec: `/v3/api-docs`
- Swagger UI: `/swagger-ui.html`
- Declares the two auth schemes (`bearer-jwt`, `api-key`) and the documented
  scopes so consumers can try endpoints in the browser.
