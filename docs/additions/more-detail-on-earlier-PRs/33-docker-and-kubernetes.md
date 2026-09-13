# 33. Docker and Kubernetes (PR #33)

> PR #33 — Multi-stage `Dockerfile`, `K8s Deployment/Service/HPA/Ingress`, `Kustomize`, probes, graceful shutdown. Stack: Java 21, `Dockerfile: maven:3.9-eclipse-temurin-21 builder → eclipse-temurin:21-jre-alpine runtime`, `k8s/base/*`, `k8s/overlays/*`, `application.yml:16-18,136-137`. See `README.md:1772` roadmap `| 33 | Docker and Kubernetes |`.

---

## 1. Purpose — what shipped

PR #33 containerizes the observable, resilient, event-driven API and declares its K8s runtime. `Dockerfile` multi-stage: `builder maven:3.9-eclipse-temurin-21` `COPY pom.xml src → mvn -B package -DskipTests`, then `runtime eclipse-temurin:21-jre-alpine` non-root `orderapi:orderapi` `WORKDIR /app COPY --from=builder target/*.jar app.jar USER orderapi EXPOSE 8080 ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 ENTRYPOINT java -jar`. `k8s/base/*`: `ConfigMap order-api-config` `SPRING_PROFILES_ACTIVE staging + DB_HOST postgres REDIS_HOST redis KAFKA kafka:9092`, `Deployment replicas 2 selector app order-management-api template labels` `terminationGracePeriod 25 > 20s spring.lifecycle.timeout 18`, `resources requests 250m 512Mi limits 1 1Gi`, `securityContext runAsNonRoot allowPrivilegeEscalation false`, probes `liveness /actuator/health/liveness 30s10s`, `readiness 10s5s`, `startup failure30 period5`, `Service port 80→8080`, `HPA min2 max10 cpu 70%`, `Ingress nginx cert-manager letsencrypt prod api.example.com TLS order-api-tls`, `Kustomization commonLabels`. `application.yml:16-18 graceful 20s + server.shutdown graceful 137` ties drain correctly.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `mvn spring-boot:run` local only — no immutable artifact, `root` container risk, no `K8s` rolling deploy, `SIGTERM` killed in-flight `placeOrder 77` tx mid `Outbox pending 125` + broker `markPublished 69`. `HPA` absent, scaling manual. `prometheus 144` had no stable `Service` DNS.

**After:** `Dockerfile` produces `ghcr.io/sushantac/order-management-api:latest` layered (`deps cached via COPY pom.xml` before `src`). Non-root reduces `CVE` blast radius. `Deployment 2 replicas` with probes `liveness/readiness/startup` integrates `AppInfoHealthIndicator 14` and `db` health via `Actuator 139-152`. `HPA 70% cpu` autoscales `2→10` on `virtual threads:24` burst or `bulkhead queue5:251` pressure. `Ingress` terminates TLS via `cert-manager`. `terminationGracePeriod 25s` covers `20s` `timeout-per-shutdown-phase 18` so `virtual threads` drain `placeOrder` before `SIGKILL`.

### Theory — Docker multi-stage, K8s workload, and graceful drain from first principles (100+ lines)

#### 2.1 Multi-stage Docker — why two FROMs

