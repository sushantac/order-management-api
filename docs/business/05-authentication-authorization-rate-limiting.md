# 05 — Authentication, Authorization & Rate Limiting

## Two ways to call the API

### 1. Bearer JWT (OAuth2) — for interactive users / front-ends
```
Authorization: Bearer <access_token>
```
The API is an **OAuth2 resource server**: it validates the token's signature
and expiry and reads the OAuth2 `scope` claim, but never issues tokens. Scopes
become Spring authorities prefixed `SCOPE_`:

| Scope | Meaning |
|---|---|
| `order_read` | Read orders/customers/catalogue where a guard demands it |
| `order_write` | Place orders |
| `pii_read` | See raw personal data (emails, phones, names) and use data portability |
| `pii_write` | GDPR erasure |

In production your auth server signs tokens with RS256; this repo validates
against the configured JWKS. For local/learning use it accepts HS256 tokens
signed with `app.security.jwt-secret`.

### 2. API key — for machine-to-machine integrations
```
X-API-Key: dev-api-key-orderapi   (configurable via app.security.api-key)
```
An API key authenticates as a trusted machine client (`ROLE_API_KEY`) and
satisfies the same endpoint guards as the scopes. API keys also unlock **raw
PII** - they are the "system/trusted" identity.

## Endpoint guard matrix (security enabled)

| Endpoint(s) | Requires |
|---|---|
| POST `/api/v1/orders` | `SCOPE_order_write` or API key |
| GET `/api/v1/customers`, GET `/customers/{id}` | `SCOPE_order_read` or API key |
| GET `/api/v1/products/export.csv` | `SCOPE_order_read` or API key |
| DELETE `/customers/{id}/data` (erasure) | `SCOPE_pii_write` or API key |
| GET `/customers/{id}/portability` | `SCOPE_pii_read` or API key |
| Everything else under `/api/v1/**` | any authenticated caller (valid token or key) |
| `/actuator/health`, `/actuator/info`, `/v3/api-docs`, `/swagger-ui/**` | public (no auth) |

### Errors
- No/invalid credentials → `401 AUTHENTICATION_REQUIRED`.
- Valid but insufficient → `403 ACCESS_DENIED`.

## What "authenticated" protects
Beyond endpoint guards, the API is stateless and applies standard security
headers to responses (HSTS, CSP, `X-Content-Type-Options: nosniff`), CORS only
for configured origins (default `http://localhost:3000`), and CSRF protection is
disabled because bearer tokens are not cookie-based.

## Rate limiting (per API key)

API-key callers are rate limited with a **per-key bucket**:

| Header | Meaning |
|---|---|
| `X-RateLimit-Remaining` | Permits left in the current window |
| `X-RateLimit-Reset` | Epoch seconds when the window refills |
| `Retry-After` | Seconds to wait (on 429) |

Default limit: `1000` requests per key per minute (configurable via
`resilience4j.ratelimiter.instances.apiKey.*`). Over the limit → **429** with a
Problem-Detail style body:
```json
{ "status": 429, "code": "RATE_LIMITED", "hint": "Too many requests - retry after the rate-limit window." }
```
Each key has its own bucket, so one noisy tenant never exhausts another's quota.
JWT callers are not rate limited here (scope-level quotas are an API-gateway
concern in production).

## Example calls
```bash
# JWT bearer (tokens are issued by your auth server)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/orders/1

# API key (machine client)
curl -H "X-API-Key: dev-api-key-orderapi" http://localhost:8080/api/v1/products/1
```
