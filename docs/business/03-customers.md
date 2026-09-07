# 03 — Customers

## Business behaviour

A customer is a person (or business account) who can place orders. Deleting a
customer is **restricted by business rule**: a customer who still has orders is
refused (`409`) because orders are retained history. Removing a customer
completely (or anonymizing their data) is a GDPR action, not a normal delete -
see `06-privacy-gdpr-and-pii.md`.

Customer data is **PII**: full values are only returned to callers with the
privileged `pii_read` scope or an API key; other authenticated callers receive
masked email/name/phone in responses (see the privacy doc for exact rules).

## Endpoints

| Endpoint | Auth guard | Behaviour |
|---|---|---|
| GET `/api/v1/customers?page=&size=&sort=` | `SCOPE_order_read`/API key | Paged list (PII masked for weaker callers) |
| GET `/api/v1/customers/{id}` | `SCOPE_order_read`/API key | One customer |
| GET `/api/v1/customers/{id}/view` | any authenticated | Flexible view with field filtering/includes |
| GET `/api/v1/customers/legacy` | any authenticated | Deprecated alias of list (see versioning doc) |
| POST `/api/v1/customers` | any authenticated | Create → `201` + `Location` |
| PUT `/api/v1/customers/{id}` | any authenticated | Full update |
| DELETE `/api/v1/customers/{id}` | any authenticated | Delete → `204`; refused (`409`) if the customer has orders |
| DELETE `/api/v1/customers/{id}/data` | `SCOPE_pii_write`/API key | GDPR erasure (delete or anonymize) |
| GET `/api/v1/customers/{id}/portability` | `SCOPE_pii_read`/API key | GDPR data-portability export |

## Customer payload
```json
{
  "email": "ada@example.com",      // required, must be a valid e-mail
  "fullName": "Ada Lovelace",      // required, 2..100 chars on create
  "phoneNumber": "+61 400 000 000" // optional, digits/+/-/space only
}
```
Response adds `id` and `createdAt`. `email` is unique - a duplicate create or
update returns `409 DATA_CONFLICT`.

## Flexible customer view (`GET /{id}/view`)

Query parameters:
- `fields=id,fullName` — return only the requested keys (client asks for what
  it needs; sensitive fields are still policy-protected).
- `include=orders` and/or `include=addresses` — embed summaries:
  - `orders`: `orderNumber`, `status`, `totalAmount`
  - `addresses`: `city`, `street`

Example:
```
GET /api/v1/customers/7/view?fields=id,fullName,email&include=orders
```
Like the standard responses, `email`, `fullName`, `phoneNumber` and `street`
are masked unless the caller has full PII access.

## Delete rules recap
- Customer without orders → `204 No Content` (addresses cascade away with them).
- Customer with orders → `409 RESOURCE_IN_USE` and nothing is deleted. Use the
  GDPR erasure endpoint if the customer's data must be removed/anonymized while
  history is retained.

## Example calls
```bash
# create
curl -X POST http://localhost:8080/api/v1/customers \
  -H "X-API-Key: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"email":"ada@example.com","fullName":"Ada Lovelace"}'
# list (masked view without a pii scope)
curl -H "X-API-Key: dev-api-key-orderapi" \
  "http://localhost:8080/api/v1/customers?page=0&size=20"
```
