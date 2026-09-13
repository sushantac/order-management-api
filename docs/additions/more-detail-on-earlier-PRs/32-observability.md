# 32. Observability (PR #32)

> PR #32 — Micrometer, Prometheus, OTel tracing, structured logs, Actuator. Stack: Java 21, Spring Boot 3.4.1, `micrometer-registry-prometheus:291`, `micrometer-tracing-bridge-otel:294`, `opentelemetry-exporter-otlp:299`, `logstash-logback-encoder 7.4:303`, `ObservabilityConfig.java:20`, `AppInfoHealthIndicator.java:14`, `application.yml:139-162 logging 168-177`. See `README.md:1772` roadmap `| 32 | Observability |`.

---

## 1. Purpose — what shipped

PR #32 turns the API from a black box into three signals: **metrics** (`Micrometer 291` → `Prometheus registry:29` scraped at `/actuator/prometheus:144`), **traces** (`micrometer-tracing-bridge-otel 294` + `opentelemetry-exporter-otlp 299` → `OTLP endpoint 161` Jaeger), **logs** (`logstash-logback-encoder 303` JSON layout per prod profile). `ObservabilityConfig.java:20` (`@Configuration`) registers `TimedAspect:23` (activates `@Timed`) and `PrometheusMeterRegistry:29` (`PrometheusConfig.DEFAULT`). `AppInfoHealthIndicator.java:14` (`implements HealthIndicator`) contributes `component order-management-api + journey PR #32` to `/actuator/health:19`. Existing `@Timed` on `ProductCatalogueService.get:52` and `OrderService` now emit `product_get_seconds`, `resilience4j_*`, `hikaricp_*`, `jvm_*` series. `management:139` exposes `health,info,metrics,prometheus:144` with `probes:151 liveness/readiness` for K8s.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `org.hibernate.SQL DEBUG 175` + `actuator/health` existed, but no `prometheus:144` endpoint, no `@Timed 52` aggregation, no `traceId/spanId` propagation, no JSON logs. Operators guessed latency from logs; `breaker OPEN 5s:228` and `outbox PENDING 12` depth were invisible; `RateLimiter 429` had no counter. `TimedAspect:23` missing made `@Timed("product.get") 52` inert.

**After:** `GET /actuator/prometheus` emits `HELP/TYPE` exposition for Prometheus scraping; `GET /actuator/metrics/{name}` JSON. `TimedAspect:23` weaves `product.get` histogram `p50/p95 0.95:52`. Tracing when `management.tracing.enabled true:155` (dev/prod) samples `probability 0.1:158` and exports `OTLP 161 http://localhost:4318`. Prod log profile switches to `LogstashEncoder` JSON with `traceId`. Health `/actuator/health` merges `db + redis + AppInfoHealthIndicator 14`.

### Theory — Metrics, traces, logs and Actuator from first principles (100+ lines)

#### 2.1 The three pillars — correlated by traceId

Observability = metrics (numbers over time), traces (request causally), logs (discrete events). Correlation key is `traceId` (128-bit) + `spanId` per service hop. `Micrometer Tracing` (`bridge-otel 294`) creates `Span` per `DispatcherServlet` request, stores in `ThreadLocal` (virtual-thread safe) and `MDC` keys `traceId/spanId`, so every `log.warn 73 OutboxPublisher` automatically carries trace. `Actuator` `management:139` is the control plane exposing them as HTTP.

#### 2.2 Micrometer — MeterRegistry, Timer, Counter, Gauge

`MeterRegistry` is a dimensional TSDB facade. `PrometheusMeterRegistry:29` extends it with Prometheus exposition format (`HELP # TYPE`). Instruments:

- `Timer` (`@Timed 52` → `TimedAspect 23`): records `count, sum, max, histogram`. `product.get_seconds_bucket{le="0.005"}` distinguishes cache hit `<5ms` vs miss `~20ms` (`SELECT 55`).
- `Counter` (`AiMetrics.java 1` `Counter` per LLM token): monotonic `total`.
- `Gauge` (`jvm.memory.used`, `hikaricp_connections_active`): sampled value.
- `DistributionSummary` (`AiMetrics`): histogram without timing.

Tags = labels: `cache=products`, `outcome=SUCCESS`, `exception=PaymentFailedException`. Alternative `Dropwizard Metrics` similar but Micrometer is vendor-neutral (Prometheus, Datadog, OTel). `management.endpoints.web.exposure.include health,metrics,prometheus 144` selects scrape surface; `info` also exposed.

