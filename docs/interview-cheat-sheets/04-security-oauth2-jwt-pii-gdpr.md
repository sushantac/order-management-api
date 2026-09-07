# 04 — Security: OAuth2, JWT, PII & GDPR

## Mental model: resource server
Roles: **Authorization Server** (issues tokens) vs **Resource Server** (validates
tokens, protects resources). An API is a resource server: it never issues tokens,
never stores passwords, and stays decoupled from the IdP.

## Token flow (what you implemented)
1. Client sends `Authorization: Bearer <JWT>`.
2. `JwtDecoder` validates signature/expiry (HS256 local secret for learning;
   production = RS256 against the issuer's **JWKS** + pin `iss`/`aud`).
3. `JwtAuthenticationConverter` turns the OAuth2 `scope` claim into
   `SCOPE_order_read`, `SCOPE_order_write`, ... authorities.
4. `@PreAuthorize("hasAuthority('SCOPE_order_write')")` guards methods.

Why `SCOPE_` prefix? It is the Spring convention so `hasAuthority('SCOPE_x')`
and `hasRole()` do not collide.

## Stateless config essentials
- No sessions; `SessionCreationPolicy.STATELESS`; CSRF off (token APIs).
- CORS for your front-end origin only; security headers (HSTS, CSP,
  X-Content-Type-Options).
- Permit-list: health, OpenAPI docs; everything else authenticated.
- API keys for **machine clients**: `X-API-Key` → `ROLE_API_KEY` principal.
  Per-key rate limiting: bucket per key → 429 + `X-RateLimit-Remaining`,
  `X-RateLimit-Reset`, `Retry-After`.
- Error mapping matters: 401 `AUTHENTICATION_REQUIRED`, 403 `ACCESS_DENIED` as
  Problem Details - do NOT let a catch-all handler turn denials into 500s.

## PII / GDPR (know the difference from auth)
PII protection is data-flow, at three boundaries:
1. **Response**: fields annotated `@MaskedPii(PiiType.*)` serialize masked unless
   caller has the strong `pii_read` scope / API key (least privilege per field).
2. **Logs**: bodies are scrubbed before logging regardless of caller rights
   (log sink policy ≠ viewer rights).
3. **Subject rights**:
   - Erasure `DELETE /customers/{id}/data`: DELETE when no history; **anonymize**
     when history must be retained (GDPR Art. 17(3)) - overwrite email/name/phone.
   - Portability `GET /customers/{id}/portability`: full machine-readable export.
   - Audit trail survives erasure: no FK to the customer, and contains no raw PII.

PCI-DSS angle: never store PAN/CVV - gateway returns a reference; prove it with a
schema test ("payments table has no card columns").

## Tell me about...
**"How does auth work in your API?"** → Stateless OAuth2 resource server: validate
bearer JWT → map `scope` to `SCOPE_*` authorities → `@PreAuthorize` at the method
level. Machine clients use `X-API-Key` with per-key rate limits. Stronger `pii_*`
scopes guard raw personal data and GDPR actions.

## Traps
- Catch-all exception handler swallowing 401/403 (real bug) - add explicit handlers.
- Masking decision vs old tests: anonymous/test contexts keep full view so the
  pre-security suites stay green; authenticated callers without `pii_read` get masked.
- HS256 in prod is wrong (shared secret). Say "JWKS + RS256 + iss/aud" unprompted.

## Rapid Q&A
- JWT stateless vs sessions? → JWT scales/decouples but revocation is harder;
  short expiry + refresh; keep sessions for interactive web if logout must be instant.
- How to revoke? → short-lived tokens + allowlist/denylist or token versioning.
- Scope vs role? → scope = what a token may do (OAuth2); role = who the user is
  (RBAC). Often combined: scope → authorities.
- GDPR erasure vs delete? → Article 17(3) allows retention when legally required -
  anonymization is the compliant middle ground.
