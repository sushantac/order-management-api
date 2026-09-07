# 09 — DevOps: Docker, Kubernetes, GitOps & CI/CD

## Containers
- Image = immutable artifact; container = running instance.
- **Multi-stage Dockerfile**: build stage (Maven) → runtime stage (slim JRE,
  non-root user). Small, no toolchain in prod, no source in the image.
- compose runs the whole stack for dev (postgres, redis, kafka, jaeger, api) with
  health-gated `depends_on`.

## Kubernetes cheat
| Object | What it does |
|---|---|
| Pod | smallest unit; container(s) + IP |
| Deployment | desired state, replicas, rolling updates |
| Service | stable DNS/IP in front of pods |
| ConfigMap | non-secret config |
| Secret / SealedSecret | secret config; sealed = encrypted in git |
| HPA | scale replicas by CPU/custom metric |
| Ingress | external HTTP + TLS (cert-manager) |

### Probes - memorize the difference
- **startup**: is the process up yet? (protect slow cold starts from kill)
- **readiness**: can it take traffic? (remove pod from Service when false)
- **liveness**: is it wedged? (restart when false)
Use **separate endpoints** - a busy-but-healthy pod must not be restarted.
Graceful shutdown: stop new work, drain in-flight (timeout configured).

## GitOps & CI/CD - the interview narrative
- **CI**: every PR runs the full test suite (GitHub Actions + Testcontainers).
- **GitOps**: desired state lives in git; ArgoCD watches env branches and converges
  the cluster. Deploy = merge. Rollback = revert. Audit = git history.
- **Promotion**: env = branch + Kustomize overlay (dev→test→uat→staging→prod);
  a promotion bumps the image tag in the overlay and merges to the env branch.
- **Kustomize**: base + overlays (per-env replicas/image tags/profile) - no templating
  language, plain YAML.
- **Secrets**: never plaintext in git. `kubeseal` → SealedSecret (only the cluster
  controller can decrypt it).
- **Progressive delivery**: promote one stage at a time, watch SLOs, feature flags
  turn capabilities on gradually/dark.

## Feature flags
Code ships disabled; `app.features.*` per environment flips it on - no redeploy,
instant rollback, canary-friendly. Expose current state (`/api/v1/features`) so ops
and tests can see what is on.

## Tell me about...
**"How do you ship a change to prod safely?"** → "CI runs the full suite on every
PR. Merge to develop; promotion bumps the overlay image tag and opens an env PR;
ArgoCD syncs each environment from git. Secrets are SealedSecrets, so nothing
plaintext is committed. We promote stage-by-stage watching SLOs, and rollback is a
revert commit that ArgoCD converges automatically."

## Rapid Q&A
- Docker vs K8s? → Docker packages/runs containers; K8s schedules/orchestrates them.
- Why Kustomize not Helm? → plain YAML, no server-side templating; Helm wins for
  packaging/dependency-heavy charts.
- Liveness failing because of a slow DB? → wrong endpoint: readiness depends on DB,
  liveness does not.
- HPA on what? → CPU utilization is the classic start; custom metrics (queue depth,
  RPS) later.
- Blue/green vs canary? → both are progressive delivery; canary = % traffic,
  observe SLOs, ramp.
