# 06 — Closing the Loop: Execute Everything, Then Finish

The first run's biggest gap: artifacts existed on paper but were never
exercised. This closure plan makes "executed at least once with evidence" a
hard gate before the journey is declared done.

## 1. CI runs for real
- [ ] Push a feature branch and let the GitHub Actions workflow run to
      completion; record the run URL in RUN-LOG.
- [ ] Ensure the workflow includes: full test suite, docker build, kustomize
      build, yamllint, promtool rule check, OpenAPI snapshot diff.
- [ ] Enable branch protection on `develop` with the CI job as a required check.

## 2. The Docker image actually works
- [ ] `docker build -t order-api:local .` succeeds.
- [ ] `docker compose up -d --build` starts ALL services.
- [ ] `curl localhost:8080/actuator/health/liveness` returns UP from the
      container; the compose `api` healthcheck passes with the real image
      (verify the probe command exists in the base image).
- [ ] Place an order against the composed stack (curl) and watch it reach
      Kafka (`kafka-console-consumer`) and Redis cache gauges.

## 3. Kubernetes manifests applied somewhere real
- [ ] `kustomize build k8s/overlays/dev` and every other overlay succeed.
- [ ] Apply dev overlay to a real cluster (minikube/kind) and verify pods
      become Ready (startup/liveness/readiness).
- [ ] Verify the SealedSecret decrypt flow in that cluster (kubeseal →
      controller → Secret) or explicitly document why it is deferred.

## 4. Load test has real numbers
- [ ] Run `k6 run scripts/k6-load-test.js` against the composed stack.
- [ ] Record p95 latency, error rate, and RPS in RUN-LOG; tune thresholds to
      reality (or say they are targets, not results).

## 5. Observability verified, not just configured
- [ ] Scrape `/actuator/prometheus` and confirm order/product/HTTP metrics.
- [ ] Trigger a trace (prod profile + Jaeger in compose) and SEE a span in the
      Jaeger UI (screenshot/URL recorded).
- [ ] `promtool check rules docs/monitoring/prometheus/alerts.yml` clean.

## 6. Security & privacy spot-checks on the running app
- [ ] Walk the full scope matrix against the running app (positive + negative).
- [ ] Demonstrate PII masking + log redaction on a live request.
- [ ] Run GDPR erasure on a customer with orders → ANONYMIZED; confirm the
      audit row and that order history remains unlinked.
- [ ] Verify rate-limit 429 headers with a tiny window override.

## 7. Retrospective from data
- [ ] Complete RUN-LOG with per-PR: test counts, elapsed, incidents, CI links.
- [ ] Compare against this repo's `docs/good-and-bad/` - mark every weakness
      either fixed or explicitly accepted (with reason).
- [ ] Write the new retrospective into the new repo's docs.

## 8. Final "done" definition
The journey is done when ALL of the above boxes have evidence AND 35/35 PRs
merged with green full suites AND zero direct-to-develop feature commits. Only
then update the README status to "journey complete".

---

*Prepared from the lessons of the first run. The blueprint is the plan; the
RUN-LOG is the proof.*