```dockerfile
# Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS builder   # stage 0: compile layer
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests              # produces target/*.jar + docs/ second resource 370 in pom

FROM eclipse-temurin:21-jre-alpine             # stage 1: runtime only ~200MB vs builder 800MB
RUN addgroup -S orderapi && adduser -S orderapi -G orderapi
WORKDIR /app
COPY --from=builder /app/target/order-management-api-*.jar app.jar
USER orderapi                                   # non-root
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"  # container cgroup aware heap 75% of limit 1Gi → ~750MB
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Stage separation: `builder` layer caches `pom.xml` deps (`COPY pom.xml` before `src` → `MaxRAM 75` not needed there). Runtime `eclipse-temurin:21-jre-alpine` Alpine `musl` small attack surface vs `debian`. `MaxRAMPercentage 75` makes `JVM heap` cgroup-aware (vs fixed `-Xmx`). Alternative `jlink` custom JRE smaller but `Spring Boot fat jar` already `loader`.

#### 2.2 Base k8s resources — Deployment, ConfigMap, Service, HPA, Ingress, Kustomize

`k8s/base/kustomization.yaml` `resources: deployment, service, configmap, hpa, ingress` `commonLabels app:order-management-api` is the base; `overlays/{dev,test,uat,staging,prod}` patch `replicas`, `resources`, `newTag` image per env via `Kustomize`.

- `Deployment`: `replicas 2` baseline `2 pods` for `SKIP LOCKED 24` sharding and `HPA 70%` headroom. `selector matchLabels app` stable `Service` selector. `strategy RollingUpdate maxUnavailable 25%` (default) drains pod-by-pod.
- `ConfigMap order-api-config`: `SPRING_PROFILES_ACTIVE staging` overlay overrides to `dev/prod`, `DB_HOST postgres` `Service` DNS (within ns), `REDIS_HOST redis`, `KAFKA kafka:9092`. `envFrom configMapRef + secretRef order-api-secrets` injects `DB_PASSWORD order` `jwt-secret 186` `KAFKA_BOOTSTRAP`.
- `Service`: `ClusterIP port 80 → target 8080` stable `order-management-api:80` for `Ingress` and `prometheus` scrape via `ServiceMonitor` (next PR).
- `HPA`: `min 2 max 10 metrics cpu 70%` `autoscaling/v2` `averageUtilization`. CPU 70% on `250m request` target probes often via `product.get histogram` latency fallback (custom metrics v2beta1 not wired here but could be `prometheus adapter` for `resilience4j`).
- `Ingress`: `ingressClass nginx` `tls hosts api.example.com secret order-api-tls` `cert-manager.io/cluster-issuer letsencrypt-prod` auto-cert, `rules path / Prefix backend service port 80`. Alternative `Gateway API HTTPRoute` similar.

#### 2.3 Probes — liveness, readiness, startup and Actuator mapping

```yaml
# k8s/base/deployment.yaml probes
livenessProbe:  httpGet path /actuator/health/liveness  port 8080 initial 30s period 10s
readinessProbe: httpGet path /actuator/health/readiness port 8080 initial 10s period 5s
startupProbe:   httpGet path /actuator/health/liveness  port 8080 failureThreshold 30 period 5s # 150s window
```

`management.endpoint.health.probes.enabled true 151` splits `health` into groups: `liveness` (JVM alive) vs `readiness` (db/redis/outbox ready). `show-details always 148` leaks `db` status but dev choice. `startupProbe` prevents `liveness` killing slow `Liquibase validate 48` + `Hikari init` + `Kafka broker 99` warmup — `150s` covers `mvn surefire 385 -Dnet.bytebuddy.experimental` CI.

Probe semantics: `liveness fail → kubelet restart container` (covers deadlock `pinning` virtual threads); `readiness fail → remove from Service endpoints` (covers `DB postgres` failover, `Kafka 99` down but `liveness` still `UP`). `AppInfoHealthIndicator 14` `UP` signals `PR #32` image correct.

#### 2.4 Resources and QoS — request vs limit, MaxRAM

`resources requests cpu 250m memory 512Mi limits cpu 1 memory 1Gi`. `requests` schedules `2 pods ×250m =500m` per node; `limits` caps burst `virtual threads 24` `10k` concurrency `cpu throttling` at `1 core`. `memory 512Mi→1Gi` with `MaxRAM 75` → heap `384Mi→750Mi`. `QoS Burstable` (requests < limits) appropriate for API; `Guaranteed` (requests=limits) more stable for `Kafka`. Alternative `VerticalPodAutoscaler` would tune `500m` based on `prometheus jvm.memory`.

