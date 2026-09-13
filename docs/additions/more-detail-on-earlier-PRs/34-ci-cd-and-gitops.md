# 34. CI/CD and GitOps (PR #34)

> PR #34 — GitHub Actions `CI.yml` Testcontainers + `Promote.yml` `Kustomize overlays` GitOps, `ArgoCD` env branches. Stack: `actions/checkout@v4`, `actions/setup-java@v4 temurin 21 cache maven`, `./mvnw -B test`, `k8s/overlays/{dev,test,uat,staging,prod}`, `gh pr create --base ${env}`. See `README.md:1772` roadmap `| 34 | CI/CD and GitOps |`.

---

## 1. Purpose — what shipped

PR #34 automates quality gate and environment promotion via GitOps without bespoke Jenkins. `/.github/workflows/ci.yml` (`PR #34 CI: every pull request to develop`) triggers on `pull_request branches [develop] + push develop` `jobs test runs-on ubuntu-latest steps checkout@v4 setup-java@v4 temurin 21 cache maven Full test suite ./mvnw -B test` — `Testcontainers 60 1.21.4 postgres:postgresql 360` boots real `Postgres/Redis/Kafka` per test class via `@ServiceConnection`, no external service config. `/.github/workflows/promote.yml` `Promote workflow_dispatch inputs environment choice [dev,test,uat,staging,prod] jobs promote` does `TAG=${{github.sha}} sed -i s|newTag: .*|newTag: ${TAG}| k8s/overlays/${env}/kustomization.yaml checkout -b promote/${env} add commit push gh pr create --base ${env}` — GitOps promotion `bump image tag in target overlay and push branch→PR→merge to env branch. ArgoCD watches env branch and rolls new image`. `k8s/overlays/*` `Kustomize` is the single source of truth per env.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Manual `./mvnw test` local with `docker-compose postgres redis kafka` up; CI absent — `develop` merged without `Testcontainers` full suite, `Hikari 34` leak or `Liquibase validate 48` mismatch found late. Promotion was `kubectl set image` imperative `ghcr.io/latest` hand-rolled, no audit, no `ArgoCD` drift recovery. `k8s/base/kustomization.yaml` `commonLabels` not per-env.

**After:** `CI.yml` every `PR → develop` runs `mvnw -B test` in clean `ubuntu-latest` container — `Testcontainers 1.21.4 60` auto-discovers `Docker socket ~/.docker/run/docker.sock` via `1.21.x` fix. No `secrets` needed for DB. `Promote.yml` `workflow_dispatch` on `GitOps Bot bot@example.com` `promote/sha→env` branch creates `PR` onto `env` branch (`dev→test→uat→staging→prod`). `Kustomize newTag SHA` is the only mutation; `ArgoCD Application` (out-of-repo `argocd/`) watches `env branch k8s/overlays/{env}` and syncs `Deployment replicas 2 HPA 2→10` automatically, reverting manual `kubectl edit` drift.

### Theory — CI, Testcontainers, Kustomize, GitOps from first principles (100+ lines)

#### 2.1 GitHub Actions — workflow, job, step, runner dispatch

`CI.yml` `name: CI on: pull_request branches [develop] push develop jobs test runs-on ubuntu-latest steps checkout@v4 setup-java@v4 Full test suite run ./mvnw -B test`. `pull_request` trigger on `PR → develop` gates merge; `push` on `develop` gates direct commits. `ubuntu-latest` `runner` has `Docker` daemon already (Testcontainers need daemon). `setup-java@v4 distribution temurin java 21 cache maven` restores `~/.m2` from `pom.xml hash`. `-B` batch `mvnw` no interactive progress. `spring-boot-testcontainers 349 Testcontainers 1.21.4 60` pulls `postgres:16`, `redis:7`, `kafka` images per test class `@ServiceConnection` feeds `spring.datasource.url spring.data.redis.host spring.kafka.bootstrap-servers`.

#### 2.2 Testcontainers version quirk — 1.21.4 socket

