# 06 — Privacy: PII Masking, GDPR & Data Retention

## What is treated as personal data (PII)
`email`, `fullName`, `phoneNumber`, and (in exports/views) address details
(`street`, `city`, `state`, `postalCode`, `country`).

## PII masking rules

### In API responses
- **Full (raw) values** are returned to:
  - callers holding `SCOPE_pii_read`, or
  - API-key clients (trusted machine identity).
- **Masked values** are returned to every other authenticated caller, e.g.:
  - `ada@example.com` → `a***@e***.com`
  - `Ada Lovelace` → `A***e`
  - `+61 400 000 000` → `+61***00`

Masking applies consistently to customer responses/lists/views, the order
response `customerEmail`, and address `street` in the `/view` payload. Masked
values are deterministic (same input ⇒ same mask), so they are safe to use in
support and audit contexts.

### In logs
All `/api/**` request/response bodies are **scrubbed before logging**
(`email`, `phone`, `name`, address fields, plus any e-mail-shaped string under
any key), regardless of who called. Log redaction is a property of the log
sink, not of the caller's rights - support engineers never see raw PII in logs.

## GDPR subject rights

### Right to erasure — `DELETE /api/v1/customers/{id}/data`
Requires `SCOPE_pii_write` or an API key.

The API decides between two legally distinct outcomes and tells you which one it
did in the response `action`:

| Action | When | What happens |
|---|---|---|
| `DELETED` | Customer has **no order history** | Customer row (and addresses) physically removed |
| `ANONYMIZED` | Customer **has orders that must be retained** (GDPR Art. 17(3)) | Personal fields overwritten with non-identifying values: email `erased-<id>@erased.invalid`, name `Erased User`, phone cleared. Order history is kept for legal/accounting reasons but can no longer be linked to the person |

The call is **idempotent**: erasing an already-anonymized customer is safe.
The endpoint returns `200` with a body describing the action:
```json
{ "customerId": 7, "action": "ANONYMIZED",
  "completedAt": "2026-09-07T10:15:30", "note": "3 order(s) retained ..." }
```

### Right to data portability — `GET /api/v1/customers/{id}/portability`
Requires `SCOPE_pii_read` or an API key.

Returns a **complete machine-readable JSON export** (GDPR Art. 20) of everything
the API holds about the data subject: profile (raw values - it is the subject's
own data), addresses, and the full order history (order numbers, dates, status,
totals, payment method, line items). Includes a `statement` describing the
purpose of the export.

## Audit trail & retention

- Every erasure and every portability export is written to the compliance audit
  log: `customerId`, `action` (`CUSTOMER_ERASED`, `CUSTOMER_ANONYMIZED`,
  `PORTABILITY_EXPORTED`), `actor` (JWT subject or API-key client), timestamp,
  and non-PII detail (order/address counts, mode).
- Audit rows survive even a physical erasure (they do not reference the deleted
  customer) and never contain raw personal data.
- Accountable **who/when** metadata (`createdBy/updatedBy/createdAt/updatedAt`)
  is captured on every business row automatically.

## PCI-DSS (payment data)
The API and its schema contain **no cardholder data** (no PAN, CVV, expiry,
cardholder name) - payments only store method/status/amount/transaction id. A
schema test enforces this, keeping PCI scope minimal.

## Example calls
```bash
# export (full data)
curl -H "X-API-Key: dev-api-key-orderapi" \
  http://localhost:8080/api/v1/customers/7/portability

# erasure
curl -X DELETE -H "X-API-Key: dev-api-key-orderapi" \
  http://localhost:8080/api/v1/customers/7/data
```