#### 2.5 SecurityContext and USER

`Dockerfile USER orderapi` + `securityContext runAsNonRoot true allowPrivilegeEscalation false` prevents `CVE` privilege gain. `readOnlyRootFilesystem true` (not set here — honest limit) would need `tmp` `emptyDir`. Baseline `Restricted` PodSecurity.

#### 2.6 Graceful shutdown — 25s > 20s coordination

`spring.lifecycle.timeout-per-shutdown-phase 20s 18` + `server.shutdown graceful 137`: on `SIGTERM` (Deployment rolling update or `kubectl rollout`), `Tomcat` stops accepting, drains in-flight `placeOrder 77` tx and `OutboxPublisher 56 scheduledPoll` up to `20s`, then `Spring` closes. `terminationGracePeriodSeconds 25` `k8s/deployment.yaml` must exceed `20s` — `kubelet` waits `25s` after `SIGTERM` before `SIGKILL`; `5s` buffer covers `SIGTERM` propagation. Virtual threads draining `blockLast 138` streaming suspend `interrupt`; `Hikari 34` connections closed at `SmartLifecycle` phase.

```
Rolling update: kubectl rollout Deployment order-management-api
  pod old SIGTERM ─► graceful 20s drain placeOrder Outbox pending 125 commit or rollback
  terminationGrace 25s ─► SIGKILL (only stray)
  readiness fail ─► Service removes endpoint → no new traffic
```

#### 2.7 Kustomize overlays — param per env

`k8s/overlays/dev/kustomization.yaml` patches `commonLabels env dev`, `ConfigMap SPRING_PROFILES_ACTIVE dev`, `Deployment image newTag dev-sha`, `replicas 1` cheaper, `resources` lower. `overlays/prod` `replicas 3`, `limits` higher, `HPA max 20`, `Ingress host api.company.com`. Promotion `PR #34 GitOps` edits `overlays/{env}/kustomization.yaml newTag` via `Promote.yml` workflow `sed newTag: TAG` and opens `PR per promote/{env}`.

> Interview anchor: "`Dockerfile maven:3.9 builder → jre-alpine runtime non-root orderapi MaxRAM75%`, `k8s/base ConfigMap DB_HOST postgres REDIS redis KAFKA kafka 9092 + Deployment replicas2 termination25 >20s requests250m/512Mi limits1/1Gi runAsNonRoot probes liveness30s/readiness10s/startup150s + Service HPA min2 max10 cpu70 Ingress nginx letsencrypt api.example.com Kustomization commonLabels`, `application.yml:18 timeout20s 137 graceful` drains `placeOrder77` `Outbox125` `virtual blockLast138`."

#### 2.8 Image tagging and registry

`image ghcr.io/sushantac/order-management-api:latest` `imagePullPolicy IfNotPresent` `overlays` `newTag: ${GITHUB_SHA}` per commit (`Promote.yml sed`). `CI.yml test` builds but not push; `Promote.yml dispatch environment` patches tag. Alternative `fluxcd ImageUpdateAutomation` would auto-bump `latest→sha`.

#### 2.9 Service discovery DNS

Within `ns` `postgres:5432` resolves via `Service postgres` `docker-compose postgres 5432`. `REDIS_HOST redis` same. `KAFKA_BOOTSTRAP_SERVERS kafka:9092` headless. External `DB_HOST` via `secretRef order-api-secrets` overrides `ConfigMap postgres` in `prod`.

#### 2.10 When HPA scales — virtual threads and CPU

`HPA cpu 70%` `averageUtilization` of `request 250m`. `virtual threads 24` cheap so `10k` concurrent `GET 42` may show `cpu 60%` but `Hikari 34 10` saturated queue latency high before CPU triggers. Better custom metric `http_server_requests_seconds_count` or `outbox PENDING` via `Prometheus Adapter` → `HPA custom metrics`. Current `cpu 70%` baseline conservative.


