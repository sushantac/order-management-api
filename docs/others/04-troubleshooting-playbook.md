# 04 — Troubleshooting Playbook

Experts recognise symptoms fast. For each symptom: likely cause → how to
confirm → fix. Built from real failures in this project plus classic ones.
Pair each entry with the relevant `docs/learnings` chapter.

## Data / JPA
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| Removed child row still in DB | `orphanRemoval` without cascade | Inspect mapping; watch SQL | add `cascade=ALL, orphanRemoval=true` |
| `LazyInitializationException` on serialization | entity with lazy association read outside session | stack shows lazy init | DTO mapping inside tx; cache DTOs; fetch join/entity graph |
| N+1 query storm | lazy collection accessed per parent | enable SQL logging; count statements | @EntityGraph/JOIN FETCH/batch size |
| Projection returns null fields | alias ≠ getter name | compare aliases vs getters | fix aliases |
| Schema mismatch at startup | entity vs DB drift | read validate error | add Liquibase changeset; never auto-update |
| One row causes per-order extra SELECTs | nullable inverse @OneToOne | see per-row select on payment | owning/non-null mapping or lazy design |

## Transactions & concurrency
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| Oversold stock under load | no version/lock | concurrent test with counter | @Version + retry or FOR UPDATE |
| `OptimisticLockingFailureException` spam | high contention | logs | bounded retry with backoff; consider pessimistic |
| Deadlock error (40P01) | lock ordering conflict | Postgres logs | consistent ordering; retry DeadlockLoser |
| Retry loops forever on bad data | retrying business rejection | exception type | only retry transient failures |
| Slow under many users but idle CPUs | platform threads blocking | thread dumps | enable virtual threads |

## Caching
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| Cached stock stale after order | writer not evicting | read right after order | evict in every stock writer (incl. outside catalogue) |
| Cache never hit | TTL too short / key mismatch | hit/miss metrics | align keys; raise TTL |
| Serialization error from cache | cached entity w/ lazy state | stack trace | cache DTOs only |
| L2 cache leaks between tests | JVM-wide CacheManager | tests pass alone, fail together | per-class context + own DB; L2 off except its test |

## API & errors
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| 500 instead of 404/403/405 | catch-all Exception handler | hit unknown URL | explicit handlers / last-resort semantics |
| 403 on an endpoint that should pass | missing scope/guard mismatch | check scope matrix | fix matrix/guard (or token scopes) |
| 401 vs 403 confusion | authn vs authz | valid token, insufficient scope | 401=no identity, 403=no permission |
| Duplicate order on retry | no Idempotency-Key or not honoured | send same key twice | replay stored response |
| DELETE fails 412 | stale ETag/If-Match | compare version | re-GET, retry with fresh ETag |

## Security & privacy
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| Raw PII in response for plain scope | masking not applied on that path | call with order_read | apply serializer/decider on every serialization path |
| Raw PII in logs | redaction not wired | grep log | PiiRedactionFilter/scrub at sink |
| HS256 accepted in prod-ish | dev secret config | check spring profile | JWKS/RS256 for prod |

## Messaging & reliability
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| Order committed, event missing | published in-tx not via outbox | check outbox table | always write outbox row in same tx |
| Duplicate event delivery | at-least-once | consumer logs | idempotent consumer/dedupe |
| Consumer group stuck | poison message without DLT | offsets don't advance | DLT + bounded retry |
| Breaker opens too easily | retry outside breaker (counts attempts) | metrics/call count | retry inside breaker |
| 429s for one tenant block another | shared bucket | test two keys | per-key buckets |

## Observability & operations
| Symptom | Cause | Confirm | Fix |
|---|---|---|---|
| No correlation across logs | MDC not set/cleared | log lines lack id | CorrelationIdFilter + propagate header |
| Health DOWN w/o Redis | redis health contributor | /actuator/health detail | configure expected Redis or disable contributor in profile |
| Pod restarts on cold start | liveness too eager | logs | startup probe |
| Readiness fails under load | readiness checks downstreams | deploy | keep readiness DB-aware, liveness local |
| CI "works on my machine" | artifacts never run in CI | run CI | add docker/compose/kustomize steps to pipeline |

## Journal template (record every real incident you fix)
```
Date / Symptom / First guess / Actual cause / How I confirmed / Fix / What to
check first next time
```
A stack of ten written entries = far more credible interview "tell me about a
hard bug" material than a memorised answer.
