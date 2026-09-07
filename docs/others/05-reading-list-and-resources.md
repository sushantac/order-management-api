# 05 — Reading List & Resources (by learning aspect)

Priority ordering: **1** = read before/while you practice that concept, **2** =
read when you want depth, **3** = reference/skim later. Do not read to delay
building - the labs in `06` come first for each topic.

## Foundations
- 1. *Effective Java* (Bloch) - items on records, streams, exceptions, concurrency.
- 1. *Clean Code* (Martin) & *Refactoring* (Fowler) - the *why* of the style used.
- 1. Official: Java 21 release notes (records, pattern matching, virtual threads), Spring Boot reference (profiles, configuration).
- 2. *The Pragmatic Programmer* (Hunt/Thomas).

## Databases, schema, migrations
- 1. Official: Liquibase docs (changelog best practice: never edit run changesets).
- 1. PostgreSQL docs: `DDL`, constraints, `EXPLAIN` basics, `SELECT ... FOR UPDATE`.
- 2. *Use The Index, Luke!* (free) - index/FK anatomy.

## JPA & Hibernate
- 1. Hibernate User Guide: fetching, batching, caching, annotations.
- 1. Vlad Mihalcea's blog/articles - N+1, caching, locking (concise, example-heavy).
- 2. *Java Persistence with Hibernate* (Bauer/King/Gregory) - deep reference.
- 3. Thorben Janssen (thoughts-on-java) for practical recipes.

## Transactions, concurrency, caching
- 1. *Designing Data-Intensive Applications* (Kleppmann) ch.7 (transactions) & ch.12 - the mental model every senior needs.
- 1. Java Concurrency in Practice (Goetz) - locks, atomics, executors (classic).
- 2. JEP 444 (virtual threads) + JEP 453 (structured concurrency) - the JDK rationale.
- 3. Redis docs (data types, expiry) for the cache design.

## API design & validation
- 1. *REST APIs* — Richardson & Ruby (or the Pragmatic REST guide).
- 1. RFC 7807/9457 (Problem Details) and RFC 8594 (Deprecation/Sunset).
- 2. *API Design Patterns* (JJ Geewax) - versioning, idempotency, long-running ops.

## Security & privacy
- 1. OAuth2 spec (RFC 6749/6750) + OpenID Connect basics; Spring Security reference.
- 1. OWASP Top 10 and OWASP API Security Top 10.
- 2. GDPR Art. 17/20/30 text + guidance (masking/anonymization vs pseudonymization).
- 2. *The Web Application Hacker's Handbook* (optional, breadth) or PortSwigger Academy.

## Messaging / Kafka
- 1. *Designing Data-Intensive Applications* ch.11 (stream processing, exactly-once semantics).
- 1. Kafka docs: consumers, offsets, idempotence, transactions; Confluent blog (outbox pattern).
- 2. *Kafka: The Definitive Guide* (Narkhede et al.).

## Resilience
- 1. Resilience4j docs (breaker states, retry, bulkhead) + Martin Fowler's *Circuit Breaker* article.
- 1. *Release It!* (Nygard) - stability patterns (bulkheads, circuit breakers, timeouts) - the classic.

## Observability
- 1. Google SRE book (free online) - monitoring, SLOs, error budgets.
- 1. Micrometer + OpenTelemetry docs (span, trace, exporters).
- 2. *Observability Engineering* (Majors et al.).

## Containers / Kubernetes / GitOps
- 1. Docker + Kubernetes docs: probes, resources, HPA.
- 1. Kustomize docs; ArgoCD docs (app-of-apps, sync policies).
- 2. *Kubernetes Up & Running* (Burns et al.).

## Practice & career multipliers
- 1. LeetCode-style Java concurrency problems are less useful than *reproducing*
  failures from `04` - prefer labs.
- 2. Certifications if you want external validation: Oracle Java 17/21
  professional-level, or cloud/Spring-ecosystem certs relevant to your employer.
- 3. Follow release notes/blogs (Spring Blog, Inside Java, Hibernate, Confluent)
  for 15 min/week to stay current.

## Community & hands-on
- GitHub advanced-code search: read one real OSS service's `pom.xml` + config
  per month (e.g. spring-petclinic, spring-boot samples, real e-commerce OSS).
- Talk to the code: `git log`/PRs of popular Spring projects to see review
  discussions - judgement is learned from *why* reviewers object.