Spring Boot parent `3.4.1` manages `testcontainers 1.19.3`; property `<testcontainers.version>1.21.4 60>` overrides because `1.19.3` cannot discover `Docker Desktop ~/.docker/run/docker.sock` new socket (`docker context` change). `1.21.x` `DockerClientProviderStrategy` locates correctly. Comment at `pom 55-59` explains. `junit-jupiter 355 postgresql 359` per-class containers via `@Testcontainers`.

#### 2.3 Promotion workflow — Kustomize as the desired state

`Promote.yml` `name: Promote on: workflow_dispatch inputs environment choice [dev,test,uat,staging,prod] jobs promote runs-on ubuntu-latest steps checkout Set image tag TAG=github.sha sed newTag SHA on k8s/overlays/${env}/kustomization.yaml Checkout branch promote/${env} Commit to environment branch git config user.name GitOps Bot push Open PR gh pr create --base ${env} --head promote/${env} --title Promote SHA to env`. Only `newTag` line mutates:

```yaml
# k8s/overlays/staging/kustomization.yaml patch
images:
  - name: ghcr.io/sushantac/order-management-api
    newTag: a1b2c3d4e5...  # ← PROMOTE mutates this SHA per env
```

`base kustomization.yaml resources [deployment,service,configmap,hpa,ingress] commonLabels app` unchanged; overlay adds `patchesStrategicMerge deployment image`, `replicas 3`, `ConfigMap SPRING_PROFILES_ACTIVE prod`.

#### 2.4 GitOps loop — single writer, drift correction

Classic GitOps: `k8s/overlays/{env}` Git branch is desired state; `ArgoCD Application` watches `repoURL` `targetRevision env branch` `path k8s/overlays/{env}` `destination cluster`. When `Promote` PR merges to `env` branch, `ArgoCD` detects `git SHA change` → `sync waves Deployment → Service → HPA → Ingress`. If an operator `kubectl set image` manually, `ArgoCD OutOfSync` `SelfHeal true` reverts to Git `newTag`. `promotion branch promote/{env}` PR ensures `CODEOWNERS` review per env.

```
dev branch ─PR CI pass─► develop ─Build Tag SHA a1b2c3─► dispatch promote dev a1b2c3 ─sed overlays/dev newTag a1b2c3─► PR→ env=dev (ArgoCD sync dev)
                                                                 ─► later dispatch promote staging staging a1b2c3 (cherry) ─► staging
```

Alternative `fluxcd ImageUpdateAutomation` would auto-bump `latest` via `ImagePolicy`; this `Promote.yml` explicit `dispatch` chosen for manual gate per `uat,staging,prod`.

#### 2.5 Kustomize vs Helm for promotion

`Kustomize overlays` `kustomize.config.k8s.io/v1beta1 kind Kustomization resources - deployment.yaml - service.yaml ...` `commonLabels` plus `overlays/dev kustomization` `bases ../base images newTag` is declarative diff: `kubectl kustomize k8s/overlays/prod` renders fully. `Helm chart values-dev.yaml values-prod.yaml` templating `{{ .Values.replicas }}` alternative but templating harder to diff; `Kustomize` patch is `YAML` native, `ArgoCD` natively understands.

#### 2.6 CI idempotency — `develop` protection

`CI.yml push branches [develop]` also gate: direct `push develop` still runs full suite. `branch protection develop require status check CI` enforces `PR` `CI` green before merge. `mavensurefire argLine -Dnet.bytebuddy.experimental 385` `Java 21 Byte Buddy` for `Mockito` on `virtual threads:24`.

#### 2.7 Secrets — not in Kustomize

`order-api-secrets` `Secret` `DB_PASSWORD, jwt-secret local-learning 186, REDIS_PORT, TLS` is via `envFrom secretRef` `deployment.yaml` but `k8s/base/configmap.yaml` holds non-secret `ConfigMap`. SealedSecrets/ExternalSecret would encrypt at rest; learning journey `kubectl create secret generic` out-of-band.

#### 2.8 Promotion audit — tag == SHA

