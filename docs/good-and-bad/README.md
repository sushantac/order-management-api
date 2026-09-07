# Order Management API — Good & Bad Retrospective

An honest, self-critical review of the whole 35-PR build: what is genuinely good,
what is bad or risky, and what could have been done better (process + design +
code). Written to be useful - not to defend the project. Every claim below is
checkable against the code in this repository.

Read this document in three parts:

| Part | Question it answers |
|---|---|
| [01-strengths-and-what-went-well.md](01-strengths-and-what-went-well.md) | What is GOOD here, and why |
| [02-weaknesses-and-what-is-bad.md](02-weaknesses-and-what-is-bad.md) | What is BAD / risky / would not ship as-is |
| [03-could-have-done-better.md](03-could-have-done-better.md) | What could have been done better + a prioritized fix list |

## Executive summary

**Good:** a genuinely broad, tested, documented Spring Boot codebase - real
Postgres/Redis/Kafka in tests, scope-based security, GDPR mechanics, an outbox,
resilience patterns, caching with correct eviction, virtual-thread concurrency,
observability, and GitOps artifacts. The *learning* output (comments, README,
per-PR diffs) is unusually strong, and several bugs were found precisely because
behaviour was asserted in tests.

**Bad:** it is a *teaching* project and some parts are intentionally simplified,
but several simplifications are also real design weaknesses that would need to
change for production: authz is inconsistent across endpoints, there is no user
or tenant ownership model, errors sometimes mis-map (404→500), bulk/other writes
bypass idempotency and guards, the simulated payment/API-key/rate-limit pieces
are single-process, retry/outbox edge cases are incomplete, and several
"enterprise/ops" artifacts (Docker image, CI, k8s manifests, SLO alerts) are
written but never executed end-to-end.

**Could have been better:** define an explicit architecture/ADR first and make
layering a reviewed rule (domain currently imports API DTOs); add cross-cutting
guards and idempotency from PR #1 of each resource instead of retrofitting;
protect the git process (I accidentally committed to `develop` twice during the
final PRs - no guard stopped it); and validate every infrastructure artifact in
CI (build the image, run a container, lint the YAML/prometheus rules).

The final section of part 03 is a prioritized, actionable roadmap.

---

*Companion docs: functional behaviour → `docs/business/`, technical concepts →
`docs/learnings/`, interview prep → `docs/interview-cheat-sheets/`.*
