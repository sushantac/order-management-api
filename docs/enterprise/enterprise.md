# Enterprise features - design notes (PR #35)

This learning repo implements the *practicable* slice and documents the rest.

## Implemented here
- **Internationalization (i18n)**: `i18n/messages*.properties` + `MessageSource`;
  the `/api/v1/messages/{key}` endpoint resolves by `Accept-Language`.
- **CSV export** endpoint (flag-gated by `FeatureFlags.csvExport`):
  `GET /api/v1/products/export.csv`.
- **Feature flags** (PR #34) ride along into this slice.

## Designed / documented
- **Multi-tenancy**: schema-per-tenant (strongest isolation, more infra) vs a
  discriminator column (shared schema, simpler). Chosen default for this product:
  discriminator column + RLS-style WHERE on every query via a `TenantContext`
  filter - with schema-per-tenant as the upgrade path for enterprise tiers.
- **Developer portal / API-key self-service**: the auth server issues scoped keys;
  the portal (out of repo) calls the same `X-API-Key` + scope model and rate
  limiter buckets (PR #26/#29) that the API already enforces.
- **Scheduled jobs**: scheduling is already enabled (`@EnableScheduling`, PR #31's
  outbox poller). Production jobs (daily exports, DB backups) use the same
  mechanism with Quartz only when cron-guarantees/failure-recovery are required.
- **Background processing**: virtual-thread executors (PR #30) + an outbox (PR #31)
  are the backbone; a worker queue (e.g. SQS/Redis stream) would extend it.
- **Database backup strategy**: nightly WAL-based backup (pgBackRest) + point-in-
  time recovery; restore drills in test; backups encrypted at rest and tested by
  a restore job before promotion to prod.
