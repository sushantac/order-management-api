# 04 — Security & Privacy Design

## 4.1 Authentication & authorization model
- The API is an **OAuth2 resource server**: validates bearer JWTs (signature,
  expiry); never issues tokens. Development profile: HS256 with a shared
  secret; production profile: RS256 validated against the issuer's JWKS with
  `iss`/`aud` pinning (documented; production switch is forward plan).
- JWT `scope` claim → `SCOPE_*` Spring authorities via a converter;
  `@PreAuthorize` at method level. Distinct privilege classes:
  `order_read` < `order_write` < `pii_read`/`pii_write`.
- **API keys** for machine clients: `X-API-Key` → trusted `ROLE_API_KEY`
  principal satisfying the same guards; per-key rate limiting (below).
- Session-less: `SessionCreationPolicy.STATELESS`; CSRF disabled (non-cookie);
  CORS locked to configured origins; security headers (HSTS, CSP,
  X-Content-Type-Options) applied by the filter chain.

## 4.2 PII protection design (data-flow view)
| Boundary | Control | Notes |
|---|---|---|
| Responses | Serializer masking (`@MaskedPii` fields, `PiiAccessDecider`) | raw only for `pii_read`/API key; deterministic masks (a***@e***.com) |
| Hand-built views (`/view`) | explicit policy application per field | consistent with serializer rule |
| Logs | `PiiRedactionFilter` scrubs bodies before logging | log-sink policy independent of caller rights |
| Events | payloads contain no PII | consumers fetch details from the API |
| Persistence | raw in DB (encryption-at-rest is infra concern) | masked never stored; store raw only where needed |
| Audit | non-PII detail only | no raw values in compliance trail |

## 4.3 GDPR subject rights
- **Erasure** `DELETE /customers/{id}/data`: DELETED when no history; else
  ANONYMIZED (overwrite identity fields, keep history for legal retention,
  GDPR Art. 17(3)); idempotent; every call audit-logged.
- **Portability** `GET /customers/{id}/portability`: complete machine-readable
  JSON export (profile, addresses, order history).
- Audit entries survive physical erasure (no FK) and record actor + timestamp.

## 4.4 PCI-DSS stance
Scope reduction is the control: the system never captures or stores cardholder
data (no PAN/CVV/expiry/cardholder columns - enforced by a schema test). The
gateway abstraction returns only a transaction reference. A future real PSP
integration sits behind the same interface without widening storage scope.

## 4.5 Abuse protection
- Per-key rate limiting: bucket per `X-API-Key`, 429 + `Retry-After`,
  `X-RateLimit-Remaining/Reset`. Current implementation is per-process
  (Resilience4j in-memory) - shared/Redis limiting is forward plan so N
  instances enforce one quota.
- Permit-list for public endpoints; everything else authenticated; method
  guards enforced for matrixed operations.

## 4.6 Threat posture notes
| Threat | Mitigation |
|---|---|
| Replay/duplicate charge | idempotency keys; atomic transaction |
| Token forgery | signature validation; prod JWKS/RS256 |
| Privilege escalation | least-privilege scopes; explicit 403 handling |
| PII leakage in logs | redaction filter (caller-independent) |
| Erasure of audit evidence | audit table independent of customer row |
| DDoS via catalogue | rate limits; caching reduces DB load |
