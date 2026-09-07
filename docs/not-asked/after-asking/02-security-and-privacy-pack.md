> **Requested artefact** - produced as an explicitly requested deliverable
> ("consider I asked for all these"). Canonical home: `docs/not-asked/after-asking/`.

# 02 — Security & Privacy Pack

## Part A — STRIDE threat model (per component)

| Component | Spoofing | Tampering | Repudiation | Info disclosure | DoS | Elevation |
|---|---|---|---|---|---|---|
| JWT resource server | forged/expired token; weak HS256 secret | — | — | over-broad scopes → PII | — | missing scope check → write as reader |
| API-key auth | leaked static key | — | key not attributed per action (shared) | — | — | key with too many powers |
| PII endpoints (masking) | — | — | — | un-masked path (e.g. /view, logs) | — | caller with order_read sees raw |
| GDPR erasure | — | erased data still recoverable in backups/events | erasure without audit | audit storing PII | — | anyone able to erase without pii_write |
| Payments (simulated) | fake gateway | order/payment amount tamper | — | transaction data | slow/naive gateway | charge more than order |
| Outbox/Kafka | fake producer | event tamper at rest/in transit | consumer without dedupe | PII in events | poison messages | consumer over-privileged |
| Cache (Redis) | unauthenticated Redis | stale/poisoned cache | — | cache key enumeration | cache storm | cache poisoning via write path |
| OpenAPI/health | — | — | — | actuator details in prod | — | health shows internals |

For each row, the **mitigation** column lives in `docs/design/04` and in code
(scope matrix, masking, redaction, outbox/DLT, probes). This table is the
**review aid**: go row by row and confirm a control exists and is tested.

## Part B — DPIA-style data-flow document (condensed)

**PII inventory:**
| Data | Where stored | Who sees raw | Retention |
|---|---|---|---|
| email, fullName, phoneNumber | customers (Postgres) | pii_read / API key; masked otherwise | until erasure/anonymization |
| street/city/state/postal/country (addresses) | addresses | masked in views | customer lifetime |
| customerEmail in order responses | derived | masked | order lifetime |
| actor (JWT subject / API key client) | audit_log, audit columns | ops/support | audit lifetime (no PII) |
| order/customer history | orders | via API per scope | legal retention (Art. 17(3)) |

**Lawful basis map (learning-frame):** contract performance (orders),
legal obligation (retention, audit), consent/rights (erasure/portability), and
legitimate interest where annotated. Do NOT overclaim "consent" - mark it per
feature in a real DPIA.

**Controls checklist (each must be demonstrable):**
- [ ] Masking applies on every serialization path (not just the demo DTO).
- [ ] Log redaction is caller-independent and asserted by test.
- [ ] Kafka payloads contain no PII.
- [ ] Audit log has no FK to customers (survives erasure) and no raw PII.
- [ ] Erasure returns DELETED or ANONYMIZED and is idempotent.
- [ ] Portability export returns a complete, machine-readable copy.
- [ ] Retention policy documented (idempotency keys/outbox cleanup planned).

## Part C — Security review checklist (use before any "it's ready" claim)
1. Walk the **scope matrix** against the live app (every endpoint, pos+neg).
2. Confirm **404/405/415** survive the global handler (unknown route test).
3. Confirm **401 vs 403** semantics and error codes are tested.
4. Check security headers on responses (HSTS on HTTPS, CSP, nosniff).
5. Review every actuator endpoint exposed in each profile (health details in
   prod? metrics auth?).
6. Confirm the JWT decoder uses JWKS/RS256 in prod profile, HS256 only dev.
7. API keys: not committed in code; hashing/rotation = forward plan (logged).
8. Check schema: no card-data columns; test enforces it.
9. Check cache poisoning surface: only app writes cache keys; TTL set.
10. Check Kafka ACLs/topic auth in real deployment; in-repo it is dev-only.

Record the result as `evidence/security-review-<date>.md` with pass/fail and
the fix ticket for anything failing.
