# 07 — Resilience Patterns & Performance

## The resilience stack (say it as one story)
A downstream call (payment gateway) is protected by, outermost first:
1. **Bulkhead (thread pool)** - gateway work runs on its own small pool, so a
   slow gateway cannot starve the DB/HTTP threads ("ship compartments").
2. **Circuit breaker** - CLOSED → after too many failures or slow calls → OPEN
   (calls fail instantly) → after a wait → HALF_OPEN (one probe) → success closes,
   failure reopens. Fail fast instead of queueing on a sick provider.
3. **Retry (exponential backoff)** - absorbs transient declines.

**Critical subtlety (from real tests):** Retry is INSIDE the circuit breaker, so
one logical request = one breaker record; the inner attempts retry within it.
If you swap them, the breaker sees every attempt and opens much sooner than intended.
Slow calls trip the breaker too - **latency is a failure mode**, not just errors
(`slow-call-duration-threshold`).

## Rate limiting (per client)
- Bucket per `X-API-Key` (separate buckets → one noisy tenant can't starve another).
- Over limit → **429** + `Retry-After`, plus `X-RateLimit-Remaining`,
  `X-RateLimit-Reset` on success so clients self-throttle.
- Config-driven via Resilience4j properties (tune without redeploy).

## Failure taxonomy (know it)
- Business rejection (insufficient stock) → do NOT retry; it will never succeed.
- Transient (optimistic-lock conflict, deadlock, timeout) → retry with backoff.
- Provider down → breaker opens; retries would just pile up.

## Chaos testing (differentiator)
Inject deterministic failures and latency into the real stack (a test gateway that
fails N times or sleeps) and assert the breaker state machine: OPEN, fast-fail (no
new attempts reach the provider), HALF_OPEN recovery after wait.

## Performance & Java 21 concurrency
- Virtual threads: thread-per-request without OS-thread costs; park on blocking I/O.
- Structured fan-out: launch independent reads on virtual threads and join all
  before returning (no orphan work).
- Load test with k6 (script in repo) + thresholds (error rate, p95 latency);
  cache metrics tell you whether caching actually helps.

## Tell me about...
**"Making a flaky dependency safe."** → "I wrapped the payment gateway in a
thread-pool bulkhead → circuit breaker → retry stack. When the provider degrades, the
breaker opens and calls fail in milliseconds instead of hanging; chaos tests inject
failures and latency to prove OPEN/HALF_OPEN/CLOSED behaviour. API keys are rate
limited per key with standard headers, and k6 thresholds guard latency/errors."

## Rapid Q&A
- Retry + idempotency? → retries need safe repetition: idempotency keys / version
  checks on the receiver side.
- Bulkhead sizes? → sized on p99 concurrency + headroom; tune by experiment.
- Circuit breaker on DB calls? → usually no: DB has its own pool/timeouts; breakers
  suit network services with real outage modes.
- 429 vs 503? → 429 = client too fast; 503/retryable = service can't cope.
