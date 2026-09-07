# 09 — Decisions, Non-Functional Requirements, Risks & Roadmap

## 9.1 Options considered & decisions (designer summary)
| Decision | Options | Chosen | Rationale |
|---|---|---|---|
| API style | REST / GraphQL | REST v1 (+optional dual API later) | breadth of tooling, teaching fit, simple contracts |
| Schema mgmt | Hibernate DDL / Liquibase / Flyway | Liquibase | versioned, DB-agnostic, readable changelogs |
| Read scaling | L2 only / app cache (Redis) / both | Redis cache-aside for read models + selective Ehcache L2 | shared cache correct across instances; DTOs not entities |
| Reliable publish | in-tx publish / CDC / outbox | outbox poller (SKIP LOCKED) | no 2PC, DB-portable, testable |
| Consumer offsets | auto / manual | manual ack + DLT | at-least-once with poison isolation |
| Security | session / opaque tokens / JWT resource server | OAuth2 RS (scopes→authorities) | decoupled from IdP, stateless, scope-based least privilege |
| Gateway resilience | annotations / programmatic | programmatic composition (bulkhead→breaker→retry) | explicit, testable ordering; retry-inside-breaker semantics verified |
| Multi-instance mutex | DB lock / Redisson | Redisson RLock (lease) | crash-safe cross-instance exclusion |
| Rate limiting | per-process / shared | per-process (v1) → shared Redis (plan) | v1 keeps single-node learning scope honest |
| Deploy | scripts / Helm / Kustomize+ArgoCD | Kustomize overlays + GitOps model | plain YAML, env=git, convergence |

## 9.2 Decision log (ADR digest)
ADR-0001 Layering & dependency rules (ArchUnit); ADR-0002 Error contract
(last-resort 500; framework 4xx preserved); ADR-0003 Scope matrix (write before
controllers; all writes guarded); ADR-0004 Idempotency on all unsafe writes;
ADR-0005 State-transition maps; ADR-0006 Cache DTOs + eviction-by-writers;
ADR-0007 Outbox/no-PII/manual-ack/DLT; ADR-0008 Tenancy boundary
(single-tenant; additive seam). Full text template: `docs/next-time/04`.

## 9.3 NFR matrix
| NFR | Target | Evidence |
|---|---|---|
| Correctness | atomic orders; one-winner last unit | integration tests (concurrency) |
| Availability | ≥99.9%; probes split | SLO file + probes design |
| Performance | p95 budgets set | SLO file; k6 script (measurement pending) |
| Security | scope matrix, OWASP basics | security tests; headers; masked PII |
| Privacy | GDPR subject rights; no-PII audit | GDPR/PII test suites |
| Reliability | breaker/bulkhead/rate limit | chaos + resilience tests |
| Operability | 1-command run; probes; docs | compose + README + design docs |
| Portability | Java 21/Spring Boot, Docker/K8s | Dockerfile + manifests |

## 9.4 Known limitations & risks (with owners)
| # | Limitation / risk | Mitigation / owner | Plan |
|---|---|---|---|
| R1 | Endpoint guards incomplete vs matrix | Solution/Eng | P0 (matrix test walk) |
| R2 | Catch-all handler can mask 4xx | Solution/Eng | P0 last-resort semantics + regression |
| R3 | Idempotency only on single order create; keys unbounded | Eng | P1 apply broadly + TTL |
| R4 | Layering drift (domain→API DTO/messaging) | Architecture | P2 ArchUnit enforcement |
| R5 | Rate limit per-process | Eng/Platform | P1 Redis-backed shared limiter |
| R6 | HS256 dev tokens; static API key | Security | P3 JWKS profile + key hashing/rotation |
| R7 | Outbox rows retry forever; no consumer dedupe demo | Eng | P2 thresholds/alert + dedupe |
| R8 | Infra artefacts un-executed (CI/image/k8s/k6) | Platform | P1 `docs/next-time/06` closure |
| R9 | State transitions unguarded | Eng | P1 transition maps + tests |
| R10 | Timing-sensitive tests | QA | P2 Awaitility refactor |

## 9.5 Forward architecture (post v1.0)
1. Execute §8/§9 closure items to make every artefact evidence-backed.
2. Real PSP behind `PaymentGateway`; refunds; payment event streams.
3. Ownership/tenant model (ADR-0008) before multi-tenant claims.
4. Schema Registry / Avro; consumer dedupe; outbox health signals.
5. Split read/write models (projections/read model) as load grows.
6. Zero-downtime rollout: readiness + graceful shutdown verified in CI/k8s.
7. Progressive delivery tooling (flags → canary) and contract-test
   verification in CI (Pact broker).

## 9.6 Sign-off notes
This design is baselined against the as-built system. Deviations from the
original plan are recorded inline (§3.3, §5.1) and in §9.4 with owners and
priorities, so the design remains the single reference for the next phase of
work.