#### 2.3 Prometheus exposition — pull model

`GET /actuator/prometheus` (`PrometheusMeterRegistry 29`) renders:

```
# HELP product_get_seconds Timer of product.get
# TYPE product_get_seconds histogram
product_get_seconds_count{method="get",outcome="SUCCESS"} 4200
product_get_seconds_sum  89.3
product_get_seconds_bucket{le="0.005"} 3800   // hit
product_get_seconds_bucket{le="0.02"}  4100   // miss
# HELP resilience4j_circuitbreaker_state gauge 1=OPEN
resilience4j_circuitbreaker_state{name="paymentGateway",state="closed"} 1
```

Prometheus scrapes every `15s`; no agent push. `micrometer-registry-prometheus 291` bundles `PrometheusMeterRegistry`; custom `PrometheusConfig.DEFAULT` `29` uses default histogram. Alternative `OTLP push` for metrics via `micrometer-registry-otlp` would unify with tracing exporter `299` but pull is simpler per `docker-compose prometheus`.

#### 2.4 TimedAspect — why annotation needs a bean

`@Timed("product.get") 52` is inert without `TimedAspect 23` (`@Aspect` via `spring-boot-starter-aop 109`). `ObservabilityConfig:23 timedAspect(MeterRegistry)` returns `TimedAspect` which advises `@Timed` methods via `AopProxy` (same layer as `@Cacheable`/`@Transactional`). Without this bean, `ProductCatalogueService.get 54` would not create `product_get_seconds`. `management.tracing.enabled` separate — `TimedAspect` works even when tracing `false 155`.

#### 2.5 OpenTelemetry tracing — sampler, exporter, Jaeger

`micrometer-tracing-bridge-otel 294` creates `Span` with `traceId` random 128-bit, `parentId` for caller (no upstream here). `management.tracing.sampling.probability 0.1 158` samples 10% (not all: prod 1k rps → 100 traces/s bounded). `management.otlp.tracing.endpoint http://localhost:4318 161` is `OTLP HTTP` collector (Jaeger `docker-compose jaeger`). `opentelemetry-exporter-otlp 299` sends `Span` via `HTTP/protobuf`. When `enabled false 155` (default), `Span` is no-op (zero overhead). `management.tracing.enabled true` in `application-dev.yml/prod.yml` activates.

```
Request GET /api/v1/products/1 → Span http GET /api/v1/products/{id} (traceId=abc)
  ├─ cache get products::1 (span)
  └─ SELECT * FROM products WHERE id=1 (span via p6spy not enabled, but Hibernate stat 177 supplements)
```

Trace links payment retry `200ms` spans: each `attempt 74` retry creates child span. Alternative `Zipkin` (`micrometer-tracing-bridge-brave`) similar but OTel is CNCF standard; `Jaeger` UI queries `traceId`.

#### 2.6 Structured logs — LogstashEncoder JSON + MDC

`logstash-logback-encoder 303 7.4` provides `LogstashEncoder` layout in `logback-spring.xml` prod profile: each line is JSON `{"timestamp":"..","level":"WARN","logger":"OutboxPublisher:73","message":"..","traceId":"abc","spanId":"123","thread":"VirtualThread[#45]"}`. When tracing active, `MDC traceId` auto-populated by `OtelMDC`. Virtual thread name `VirtualThread[#n]` shows per-request. Non-prod keeps pattern `%d{HH:mm} %t %level %logger - %msg` with `%X{traceId}` manually. Alternative `ECS JSON` similar.

#### 2.7 Health — composite merging

`Actuator health 146 show-details always 148 probes enabled 152` plus `AppInfoHealthIndicator 14` `@Component` auto-discovered via `HealthContributorRegistry`. Response:

```json
// GET /actuator/health
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},"appInfo":{"status":"UP","details":{"component":"order-management-api","journey":"PR #32"}}},"groups":["liveness","readiness"]}
```

`liveness` checks JVM alive; `readiness` checks `db + redis` (K8s probes `k8s/deployment.yaml` `livenessProbe /actuator/health/liveness: initial 30s` `readiness 10s`). Custom indicator useful for dashboard grep. Alternative `HealthIndicator` for `outbox lag` would surface `countByStatus PENDING 12 >1000` as `DOWN`.