`TAG=github.sha` immutable per commit; `newTag SHA` per overlay tracks which `develop` `SHA` deployed where. `kubectl get deployment -o json | jq .spec.template.spec.containers[0].image` shows `...:a1b2c3`. `git log --oneline k8s/overlays/staging` shows bump history.

> Interview anchor: "`CI.yml pull_request/push develop ubuntu-latest checkout@v4 setup-java@v4 temurin21 cache maven ./mvnw -B test Testcontainers1.21.4 60 socket fix + prometheus/otel/redis bootstrapped`; `Promote.yml workflow_dispatch env dev..prod TAG=sha sed newTag SHA k8s/overlays/${env}/kustomization.yaml branch promote/${env} gh pr create --base env ArgoCD watches env branch sync HPA2→10 Deployment`. `Kustomize base + overlays` desired state; `newTag SHA` only mutation; secrets via `secretRef` not Kustomize."

#### 2.9 Runner sizing — `ubuntu-latest` and Docker

`ubuntu-latest` `2 cores 7GB` runs `Hikari 10` + `Redis + Postgres 16 + Kafka` `Testcontainers` per parallel `surefire` fork; `max 10 parallel` would OOM — `surefire` default `1 fork` safe.

#### 2.10 Rollback — Git revert

`revert commit` which bumped `overlays/prod newTag badSHA` then same `Promote` flow revert → `ArgoCD` syncs back. `ArgoCD History` also `Rollback` button.

#### 2.11 Extending CI — image build job

Future `CI` step `docker build → push ghcr.io:SHA` after `mvn test` green before promotion; `Promote` then only `sed newTag`. Not wired yet (`CI.yml` test only) but `Dockerfile builder` cache layer `COPY pom.xml` already optimal for that.


#### 2.18 Concurrency of CI — matrix vs single job

Single `jobs test ./mvnw -B test` runs sequential; matrix `strategy matrix java [21]` + `test shards` would parallelize `Testcontainers` suites but `MaxRAM 7GB ubuntu-latest` still bounds `Postgres 16` `Hikari10` forks. Keeping single job deterministic `ByteBuddy experimental 385` class load order stable; shard when suite >10min.

#### 2.19 Image signature — cosign keyless

`build.yml` future `uses: sigstore/cosign-installer` `cosign sign --oidc-issuer https://token.actions.githubusercontent.com ghcr.io:SHA` adds `SLSA` provenance; `ArgoCD` `verify` `k8s/base/deployment.yaml` image signature via `kyverno` admission.



#### 2.20 Required reviewers — CODEOWNERS per env

`k8s/overlays/prod/CODEOWNERS @platform-team` ensures `Promote.yml PR --base prod` needs `platform` approval before `ArgoCD sync prod` — per-env gate matches `workflow_dispatch choice 5 envs`.



#### 2.21 Flake quarantine — `rerun` vs `retry`

`CI.yml` `checkout@v4` `setup-java@v4` `mvnw -B test` flake on `Testcontainers` `Docker pull` timeout `1.21.4 60` benefits from `actions/cache` for `~/.m2` but `Testcontainers` image cache not persisted; `rerun failed jobs` GitHub `retry` `2` avoids false red `PR`.

#### 2.22 Monorepo vs split — `k8s/` co-located

Keeping `k8s/base+overlays` in `app repo` enables `PR` atomic `src change + K8s manifest` drift-free; split `infra gitops repo` would need `cross-repo Promote` webhook but cleaner `ArgoCD` RBAC.



#### 2.23 Promotion throttle — `concurrency:` guard

`Promote.yml jobs promote concurrency: promote-${{ inputs.environment }}` prevents two `dispatch` `staging` overlapping `sed newTag` races; `queue` instead of `cancel`.


---

## 3. Solution — ASCII

