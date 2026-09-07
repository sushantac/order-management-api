# 03 — Flashcard Deck (spaced repetition)

Use with **Anki** (import these as cloze/basic cards; or just drill the list
aloud). Rule: 10 minutes daily, no more - consistency beats cramming. Any card
you cannot answer in 5 seconds becomes your next lab/teaching topic.

## How to import
Paste each `Q — A` line into Anki as a Basic card (`Q` front, `A` back), or
add `;`-separated values into a CSV with fields Front|Back. Group by tags
(JPA, LOCK, CACHE, API, SEC, PII, MESSAGING, RESIL, CONC, OBS, OPS).

## Cards

### Schema & migrations
- Q: Who owns the DB schema? — A: Liquibase; Hibernate runs `ddl-auto: validate`.
- Q: What happens if you edit a shipped Liquibase changeset? — A: Checksum mismatch → Liquibase refuses to start; add a new changeset instead.
- Q: FK delete rules and when to use each? — A: CASCADE (children that must die with parent), RESTRICT (history must survive), SET NULL (optional ref).
- Q: Why index every FK column? — A: FK checks/lookups are index-driven; avoid full scans; leading-column rule.

### JPA / fetching
- Q: Where does the FK column live? — A: Owning side (@ManyToOne). Inverse uses `mappedBy`.
- Q: Does orphanRemoval alone delete removed children? — A: No - needs cascade (ALL) too (real gotcha).
- Q: What is the N+1 problem and its three fixes? — A: parent + N child queries; JOIN FETCH / @EntityGraph / batch fetching.
- Q: Why cache DTOs, not entities? — A: lazy/session state + proxies must not leave the persistence context.
- Q: Projection gotcha? — A: native/interface projection aliases must match getter names.
- Q: Nullable inverse @OneToOne caveat? — A: cannot be lazy-proxied; causes extra loads - prefer owning/non-nullable.

### Transactions & locking
- Q: How does @Version stop overselling? — A: UPDATE WHERE version=?; 0 rows → exception; retry reads fresh.
- Q: Retry policy rule? — A: retry transient (lock/deadlock), never business rejections.
- Q: FOR UPDATE vs FOR SHARE? — A: exclusive writer lock vs shared lock that blocks writers only.
- Q: Virtual thread cost vs platform thread? — A: KBs vs MBs stacks; park instead of occupy OS thread.
- Q: What is pinning? — A: synchronized/native code pins the carrier thread.

### Caching
- Q: Cache-aside flow? — A: check cache → hit return; miss → load DB → populate with TTL.
- Q: Who must evict cached product stock? — A: every writer incl. orders and locking services (or stale stock oversells).
- Q: L2 (Ehcache) vs Redis app cache? — A: L2 is JVM-wide entity cache; Redis is shared read-model cache.

### API / errors
- Q: DTO rule at the boundary? — A: records in/out; entities never cross; one mapper per aggregate.
- Q: Validation groups example? — A: same DTO, @Validated(Create) vs (Update) different rules.
- Q: Error format? — A: RFC 7807 Problem Details with stable `code` + `hint`.
- Q: Why must the catch-all handler be last-resort? — A: otherwise framework 4xx (404/405/415) become 500.
- Q: Idempotency key semantics? — A: same key replays stored response; new key = new op.

### Security & privacy
- Q: Resource server role? — A: validates tokens; never issues them.
- Q: How do scopes become authorities? — A: JWT scope claim → SCOPE_* authorities → @PreAuthorize.
- Q: pii_read vs order_read? — A: separate privilege class; raw PII and GDPR need pii_*.
- Q: GDPR erasure when history exists? — A: anonymize (Art. 17(3)); audit survives, no raw PII.
- Q: Why scrub logs regardless of caller? — A: log sink policy ≠ caller rights.

### Integration & reliability
- Q: Why outbox? — A: broker cannot join DB tx; event row commits with business write; poller publishes.
- Q: Delivery semantics? — A: at-least-once; consumers idempotent + manual ack + DLT.
- Q: Retry inside or outside breaker? — A: inside - breaker counts requests; attempts retry within.
- Q: Bulkhead purpose? — A: isolate a dependency on its own pool so it can't starve others.
- Q: Slow calls vs errors for a breaker? — A: both are failure signals (latency is a failure mode).

### Observability & ops
- Q: Three pillars? — A: logs (events), metrics (rates), traces (one request across services).
- Q: Liveness vs readiness? — A: liveness = restart; readiness = traffic gate; separate endpoints.
- Q: SLI vs SLO vs SLA? — A: measure / target / contract.
- Q: GitOps deploy = ? — A: merge to env branch; ArgoCD converges; rollback = revert.
- Q: Sealed secrets? — A: encrypted Secret in git; decrypted only by cluster controller.