#### 2.8 Actuator exposure and security trade

`management.endpoints.web.exposure.include health,info,metrics,prometheus 144` — no `env,beans,heapdump` (sensitive). Production restricts via `management.endpoint.health.show-details when-authorized` not `always 148` (dev choice). `prometheus` unauthenticated here (scrape by `Prometheus` inside VPC); external gateway should mTLS.

> Interview anchor: "`ObservabilityConfig.java:20 TimedAspect23 PrometheusRegistry29 PrometheusConfig.DEFAULT` activates `@Timed52 product.get histogram p95` and `/actuator/prometheus144`. `AppInfoHealthIndicator14` adds `component+journey` to `/actuator/health`. `application.yml:139-162` `health,metrics,prometheus exposure144 probes152 tracing false155 sample0.1 158 OTLP4318 161`; `logging 168-177 DEBUG com.company DEBUG, SQL DEBUG 175`. `prometheus scrape pull, OTel push Jaeger, Logstash JSON prod carry traceId via MDC on virtual threads`."

#### 2.9 Cardinality danger

Tags `aggregateId` or `productId` as label would explode series (cardinality). Keep `product.get` low-cardinality (`method="get"`), not per-id. `resilience4j` tags `name=paymentGateway` bounded. `Micrometer` docs warn `100k series` memory blowup.

#### 2.10 Dashboard wiring

`prometheus 144` scrape `job order-api` → `Grafana` panels: `rate(product_get_seconds_count[5m])` (RPS), `histogram_quantile(0.95, product_get_seconds_bucket)` (p95), `hikaricp_connections_active`, `resilience4j_circuitbreaker_state`, `logback_events_total{level=warn}`. Alert `outbox PENDING 12` via `jvm custom gauge` or `info`.

#### 2.11 K8s probe mapping

`Deployment livenessProbe /actuator/health/liveness 30s 10s period` restarts pod when `Health DOWN`. `readinessProbe /actuator/health/readiness 10s 5s` removes pod from `Service` when `db` down (DB failover). `startupProbe /actuator/health/liveness failureThreshold30 period5` allows `150s` boot (Liquibase `validate 48` + `Hikari` init) before liveness kicks in.


#### 2.14 Metrics cardinality guard — label regex

`MeterFilter deny` `uri=/api/v1/orders/{id}` variable vs literal `/api/v1/orders/42` must be tagged as `{id}` template, not concrete id, via `WebMvcTagsProvider` bean — prevents cardinality `42,43,...` explosion (`10k` series). Current `product.get` low-cardinality correct.


#### 2.15 Cardinality sampling — trace-aware metrics

Link `product_get_seconds_bucket` exemplar `traceId abc` via `prometheus exemplars 1.13` (future) so high `p95` spike jumps to `Jaeger traceId`; current `Micrometer` 1.13 flag off but `OTLP 161` already correlates via `MDC`.


---

## 3. Solution — ASCII