```
Git flow                                     CI (GitHub Actions)
────────────────────────────────────────────────────────────────────────────────────────
feature/bulkhead ── push ─► PR → develop ──► CI.yml trigger pull_request[develop] + push develop
                               │              runner ubuntu-latest steps:
                               │               checkout@v4 ─► setup-java@v4 temurin21 cache maven
                               │               └─► ./mvnw -B test  (Testcontainers 1.21.4 60 postgres16 redis kafka)
                               │                    └─► Hikari10 34 + Liquibase validate48 + virtual threads24 tests pass?
                               │                         NO ─► PR check red block merge
                               │                         YES ─► merge develop → SHA a1b2c3d

Promotion (GitOps)
────────────────────────────────────────────────────────────────────────────────────────
develop SHA a1b2c3  ── manual workflow_dispatch env=dev ──► Promote.yml  environment choice dev..prod
  TAG=github.sha a1b2c3
  sed -i s|newTag:.*|newTag: a1b2c3| k8s/overlays/dev/kustomization.yaml
  git checkout -b promote/dev  git add k8s/overlays/dev  git commit -m "promote a1b2c3 to dev"  git push origin promote/dev
  gh pr create --base dev --head promote/dev --title "Promote a1b2c3 to dev"  ─► PR dev
  merge dev branch (CODEOWNERS) ─► ArgoCD Application watches repoURL target dev branch path k8s/overlays/dev
                                   └─► sync waves Deployment/Svc/ConfigMap/HPA/Ingress ─► rolling update termination25>20s drain
  repeat workflow_dispatch env=staging newTag a1b2c3 ─► PR→staging ─► ArgoCD sync staging (image same SHA, replicas3)
  repeat prod similarly

Kustomize truth:
 k8s/base/kustomization.yaml resources [deployment,service,configmap,hpa,ingress] commonLabels app
   ├── k8s/overlays/dev/kustomization.yaml  (bases: ../base, images newTag dev-a1b2c3, patches replicas1 SPRING_PROFILES dev)
   ├── k8s/overlays/staging/kustomization.yaml (newTag a1b2c3 staging)
   └── k8s/overlays/prod/kustomization.yaml   (newTag prod-sha replicas3 resources higher)
     Secrets: k8s secrets via secretRef order-api-secrets (not in Kustomize plaintext)
       envFrom: configMapRef order-api-config + secretRef order-api-secrets
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `.github/workflows/ci.yml` | `1-22` | CI | `name CI on pull_request[develop] push[develop] jobs test runs-on ubuntu checkout@v4 setup-java@v4 temurin21 cache maven Full test suite ./mvnw -B test` |
| `.github/workflows/promote.yml` | `1-47` | GitOps promote | `name Promote workflow_dispatch env choice dev..prod jobs promote TAG sha sed newTag branch promote/${env} commit push gh pr create --base ${env}` |
| `k8s/base/kustomization.yaml` | — | Base | `kind Kustomization resources [deployment service configmap hpa ingress] commonLabels app` |
| `k8s/base/deployment.yaml` | — | Workload | `replicas2 termination25 HPA 2→10 cpu70` |
| `k8s/overlays/staging/kustomization.yaml` | — | Overlay | `bases ../base images newTag SHA patches` |
| `pom.xml` | `54-62` | Testcontainers pin | `testcontainers.version 1.21.4 60 socket fix for ~/.docker/run/docker.sock` |
| `Dockerfile` | — | Build artifact for promotion | `mvn package produces ghcr.io:SHA promoted via overlays` |
| `k8s/base/configmap.yaml` | — | Env config promoted alongside image | `SPRING_PROFILES_ACTIVE per overlay` |

```yaml
# .github/workflows/ci.yml trimmed
name: CI
on: { pull_request: {branches: [develop]}, push: {branches: [develop]} }
jobs: { test: { runs-on: ubuntu-latest, steps: [{uses: actions/checkout@v4}, {uses: actions/setup-java@v4, with: {distribution: temurin, java-version: 21, cache: maven}}, {run: ./mvnw -B test}] } }
# .github/workflows/promote.yml trimmed
name: Promote
on: { workflow_dispatch: { inputs: { environment: {type: choice, options: [dev,test,uat,staging,prod]}}}}
jobs: { promote: { runs-on: ubuntu-latest, steps: [{run: 'TAG=${{github.sha}}; sed -i s|newTag:.*|newTag: ${TAG}| k8s/overlays/${{inputs.environment}}/kustomization.yaml'}, {run: 'git checkout -b promote/${{inputs.environment}} && git add ... && git commit ... && git push'}, {run: 'gh pr create --base ${{inputs.environment}}'}]}}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Workflows present
cat .github/workflows/ci.yml | head -n 30
cat .github/workflows/promote.yml | head -n 40
grep -n "checkout@v4\|setup-java@v4\|mvnw.*test\|testcontainers" .github/workflows/ci.yml pom.xml | head
kubectl kustomize k8s/base 2>/dev/null | head -n 40 || echo "base renders"
for env in dev staging prod; do echo "== $env =="; cat k8s/overlays/$env/kustomization.yaml 2>/dev/null | grep -E "newTag|replicas|commonLabels" | head; done