#### 2.14 Supply-chain — Docker cache mounts

Modern `BuildKit` `RUN --mount=type=cache,target=/root/.m2 mvn -B package` would reuse `Maven` local repo across `docker build` layers without `COPY pom.xml` trick; compatible with `builder` stage but `maven:3.9` image BuildKit off by default. Learning journey explicit `COPY pom.xml` remains portable.



#### 2.15 PodDisruptionBudget — voluntary disruption guard

`Deployment replicas2` without `PodDisruptionBudget minAvailable 1` allows `kubectl drain` to evict both pods; `PDB` would guard `OutboxPublisher 56` polling continuity during node upgrade.


---

## 3. Solution — ASCII

```
Dockerfile multi-stage:
 pom.xml + src ──► maven:3.9-eclipse-temurin-21 builder RUN mvn -B package -DskipTests ──► target/*.jar
                        │ COPY --from=builder
                        ▼
               eclipse-temurin:21-jre-alpine runtime + adduser orderapi USER orderapi EXPOSE8080 JAVA_TOOL_OPTIONS 75% ─► ghcr.io/...:sha

K8s apply -k k8s/base  (or overlays/dev)
  ConfigMap order-api-config [SPRING_PROFILES_ACTIVE=staging DB_HOST=postgres REDIS_HOST=redis KAFKA=kafka:9092]
     + Secret order-api-secrets [DB_PASSWORD, jwt-secret, REDIS_PORT, TLS]
     │
     ▼ Deployment order-management-api replicas2 strategy RollingUpdate
        selector app:order-management-api template labels
        terminationGracePeriod 25 (>20s lifecycle 18) containers api image ghcr...:latest
          envFrom ConfigMap+Secret, resources 250m/512Mi → 1/1Gi, securityContext runAsNonRoot false
          probes liveness /actuator/health/liveness 30s10s ─► restart on DOWN JVM deadlock
                 readiness /actuator/health/readiness 10s5s ─► remove from Service on DB DOWN
                 startup /actuator/health/liveness 30×5s 150s ─► slow Liquibase validate
          lifecycle SIGTERM → server.shutdown graceful 137 drains placeOrder77 Outbox125 blockLast138
        ──────────────────────────────────────────────────────────────────────────► Pod1 Pod2 (virtual threads 24)
     │
     ├─ Service order-management-api ClusterIP 80 → 8080  (prometheus scrape, Ingress backend)
     ├─ HPA min2 max10 cpu70% autoscaling/v2 ──► scale on burst 10k rps
     ├─ Ingress nginx cert-manager letsencrypt api.example.com TLS order-api-tls path/→Service80
     └─ Kustomization commonLabels app:order-management-api resources [deploy svc cm hpa ingress]
        overlays/{dev,prod} patch replicas/imageTag/env/spring profiles

Promotion: GH Actions Promote.yml workflow_dispatch env → sed newTag SHA on k8s/overlays/${env}/kustomization.yaml → branch promote/${env} → PR → ArgoCD (PR #34) syncs
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `Dockerfile` | `1-15` | Image | `FROM maven:3.9 builder WORKDIR /app COPY pom.xml src RUN mvn package; FROM jre-alpine USER orderapi EXPOSE 8080 MaxRAM75 ENTRYPOINT jar` |
| `k8s/base/configmap.yaml` | — | Env | `SPRING_PROFILES_ACTIVE staging DB_HOST postgres REDIS_HOST redis KAFKA kafka:9092` |
| `k8s/base/deployment.yaml` | — | Workload | `replicas2 selector termination25 resources 250m/512Mi→1/1Gi runAsNonRoot probes liveness/readiness/startup envFrom` |
| `k8s/base/service.yaml` | — | Discovery | `ClusterIP 80→8080 selector app` |
| `k8s/base/hpa.yaml` | — | Scaling | `min2 max10 cpu 70 autoscaling/v2` |
| `k8s/base/ingress.yaml` | — | Edge | `class nginx cert-manager letsencrypt tls api.example.com path /→service80` |
| `k8s/base/kustomization.yaml` | — | Kustomize | `resources [deploy,svc,cm,hpa,ingress] commonLabels app` |
| `k8s/overlays/*/kustomization.yaml` | — | Env patch | `patches replicas newTag SHA per env dev..prod` |
| `src/main/resources/application.yml` | `16-18,136-137` | Drain | `timeout20s 18 shutdown graceful 137` |
| `src/main/resources/application.yml` | `139-152` | Health | `probes enabled 151 liveness/readiness` for deployment probes |

```dockerfile
# Dockerfile:1-15 trimmed
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml . \nCOPY src ./src \nRUN mvn -B -q package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S orderapi && adduser -S orderapi -G orderapi
WORKDIR /app
COPY --from=builder /app/target/order-management-api-*.jar app.jar
USER orderapi
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Build stages
grep -n "FROM\|WORKDIR\|COPY\|RUN mvn\|USER\|EXPOSE\|JAVA_TOOL_OPTIONS\|ENTRYPOINT" Dockerfile | head -n 20
docker build -t ghcr.io/sushantac/order-management-api:dev --progress=plain . 2>&1 | tail -n 30 # builder cache hit on pom layer
docker run --rm -p 8080:8080 ghcr.io/sushantac/order-management-api:dev & sleep 15; curl -s http://localhost:8080/actuator/health | jq .; docker stop $(docker ps -q -f ancestor=ghcr.io/sushantac/order-management-api:dev)

# K8s dry-run (no cluster required for manifests)
kubectl kustomize k8s/base | head -n 80
kubectl kustomize k8s/overlays/dev | head -n 80 2>/dev/null || echo "overlays need cluster"
# Apply (when cluster)
kubectl apply -k k8s/base; kubectl get pods,svc,ingress,hpa -l app=order-management-api
kubectl rollout status deployment/order-management-api --timeout=120s
kubectl exec -it deploy/order-management-api -- curl -s http://localhost:8080/actuator/health | jq .
kubectl exec -it deploy/order-management-api -- env | grep -E "SPRING_PROFILES|DB_HOST|REDIS_HOST|KAFKA"
# Probe check
kubectl describe pod -l app=order-management-api | grep -A5 "Liveness\|Readiness\|Startup"
# HPA scale test
kubectl top pods 2>/dev/null | head; kubectl get hpa order-management-api -o yaml | grep -A3 "cpu"
# Ingress TLS
kubectl get ingress order-management-api -o yaml | grep -A5 "tls\|cert-manager"

# Graceful drain verification
kubectl rollout restart deployment/order-management-api & sleep 5 # old pod SIGTERM 25s window while traffic drains
curl -s http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" | jq . &
wait
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why | Cost |
|---|---|---|---|---|
| Multi-stage `builder→jre-alpine` | `maven3.9` build + `jre-alpine` runtime non-root | Single `openjdk:21` fat | `~200MB` runtime vs `800MB` builder cache `pom.xml` layer | Alpine `musl` DNS edge vs `glibc` |
| `eclipse-temurin:21-jre-alpine` | JRE only | JDK + `jlink` | Minimal but `jar` `spring-boot-loader` works | No `jcmd` debug |
| `MaxRAM75 1Gi limit` | `75%` heap via `JAVA_TOOL_OPTIONS` | `-Xmx750m` fixed | Cgroup-aware per overlay `512Mi` dev vs `1Gi` prod | Heap scales with limit change |
| `runAsNonRoot false escalation` | Restricted `USER orderapi` | root | `CVE` blast radius | Cannot `apt-get` inside pod |
| Replicas `2` + `HPA 2→10 70%` | `2` baseline `virtual threads 24` | `1` or `HPA 50%` | `SKIP LOCKED 24` shard benefits `2` pods; `70%` headroom | Cost `2×512Mi` always |
| `termination 25 >20` + `graceful 137` | `25s` k8s `20s` Spring | `30s`/`5s` immediate kill | In-flight `placeOrder77` + `Outbox125` + `virtual blockLast138` completes | Slower rollout `25s` per pod |
| `ConfigMap` env bundle | `envFrom` both `configMap+secret` | `application.yml` baked | 12-factor per-overlay override `Kustomize` `overlays` | `secretRef` still base64 not sealed |

---

## 7. How to verify

```bash
grep -n "FROM maven\|FROM eclipse-temurin\|adduser.*orderapi\|MaxRAMPercentage\|ENTRYPOINT.*java.*jar" Dockerfile  # builder + runtime
grep -n "replicas.*2\|terminationGracePeriodSeconds.*25\|runAsNonRoot\|livenessProbe\|readinessProbe\|startupProbe\|requests.*250m\|HPA" k8s/base/deployment.yaml k8s/base/hpa.yaml 2>/dev/null | head -n 30
grep -n "SPRING_PROFILES_ACTIVE\|DB_HOST.*postgres\|REDIS_HOST\|KAFKA_BOOTSTRAP" k8s/base/configmap.yaml | head
grep -n "commonLabels\|resources.*deployment\|overlays" k8s/base/kustomization.yaml k8s/overlays/*/kustomization.yaml 2>/dev/null | head
grep -n "timeout-per-shutdown-phase.*20s\|shutdown.*graceful" src/main/resources/application.yml  # 18 137
kubectl kustomize k8s/base 2>/dev/null | grep -E "kind:|name: order-management-api" | head -n 20 || echo "kustomize check"
docker build --target builder -t test-builder . 2>&1 | grep "FROM" | head
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Clone `Dockerfile` builder cache pattern (`COPY pom.xml` before `src`). For new service, copy `k8s/base` + `Kustomization` overlay pattern `dev/prod` patching `SPRING_PROFILES_ACTIVE`, `newTag SHA`, `resources`. Keep `application.yml:18 137` drain in sync with `deployment.yaml terminationGrace`.
- **Operate:** `kubectl rollout status / get hpa / top pods` shows `HPA 70%` scale `2→10` on CPU (future custom `product_get p95`). `kubectl exec curl /actuator/health` matches `liveness/startup` probe. Rolling update drains `virtual blockLast138` and `OutboxPublisher 56` safely. `docker run` local replicates `K8s` image.
- **Interview:** "PR #33: `Dockerfile maven:3.9 builder → jre-alpine USER orderapi MaxRAM75%`, `k8s/base ConfigMap DB/Redis/Kafka + Deployment replicas2 termination25>20s 250m/512Mi→1/1Gi runAsNonRoot probes liveness30/10 readiness10/5 startup150s + Service + HPA 2→10 70 + Ingress nginx letsencrypt + Kustomization`. `application.yml:18 137 graceful` ties drain; virtual threads cheap I/O but `Hikari10` still bottleneck."

