# 03 — What could have been done better

This part is about *process and design choices* (not just code smells): what I
would do differently on a re-run, and a prioritized roadmap for turning the
current codebase into something shippable.

## 1. Define the architecture contract before code, then enforce it

Done late → drift happened anyway:
- Decide layers up front: `domain` (pure), `application` (use-cases/ports),
  `infrastructure` (persistence/http/messaging), `api` (DTO mapping at the
  edge). Make "domain never imports api.dto / messaging" a reviewed rule.
  Today `ProductCatalogueService` returns API DTOs and `OrderService` imports
  the Kafka message type - pragmatic, but it couples the core to the shell.
- Introduce an **ADR log** (`docs/adr/`) the moment a decision repeats (cache
  strategy, outbox, PII masking, scope model). Retro-writing README sections is
  good; ADRs written at decision time are better.
- Do a **dependency-rule test** (ArchUnit) instead of eyeballing imports.

## 2. Security: design the whole matrix once, implement from day one
- Write the endpoint→scope matrix *before* building controllers (this now
  exists post-hoc in `docs/business/05`). It exposes the current
  inconsistencies immediately: bulk orders, products/categories writes and
  order PATCH/DELETE are "any authenticated", while single create needs
  `order_write`.
- Decide the ownership story up front (even "single-tenant for now, enforced
  by one tenant column + filter from day one" would have made later
  multi-tenancy additive instead of a rewrite).

## 3. Fix error semantics properly (observed defect)
- Make the catch-all handler genuinely last-resort: preserve framework
  semantics for `NoResourceFoundException` (404), `HttpRequestMethodNotSupported`
  (405), `HttpMediaTypeNotSupported` (415), and map security denials (401/403)
  explicitly (done) - and add a regression test that an unknown URL returns
  404, not 500.

## 4. Apply cross-cutting concerns uniformly, not to one showcase path
- Idempotency keys: implement once (filter/service) and apply to every unsafe
  write - bulk orders included - with a TTL/cleanup job for stored keys.
- Method guards: single annotation pattern reused across all mutations.
- Business rules for state changes: an explicit transition map
  (`PLACED→CONFIRMED→SHIPPED→DELIVERED`, `→CANCELLED`) with rejection of
  illegal transitions and tests, instead of one `@PreUpdate` string check.

## 5. Reserve production-grade choices for the things that matter
The repo proudly keeps teaching simplifications. That is fine *if labelled*;
better would be:
- JWKS/RS256 JWT validation wired behind a profile switch (not just a comment).
- API keys hashed at rest with rotation, or better: switch machine auth to
  mTLS/short-lived tokens where possible.
- Rate limiting on a shared store (Redis + fixed-window) so N instances enforce
  the same quota.
- A real (sandbox) payment provider behind the gateway interface for an
  end-to-end contract, or explicitly rename "payment" to "charge simulation"
  everywhere so nobody mistakes it for a payment system.

## 6. Close the loop on every artifact that claims to work
Biggest trust gap of the project: lots of ops/enterprise artifacts were
*created but never executed*. Do at least one real pass on each:
- CI: actually run the GitHub Actions workflow on a branch (it may not even be
  enabled in the repo) and add a `docker build` + `docker compose up` smoke
  step, plus YAML/lint steps (yamllint, promtool check rules, kustomize build).
- Image: build it, run it against compose services, fix the healthcheck command
  for the actual base image.
- K8s: `kubectl apply -k` against a real/minikube cluster once; then the
  manifests earn their place.
- k6: run it and record real numbers; delete the script or keep honest
  thresholds.

## 7. Test engineering upgrades
- Replace `Thread.sleep`-timed assertions where possible with `Awaitility`-style
  polling (still have patience windows, but shorter and less flaky).
- Make resilience tests independent of wall-clock by exposing test hooks
  (e.g. a Clock/state knob) instead of sleeping past wait durations.
- Consider JUnit parallelism for the many isolated Spring contexts (each already
  has its own DB tag - the design is parallel-ready).

## 8. Process: protect the branch model
- Two direct-to-`develop` commits happened late in the journey. Add, at minimum:
  a pre-push hook or a small script that refuses `git push origin develop`
  unless the current branch is `develop` *and* the change is a merge/approved
  doc commit; better: required branch protection + CI checks on the remote.
- Use conventional commits + a changelog generator so the 35-PR story is
  machine-readable.
- Get a real human review on at least the security and migration PRs - they are
  where single-agent blind spots live.

## 9. Sharpen the "shippable" story if you demo this
Before presenting the repo as production-grade:
- Add a liveness/readiness/startup test in an HTTP test.
- Prove an illegal state transition is rejected (with the transition map).
- Add one real negative auth test per unguarded endpoint, or guard them.
- Add ownership/tenant awareness or a README boundary stating single-tenancy.
- Add retention/cleanup for idempotency keys and outbox dead rows (alerting on
  PENDING age).

## 10. What I would keep (don't change what worked)
- The one-concept-per-PR cadence and per-PR README lessons.
- Real-infrastructure tests with per-class DB isolation.
- The "write the *why* in comments" style.
- Schema-truth tests (they catch the expensive mistakes).
- Composed resilience with verified breaker behaviour.
- The outbox + manual-ack + DLT pattern as the reference for reliable events.

---

## Prioritized fix roadmap

| Priority | Action | Effort |
|---|---|---|
| P0 | Fix catch-all error handler (404/405/415 + regression test) | S |
| P0 | Uniform endpoint guards (write scope on all mutations) | S |
| P0 | Guard or explicitly authorize bulk/legacy/PATCH/DELETE paths | S |
| P1 | Run CI + docker build + compose smoke for real | M |
| P1 | Idempotency across writes + key TTL cleanup | M |
| P1 | State-transition map with tests | M |
| P1 | Hash/rotate API keys; shared (Redis) rate limit | M |
| P2 | ArchUnit dependency rules; ADR log | S |
| P2 | Outbox dead-row handling + alerting; consumer dedupe demo | M |
| P2 | Awaitility / parallel JUnit test upgrades | M |
| P2 | Real k6 run + promtool check + kustomize build in CI | S |
| P3 | Ownership/tenant column design (ADR first) | L |
| P3 | JWKS/RS256 profile + sandbox PSP integration | L |

*(S = hours, M = days, L = weeks)*

## Closing thought
The honest scorecard: this is an **A for learning and breadth**, a **B for code
quality with real production gaps**, and a **C+ for "everything actually runs"**
until the un-executed infrastructure artifacts (CI, image, k8s, load test) are
exercised. The weaknesses are almost all *fixable and known* - which is exactly
what a retrospective is for. The next re-run would start from this list.