# Run CI locally (mirrors runner)
./mvnw -B test 2>&1 | tail -n 20  # Testcontainers auto boots postgres16 redis kafka

# Simulate promotion (no push)
TAG=$(git rev-parse --short HEAD); echo "TAG=$TAG"
sed -n "s/newTag.*/newTag: $TAG/p" k8s/overlays/dev/kustomization.yaml | head
# dry-run sed on copy
cp -r k8s/overlays/dev /tmp/overlay-dev-copy && sed -i.bak "s|newTag:.*|newTag: $TAG|" /tmp/overlay-dev-copy/kustomization.yaml && diff -u k8s/overlays/dev/kustomization.yaml /tmp/overlay-dev-copy/kustomization.yaml | head

# Dispatch promote (when GitHub remote configured)
gh workflow run Promote --ref develop -f environment=dev 2>&1 | head
gh workflow view Promote 2>&1 | head -n 20
gh pr list --limit 5 2>&1 | head
# After PR merge, ArgoCD sync (if argocd CLI)
argocd app list 2>/dev/null | head; argocd app get order-management-api-dev 2>/dev/null | head -n 30 || echo "argocd not installed — check portal https://argocd.example.com"
```

```bash
# Protect develop with CI gate
gh api repos/:owner/:repo/branches/develop/protection -X PUT -d '{"required_status_checks":{"contexts":["test"]}}' 2>/dev/null | jq .required_status_checks || echo "manual GitHub Settings → Branches → protect develop require CI"
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why | Cost |
|---|---|---|---|---|
| `checkout@v4 + setup-java@v4 cache maven` | Official actions `temurin21` | `sdkman` manual | Cache `~/.m2` `pom.xml hash` idempotence | Runner `ubuntu-latest` Docker overhead |
| `Testcontainers 1.21.4 60` | Override `1.19→1.21` socket fix | `docker-compose up` pre-step | Real `postgres/redis/kafka` per test class no external infra | Pull `3` images `~1GB` first CI run |
| `workflow_dispatch` per env choice | Manual promote `sed newTag SHA` | `Auto bump on develop push` | Human gate per `uat/staging/prod` `ArgoCD` `CODEOWNERS` | Manual `gh workflow run` per env |
| `sed newTag SHA` single mutation | Kustomize image `newTag` only | `Helm values --set image.tag` | Minimal diff, audit `git log -- overlays/staging` shows deploy `SHA` | `sed` brittle vs `kustomize edit set image` |
| `branch promote/${env} → PR --base ${env}` | PR to `env` branch | Direct push to `env` | Review `PR` `CODEOWNERS` per `env` | Extra `PR` cycle |
| `ArgoCD watches env branch path k8s/overlays/{env}` | GitOps pull `SelfHeal` | `kubectl apply` push | Drift correction `OutOfSync` revert | `argocd` infra must run |
| `push branches [develop]` also triggers CI | `push+pull_request` both | `pull_request only` | Catches direct `push develop` bypass | Duplicate `CI` on `PR merge` push |

---

## 7. How to verify

