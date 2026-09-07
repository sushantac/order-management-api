> **Requested artefact** - produced as an explicitly requested deliverable
> ("consider I asked for all these"). Canonical home: `docs/not-asked/after-asking/`.

# 01 — Evidence & Measurement Pack

Goal: every claim about this project that can be measured, **is** measured.
For each area: tool, command, what to record, where to record it.

## 1. Test coverage (JaCoCo)
Add `jacoco-maven-plugin` (report goal bound to `verify`). Baseline: `mvn verify`.
Record in a `docs/not-asked/evidence/coverage-summary.md`:
- Overall line + branch coverage.
- Coverage by package: `api` (controllers/DTOs), `domain.service`,
  `domain` (entities), `security`, `messaging`, `config`.
- Missing-class list = the "untested corners" review list.

Targets: overall line ≥ 80% initially, with *no security/messaging class below
70%*. Do NOT gate CI on coverage until you have looked at *what* is uncovered -
an arbitrary gate just teaches people to write low-value tests.

## 2. Static analysis
Add tooling to CI (not necessarily to the build gate immediately):
- **SpotBugs** (`spotbugs-maven-plugin`) + `spotbugs:spotbugs` → HTML report.
- **PMD** (`pmd:pmd`) for cyclomatic complexity + copy-paste detection (CPD).
- **Checkstyle** for conventions if the team adopts one config.
- **ArchUnit** (already planned) for the layering rules.
Record findings as a triaged list (critical / should-fix / info) in
`evidence/static-analysis.md`. Re-run every 2 PRs.

## 3. Load & performance (k6)
Run against the composed stack:
```bash
docker compose up -d --build
k6 run scripts/k6-load-test.js
```
Record in `evidence/load-test.md` per run (date, env, VUs, duration):
- p50 / p95 / p99 latency for the catalogue endpoint.
- error rate (must be < 1% for the SLO).
- achieved RPS at p95 < 300 ms (target) - or the honest number you got.
Add a second scenario later: concurrent **order placement** with idempotency
keys to prove the write path under load (measure 409/502 rate, rollback
correctness via stock count after the run).

## 4. Per-PR changelog (keep in the repo)
`CHANGELOG.md` - one entry per learning PR, machine-readable style:
```markdown
## [2026-09-07] PR #28 - Caching (Redis)
- feat: cache-aside on ProductCatalogueService (DTOs, TTL 10m)
- feat: stock-writer eviction (orders + locking services)
- test: CachingRedisIntegrationTest (real Redis)
```
Keep it updated in the same commit as each merge so the changelog is always
true. This becomes instant "tell me what you built" evidence.

## 5. Test-suite time & flakiness tracking
Record full-suite duration per merge in the changelog or `RUN-LOG`; track any
`@Disabled`/flaky test with a date + issue. Trend = operability signal.

## 6. Repository health file (single source for metrics)
`evidence/README.md` index pointing at: coverage summary, static analysis,
load test, changelog, CI run links, image build log, kustomize output, k8s
apply log, Jaeger screenshot, promtool output. One place to *prove* the
project - present it as the "evidence folder" in interviews.

## What NOT to measure yet
Do not run load tests against the default (in-memory cache, single instance)
profile and quote it as production performance. Label every number with its
environment and date.
