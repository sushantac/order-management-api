# 02 — Self-Assessment Rubric

Score yourself per concept on four levels. **Be honest; the goal is a
direction, not a grade.**

## Levels
| Level | Means |
|---|---|
| 0 — Heard of | Recognise the term, cannot explain or use it |
| 1 — Explain | Can explain it correctly to a peer, with the trade-offs |
| 2 — Build | Can implement it from scratch in this stack without looking |
| 3 — Debug | Can diagnose and fix the classic failures of this concept unaided |
| 4 — Teach/Judge | Can teach it, review others' code for it, and choose *when not* to use it |

An expert on any concept = level 3+ across the board and 4 on the ones you own.

## Scorecard (fill in the boxes: date → level; e.g. 09-07 → 2)

| Concept | Explain | Build | Debug | Target by |
|---|---|---|---|---|
| Schema migrations & `validate` | | | | W1 |
| FK delete rules & indexing | | | | W1 |
| JPA owning/inverse, cascade, orphanRemoval | | | | W2 |
| N+1: fetch join / entity graph / batching | | | | W3 |
| Projections (interface/constructor/alias) | | | | W3 |
| @Version optimistic locking + retry | | | | W4 |
| Pessimistic locking (FOR UPDATE/SHARE, deadlock) | | | | W5 |
| Cache-aside + invalidation/eviction | | | | W6 |
| 2nd-level (Ehcache) vs app cache (Redis) | | | | W6 |
| REST conventions, DTO records, mapper boundary | | | | W7 |
| Validation groups & cross-field constraints | | | | W7 |
| RFC 7807 errors + last-resort handler | | | | W7 |
| Idempotency keys | | | | W7 |
| OAuth2 resource server, scopes→authorities | | | | W8 |
| API keys + per-key rate limiting | | | | W8/W10 |
| PII masking + log redaction | | | | W8 |
| GDPR erasure vs anonymization; audit | | | | W8 |
| Outbox pattern; at-least-once | | | | W9 |
| Kafka manual ack + DLT | | | | W9 |
| Circuit breaker/retry/bulkhead composition | | | | W10 |
| Virtual threads + structured fan-out | | | | W11 |
| Reentrant vs distributed locks (lease) | | | | W11 |
| Correlation ids; logs/metrics/traces | | | | W12 |
| Health probes; SLO/alerting | | | | W12 |
| Docker multi-stage; K8s probes; Kustomize | | | | W12 |
| GitOps/CI/CD; sealed secrets; feature flags | | | | W12 |

## How to use the scores
- Any concept at **0**: read `docs/learnings` chapter + do its lab first.
- **1 but not 2**: do `06` lab; score again.
- **2 but not 3**: work `04` troubleshooting playbook failures for it.
- **3**: you are interview-safe on this concept; prove it in the cheat-sheet
  drills.
- Never above **3** without having taught it to someone or reviewed code for it.

## Review cadence
Re-score everything at week 6 and week 12. A +2 movement on a cluster means the
practice worked; no movement means change the method (less reading, more
building/teaching).