```
Request  GET /api/v1/products/1  X-API-Key
       │  Filter RateLimiter 35 → Security JWT → Controller 48 catalogue.get 49
       │     Micrometer Span (traceId abc, sampled 10% 158 if enabled 155)
       │     MDC traceId → log lines carry trace
       │     TimedAspect 23 intercepts @Timed 52 get
       │       ├─ Timer product_get_seconds hist p95 0.95 record
       │       └─ cache hit/miss path → @Cacheable 50 (products::1)
       │     SELECT 55 if miss (org.hibernate.SQL DEBUG 175)
       │  ───────────────────────────────────────────────────────────────────► HTTP 200 JSON
       │
       ▼  Actuator plane (management 139)
  /actuator/health 146 show-details always 148 probes 151 liveness/readiness true
    └─ HealthContributor AppInfoHealthIndicator 14 (component journey) + db/redis composite → UP
  /actuator/metrics/{name} → MeterRegistry query (Timer/Counter/Gauge)
  /actuator/prometheus 144 ← PrometheusMeterRegistry 29 PrometheusConfig.DEFAULT scrape pull 15s
    ├─ jvm_threads_live/virtual, process_cpu, hikaricp_connections_*, product_get_seconds_bucket
    ├─ resilience4j_circuitbreaker/rertry/bulkhead/ratelimiter series
    └─ Grafana dashboard rate(5m) histogram_quantile 0.95
  /actuator/info → app name/desc info.app 163

Tracing (when enabled 155 dev/prod):
  spring.threads.virtual 24 request virtual thread → Span http GET /{id} traceId abc parent none
    ├─ child Span product.get cache lookup
    └─ child Span attempt 74 retry child spans
    → OTLP HTTP 161 localhost:4318 → Jaeger collector → UI traceId lookup (sampler 0.1 158)

Logging:
  app (DEBUG com.company 172, SQL DEBUG 175 TRACE bind 176 stat DEBUG 177)
    └─ logback-spring.xml prod: LogstashEncoder 303 JSON {timestamp, level, logger, msg, traceId, spanId, thread VirtualThread[#n]}
       non-prod: pattern %d %t %level %logger - %msg %X{traceId}

Config: application.yml 139-177 + ObservabilityConfig 20 (TimedAspect23 + Registry29)
Infra: docker-compose prometheus scraper + jaeger 4318 + log aggregator
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/config/ObservabilityConfig.java` | `10-32` | Metrics glue | `@Configuration 19 TimedAspect 23 MeterRegistry PromRegistry 29 PrometheusConfig.DEFAULT` |
| `src/main/java/com/company/orderapi/observability/AppInfoHealthIndicator.java` | `14-23` | Health | `@Component HealthIndicator 14 Health.up component journey build 19-22` |
| `src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java` | `52` | Timed | `@Timed product.get 52 p95 0.95` `TimedAspect` records |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `21-52` | Timed order | `import Timed 21` placeOrder timed similarly |
| `src/main/resources/application.yml` | `139-162` | Actuator+OTel | `endpoints.web.exposure 144 prometheus 144 health show-details 148 probes 151 tracing enabled false 155 sample 0.1 158 otlp 161 info 163` |
| `src/main/resources/application.yml` | `168-177` | Logging | `root INFO 170 com.company DEBUG 172 SQL DEBUG 175 bind TRACE 176 stat DEBUG 177` |
| `src/main/resources/logback-spring.xml` | — | Log layout | `LogstashEncoder 303` prod JSON vs pattern non-prod with `%X{traceId}` |
| `pom.xml` | `289-305` | Deps | `micrometer-registry-prometheus 291 tracing-bridge-otel 294 exporter-otlp 299 logstash 303 7.4` |
| `k8s/base/deployment.yaml` | — | Probes | `liveness /actuator/health/liveness 30s 10s readiness 10s 5s startup 30×5s` |

```java
// ObservabilityConfig.java:22-31
@Configuration public class ObservabilityConfig{
  @Bean public TimedAspect timedAspect(MeterRegistry r){ return new TimedAspect(r);} // 23 activates @Timed
  @Bean public PrometheusMeterRegistry prometheusMeterRegistry(){ return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);} //29
}
// AppInfoHealthIndicator.java:17-22
@Component public class AppInfoHealthIndicator implements HealthIndicator{
  public Health health(){ return Health.up().withDetail("component","order-management-api").withDetail("journey","PR #32").build();}}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Config present
grep -n "actuator\|prometheus\|tracing\|otlp\|TimedAspect\|HealthIndicator" src/main/java/com/company/orderapi/config/ObservabilityConfig.java src/main/java/com/company/orderapi/observability/AppInfoHealthIndicator.java src/main/resources/application.yml | head -n 30
grep -n "micrometer\|logstash" pom.xml | head -n 20  # 291 294 299 303
grep -n "@Timed" src/main/java/com/company/orderapi/domain/service/*.java  # 52 product.get

# Run and scrape
./mvnw spring-boot:run & sleep 12
curl -s http://localhost:8080/actuator/health | jq .  # db + appInfo journey PR #32
curl -s http://localhost:8080/actuator/health/liveness | jq .; curl -s http://localhost:8080/actuator/health/readiness | jq .
curl -s http://localhost:8080/actuator/info | jq .app
curl -s http://localhost:8080/actuator/metrics | jq '.names | sort' | head -n 30
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq .
curl -s http://localhost:8080/actuator/metrics/product.get 2>/dev/null | jq .  # after a GET
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" >/dev/null  # generate product.get timer
curl -s http://localhost:8080/actuator/prometheus | grep -E "product_get|jvm_threads|hikaricp|resilience4j" | head -n 40
kill %1

# Enable tracing locally
docker compose up -d jaeger 2>/dev/null; docker ps | grep jaeger
SPRING_PROFILES_ACTIVE=dev MANAGEMENT_TRACING_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 ./mvnw spring-boot:run & sleep 10
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" >/dev/null
open http://localhost:16686 2>/dev/null || echo "Jaeger UI http://localhost:16686 search traceId"

# Prod JSON logs
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run 2>&1 | head -n 20 | grep -E "traceId|spanId|\{.*level"
```

