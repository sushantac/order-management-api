# 05 — Closure Execution Plan (prove it, then present it)

This is the adaptation of `docs/next-time/06` for the **current** repo: it is
the "execute everything once" checklist that removes the biggest credibility
gap found in the retrospective (artifacts written but never run). Do these in
order; record evidence under `docs/not-asked/evidence/`.

## Step 1 — CI runs for real
- [ ] Push a branch and let `.github/workflows/ci.yml` run to completion
      (full Testcontainers suite, JDK 21).
- [ ] Record: run URL + pass/fail + duration. If Actions is disabled on the
      repo, record that as an environment fact and run the same suite locally.
- [ ] Add the quality steps from `01` (jacoco/spotbugs/pmd) and from
      `docs/next-time/06` (docker build, kustomize build, yamllint, promtool).

## Step 2 — Image & compose smoke
- [ ] `docker build -t order-api:local .` (record output tail).
- [ ] `docker compose up -d --build` then
      `curl localhost:8080/actuator/health/liveness` → UP.
- [ ] Place an order against the composed stack; verify stock, outbox PUBLISHED,
      and one Kafka event consumed.
- [ ] Verify the compose `api` healthcheck command exists in the runtime image
      (or fix the Dockerfile/compose).

## Step 3 — Kubernetes applied once
- [ ] `kustomize build k8s/overlays/<each>` all succeed (record).
- [ ] If a local cluster is available (kind/minikube): apply dev overlay,
      wait for Ready (probes), scale HPA, curl through the ingress/service.
      Otherwise record the exact command that must be run and mark as
      environment-deferred (do NOT mark it done).

## Step 4 — Real load numbers
- [ ] `docker compose up -d --build` + `k6 run scripts/k6-load-test.js`.
- [ ] Record p50/p95/p99, error rate, RPS into `evidence/load-test.md`.

## Step 5 — Observability verified
- [ ] `curl localhost:8080/actuator/prometheus | grep order_place_seconds_count`
- [ ] Prod-profile JSON log smoke (one request → one JSON log line).
- [ ] Jaeger/OTLP: enable tracing locally, make a request, confirm a span in
      Jaeger UI (screenshot → evidence).

## Step 6 — Security spot-checks against the live app
- [ ] Walk scope matrix positive/negative on a few representative endpoints.
- [ ] Unknown URL → 404 (not 500); 401 vs 403 correct.
- [ ] GDPR: erasure of a customer with orders → ANONYMIZED + audit row.
- [ ] Rate-limit 429 with a tiny window override.
- [ ] Fill `evidence/security-review-<date>.md` from `02` Part C.

## Evidence log (create `docs/not-asked/evidence/README.md`)
Keep an index row per artifact:
| Artifact | Location/URL | Date | Result | Notes |
|---|---|---|---|---|
| CI run | | | | |
| Docker build | | | | |
| Compose smoke | | | | |
| kustomize build | | | | |
| k8s apply | | | | |
| k6 run | | | | |
| Jaeger trace | | | | |
| promtool | | | | |
| Security review | | | | |

## Definition of "can say it out loud"
Only after Steps 1-6 have evidence may you say: *"the project runs, is tested,
is measured, and its security story is reviewed."* Until then, the accurate
sentence is: *"the design and tests are complete; execution evidence is in
progress."* Both are fine to say - one is just more powerful.