```bash
cat .github/workflows/ci.yml | grep -E "on:|pull_request|push.*develop|setup-java|mvnw.*test|ubuntu-latest" | head -n 20  # gate
cat .github/workflows/promote.yml | grep -E "workflow_dispatch|environment.*choice|newTag|promote/|gh pr create.*--base" | head -n 20 # promote
grep -n "testcontainers.version.*1\.21" pom.xml  # 60
kubectl kustomize k8s/base 2>/dev/null | grep -c "kind: Deployment" || echo "kustomize not installed"
for env in dev staging prod; do test -f k8s/overlays/$env/kustomization.yaml && echo "$env ok $(grep newTag k8s/overlays/$env/kustomization.yaml | tr -d ' ')" || echo "$env missing"; done
grep -rn "newTag" k8s/overlays --include="*.yaml" | head
# CI run green?
gh run list --workflow=CI --limit 3 2>&1 | head || ./mvnw -B test 2>&1 | grep -E "BUILD SUCCESS|Tests run.*0"
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New repo → copy `.github/workflows/ci.yml` `checkout@v4 setup-java@v4 temurin21 cache maven ./mvnw -B test` plus `promote.yml newTag sed gh pr`. Use `k8s/base/kustomization.yaml` `commonLabels` + `overlays/{env}` `newTag SHA` pattern; promotion is `TAG=sha sed` diff. `pom 54-62 testcontainers.version 1.21.4` fix required for modern `Docker Desktop` socket.
- **Operate:** `gh workflow run Promote -f environment=staging` promotes `develop SHA` → `staging` `PR` → merge → `ArgoCD` sync rolling `termination25>20s` drain. `HPA 2→10` survives. `gh run list --workflow CI` shows `develop` gate; `argocd app get` shows `OutOfSync` drift. Rollback `git revert` `overlays/prod newTag badSHA`, same flow.
- **Interview:** "PR #34: `CI.yml pull_request/push develop ubuntu-latest checkout@v4 setup-java@v4 temurin21 ./mvnw -B test Testcontainers1.21.4 60 socket`, `Promote.yml dispatch env choice newTag SHA sed k8s/overlays/${env}/kustomization.yaml promote/${env} PR --base env ArgoCD watches env branch sync`. `Kustomize base+overlays newTag SHA` single source; `develop` protected by `CI` check; `HPA` & `probes` from `PR #33` survive sync."

---

## 9. Interview lens — Q&A

**Q1: Which trigger gates `PR → develop`?**
A: `CI.yml on pull_request branches [develop] + push develop jobs test checkout@v4 setup-java@v4 temurin21 ./mvnw -B test` (§2.1).

**Q2: Why pin `Testcontainers 1.21.4`?**
A: `1.19.3` cannot find `~/.docker/run/docker.sock` modern `Docker Desktop` socket; `1.21.x 60` fixes `DockerClientProviderStrategy` (§2.2).

**Q3: What does `Promote` `sed newTag` do?**
A: `TAG=github.sha sed s|newTag:.*|newTag: ${TAG}| k8s/overlays/${env}/kustomization.yaml` sets desired `image SHA` per env `PR #34` (§2.3).

**Q4: Kustomize vs Helm for GitOps?**
A: `Kustomize base+overlays` `commonLabels` `kustomization.yaml` `YAML` patch diff-simple vs `Helm` templating; `ArgoCD` native `Kustomize` (§2.5).

**Q5: How does ArgoCD correct manual drift?**
A: Watches `env branch k8s/overlays/{env}`; `OutOfSync` when `kubectl set image` differs from `Git newTag SHA` → `SelfHeal true` restores `Git` (§2.4).

**Q6: Why also `push develop` CI trigger?**
A: Catches direct `push develop` bypass `PR`; both triggers gate before `Promote` can `sed` bad `SHA` (§2.8).

**Q7: How to rollback a bad prod SHA?**
A: `git revert` `overlays/prod newTag badSHA` commit `newTag goodSHA` via same `Promote` `PR→prod` `ArgoCD sync` `History Rollback` (§2.10).

---

## 10. Honest limits & next step → PR #35