```java
// Add @Timed to any service method
@Timed(value="order.place", percentiles={0.5,0.95}, histogram=true)
public OrderResponse placeOrder(OrderRequest req){ ... }
// health contribution template
@Component public class OutboxLagHealthIndicator implements HealthIndicator{
  public Health health(){ long pending=outbox.countByStatus(PENDING); return pending>1000 ? Health.down().withDetail("pending",pending).build() : Health.up().withDetail("pending",pending).build(); }
}
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why | Cost |
|---|---|---|---|---|
| `TimedAspect 23 + PromRegistry 29` | Explicit beans `ObservabilityConfig 20` | Auto-config `spring-boot-starter-actuator` alone | Makes `@Timed 52` actually record + pull `prometheus 144` exposition | Two beans boilerplate |
| Pull `prometheus 144` scrape | Prometheus pull `PrometheusConfig.DEFAULT` | Push `OTLP` metrics gateway | Simple `docker-compose` scrape `15s`; no agent | Needs `Prometheus` own retention |
| OTel `10% 158` sampling disabled `false 155` | Sampled push `4318 161` dev/prod enable | `AlwaysOn` 100% | `1k rps` full trace is `1k spans/s` expensive; `10%` bounds | Rare trace may miss error |
| JSON logs via `LogstashEncoder 303` prod | `logstash-logback` structured | Plain pattern all env | `prod` JSON easy `traceId` grep; `non-prod` readable pattern | Two `logback-spring.xml` profiles |
| Composite health `AppInfo 14` | Custom `HealthIndicator` | Only `db` health | `component journey` visible for `readiness` grep | Extra `UP` detail |
| Expose `health,metrics,prometheus 144` only | `143` includes subset | `*` all endpoints | Avoids `env/beans/heapdump` leak `148 show-details always` dev only | Prods restrict `148` to `when-authorized` |

---

## 7. How to verify

```bash
grep -n "TimedAspect\|PrometheusMeterRegistry\|PrometheusConfig" src/main/java/com/company/orderapi/config/ObservabilityConfig.java  # 10 23 29
grep -n "AppInfoHealthIndicator\|component.*order-management\|journey.*PR #32" src/main/java/com/company/orderapi/observability/AppInfoHealthIndicator.java  # 14 20-21
grep -n "health,.*prometheus\|show-details\|probes.*enabled\|tracing.*enabled\|sampling.*probability\|otlp.*endpoint" src/main/resources/application.yml  # 144 148 151 155 158 161
grep -n "micrometer-registry-prometheus\|tracing-bridge-otel\|opentelemetry-exporter-otlp\|logstash" pom.xml  # 291 294 299 303
grep -rn "@Timed" src/main/java --include="*.java" | head  # 52 product.get
./mvnw test 2>&1 | grep -E "Tests run|Observability"
# Live
./mvnw spring-boot:run & sleep 12; curl -s http://localhost:8080/actuator/prometheus | grep -c "^# HELP" ; curl -s http://localhost:8080/actuator/health | jq .status; kill %1
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New endpoint → annotate service ` @Timed(value="widget.create", percentiles=0.95) 52` ensure `ObservabilityConfig 23` present. New health detail → `implements HealthIndicator 14` `@Component` pattern `Health.up withDetail...`. Need trace → set `MANAGEMENT_TRACING_ENABLED=true 155` profile `dev` plus `OTEL endpoint 161`. Logs auto carry `traceId` via `MDC` when `bridge-otel 294` active.
- **Operate:** `Prometheus` scrapes `prometheus 144` → alert `product_get_seconds_bucket p95 >20ms`, `hikaricp_pending >5`, `resilience4j_circuitbreaker_state OPEN`, `outbox PENDING` via custom gauge. `Jaeger` `traceId` browser finds failing `placeOrder 77` retry spans. `K8s` probes `liveness/readiness 30s/10s` use `AppInfo` to verify `PR #32` deployed.
- **Interview:** "PR #32: `ObservabilityConfig.java:20 TimedAspect23 PromRegistry29`, `AppInfoHealthIndicator14` in health, `application.yml 139-162 exposure144 probes152 tracing false155 sample0.1 158 OTLP4318 161 logging 168-177`, `pom 291 prometheus 294 bridge-otel 299 exporter 303 logstash`. Pull `prometheus` + push `OTLP` Jaeger + JSON `traceId` correlation; `product.get52` histogram hit vs miss."

