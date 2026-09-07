# 02 — Weaknesses: what is bad / risky / would not ship as-is

Honest list. Items are grouped by area with a severity and, where relevant, the
file/behaviour to check. "Bad" here = would need to change before real
production use, or is an actual defect/inconsistency today.

## A. Authorization & access control are inconsistent (HIGH)
- Only a few endpoints have method guards. Writes on products, categories,
  customers, bulk orders, PATCH and DELETE orders are "any authenticated
  caller" - while single order create needs `SCOPE_order_write`. The guard
  matrix in `docs/business/05` documents this, but the *design* is
  inconsistent: it should be write scope on every mutation, or deliberately
  documented otherwise.
- There is **no ownership model**: any authenticated caller can read *all*
  orders/customers (`GET /api/v1/orders` lists everything). Single-tenant is
  fine for learning but must be a stated boundary, and multi-tenant queries
  would need ownership filters everywhere.
- `CustomerController`'s delete relies on catching an exception and
  string-matching the message (`"cannot be removed"`) - brittle and wrong
  (see D).

## B. Error semantics are partially broken (HIGH, observed)
- The catch-all `@ExceptionHandler(Exception.class)` maps *everything* else to
  500 `INTERNAL_ERROR`. We hit it directly: hitting an unmapped actuator path
  (`/actuator/prometheus` when the endpoint wasn't exposed) produced a **500**
  instead of a 404, because `NoResourceFoundException` fell into the catch-all.
  A global handler must be a last resort for genuinely unexpected errors, and
  framework/4xx semantics (404, 405, 415) must be preserved.

## C. Idempotency & retry coverage is partial (MEDIUM)
- Idempotency is implemented only for single order create. Bulk orders, product/
  customer writes and the PATCH/DELETE paths are not idempotency-protected.
- Stored idempotency records have no TTL/cleanup - the table grows forever.
- Payments: retries are safe *because* the whole order is one transaction, but
  there is no refund/cancellation flow and no payment retry idempotency separate
  from order idempotency.

## D. Layering drift: domain depends on the API (MEDIUM, by design, still wrong)
- `ProductCatalogueService` (domain) returns `ProductResponse` (api.dto);
  `OrderService` (domain) imports `messaging.OrderPlacedMessage`; repository/
  service and DTO concerns are mixed in the domain package.
- This is *pragmatic for a small learning repo*, but in a real codebase it makes
  the domain untestable without the web layer and couples persistence decisions
  to HTTP shapes. Proper fix: application/port or read-model services returning
  domain types with mapping at the edge.

## E. Business rule enforcement is thin (MEDIUM)
- Order status PATCH allows arbitrary transitions (`PLACED`→`DELIVERED`,
  `PLACED`→`PLACED`, ...). One invariant (SHIPPED needs a shipping address)
  exists as a `@PreUpdate` callback; the rest are unguarded.
- No cancellation with stock return, no delivered/cancelled event publishing,
  no per-status business validation beyond that single callback.
- Customer delete rule depends on a `@PreRemove` message string (see A).

## F. Simulated/single-node production stand-ins (MEDIUM)
- Payment gateway: simulated, deterministic only in tests. Fine - the seam is
  clean - but "PCI-DSS" is really "no payment data exists".
- JWT: HS256 shared secret by default (learning default); prod path (JWKS/RS256)
  is documented but not implemented/verified.
- Static API key in config; no key rotation, hashing or scoped keys.
- Rate limiter buckets are **in-memory per process** (Resilience4j default) -
  two instances each allow 1000/min. Real quotas need a shared/Redis limiter.
- Kafka: single partition/1 replica; no Schema Registry; consumer group
  reference only; no producer idempotence config on the outbox path.

## G. Outbox / consumer edge cases (MEDIUM)
- A permanently failing outbox row retries forever with an attempt counter but
  no backoff, dead-letter or alerting - a stuck payload grows the PENDING queue.
- Consumer dedupe/idempotency is *advised* but not demonstrated on the consumer
  side (the sample consumer only counts).
- Publishing duplicates after crash-between-send-and-mark are possible
  (at-least-once acknowledged), acceptable, but there is no local dedupe test.

## H. Observability depth is shallow (LOW-MEDIUM)
- `@Timed` only on two methods; no HTTP metrics assertions; no dashboards wired;
  SLO/prometheus rules are files that were never run through promtool.
- Traces enabled only under prod profile; nothing verifies spans are emitted.
- Redis cache metrics read server INFO on every scrape (works at this scale,
  not at high scrape volume).

## I. Infrastructure artifacts are written but never executed (HIGH trust risk)
- The multi-stage Docker image was never built in CI; compose was never
  `docker compose up`'d in CI; the `api` compose healthcheck assumes `wget`
  exists in the image.
- K8s manifests/Kustomize overlays and ArgoCD example were only YAML-parse
  checked, never applied to a cluster.
- GitHub Actions workflows exist but no pipeline run was ever observed/validated
  end-to-end (branches were merged directly; actions may not even be enabled in
  the repo).
- `scripts/k6-load-test.js` was never actually run, so the "thresholds" are
  aspirations, not measurements.

## J. Process slips (LOW, honest admission)
- During the final stretch I twice committed feature work directly onto
  `develop` (caught and repaired both times). A branch guard would have made
  this impossible - see part 03.
- Single-agent development means no human code review; CI was declared late and
  never enforced (no required checks / branch protection used in practice).

## K. Test suite cost & smell (LOW-MEDIUM)
- Many `@SpringBootTest` contexts + several `Thread.sleep(...)`-based timing
  tests (circuit-breaker waits, lease expiry) - the full suite takes several
  minutes and has mild timing sensitivity. No parallelism configured.
- Some assertions are deliberately loose (`>=1` counters) where exact counts
  would be better (but harder).

## L. Minor correctness/documentation smells
- `application-dev.yml` uses `ddl-auto: update`, contradicting the "Liquibase
  owns the schema" rule if anyone runs dev against a shared DB.
- Bulk order create returns 201 without a guard consistent with single create.
- The deprecated `/customers/legacy` alias is unguarded while `/customers` list
  needs a scope.
- Docs are numerous and excellent, but some (business/ops) describe intended
  behaviour more confidently than it is verified (e.g. "whole stack in CI").

---

*Severity guide: HIGH = would bite real users or is an observed defect;
MEDIUM = correct for learning, wrong at production scale; LOW = polish/trust.
Part 03 turns each item into an action.*

