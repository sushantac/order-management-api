# Order Management API — Functional Documentation

*What the API does for its users and integrators, endpoint by endpoint. These
docs are behaviour-focused (not implementation-focused); for the technical
"how and why", see `docs/learnings/`.*

## Product overview

The Order Management API lets a retailer manage **customers**, a **product
catalogue** (products + categories), and **orders** that atomically reserve
stock, charge a (simulated) payment and record the whole history. It is a
learning project built in 35 one-concept pull requests, but every feature below
is real, tested behaviour you can exercise locally.

Out-of-the-box highlights:
- Secure by default: OAuth2 **JWT bearer** scopes or **API keys** (`X-API-Key`).
- Atomic order placement with optimistic stock control, retries, cache
  freshness and an **outbox** that publishes an event for integrators.
- PII protected: masked in responses/logs, plus **GDPR erasure & portability**.
- Resilient: circuit breakers, retries, bulkheads and per-key **rate limits**.
- Observable: health endpoints, metrics, correlation ids and traces.

## Quick start (run it)

```bash
docker compose up -d            # postgres + redis + kafka + jaeger
./mvnw spring-boot:run          # http://localhost:8080 (default profile)
# health:
curl http://localhost:8080/actuator/health
```

Use the `dev` profile (`SPRING_PROFILES_ACTIVE=dev`) to also switch on Redis
caching and Kafka publishing against the compose services.

## Reading conventions

- Paths are under `/api/v1` unless noted (e.g. `/api/v1/orders`).
- JSON in, JSON out (`application/json`); CSV only on `.../products/export.csv`.
- **Auth**: when security is enabled, *every* `/api/**` request needs either
  `Authorization: Bearer <JWT>` (OAuth2 scopes) or `X-API-Key: <key>`.
  Method-level guards are listed per endpoint; the complete matrix is in
  `05-authentication-authorization-rate-limiting.md`.
- **Errors**: all failures are RFC 7807 Problem Details
  (`application/problem+json`) with `status`, `code` and `hint`. The full code
  catalog is in `07-errors-idempotency-correlation-versioning.md`.
- Example calls use `dev-api-key-orderapi` for the API key; JWTs are issued by
  your auth server in real life (dev secret in `application.yml`).

## Feature index

| Document | What it covers |
|---|---|
| [01-orders.md](01-orders.md) | Place an order, bulk orders, order lifecycle & status, ETag/If-Match, order reads |
| [02-catalogue-products-categories.md](02-catalogue-products-and-categories.md) | Products CRUD + stock, categories, product cache behaviour, CSV export |
| [03-customers.md](03-customers.md) | Customer CRUD, view with field filtering/includes, delete rules |
| [04-payments.md](04-payments.md) | Payment methods & statuses, simulated gateway, failure semantics |
| [05-authentication-authorization-rate-limiting.md](05-authentication-authorization-rate-limiting.md) | Scopes, API keys, security headers, per-key rate limits |
| [06-privacy-gdpr-and-pii.md](06-privacy-gdpr-and-pii.md) | Masking rules, right to erasure, data portability, audit & retention |
| [07-errors-idempotency-correlation-versioning.md](07-errors-idempotency-correlation-versioning.md) | Problem Details catalog, Idempotency-Key, correlation id, deprecation |
| [08-observability-health-monitoring.md](08-observability-health-monitoring.md) | Health/probes, metrics, logs, traces |
| [09-integration-events-kafka.md](09-integration-events-kafka.md) | Outbox/event guarantees, Kafka topics, consumer guidance |
| [10-enterprise-and-utility-features.md](10-enterprise-and-utility-features.md) | Feature flags, i18n messages, API docs (Swagger/OpenAPI), misc utilities |
| [11-mcp-ai-integration.md](11-mcp-ai-integration.md) | AI assistant integration: MCP JSON-RPC endpoint, read-only tools, PII boundary |

## API surface at a glance

| Resource | Operations |
|---|---|
| `/api/v1/orders` | POST (create), POST `/bulk`, GET (paged), GET `/{id}`, PATCH `/{id}` (status), DELETE `/{id}` (If-Match) |
| `/api/v1/products` | GET (paged), GET `/{id}`, POST, PUT `/{id}`, DELETE `/{id}`, GET `/export.csv` |
| `/api/v1/categories` | GET (paged), GET `/{id}`, POST, PUT `/{id}`, DELETE `/{id}` |
| `/api/v1/customers` | GET (paged), GET `/{id}`, GET `/{id}/view`, GET `/legacy`, POST, PUT `/{id}`, DELETE `/{id}`, DELETE `/{id}/data` (GDPR), GET `/{id}/portability` |
| `/api/v1/features` | GET (feature flags) |
| `/api/v1/messages/{key}` | GET (localized messages) |
| `/mcp` | POST (MCP JSON-RPC: `initialize`, `tools/list`, `tools/call` — read-only tools) |
| `/actuator/*` | health (incl. liveness/readiness), metrics, prometheus, info |
| `/v3/api-docs`, `/swagger-ui.html` | OpenAPI spec + interactive UI |
