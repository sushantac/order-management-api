# 10 — Utility & Enterprise Features

## Feature flags

`GET /api/v1/features` reports which optional capabilities are switched on in
the current environment:
```json
{ "csvExport": true, "reporting": false }
```
Flags are configured per environment under `app.features.*` (e.g.
`app.features.csv-export: true`). Today `csvExport` gates the product CSV export;
flags are the mechanism used to ship a capability "dark" and enable it gradually
without redeploying.

## Internationalization (i18n)

User-visible/API messages can be localized via message keys.

`GET /api/v1/messages/{key}?arg=<value>` with an `Accept-Language` header:
```bash
curl -H "Accept-Language: de" \
  "http://localhost:8080/api/v1/messages/order.created?arg=ORD-123"
# -> { "key": "order.created", "locale": "de",
#      "message": "Bestellung ORD-123 wurde aufgegeben." }
```
Bundles shipped: English (default) and German (`i18n/messages*.properties`);
adding a language = adding a properties file. The repo also demonstrates this
endpoint with `greeting.hello`.

## Interactive API documentation

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Raw OpenAPI spec: `http://localhost:8080/v3/api-docs`
- Both are public and declare the `bearer-jwt` and `api-key` security schemes,
  so integrators can authenticate from the UI.

## Enterprise design (documented; partially implemented)

Full design notes live in `docs/enterprise/enterprise.md`. Summary of what the
product assumes for enterprise use:

- **Multi-tenancy**: chosen default = shared schema + discriminator column with
  row-level filtering (cheapest), with schema-per-tenant as the high-isolation
  upgrade path. Not yet wired into endpoints.
- **Developer portal / API-key self-service**: integrators would request scoped
  keys through a portal; the API already enforces per-key rate limits and scopes
  (see `05-authentication...`).
- **Scheduled & background work**: scheduling infrastructure exists
  (`@EnableScheduling`); production jobs (e.g. nightly exports/backups) would use
  it, adding Quartz only when cron guarantees are required.
- **Database backups**: recommended WAL-based backups + point-in-time recovery,
  encrypted at rest, with periodic restore drills before production promotion.

## Other utilities

- **Product CSV export** (flagged): `GET /api/v1/products/export.csv`
  (see `02-catalogue...`).
- **Postman collection** and **OpenAPI snapshot** ship in `docs/postman/` and
  `docs/api/` for consumer onboarding.
- **k6 load-test script** for the read-heavy catalogue endpoint:
  `scripts/k6-load-test.js`.

## Local environment matrix

| Profile | Caching | Kafka publishing | JSON logs | Tracing |
|---|---|---|---|---|
| default (no profile) | in-memory | off | off (human logs) | off |
| dev | Redis (compose) | on (compose) | off | off |
| prod | Redis | on | **on** | on (OTLP/Jaeger) |

Everything is runnable with `docker compose up -d` (postgres, redis, kafka,
jaeger) - see the compose file for service names and ports.