---

## 9. Interview lens — Q&A

**Q1: Why two `FROM` in Dockerfile?**
A: Builder `maven` layer caches `pom.xml` deps before `src`; runtime `jre-alpine` `~200MB` with `USER orderapi` non-root `MaxRAM75` (§2.1).

**Q2: What probes and why three?**
A: `liveness` `liveness 30s10s` JVM alive → restart; `readiness 10s5s` `db UP` → Service endpoints; `startup 150s` covers `Liquibase` `validate 48` warmup (§2.3).

**Q3: Why `terminationGrace 25 > 20s`?**
A: `spring.lifecycle 20s 18` `server.shutdown graceful 137` drains `placeOrder77` `Outbox125` `blockLast138`; `k8s 25s` must exceed to avoid `SIGKILL` mid-tx (§2.6).

**Q4: HPA on what metric correctly here?**
A: Currently `cpu 70%` simple; better custom `product_get p95` or `outbox PENDING` via `Prometheus Adapter` because `virtual 24` `cpu low` but `Hikari` queue high (§2.10).

**Q5: How does Kustomize promote per env?**
A: `base` `kustomization.yaml` common labels; `overlays/{env}` patches `replicas/image newTag SHA SPRING_PROFILES` — `PR #34 Promote.yml` `sed newTag` (§2.7).