---

## 9. Interview lens — Q&A

**Q1: Why does `@Timed 52` need `TimedAspect 23`?**
A: `spring-aop 109` aspect must be a bean to advice `@Timed`; `ObservabilityConfig 23` registers it, else inert (§2.4).

**Q2: Pull vs push metrics?**
A: `PrometheusMeterRegistry 29` pull `prometheus 144` `15s` scrape; push `OTLP` for traces `299` to `4318 161` (§2.3,2.5).

**Q3: How do logs carry `traceId`?**
A: `micrometer-tracing-bridge-otel 294` puts `traceId/spanId` into `MDC`; `LogstashEncoder 303` JSON includes them; visible `%X{traceId}` (§2.6).

**Q4: What health does `AppInfoHealthIndicator 14` add?**
A: Merged into `/actuator/health` composite `component+journey` details `19-22` for `readiness` grep (§2.7).

**Q5: Why `10% sampling 158` and `enabled false 155` default?**
A: Bounds `span/s` cost `1k rps →100/s`; default off keeps non-Jaeger tests zero overhead (§2.5).

**Q6: How do virtual threads appear in metrics?**
A: `jvm.threads.live/virtual` gauges show `VirtualThread[#]` parked count; `product.get_seconds_bucket` separates hit `<5ms` (§2.11).

**Q7: Probe mapping to K8s?**
A: `liveness /actuator/health/liveness` `30s10s` restart; `readiness 10s5s` removes `Service` on `db DOWN`; `startup 150s` covers `Liquibase 48` (§2.11).

---

## 10. Honest limits & next step → PR #33

`sample 0.1 158` may miss rare `PaymentFailedException` trace; raise per-route. No `exemplars` linking metric bucket to `traceId`. `logstash` JSON only in prod profile — dev parity low. No `SLO` `error budget` recording rule. `prometheus` `HELP` lacks `unit` (should be `seconds`). Next PR containers the observable service: multi-stage `Dockerfile` non-root `orderapi` + `K8s Deployment/Service/HPA/Ingress` carrying probes `liveness/readiness` and resource `requests 250m 512Mi` so pull `prometheus` and `HPA 70%` scale correctly.

See [`33-docker-and-kubernetes.md`](./33-docker-and-kubernetes.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Record `@Timed 52` | `TimedAspect` | `ObservabilityConfig.java:23` | Aspect bean |
| Scrape metrics | `PromRegistry 29 prometheus 144` | `ObservabilityConfig.java:29 application.yml:144` | Pull |
| Trace request | `bridge-otel + exporter OTLP 4318` | `application.yml:155-161 pom 294 299` | `sample 0.1` |
| JSON logs `traceId` | `LogstashEncoder` | `pom 303 logback-spring.xml` | MDC |
| Health `PR #32` | `AppInfoHealthIndicator` | `AppInfoHealthIndicator.java:14` | Composite |

#### 2.12 Exemplar support — linking metrics to traces (future)

Prometheus exemplars (`# HELP` with `traceId` sample) would link `product_get_seconds_bucket` high latency directly to `Jaeger traceId`. Not enabled here (needs `PrometheusMeterRegistry` exemplar config); future `micrometer-registry-prometheus` 1.13+ supports `with exemp`.

#### 2.13 Security of actuator — why not expose `env`

`env, beans, heapdump` would leak `DB_PASSWORD order` and `jwt-secret 186`. `exposure 144` allowlist `health,info,metrics,prometheus` is minimal; `show-details always 148` relaxed for learning — production switches to `when-authorized` + `roles`.

---
## Extra — runbook checklist

- `curl /actuator/prometheus | grep HELP` count >20 proves `ObservabilityConfig 20` loaded.
- `curl /actuator/health | jq .components.appInfo.details.journey` should be `PR #32`.
- `Jaeger http://localhost:16686` search service `order-management-api` after `MANAGEMENT_TRACING_ENABLED=true` request.
