# 07 — Industry Patterns & Case Studies

Ground each concept in what real companies/incidents did. Use these to build
judgement ("when NOT to use X") and to sound credible in interviews by citing
a real example, not just the textbook.

## Payments: idempotency & double-charge prevention
- **Stripe** exposes `Idempotency-Key` on every write - the pattern this API
  uses for order creation. Retries with the same key are safe; the risk being
  prevented is a duplicate charge on network retry.
- **Every real PSP** (Stripe, Adyen, Braintree) also requires *provider-side*
  idempotency because the API and PSP can disagree about whether a request
  arrived. Lesson: client key + provider key both matter.
- Study: "Stripe API reference - Idempotent Requests".

## Ordering & inventory consistency (real incidents)
- Retail flash-sales outages are typically caused by **overselling** (read-
  modify-write on stock without a version/lock) and by a single shared counter
  becoming a bottleneck. Mitigations seen in the wild: per-warehouse/per-SKU
  stock rows (sharding the counter), optimistic retries, or pessimistic locks
  on the hot row - exactly the trade-off explored in this repo's locking PRs.

## The Outbox pattern (why it is everywhere)
- Publicly documented by companies like **Uber, Shopify, and Confluent**:
  an *outbox table* written with the business transaction and a relay to the
  broker solves "commit then publish" atomicity without distributed
  transactions. Alternatives considered in industry: CDC (Debezium) tails the
  DB log instead of an app-maintained table.
- Lesson from incidents: publishing to Kafka *inside* the transaction and then
  the broker being down at commit causes missing events and silent divergence -
  the exact failure the outbox exists to prevent.

## Circuit breakers & cascading failure
- **Netflix** popularised Hystrix/resilience after real cascading outages;
  Fowler's "CircuitBreaker" article is the canonical explanation of
  CLOSED/OPEN/HALF_OPEN.
- Real-world lesson: **latency is a failure mode** - a dependency that slowly
  degrades exhausts thread pools even while returning 200s. This is why this
  project's breaker trips on *slow calls*, not only errors.

## Caching & the thundering herd / invalidation
- **Stale cache + money/stock = support tickets and overselling.** Industry
  practice: cache *presentation* data, never authoritative counters; always
  define the invalidation contract per writer (write-through, TTL bounds).
  Memcached/Redis "miss storm" after expiry is the classic perf failure;
  mitigations: jittered TTLs, request coalescing, or keeping a warm copy.

## GDPR in engineering
- Real erasure requests (Art. 17) hit "but we need the data for legal
  retention" daily; the industry-accepted answer is **anonymization** (as this
  repo implements) rather than pretending deletion is possible where records
  must survive. Audit logs are processed data too - do not store raw PII in
  them (a common finding in DPIAs).

## Virtual threads & platform threads at scale
- Java 21 virtual threads are being adopted by I/O-bound services to raise
  concurrency per node (e.g., many Spring Boot 3.2+ migrations cite "thousands
  of concurrent requests without a huge thread pool"). Caution from the field:
  CPU-bound work does not benefit, and blocking inside `synchronized` *pins*
  carriers.

## GitOps & progressive delivery
- **ArgoCD/Flux** convergence and **GitHub's own** internal workflows are the
  reference implementations of "deploy = merge, rollback = revert".
- **Feature flags** (LaunchDarkly-style, or simple config flags as here) are
  the standard tool for dark launches and instant rollback; combine with
  canary % routing and SLO monitoring for progressive delivery.

## Observability & SLO culture
- **Google's SRE book** popularised error budgets: paging on budget burn (as
  the alert rules here do) instead of on every anomaly. "Availability 99.9%"
  budgets ~43 min/month of downtime - internalise the arithmetic when you
  define or defend an SLO.

## How to use this file
For each concept, retell one case in 3 sentences (what happened, what pattern
fixed it, what the trade-off was). That single habit converts textbook
knowledge into engineering judgement - the difference interviewers probe for.