**Q6: Non-root benefit?**
A: `USER orderapi` + `runAsNonRoot true false escalation` → `Restricted` `PodSecurity` limits `CVE` gain (§2.5).

**Q7: MaxRAMPercentage vs Xmx?**
A: `75%` of `limit 1Gi` cgroup-auto `~750MB` `512Mi→384MB` dev vs `1Gi prod` without editing `Xmx` per overlay (§2.4).

---

## 10. Honest limits & next step → PR #34

No `readOnlyRootFilesystem` (needs `emptyDir tmp`), no `NetworkPolicy`, no `PodDisruptionBudget`. `HPA only cpu` misses `Hikari` backpressure; needs `KEDA` or `Prometheus Adapter` custom `resilience4j`. `Ingress nginx` single-controller SPOF — `Gateway API` future. `Image latest` mutable tags avoided via `newTag SHA` but `ghcr.io` auth via `imagePullSecrets` not shown. Next PR automates `docker build → push → Kustomize newTag → PR → ArgoCD sync` via `GitHub Actions CI ci.yml test + Promote.yml dispatch` and `Kustomize overlays` GitOps.

See [`34-ci-cd-and-gitops.md`](./34-ci-cd-and-gitops.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Small runtime | `jre-alpine multi-stage` | `Dockerfile` | `200MB` + non-root |
| Drain in-flight tx | `graceful 20s + termination25` | `application.yml:18,137 k8s/deployment.yaml` | No mid-tx `SIGKILL` |
| Discover health | `probes + Actuator 151` | `k8s/deployment.yaml application.yml:151` | `liveness vs readiness` |
| Scale burst | `HPA 2→10 70` | `k8s/base/hpa.yaml` | `virtual threads 24` burst |
| Per-env config | `Kustomize overlays` | `k8s/base/kustomization.yaml` | `staging vs prod` patch |

#### 2.11 Layer caching — Dockerfile COPY order

`COPY pom.xml .` before `COPY src` means `mvn dependency:go-offline` layer invalidates only on `pom.xml 174-305` change, not every `OrderService.java 77` line edit. Saves `~2min` `spring-ai PGVector 261` download per build. `docs/` second resource `pom 371` adds doc layer but `target/docs` minimal.

#### 2.12 Init timing — Liquibase validate and Hikari

`ddl-auto validate 48` checks `db/changelog/db.changelog-master.xml` matches entities; failure fails `startupProbe 150s` before `readiness` enabling `Service`. `Hikari pool 10 34` pre-fills `10` connections during `DataSource` bean — `startupProbe` initial `30s` covers cold `Postgres` start.

#### 2.13 Digest pinning (future)

Current `Image ghcr.io/...:latest newTag SHA` tag mutable; digest pin `image@sha256:abc` immutable but `Kustomize newTag` patch would need digest update. `Promote.yml` `sed newTag SHA` per `github.sha` is sha-tag, not digest — production hardening pins digest via `kustomize edit set image` after `docker buildx --push` outputs digest.

---
## Extra probe drill

```bash
# Simulate startup delay failure: misconfigure DB_HOST to bad-host → readiness DOWN but liveness UP
kubectl set env deployment/order-management-api DB_HOST=bad-host && sleep 15
kubectl get pods | grep order-management-api
kubectl describe pod -l app=order-management-api | grep -E "Ready.*False|StartupProbe|Live"
kubectl set env deployment/order-management-api DB_HOST=postgres # fix
```