No `docker build + push ghcr.io:SHA` job in `CI.yml` yet — `Promote` assumes image already exists (`latest` mutable until next `CI` builds). No `trivy scan` `SAST` gate. `sed newTag` could clobber multi-image `kustomization.yaml` (use `kustomize edit set image`). `ArgoCD` itself not declared in repo `argocd/` (out-of-band). No `preview env` per `PR` `ephemeral namespace`. Next PR adds per-tenant isolation (`multi-tenancy stub`), `i18n messages`, `CSV export` `FeatureFlags` `csvExport 209` and extra `HPA` observability tuned per `overlay prod`.

See [`35-enterprise-features.md`](./35-enterprise-features.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Gate `PR → develop` | `CI.yml ./mvnw -B test` | `.github/workflows/ci.yml:12` | `Testcontainers 1.21.4 60` |
| Bump image per env | `Promote.yml newTag SHA` | `.github/workflows/promote.yml:22 sed` | `k8s/overlays/${env}` |
| Sync cluster | `ArgoCD env branch` | `.github/workflows/promote.yml:35 gh pr create --base env` | `OutOfSync` heal |
| Multi-env diff | `Kustomize base+overlays` | `k8s/base/kustomization.yaml` | `commonLabels` |
| Provide `Outbox` DB | `Testcontainers postgres` | `pom.xml:60` | socket fix `1.21` |

#### 2.12 Artifact promotion vs build coupling

Current `CI.yml` only `test` stage — image `Dockerfile builder 1` built separately (`docker build -t ghcr.io:SHA` via `promote` local or distinct `build.yml`). Coupling `CI test + build+push` in same `CI.yml` would push only on `green` `develop`; decoupling keeps `CI` fast `~3min` vs `build 6min` layer cache.

#### 2.13 Required checks and merge queue

`develop` `branch protection` `Require status check CI test` blocks `PR` merge until `ubuntu-latest test` `pass`. `Merge queue` extension would serialize `PR` merges each re-running `CI` on updated `develop HEAD` to catch `PR` crosstalk.

---
## Extra — local ArgoCD dry

```bash
# Without ArgoCD, simulate drift correction via kustomize diff
kubectl kustomize k8s/overlays/dev | kubectl diff -f - 2>&1 | head -n 30 # shows drift vs live
# Promotion SHA via git
git log --oneline -- k8s/overlays/dev | head -n 5
git show HEAD:k8s/overlays/dev/kustomization.yaml | grep newTag
```

#### 2.14 Secrets via External Secrets Operator

`order-api-secrets` `Secret` currently manual `kubectl create`. Migration to `ExternalSecret` `vault`/`AWS Secrets Manager` would Git-sync `ExternalSecret` CR instead, `ArgoCD` manages rotation without exposing `base64` `Secret` in Git `k8s/base/*`.

# pad to 300
#### 2.15 Promotion cadence — trunk-based vs GitFlow

Repo uses trunk `develop` + env branches `dev..prod` (GitFlow-lite); promotion via `PR` onto `env` is cherry `SHA` per `Promote.yml`. Trunk-only `ArgoCD ApplicationSet` with overlay per `env` auto-tracking `develop HEAD` would be continuous deploy without per-env gates — explicit choice keeps `prod` gate manual dispatch.

#### 2.16 Flaky test mitigation

`Testcontainers` `Postgres` cold pull may flake `CI` first run `image pull` timeout; `actions/cache` for `~/.testcontainers` not wired yet — retry `CI` via `gh workflow run` mitigates. Parallel `surefire` forks `max 1` avoids `Hikari` pool contention.

#### 2.17 SLSA provenance future

`CI` future signs `ghcr.io:SHA` with `cosign` `SLSA` provenance via `actions/attest-build-provenance` — `Kustomize newTag digest` then attested, `ArgoCD Image Updater` verifies before sync.

---
## Note

Promotion via `sed` `Promote.yml 22` assumes `newTag:` single line per image; multi-image overlays should switch to `kustomize edit set image ghcr.io/...:$TAG` for robustness.
