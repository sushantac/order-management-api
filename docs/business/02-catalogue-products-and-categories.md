# 02 — Catalogue: Products & Categories

## Business behaviour

The catalogue is the read-mostly half of the API: products are fetched far more
often than they change, so product reads are cached (TTL 10 min in dev/prod)
and invalidated whenever a product or its stock changes. Lists are not cached.

Products may belong to many categories and categories to many products
(many-to-many); category membership is managed from the Product side in this
version (no product/category link endpoint yet).

## Products

| Endpoint | Auth | Behaviour |
|---|---|---|
| GET `/api/v1/products?page=&size=&sort=` | any authenticated | Paged product list |
| GET `/api/v1/products/{id}` | any authenticated | One product (served from cache when warm) |
| POST `/api/v1/products` | any authenticated | Create → `201` + `Location` |
| PUT `/api/v1/products/{id}` | any authenticated | Full update → evicts cache |
| DELETE `/api/v1/products/{id}` | any authenticated | Delete → `204`; fails if products are referenced by order history (`RESTRICT` FK) |
| GET `/api/v1/products/export.csv` | `SCOPE_order_read`/API key | Whole catalogue as CSV |

### Product payload
```json
{
  "name": "Widget",                     // required, <= 255 chars
  "description": "A small widget",      // optional
  "price": 5.00,                        // required, >= 0
  "stockQuantity": 100                  // required, >= 0, sanity-checked
}
```
Response adds `id`.

### Product semantics
- `price` is decimal (`BigDecimal`), never floating point.
- `stockQuantity` is the sellable stock. Orders decrement it atomically; product
  reads immediately reflect the new level (the cache is evicted by every stock
  writer).
- Validation errors → `400 VALIDATION_ERROR`; update/delete of an unknown id →
  `404 RESOURCE_NOT_FOUND`; deleting a product referenced by order history is
  refused by the database (`409 DATA_CONFLICT`).

### CSV export (feature-flagged)
`GET /api/v1/products/export.csv` returns `text/csv`:
```
id,name,price,stockQuantity
1,Widget,5.00,100
```
Names containing commas are quoted. The export honours the `csv-export` feature
flag (`app.features.csv-export`); when disabled it returns `404`.

## Categories

| Endpoint | Behaviour |
|---|---|
| GET `/api/v1/categories?page=&size=` | Paged list |
| GET `/api/v1/categories/{id}` | One category |
| POST `/api/v1/categories` | Create → `201` + `Location` |
| PUT `/api/v1/categories/{id}` | Update description |
| DELETE `/api/v1/categories/{id}` | Delete → `204`; refused while products reference the category |

Category payload: `{ "name": "Gadgets", "description": "..." }` (name required
and immutable after creation - updates change the description only).

## Caching behaviour (functional view)

- First read of a product hits the database; subsequent reads within the TTL
  (10 minutes in dev/prod) are served from the cache.
- Creating, updating or deleting a product evicts the cache.
- Placing an order evicts the cached stock of every product ordered, so buyers
  always see accurate availability.
- The default profile uses an in-memory cache (no Redis needed); dev/prod use
  Redis. Operators can watch hit/miss rates via the `redis.keyspace.hits` /
  `redis.keyspace.misses` metrics.

## Example calls

```bash
# create a product
curl -X POST http://localhost:8080/api/v1/products \
  -H "X-API-Key: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"name":"Widget","price":"5.00","stockQuantity":100}'
# fetch it (cached after the first call)
curl http://localhost:8080/api/v1/products/1 \
  -H "X-API-Key: dev-api-key-orderapi"
# CSV catalogue export
curl -H "X-API-Key: dev-api-key-orderapi" \
  http://localhost:8080/api/v1/products/export.csv
```
