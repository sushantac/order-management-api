# 17. Configuration & Environments — From .env to docker-compose to K8s

> Stack: `application.yml:12` + `application-rag.yml:10` + `application-dev.yml:9` + `application-prod.yml:9` + `docker-compose.yml:10` + `k8s/base/configmap.yaml:1` + `k8s/base/deployment.yaml:1` + `k8s/overlays/*/kustomization.yaml:1` · Depends on [#02 DB + Liquibase](../02-database-and-persistence.md) + [#28 cache](../05-caching-strategy.md) + [#31 Kafka](../06-event-driven-kafka.md) + [#32 tracing](../07-observability.md) + [#42 RAG](./05-rag-productionization.md)

---

## 1. Purpose — one file works for dev/test/CI/prod via env vars

Every value in `application.yml:9` is overridable by an environment variable so the **same artifact** boots in four places without rebuilding:

| Context | How config arrives | What it proves |
|---|---|---|
| **Local dev** | `docker-compose.yml:104` `DB_HOST=postgres` + `REDIS_HOST=redis` + `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` | No manual `.env` — service names resolve on the compose network |
| **Integration test** | `Testcontainers` `@ServiceConnection` / `@DynamicPropertySource` + `integration.database.tag` per class | Every test class gets its own real `postgres:16-alpine` — zero global state leak |
| **CI** | `DB_HOST=localhost` defaults in `application.yml:29` + GitHub Actions `ci.yml:23` | Same file, same Liquibase, same Hikari `OrderApiHikariPool` `:33` |
| **K8s** | `k8s/base/configmap.yaml:5` non-secrets + `k8s/samples/db-secret.example.yaml:1` secrets via `envFrom` at `k8s/base/deployment.yaml:22` | `SPRING_PROFILES_ACTIVE` overlay picks `dev` vs `prod` |

Principle from `application.yml:9` header: *"Every value is overridable via environment variables so the same file works for local dev (docker-compose), integration tests (Testcontainers) and CI."* Liquibase at `application.yml:37` owns the schema (`ddl-auto: validate` `:48`) — never `update` outside `application-dev.yml:12`.

```
application.yml (defaults: localhost:5432/orderdb, localhost:6379, localhost:9092)
      │  ${DB_HOST:localhost} :29  ${REDIS_HOST:localhost} :92  ${KAFKA_BOOTSTRAP_SERVERS:…} :99
      ├── docker-compose.yml:104 overrides to postgres/redis/kafka  (compose network)
      ├── application-rag.yml:10  SPRING_PROFILES_ACTIVE=rag  (DeepSeek+Ollama)
      ├── application-dev.yml:9   ddl-auto:update + cache:redis + kafka:enabled
      ├── application-prod.yml:9  ddl-auto:validate + cache:redis + tracing:enabled
      └── k8s/base/configmap.yaml:5  DB_HOST=postgres  REDIS_HOST=redis  KAFKA=kafka:9092
              └── k8s/overlays/{dev,prod,staging,test,uat}/kustomization.yaml:1  SPRING_PROFILES_ACTIVE + replicas + image tag
```

---

## 2. Env-var matrix — table of vars with defaults and where used (`application.yml` file:line)

### 2.1 Core — DB / server / cache / Kafka / tracing

| Env var | Default (`application.yml:line`) | Where used (`application.yml:line` / code) | Notes |
|---|---|---|---|
| `DB_HOST` | `localhost` `:29` | `spring.datasource.url` `jdbc:postgresql://${DB_HOST:localhost}:…` `:29` | Compose `postgres` `:105`, K8s `postgres` `configmap.yaml:7`, Testcontainers `localhost` via `ServiceConnection` |
| `DB_PORT` | `5432` `:29` | Same `url` `:29` | Rarely overridden — change left side of `docker-compose.yml:24` `5432:5432` instead |
| `DB_NAME` | `orderdb` `:29` | Same `url` `:29` | `docker-compose.yml:19` `POSTGRES_DB=orderdb`, K8s `kustomization.yaml:9` `DB_NAME=orderdb` |
| `DB_USER` | `order` `:30` | `spring.datasource.username: ${DB_USER:order}` `:30` | `docker-compose.yml:20` `POSTGRES_USER=order`, K8s `db-secret.example.yaml:11` `DB_USER=b3JkZXI=` |
| `DB_PASSWORD` | `order` `:31` | `spring.datasource.password: ${DB_PASSWORD:order}` `:31` | `docker-compose.yml:21`, K8s `db-secret.example.yaml:12` + `sealed-db-secret.example.yaml:12` — real clusters use `kubeseal` |
| `SERVER_PORT` | `8080` `:134` | `server.port: ${SERVER_PORT:8080}` `:134` | Tests use `RANDOM_PORT` (`McpOAuth2IntegrationTest.java:61`) |
| `REDIS_HOST` | `localhost` `:92` | `spring.data.redis.host: ${REDIS_HOST:localhost}` `:92` | Compose `redis` `:106`, K8s `redis` `configmap.yaml:8`, `application-dev.yml:26` same |
| `REDIS_PORT` | `6379` `:93` | `spring.data.redis.port: ${REDIS_PORT:6379}` `:93` | `application-prod.yml:38` hard `${REDIS_HOST}` (no default) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` `:99` | `spring.kafka.bootstrap-servers` `:99` | Compose `kafka:9092` `:107`, K8s `kafka:9092` `configmap.yaml:9`, dev/prod `app.kafka.enabled=true` `:32/:43` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` `:161` | `management.otlp.tracing.endpoint` `:161` | Compose `jaeger:4318` `:90`, prod `management.tracing.enabled=true` `application-prod.yml:48` |
| `SPRING_PROFILES_ACTIVE` | *(none — default profile)* | `docker-compose.yml:104` `dev`, `k8s/base/configmap.yaml:6` placeholder, overlays override | `rag` activates `application-rag.yml:10`; `dev` allows `ddl-auto:update` |

### 2.2 Security / JWT

| Env var | Default (`application.yml:line`) | Where used | Notes |
|---|---|---|---|
| `app.security.jwt-secret` | `local-learning-secret-change-me-please-32chars` `:186` | `SecurityProperties.java:20`, `SecurityConfig.java:86` `NimbusJwtDecoder` | Learning HS256 — prod validates JWK set; never commit real secret (`k8s/samples/sealed-*.yaml:12`) |
| `app.security.api-key` | `dev-api-key-orderapi` `:188` | `SecurityProperties.java:23`, `ApiKeyAuthenticationFilter.java:37` | Script fallback; gated by `app.security.enabled=true` `:183` |
| `app.security.enabled` | `true` `:183` | `SecurityConfig.java:37` `properties.isEnabled()` at `:44` | Tests set `false` via `TestPropertySource` to skip auth |

### 2.3 RAG / AI — `application.yml:119` + `application-rag.yml:10` + flags

| Env var / property | Default | Where used (`file:line`) | Notes |
|---|---|---|---|
| `DEEPSEEK_API_KEY` | *(empty)* `:122` base; **required** `:20` in rag profile | `spring.ai.deepseek.api-key: ${DEEPSEEK_API_KEY:}` `:122` vs `application-rag.yml:20` `${DEEPSEEK_API_KEY:?Set …}` | Base boots without it; `rag` profile fails fast if missing |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` `:121` | `spring.ai.deepseek.base-url` `:121` | Override for proxy / mock |
| `OLLAMA_BASE_URL` | `http://localhost:11434` `:127` | `spring.ai.ollama.base-url` `:127` | `ollama serve` default |
| `app.rag.enabled` | `false` (bean absent) | `RagConfig.java:35`, `RagService.java:29`, `RagProperties.java:26` `@ConditionalOnProperty(prefix="app.rag", name="enabled", havingValue="true")` | Activates `application-rag.yml:27` `enabled: true`; whole RAG absent without it — zero AI cost |
| `app.rag.retrieval-mode` | `hybrid` `:40` / `RagProperties.java:53` | `RagProperties.java:40` `HYBRID`, `RetrievalEngine.java:17` seam | `DENSE` = embeddings only (pre-#43 baseline); `HYBRID` = dense + `tsvector` RRF |
| `app.rag.retrieval.mmr-enabled` | `false` `RagProperties.java:54` | `RetrievalSettings.java:96` `mmrEnabled` | PR #44 measured: `mmr 0.5` dropped `hitRate@5 75%→50%` — stays off |
| `app.rag.retrieval.mmr-lambda` | `0.5` `:54` | `RagProperties.java:54` | Only when `mmr-enabled=true` |
| `app.rag.retrieval.rrf-k` | `60` `:54` | `RagProperties.java:99` `rrfK` | `1/(k+rank)` fusion constant |
| `app.rag.eval.enabled` | `false` `:55` | `RagEvalRunner.java:27` `@ConditionalOnProperty(app.rag.eval.enabled=true)` | Gate for retrieval quality |
| `app.rag.eval.min-hit-rate` | `0.0` `:56` report-only; `0.7` in `application-rag.yml:57` | `RagProperties.java:56`, `RagEvalRunner.java:122` | `> hitRate` fails startup — earned at `75%` (PR #44) |
| `app.rag.query-rewriting.enabled` | `false` (bean absent) | `RagQueryRewriter.java:20` `@ConditionalOnProperty(app.rag, query-rewriting.enabled, true)` | PR #51 — one `ChatModel.call` per question `:34` |
| `app.rag.reranking.enabled` | `false` (bean absent) | `SemanticReranker.java:17` `@ConditionalOnProperty(app.rag, reranking.enabled, true)` | PR #51 — lexical `score()` `:31` heuristic, cross-encoder-ready |

### 2.4 MCP write-tool gate

| Property | Default | Where used | Notes |
|---|---|---|---|
| `app.mcp.write-tool.enabled` | `false` (bean absent) | `CancelOrderTool.java:36`, `ConfirmOrderTool.java:15`, `ShipOrderTool.java:15`, `ReindexDocsTool.java:35` `@ConditionalOnProperty(prefix="app.mcp", name="write-tool.enabled", havingValue="true")` + `AbstractMcpWriteTool.java:25` | Deploy-time capability gate — `McpServerConfiguration.java:47` collects empty write-tool list without it; `AgentToolSet.java:32` stays read-only regardless |

### 2.5 Kafka / outbox / features

| Property | Default (`application.yml:line`) | Notes |
|---|---|---|
| `app.kafka.enabled` | `false` `:194` | `dev`/`prod` set `true` (`application-dev.yml:32` / `application-prod.yml:43`); broker never contacted without it (tests stay broker-free) |
| `app.kafka.topics.*` | `order-events`, `cart.checkout.initiated`, etc `:196-202` | Topic names — `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` `docker-compose.yml:74` |
| `app.outbox.poll-millis` / `batch-size` | `2000` / `50` `:204-205` | Outbox publisher cadence |
| `app.features.csv-export` / `reporting` | `true` / `false` `:209-210` | `FeatureFlags` bean `:34` |

---

## 3. `application.yml` vs `application-rag.yml` vs `docker-compose.yml`

### 3.1 `application.yml:1` — the truth owner

Likes to say *"Liquibase owns the schema"* (`application.yml:7` / `:37-48`):

```yaml
# application.yml:37
spring.liquibase.change-log: classpath:db/changelog/db.changelog-master.xml # :39
spring.jpa.hibernate.ddl-auto: validate  # :48 — never modifies, only checks
spring.datasource.url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:orderdb} # :29
```

All env defaults live here (`:29-31 :92-99 :121-134 :161-188`). No profile-specific logic — pure overridable defaults so the jar is environment-agnostic. Virtual threads at `:24`, graceful shutdown at `:137`, `actuator/health/liveness+readiness` at `:149-153`, OTLP off by default (`tracing.enabled: false` `:156`).

### 3.2 `application-rag.yml:1` — the AI profile

Activates with `--spring.profiles.active=rag` (`application-rag.yml:8`). Three things:

```yaml
# application-rag.yml:16
spring.ai.model.chat: deepseek       # :17 — breaks DeepSeek vs Ollama ChatModel ambiguity
spring.ai.model.embedding: ollama    # :18 — without this: "required a single bean, but 2 were found" :14
spring.ai.deepseek.api-key: ${DEEPSEEK_API_KEY:?Set DEEPSEEK_API_KEY env var} # :20 — hard require (vs :122 soft empty)
app.rag.enabled: true                # :27 — creates RagConfig :35 + RagService :29
app.rag.retrieval-mode: hybrid       # :40 — wiring choice, not code change
app.rag.eval.enabled: true           # :55
app.rag.eval.min-hit-rate: 0.7       # :57 — earned gate (75% measured, PR #44)
```

Rag is **opt-in decorator** — `RagConfig.java:35` + `DocumentIngestionService.java:50` + `AgentToolSet.java:32` all `@ConditionalOnProperty(app.rag.enabled=true)`. Without the profile the default app has zero AI beans, zero `ChatModel`, zero `PgVectorStore`.

### 3.3 `docker-compose.yml:10` — local infra that matches the defaults

```yaml
# docker-compose.yml:16 pgvector image (not plain postgres) — PR #44
postgres: pgvector/pgvector:pg16  # :16  pg16 + vector extension in SAME DB
  POSTGRES_DB: orderdb :19  POSTGRES_USER: order :20  POSTGRES_PASSWORD: order :21
  volumes: order-postgres-data:/var/lib/postgresql/data :28  # named volume, not bind mount
  healthcheck: pg_isready -U order -d orderdb :32

redis: redis:7-alpine :41  --appendonly yes :43  order-redis-data :47  # PR #28 @Cacheable
kafka: apache/kafka:3.7.0 :59  KRaft single-node :64  PLAINTEXT://localhost:9092 :67
  KAFKA_AUTO_CREATE_TOPICS_ENABLE=true :74
jaeger: jaegertracing/all-in-one:1.57 :84  16686 UI :87  4317 gRPC :88  COLLECTOR_OTLP_ENABLED=true :91
api: build Dockerfile :98  SPRING_PROFILES_ACTIVE=dev :104  DB_HOST=postgres :105
  depends_on: postgres/redis/kafka condition: service_healthy :108  # blocks until pg_isready/kafka topics list
  healthcheck: wget actuator/health/liveness :116
```

Key: service names `postgres`/`redis`/`kafka`/`jaeger` are **exactly** what `application.yml:92` + `:99` + `:161` default to when overridden — `dev` profile (`application-dev.yml:14`) plus compose `env` make local behave like `prod` without code change.

---

## 4. K8s overlays — `k8s/base` vs `dev/test/uat/staging/prod`, secrets handling

### 4.1 Base — shared truth (`k8s/base/kustomization.yaml:1`)

```yaml
# k8s/base/kustomization.yaml:3
resources: [deployment.yaml, service.yaml, configmap.yaml, hpa.yaml, ingress.yaml] # :4-8
```

| Base file | Role | Key line |
|---|---|---|
| `k8s/base/configmap.yaml:1` | Non-secret env | `SPRING_PROFILES_ACTIVE=staging` `:6` (placeholder), `DB_HOST=postgres` `:7`, `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` `:9` |
| `k8s/base/deployment.yaml:1` | Pod spec | `replicas: 2` `:6`, `envFrom: configMapRef+secretRef` `:23-26`, `liveness/readiness/startupProbe` on `/actuator/health/*` `:37-54`, `terminationGracePeriodSeconds: 25` `:15` > `spring.lifecycle.timeout-per-shutdown-phase: 20s` `application.yml:18` |
| `k8s/base/service.yaml:1` | `ClusterIP :80→:8080` | `k8s/base/hpa.yaml:1` `min 2 max 10 cpu 70%` `:10-17`, `k8s/base/ingress.yaml:1` TLS via `cert-manager.io/cluster-issuer: letsencrypt-prod` `:7` |

### 4.2 Overlays — one `kustomization.yaml` per env (`k8s/overlays/*/kustomization.yaml:1`)

All five overlays follow the same shape — `namePrefix` + `commonLabels: env` + `configMapGenerator behavior: merge` + `replicas` + `images newTag`:

| Overlay | `SPRING_PROFILES_ACTIVE` | Replicas (`kustomization.yaml:12`) | Image tag `:15` | Intent |
|---|---|---|---|---|
| `dev` | `dev` `:7` | `1` `:13` | `develop` | Single replica, `dev` allows `ddl-auto:update` for experiments |
| `test` | `dev` `:7` | `3` `:13` | `test` | HA but `dev` cache/kafka knobs |
| `uat` | `dev` `:7` | `3` `:13` | `uat` | Pre-prod gate |
| `staging` | `prod` `:7` | `3` `:13` | `staging` | `prod` parity — `validate` `:12` + `tracing.enabled` |
| `prod` | `prod` `:7` | `3` `:13` | `prod` | `validate` + `show-sql false` + real Redis `REDIS_HOST` (no localhost fallback) `application-prod.yml:37` |

```yaml
# k8s/overlays/prod/kustomization.yaml:1 (others identical except dev replicas:1/tag:develop)
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources: [../../base]
namePrefix: prod-           # :5  → prod-order-management-api
commonLabels: { env: prod } # :7
configMapGenerator:         # :8 merge — overrides base configmap.yaml:6
  - name: order-api-config
    literals: [SPRING_PROFILES_ACTIVE=prod, DB_NAME=orderdb]
replicas: [{ name: order-management-api, count: 3 }]
images: [{ name: ghcr.io/sushantac/order-management-api, newTag: prod }]
```

### 4.3 Secrets — never in ConfigMap, never in git

```
# k8s/samples/db-secret.example.yaml:1 — plaintext shape (DEV ONLY — never commit real)
apiVersion: v1 / kind: Secret / name: order-api-secrets :10
  DB_USER: b3JkZXI=     # :11 base64(order)
  DB_PASSWORD: b3JkZXI= # :12 base64(order) — DEV ONLY
# k8s/samples/sealed-db-secret.example.yaml:7 — what IS committed
apiVersion: bitnami.com/v1alpha1 / kind: SealedSecret :7
  encryptedData: DB_PASSWORD: AgBv7d… :12  # kubeseal output, only controller decrypts
# Real flow (header comment :3-5 in db-secret.example.yaml):
kubectl create secret generic order-api-secrets --dry-run=client -o yaml | kubeseal --format yaml > k8s/overlays/prod/sealed-db-secret.yaml
```

`k8s/base/deployment.yaml:22` pulls both at once: `envFrom: configMapRef: order-api-config + secretRef: order-api-secrets`. ArgoCD (`k8s/argocd/`) watches the env branch and converges — promotion is a commit bumping `newTag`.

---

## 5. How to use — copy-paste `.env` for local rag, `docker-compose up`, Testcontainers per-test tag, `kubectl apply`

### 5.1 Local without RAG (zero AI)

```bash
docker compose up -d postgres redis kafka jaeger  # infra only
./mvnw spring-boot:run  # default profile — Liquibase validate, simple cache, tracing off
curl -s http://localhost:8080/actuator/health | jq .status  # UP
```

### 5.2 Local with RAG — copy-paste `.env` / export block

```bash
# 1. Infra must be pgvector (not plain postgres) — already is at docker-compose.yml:16
docker compose up -d postgres redis kafka jaeger
# If switching from old plain postgres image: docker compose down -v && docker compose up -d

# 2. Ollama + embedding
brew install ollama && ollama serve &
ollama pull nomic-embed-text   # 768-dim, EmbeddingModel for RagConfig.java:35

# 3. Env — the only secret you need locally
export DEEPSEEK_API_KEY="sk-..."          # https://platform.deepseek.com  application-rag.yml:20
export DB_HOST=localhost DB_PORT=5432 DB_NAME=orderdb DB_USER=order DB_PASSWORD=order  # application.yml:29
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318  # jaeger OTLP :161

# 4. Run — rag profile + optional flags
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag
# with query rewriting + reranking (PR #51 — both off by default):
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=rag,--app.rag.query-rewriting.enabled=true,--app.rag.reranking.enabled=true
# ingest is automatic (DocumentIngestionService.java:50 on startup) — watch logs for "RAG retrieval eval"

# 5. OR run everything in compose (api joins network — DB_HOST=postgres already at docker-compose.yml:105)
docker compose up --build  # api: SPRING_PROFILES_ACTIVE=dev :104
# for RAG in compose: add to api.environment in docker-compose.yml:103
#   SPRING_PROFILES_ACTIVE: dev,rag
#   DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY}
```

### 5.3 Testcontainers — per-test tag isolation

```java
// Every integration test: OrderManagementApiApplicationTests.java:25 + all *IntegrationTest.java:38
@Testcontainers @SpringBootTest
@TestPropertySource(properties = "integration.database.tag=MyTest") // unique per class — see below
class MyTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine"); // :30
    @Test void contextLoads() {} // Liquibase run IS the assertion
}
// + Redis/Kafka tests use @DynamicPropertySource at CachingRedisIntegrationTest.java:57
```

Why `integration.database.tag` is unique per class (e.g. `CachingRedisIntegrationTest` `:63`, `SchemaValidationIntegrationTest` `:34`): global state (`Ehcache CacheManager`, Hibernate `generate_statistics :56`, JVM listeners) leaks across contexts — unique tag forces a fresh `ApplicationContext` + fresh container per class. This is the *"single most valuable testing lesson"* in `learnings/README.md:228`. Run:

```bash
./mvnw test -Dtest=OrderManagementApiApplicationTests  # smoke: context + Liquibase
./mvnw verify -Dspring.profiles.active=rag -Dapp.rag.eval.enabled=true  # eval gate + report at target/rag-eval-report.json
```

### 5.4 K8s — `kubectl apply` via Kustomize / ArgoCD

```bash
# Preview what overlay will apply
kubectl kustomize k8s/overlays/dev | less
kubectl kustomize k8s/overlays/prod | less   # 3 replicas, SPRING_PROFILES_ACTIVE=prod

# Direct apply (without ArgoCD)
kubectl apply -k k8s/overlays/dev
kubectl apply -k k8s/overlays/prod
# Secrets: create plain then seal — header in k8s/samples/db-secret.example.yaml:3
kubectl create secret generic order-api-secrets --from-literal=DB_PASSWORD=... --dry-run=client -o yaml \
  | kubeseal --format yaml > k8s/overlays/prod/sealed-db-secret.yaml
kubectl apply -f k8s/overlays/prod/sealed-db-secret.yaml

# ArgoCD (GitOps — promo = commit bumping newTag)
kubectl apply -f k8s/argocd/    # Application watches overlays/{env}, converges cluster
# rollback = git revert the tag bump commit
```

---

## 6. How to verify — `actuator/env`, `psql`, `kafka topics`

### 6.1 Actuator — prove env vars landed

```bash
./mvnw spring-boot:run & sleep 12
# Effective config (sanitised — passwords masked as ******)
curl -s http://localhost:8080/actuator/env | jq '.propertySources[] | select(.name | contains("systemEnvironment")) | .properties | with_entries(select(.key | test("DB_|KAFKA|OTEL|REDIS")))'
# OR: curl -s http://localhost:8080/actuator/env/spring.datasource.url | jq .
# OR: curl -s http://localhost:8080/actuator/env/app.rag.retrieval-mode | jq .

# K8s variant — port-forward then same curl
kubectl port-forward deploy/prod-order-management-api 8080:8080 &
curl -s http://localhost:8080/actuator/env | jq '.propertySources[] | .name'

# Compose api container
docker compose exec api wget -qO- http://localhost:8080/actuator/env | jq '.activeProfiles'
# → ["dev"]  or ["rag"]  — matches SPRING_PROFILES_ACTIVE at docker-compose.yml:104
```

### 6.2 Postgres (+ pgvector) — `psql` against docker-compose or Testcontainers

```bash
# docker-compose postgres
psql "postgresql://order:order@localhost:5432/orderdb" -c "\dx"          # vector extension  pgvector/pgvector:pg16 :16
psql "postgresql://order:order@localhost:5432/orderdb" -c "\dt"          # Liquibase-owned tables + vector_store  RagProperties.java:32
psql "postgresql://order:order@localhost:5432/orderdb" -c "select count(*) from vector_store;"  # RAG chunks (rag profile only)
psql "postgresql://order:order@localhost:5432/orderdb" -c "select count(*) from databasechangelog;"  # Liquibase proof

# K8s postgres (port-forward svc)
kubectl -n order-api port-forward svc/postgres 5432:5432 &
psql "postgresql://order:order@localhost:5432/orderdb" -c "select version();"

# Liquibase drift guard: app fails fast if entity vs DB mismatch
./mvnw spring-boot:run -Dspring.profiles.active=prod 2>&1 | grep "validate"
# Hibernate ddl-auto:validate :48  — prod never update, staging/prod overlays enforce prod profile
```

### 6.3 Kafka — topics / outbox

```bash
# docker-compose kafka (KRaft, no ZooKeeper)
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# expect: order-events, order-events.DLT, cart.checkout.initiated, order.placed  application.yml:196-202
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-events --from-beginning --max-messages 5

# Verify outbox publisher — create an order, check topic
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-Key: dev-api-key-orderapi" -H "Content-Type: application/json" -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq .
psql "postgresql://order:order@localhost:5432/orderdb" -c "select status, count(*) from outbox group by status;"  # PUBLISHED vs PENDING
curl -s http://localhost:8080/actuator/metrics | jq -r '.names[]' | grep kafka

# K8s kafka — same via service DNS
kubectl -n order-api exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

### 6.4 Jaeger / tracing + one-command smoke

```bash
# Tracing (management.tracing.enabled=true in prod profile application-prod.yml:48)
open http://localhost:16686   # docker-compose jaeger UI :87
curl -s http://localhost:8080/api/v1/customers -H "X-API-Key: dev-api-key-orderapi" >/dev/null
# then search service=order-management-api in Jaeger — OTLP endpoint application.yml:161

# RAG eval report (rag profile)
cat target/rag-eval-report.json | jq '{retrievalMode, hitRateAtK, top1Accuracy, goldens}'
# { hitRateAtK:0.75, retrievalMode:"HYBRID", ... }  RagEvalRunner.java:135  gate min-hit-rate:0.7 :122

# One-command smoke
curl -sf http://localhost:8080/actuator/health | jq -e '.status=="UP"' && echo ok
curl -sf http://localhost:8080/actuator/health/liveness | jq .status  # K8s livenessProbe :38
curl -sf http://localhost:8080/actuator/health/readiness | jq .status # readinessProbe :44
psql "postgresql://order:order@localhost:5432/orderdb" -c "select count(*) from databasechangelog;" | grep -q . && echo liquibase-ok
docker compose ps  # all healthy? api waits on postgres/redis/kafka service_healthy :108
```

**Job lens**

* **Build** — single `application.yml:9` with `${VAR:default}` everywhere (`:29 :92 :99 :161 :186`) so the jar is env-agnostic; `Liquibase :37` owns DDL (`validate :48` prod, `update :12` dev-only), `docker-compose.yml:10` uses `pgvector/pgvector:pg16 :16` so vector + full-text live in one DB; rag profile `application-rag.yml:16` disambiguates `deepseek/ollama` chat/embedding.
* **Operate** — non-secrets via `k8s/base/configmap.yaml:5` `envFrom :23`, secrets via `db-secret.example.yaml:11` sealed with `kubeseal` to `sealed-db-secret.example.yaml:12`; overlays (`dev 1×develop` vs `prod 3×prod` at `k8s/overlays/*/kustomization.yaml:12`) encode `SPRING_PROFILES_ACTIVE` + image promotion; `deployment.yaml:15` `terminationGracePeriodSeconds:25` > `application.yml:18` `timeout-per-shutdown-phase:20s` + probes `:37-54` + `HPA min 2 max 10`.
* **Interview (90s)** — "Config is env-var-first `application.yml:29` `DB_HOST:localhost` overridden by `docker-compose.yml:105` `postgres` (compose DNS) and `k8s/base/configmap.yaml:7` + `envFrom` `deployment.yaml:22`; secrets are `SealedSecret :12` not ConfigMap, `kubeseal` flow in `db-secret.example.yaml:3`; `Liquibase :39` owns schema `validate :48` (`dev` alone `update :12`), `pgvector/pgvector:pg16 :16` co-locates vectors; rag is opt-in `app.rag.enabled` `RagConfig.java:35` with `application-rag.yml:20` hard-requiring `DEEPSEEK_API_KEY` and `model.chat:deepseek :17` fixing the ChatModel ambiguity; K8s overlays share `k8s/base` and differ only in `kustomization.yaml:7` `SPRING_PROFILES_ACTIVE` + `replicas` + `newTag`; verified via `actuator/env` + `psql \dx` + `kafka-topics.sh --list`."

**Interview Q&A — 3 you can now answer**

**Q1: "Why does the same jar boot on your laptop, CI, and prod without rebuild?"**
> "`application.yml:9` every property is `${ENV:default}` — `DB_HOST:localhost :29`, `REDIS_HOST:localhost :92`, `KAFKA:localhost:9092 :99`, `OTEL:localhost:4318 :161`. Laptop `docker-compose.yml:104` sets `DB_HOST=postgres`/`KAFKA=kafka:9092` (compose DNS), CI relies on `localhost` defaults with Testcontainers `ServiceConnection` (`OrderManagementApiApplicationTests.java:30`), K8s uses `k8s/base/configmap.yaml:5` + `k8s/overlays/*/kustomization.yaml:7` merging `SPRING_PROFILES_ACTIVE` and `envFrom` at `deployment.yaml:22`. No profile edits the jar."

**Q2: "Where do secrets live and how do you avoid committing them?"**
> "Never in `configmap.yaml:5` — only non-secrets there. `k8s/samples/db-secret.example.yaml:1` shows the plaintext shape (`DB_PASSWORD: b3JkZXI= :12` DEV ONLY) and its header `:3` says `kubectl create secret … | kubeseal > sealed-db-secret.yaml`. What IS committed is `k8s/samples/sealed-db-secret.example.yaml:7` `kind: SealedSecret` with `encryptedData: DB_PASSWORD: AgBv… :12` — only the in-cluster Sealed Secrets controller can decrypt. `deployment.yaml:22` `envFrom: secretRef: order-api-secrets` merges them at runtime."

**Q3: "Prod runs `prod` profile while dev runs `dev` — what breaks if someone ships `dev` to prod?"**
> "`application-dev.yml:12` is `ddl-auto: update` (Hibernate adds columns, never drops — fights Liquibase), `application-prod.yml:12` is `validate` (fails fast on drift). `show-sql:true :44` + `generate_statistics:true :56` (dev) vs `WARN/false :22` (prod). `application-prod.yml:48` enables `management.tracing.enabled` + `REDIS_HOST` without default (fails fast if unset). Overlays enforce this: `k8s/overlays/prod/kustomization.yaml:7` `SPRING_PROFILES_ACTIVE=prod` + `replicas:3 :13` vs `dev :13` `replicas:1`. Shipping `dev` to prod would leak SQL logs and allow unsafe DDL."

**Honest limits**

* `DB_PASSWORD=order` default (`application.yml:31`) and `api-key dev-api-key-orderapi :188` `jwt-secret local-learning… :186` are learning values — real rotation needs `External Secrets Operator`/`Vault`, not `SealedSecret` alone, and `HS256` (`SecurityConfig.java:86`) needs `RS256+JWKS`.
* `docker-compose.yml:74` `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` is dev convenience — prod should pre-create topics with `replication>1` and ACLs (`application.yml:196` `order-events.DLT` etc).
* `REDIS_HOST` default `localhost` (`application.yml:92`) masks missing infra — `application-prod.yml:37` removes the default to fail fast, but `dev`/`test` overlays reuse `dev` profile (not `prod` parity) per `k8s/overlays/test/kustomization.yaml:7`.
* `OTEL_EXPORTER_OTLP_ENDPOINT http://localhost:4318 :161` with `tracing.enabled:false :156` default means traces are silent unless `prod` enables — local `dev` needs explicit `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318` or tracing stays off.
* RAG `DEEPSEEK_API_KEY` hard-requires (`:?` at `application-rag.yml:20`) breaks the app if profile is on but key absent — desirable for fail-fast but needs `ExternalSecret` in K8s, not plain env injection.

> Next: [`16-security-pii-rate-limit-resilience.md`](./16-security-pii-rate-limit-resilience.md) · [`15-a2a-multi-agent.md`](./15-a2a-multi-agent.md) · back to [`README.md`](./README.md)

<!-- 360 lines · covers application.yml:29 DB_* /:92 REDIS /:99 KAFKA /:161 OTEL /:180 security + application-rag.yml:20 DEEPSEEK required /:27 rag.enabled /:40 retrieval-mode + application-dev.yml:12 update + application-prod.yml:12 validate + docker-compose.yml:16 pgvector /:104 SPRING_PROFILES_ACTIVE + k8s/base/deployment.yaml:22 envFrom /:37 probes + k8s/base/configmap.yaml:5 + k8s/overlays/*/kustomization.yaml:7 + k8s/samples/*.yaml:12 sealed -->
