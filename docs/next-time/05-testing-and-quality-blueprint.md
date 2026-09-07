# 05 — Testing & Quality Blueprint

How to test so behaviour is *proven*, not hoped - and so the first run's
defects become regression tests instead of surprises.

## 1. Test pyramid mapped to real infra
| Layer | What | Infra |
|---|---|---|
| Unit | mappers, maskers, transition maps, sequence allocator, validators | none |
| Integration (service/repo) | transactions, locking, outbox, projections | real Postgres (Testcontainers) |
| API | controllers end-to-end via MockMvc | real Postgres (+ Redis when under test) |
| Message | outbox→Kafka→consumer, DLT | @EmbeddedKafka |
| Schema truth | tables, FKs, indexes, "no card columns" | real Postgres catalogs |

## 2. Isolation rules (hard-won)
- Ehcache `CacheManager` and Hibernate stats are JVM-wide → **every integration
  test class gets its own Spring context and its own DB** via a unique
  `integration.database.tag`; L2 stays OFF except its dedicated test.
- Tests must not depend on execution order; `@TestMethodOrder` only where a
  shared state machine is intentionally exercised (and reset in `@BeforeEach`).
- Timing/circuit tests: use **Awaitility** polling instead of raw sleeps;
  where wall-clock is inherent (breaker wait, lock lease), keep sleeps short
  and tolerant (assert ranges, not exact values).

## 3. Empirical-gotcha regression list (encode each as a test)
Derived from the first run; if any of these regresses, CI should say so:
1. `orphanRemoval` requires cascade to issue a DELETE.
2. Nullable inverse `@OneToOne` cannot be lazy (choose owning/non-null).
3. Interface-projection aliases must match getter names.
4. Unknown URL returns 404, not 500 (catch-all must be last resort).
5. Method-security denial → 403 (never 500) and auth failure → 401.
6. Retry inside the circuit breaker ⇒ breaker counts requests, not attempts.
7. A poisoned Kafka message reaches the DLT and the group keeps processing.
8. Outbox: order + event row commit together; failed publish retries; PUBLISHED
   only after broker ack.
9. Cache eviction: every writer that changes a product (incl. stock via orders/
   locking) makes the next read fresh.
10. 100 concurrent buyers of the last unit → exactly one winner.
11. Redisson lease: a "crashed" holder's lock is reclaimed.
12. PII: anonymous/order_read get masked values; pii_read/API key get raw; logs
    never contain raw PII even for privileged callers.

## 4. Per-suite conventions
- Naming: `<Feature>IntegrationTest` (MockMvc/HTTP), `<Feature>Test` (pure),
  `<Domain>IntegrationTest` (service+repo), `DatabaseSchemaIntegrationTest`.
- Every test class carries the cache-off property + unique DB tag.
- Payment/outbox tests override the gateway with a deterministic fake
  (`@Primary` bean) - never random.
- Count-based asserts preferred: "1 query" not "fewer than N".
- Test files live next to the concept (e.g. `security/`, `messaging/`,
  `resilience/`, `observability/`, `pii/`).

## 5. Quality gates in CI (per PR)
1. `./mvnw -B test` (full suite, real infra).
2. `archunit` dependency rules.
3. OpenAPI snapshot diff (docs drift).
4. yamllint on compose/k8s/*.yml; `promtool check rules docs/monitoring/**`;
   `kustomize build` on every overlay.
5. `docker build .` (and a compose smoke for infra PRs).
6. Coverage report committed to the PR summary (overall trend).

## 6. Definition of "good enough to merge" for tests
- The new behaviour has at least one positive and one negative test.
- Any bug the PR fixes has a regression test named after the symptom.
- Full suite green; no new sleeps; no disabled tests; no `@Disabled` without a
  linked issue.
