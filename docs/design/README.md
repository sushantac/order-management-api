# Order Management API — Solution Design Document

**Document type:** Solution / High-Level + Detailed Design
**Author:** Solution Design (Learning Journey, 35-PR delivery)
**Version:** 1.0 · **Status:** Approved-as-built (design matches implementation; deviations noted)
**Date:** 2026-09-07
**Distribution:** Architects, Engineering, QA, Ops/SRE, Security

## Document control

| Ver | Date | Author | Change |
|---|---|---|---|
| 0.1 | build | Solution Design | Drafted alongside the 35-PR implementation |
| 1.0 | 2026-09-07 | Solution Design | Baselined post-delivery; aligned with `docs/good-and-bad` retrospective |

## Purpose & audience

This document describes the **Order Management API** solution - context,
requirements, architecture, and detailed design across data, API, security,
application, integration, resilience, observability and operations. It is
written for readers who need to **understand, extend, operate or review** the
system, and it records the decisions that matter so they do not have to be
rediscovered. Implementation companions: `docs/business` (functional),
`docs/learnings` (concepts), `docs/design` (this document).

## Table of contents (this package)

| Doc | Contents |
|---|---|
| [01-context-and-architecture.md](01-context-and-architecture.md) | Context, goals, scope, architecture principles, context & container views, key decisions |
| [02-data-design.md](02-data-design.md) | Domain model, schema & migrations, locking, retention, indexes |
| [03-api-and-interface-design.md](03-api-and-interface-design.md) | API surface, DTO/contract model, security matrix, error contract, idempotency, versioning |
| [04-security-and-privacy-design.md](04-security-and-privacy-design.md) | AuthN/AuthZ, PII handling, GDPR, PCI stance, threat posture |
| [05-application-and-concurrency-design.md](05-application-and-concurrency-design.md) | Layered/module design, transactional flows, caching, concurrency, virtual threads |
| [06-integration-and-messaging-design.md](06-integration-and-messaging-design.md) | Domain events, event store, outbox, Kafka topics, delivery semantics |
| [07-reliability-observability-and-performance.md](07-reliability-observability-and-performance.md) | Resilience patterns, rate limiting, observability, SLOs, performance budget |
| [08-deployment-operations-and-gitops.md](08-deployment-operations-and-gitops.md) | Container/K8s, environments, secrets, CI/CD, GitOps, runbooks |
| [09-decisions-risks-and-roadmap.md](09-decisions-risks-and-roadmap.md) | Option analyses, decision log, NFR matrix, known limitations, forward plan |

## How to read

- **Architects/stakeholders**: `01` and `09`.
- **Engineers extending the API**: `02`–`06` plus the applicable scope-matrix
  and error-catalog sections.
- **QA/Test**: `09` (NFR matrix) and the testing approach in
  `docs/next-time/05` (this design is the reference implementation of it).
- **Ops/SRE**: `07` and `08`.

## 1-minute summary

A stateless, horizontally scalable Java 21 / Spring Boot 3.2 API for order
management. It owns its schema via Liquibase on PostgreSQL, protects PII and
enables GDPR subject rights, charges orders atomically through a resilient
(simulated) gateway, publishes integration events reliably via an outbox to
Kafka, caches read models in Redis, runs on virtual threads, and is delivered
by GitOps (Docker/Kubernetes/Kustomize + GitHub Actions). Known simplifications
(e.g. simulated gateway, HS256 dev tokens, per-process rate-limit buckets) are
labelled as such in §9 and are part of the deliberate learning scope.

> Note: this system is a **learning implementation** - the design is realistic,
> the breadth is deliberately wide, and several production refinements are
> recorded as "forward plan" rather than hidden. See `docs/good-and-bad/03`
> for the prioritized engineering roadmap that would accompany this design in
> a real programme.
