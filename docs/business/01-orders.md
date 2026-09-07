# 01 — Orders

## Business behaviour

Placing an order is **atomic**: the API deducts product stock, creates the order
and its lines, charges the payment and records the processed payment - all in
one transaction. If any step fails (e.g. payment declined), everything rolls
back: no order, no stock deduction, no charge.

Business rules:
- A customer and every ordered product must exist.
- Quantity must be ≥ 1 and ≤ available stock. Insufficient stock is rejected
  (`409 INSUFFICIENT_STOCK`) and nothing is created.
- Money is computed from the catalogue price at order time (line prices are a
  snapshot) - later price changes do not affect placed orders.
- An order gets a unique human-friendly `orderNumber` (ORD-xxxxxxxxxx) at
  persist time.
- **Idempotency**: send the same `Idempotency-Key` header to make retries safe -
  only the first request executes; duplicates replay the stored 201 response.

## Endpoints

### POST /api/v1/orders — place an order
Guard: `SCOPE_order_write` or API key.

Request body:
```json
{
  "customerId": 1,
  "items": [ { "productId": 10, "quantity": 2 } ]
}
```
Optional header: `Idempotency-Key: <client-generated-uuid>`.

Responses:
- `201 Created` + `Location: /api/v1/orders/{id}` — body is the order (below).
- `400 VALIDATION_ERROR` — missing/blank fields, invalid quantities,
  duplicate product in one request, or a payment failure (502 for gateway).
- `404 RESOURCE_NOT_FOUND` — unknown customer or product.
- `409 INSUFFICIENT_STOCK` — stock too low.
- `409 CONCURRENT_MODIFICATION` — lost an optimistic-lock race (the service
  retries automatically; a persistent conflict surfaces here).

Example:
```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: 1a2b3c" \
  -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":10,"quantity":2}]}'
```

### POST /api/v1/orders/bulk — place many orders
Request body is an **array** of order requests. Each element is placed in its
own transaction (all-or-nothing per element, not across the batch). Returns
`201` with an array of order responses. Any authenticated caller can use this
endpoint (no extra method guard today).

### GET /api/v1/orders?page=0&size=20&sort=id,desc — list orders
Paged list of all orders (Spring Data `Page` envelope: `content`, `totalElements`,
`number`, ...). Any authenticated caller can list orders in the current build.

### GET /api/v1/orders/{id} — read one order
Returns the order plus an **ETag** header (`"v<version>"`) used for optimistic
concurrency by the DELETE endpoint.

### PATCH /api/v1/orders/{id} — change status
Body is an RFC 6902 **JSON Patch** array; only `replace /status` is supported:
```json
[ { "op": "replace", "path": "/status", "value": "CONFIRMED" } ]
```
Status values: `PLACED`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, `CANCELLED`.
Any other patch operation returns `400`.

### DELETE /api/v1/orders/{id} — delete an order
Requires the **current** `If-Match` header (the ETag from a GET). A stale or
missing ETag returns `412 Precondition Failed` - this protects against deleting
an order someone else has just changed. Success → `204 No Content`.

## Order response shape

```json
{
  "id": 1,
  "orderNumber": "ORD-1A2B3C4D5E",
  "orderDate": "2026-09-07T10:15:30",
  "status": "PLACED",
  "totalAmount": 10.00,
  "customerEmail": "c***@e***.com",
  "items": [
    { "id": 1, "productId": 10, "productName": "Widget", "quantity": 2,
      "unitPrice": 5.00, "totalPrice": 10.00 }
  ]
}
```

> Note: `customerEmail` is masked unless the caller holds the privileged
> `pii_read` scope or an API key - see `06-privacy-gdpr-and-pii.md`.

## Supporting behaviour worth knowing

- Deleting a **customer** who still has orders is refused (`409`); orders are
  history, not collateral.
- Order placement publishes an integration event via the **outbox** to Kafka
  (topic `order-events`) - see `09-integration-events-kafka.md`.
- Orders write through a resilience-protected payment gateway: transient
  gateway failures are retried, and if the gateway is unhealthy the call fails
  fast as `502 PAYMENT_FAILED` instead of hanging (see `04-payments.md`).
