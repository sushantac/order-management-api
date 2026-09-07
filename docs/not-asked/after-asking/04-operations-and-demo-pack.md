> **Requested artefact** - produced as an explicitly requested deliverable
> ("consider I asked for all these"). Canonical home: `docs/not-asked/after-asking/`.

# 04 — Operations & Demo Pack

## Runbook A — Local + dev deploy
1. `docker compose up -d --build` (postgres, redis, kafka, jaeger, api).
2. Health: `curl localhost:8080/actuator/health/readiness` → UP.
3. Dev profile notes: Redis/Kafka enabled; `ddl-auto: update` is fine on a
   throwaway DB only - prefer Liquibase for anything shared.

## Runbook B — Promotion (dev → test → uat → staging → prod)
1. Merge feature PR to `develop` (CI green).
2. Lower envs: `./scripts/promote.sh develop test` then manual UAT/staging.
3. Prod path goes via `main`; promotion is owner-gated (never automatic).
4. After each promotion: check readiness probe, one smoke order, SLO alerts.

## Runbook C — Incident response
Severity flow:
1. **Detect**: alert from `docs/monitoring/prometheus/alerts.yml` (burn rules)
   or health probes; grab `X-Correlation-Id` from the customer/ticket.
2. **Triage**: health/liveness? readiness? DB? Redis? Kafka? breaker open?
   - 5xx rising with gateway: check breaker metrics → provider incident.
   - Orders failing with 502: gateway path (retries already applied).
   - Errors on write with 409: contention or validation - not an outage.
3. **Mitigate**: feature flag off → rollback commit (GitOps revert) → scale
   (HPA) → circuit/rate-limit knobs (config, no redeploy where possible).
4. **Communicate**: status + ETA + correlation id.
5. **Post-incident**: record in `docs/not-asked/evidence/incidents/<date>.md`
   with the journal template from `docs/others/04`.

## Runbook D — Rollback
- Code/behaviour: revert the commit; ArgoCD converges; verify readiness.
- Feature-flag rollback: flip `app.features.*` (no deploy).
- Schema: forward migration only + new changeset; document how to restore
  from backup if a migration must be undone (never edit a run changeset).
- Data: restore from PITR (below).

## Runbook E — Backup & restore (design; execute in a real env)
- **Backups**: nightly pg_basebackup/WAL archiving (pgBackRest or
  `pg_dump` for small scale); encrypt at rest; retain N days.
- **Restore drill**: quarterly in test: restore latest + replay WAL to a point
  in time; verify data + Liquibase checksums.
- **App state**: Redis is a cache (rebuilt on miss) - no backup needed; Kafka
  outbox makes event replay possible; confirm consumers tolerate replay.

## Runbook F — 10-minute guided interview demo
1. **Start the story (30s)**: scope + one-concept-per-PR + real-infra tests.
2. **Show the order flow (2 min)**: place an order (idempotency header), show
   201 + Location + ETag; repeat the same call → replay; GET product shows
   stock went down (eviction works).
3. **Show the failure mode (2 min)**: pause the Kafka/stop broker and place an
   order → it still succeeds; start it, show the outbox published it later.
4. **Show PII (1 min)**: GET customer with `order_read` (masked) vs API key
   (raw); grep logs → no raw email.
5. **Show resilience (2 min)**: chaos: slow gateway → watch breaker open →
   next order fails fast; recovery probe.
6. **Show ops (2 min)**: `/actuator/health/liveness|readiness`,
   `/actuator/prometheus`, a Jaeger trace, and the kustomize overlays.
7. **Close (30s)**: point to evidence folder + the honest "known limitations"
   section (docs/good-and-bad) - honesty lands better than polish.

Practice it 3 times on a clean environment; time-box each section.
