# 08 — Deployment, Operations & GitOps

## 8.1 Containerisation
- Multi-stage `Dockerfile`: Maven build stage → slim JRE 21 runtime, non-root
  user, no toolchain. Heap bounded via `-XX:MaxRAMPercentage=75`.
- `docker-compose.yml` runs the full local stack: postgres, redis, kafka
  (KRaft), jaeger and the `api` service with health-gated `depends_on`.
- Healthcheck: `GET /actuator/health/liveness` from the container.

## 8.2 Kubernetes design
| Object | Design |
|---|---|
| Deployment | replicas per overlay (2 base; env-tuned), resource requests/limits, non-root securityContext, terminationGracePeriod 25s |
| Probes | startup + liveness + readiness on distinct actuator endpoints; readiness gates traffic |
| Service | ClusterIP port 80 → 8080 |
| ConfigMap | non-secret config (profiles, hostnames) per env |
| Secret | SealedSecret (encrypted in git; controller decrypts) - plaintext sample only |
| HPA | CPU utilization 70%, 2–10 replicas |
| Ingress | nginx + cert-manager TLS annotation |

Graceful shutdown: `server.shutdown: graceful` + 20s drain timeout.

## 8.3 Environment & promotion model (GitOps)
| Branch | Overlay | Purpose |
|---|---|---|
| develop | — (CI) | integration; source of promotion |
| test / uat / staging | overlays/test|uat|staging | staged verification (dev profile lower envs; prod profile staging) |
| main / prod | overlays/prod | production (prod profile: Redis, Kafka, JSON logs, tracing) |

Promotion = commit bumping the overlay image tag → ArgoCD (model) syncs env
branches. Rollback = revert commit (convergence). Promotion is gated by the
user/ops owner - never automatic past test.

## 8.4 CI/CD
- GitHub Actions CI: full Testcontainers suite on PRs to develop; JDK 21,
  Maven cache.
- Promotion workflow: manual dispatch bumps image tag + opens env PR.
- Forward plan (must execute - see §9): CI job for docker build + compose
  smoke + kustomize build + yamllint + promtool; enforce required status checks.

## 8.5 Operations runbooks & artefacts
- Health/probes and metrics endpoints; correlation id flow for ticket triage.
- Feature flags (`app.features.*`) for dark launch / instant rollback.
- Environment variable overrides for host/port/secret wiring
  (`DB_HOST`, `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, ...).
- Secrets: Sealed Secrets flow documented in `docs/k8s/deploy.md`.
- Promotion helper: `scripts/promote.sh` (env ladder with guards).
- Load/verification: `scripts/k6-load-test.js`.

## 8.6 Known operational gaps (tracked)
- CI workflow declared but not yet executed end-to-end; image/K8s/k6 artefacts
  written but not yet run against a real stack - tracked in
  `docs/next-time/06` and `docs/good-and-bad/02` §I with an execution plan.
- ddl-auto: update in dev profile is a hazard if dev DBs are shared (use
  validate + migrations even in dev for anything long-lived).
