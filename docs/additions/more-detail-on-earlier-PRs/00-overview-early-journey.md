# 00. Overview — Early Journey #1-#35: Data, Persistence, API, Security, Events, Platform

> 35 PRs from `README.md:1772` roadmap grouped into 6 planes, each with purpose → problem → implementation → how to use → job lens.

---

## 1. Purpose

The base journey (#1-#35) is the production-grade skeleton that the bonus AI arc (#38-#52) assumes: data foundations, persistence, API surface, security/compliance, events/resilience, platform. This overview maps the 35; each per-PR doc is the deep dive.

---

## 2. The 6 Planes (ASCII)

```
docs/additions/more-detail-on-additions/   ← bonus AI (#38-#52) assumes this base

[1-7] Data Foundations: skeleton → Liquibase → JPA entities → cascading → fetch → batch
  ↓
[8-14] Persistence Deep Dive: locking → auditing → queries → specs → callbacks → validation → SQL log
  ↓
[15-25] API Surface: DTOs → controllers → validation → exception/idempotency → OpenAPI
  ↓
[26-28] Security & Compliance: JWT/API-key → PII/GDPR → Redis cache
  ↓
[29-31] Events & Resilience: Resilience4j → virtual threads → outbox/Kafka
  ↓
[32-35] Platform: observability → Docker/K8s → CI/CD → enterprise
```

---

## 3. File Map

| Plane | PRs | Key Files |
|-------|-----|-----------|
| Data | #1-#7 | `pom.xml`, `domain/*.java`, `db/changelog/v1.0/*`, `Repository.java` |
| Persistence | #8-#14 | `Order.java:151`, `OrderService.java:81`, `application.yml:180` |
| API | #15-#25 | `OrderController.java:22`, `dto/OrderResponse.java`, `OpenApiConfig.java` |
| Security | #26-#28 | `SecurityConfig.java:60`, `PiiRedactionFilter.java`, `AuditLog.java` |
| Events | #29-#31 | `OutboxEntry.java`, `KafkaConfig.java`, `resilience4j` |
| Platform | #32-#35 | `ObservabilityConfig.java`, `Dockerfile`, `k8s/base`, `.github/workflows` |

---

## 4. How to Use (60s smoke)

```bash
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq .
psql -c "\d orders" -c "SELECT * FROM outbox LIMIT 5;"
docker compose up -d postgres redis kafka
kubectl apply -k k8s/base; kubectl get pods
```

---

## 5. Job Lens

Pick one plane, run its **How to Use** one-liner, then narrate Purpose→Problem→Implementation→Job Lens from that doc — 90s, job-ready. See `README.md` table for 35 rows.

> Next: [`01-project-setup.md`](./01-project-setup.md) or [`README.md`](./README.md) · Bonus: [`../more-detail-on-additions/README.md`](../more-detail-on-additions/README.md)
