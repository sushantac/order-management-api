# 06 — Event-Driven Architecture & Kafka

## Mental model
Producers publish *facts* ("order placed") to a broker; consumers react in their
own time and scale independently. The order service never calls downstream
systems directly - the topic is the coupling point.

## Domain events vs event store (background context)
- Domain event = a record of something that happened (`OrderPlacedEvent`).
- Event store = append-only persistence of facts. Integrity = **unique
  (aggregateId, version)** - a duplicate append is impossible.

## The outbox pattern (core interview answer)
Problem: publishing to Kafka inside a DB transaction is not atomic (no 2PC).
Solution:
1. In the SAME transaction as the write, insert a row into an `outbox` table
   (payload = serialized event, status PENDING).
2. A polling publisher claims rows (`SELECT ... FOR UPDATE SKIP LOCKED` so several
   app instances never claim the same row), publishes to Kafka.
3. Mark the row PUBLISHED only after the broker acks. Failure bumps attempts and
   the row stays PENDING → retried next poll.

Result: **event exists iff the business write exists** (atomicity) with
at-least-once delivery.

## Delivery semantics - say it precisely
- Kafka gives **at-least-once** by default (offsets committed after processing;
  crashes → redelivery). Duplicates possible.
- Therefore: consumers must be **idempotent**, commit offsets **manually after
  successful processing** (AckMode.MANUAL), and send poison/unprocessable messages
  to a **dead-letter topic** so they never block the group.
- "Exactly-once" = transaction API/idempotent producer + idempotent consumer -
  mention it is expensive and usually unnecessary.

## Practical Kafka checklist (what the repo does)
- KRaft single node in docker-compose (no ZooKeeper); topics via `NewTopic` beans
  or broker auto-create.
- JSON payload records (no PII); Avro + Schema Registry is a serializer swap.
- `@KafkaListener` + `Acknowledgment` (manual offsets).
- `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → `topic.DLT`.
- Producer sends synchronously (`.get(timeout)`) so the outbox marks PUBLISHED
  truthfully.
- Master switch `app.kafka.enabled` so non-Kafka test contexts never touch a broker;
  integration tests use `@EmbeddedKafka`.

## Tell me about...
**"How do you reliably publish events from a database-backed service?"** → "Two-phase
commit with a broker isn't practical, so I use the outbox: the event row commits with
the order; a poller claims PENDING rows with SKIP LOCKED, publishes, and marks them
published only after an ack. Consumers are idempotent, ack manually, and poison goes
to a dead-letter topic. A test with an embedded broker proves the round trip."

## Rapid Q&A
- Ordering? → per-partition; key = aggregate id for per-order ordering.
- Exactly-once needed? → usually at-least-once + idempotent consumers is enough.
- Consumer lag? → metric per group; scale partitions/consumers.
- Outbox vs CDC? → outbox is app-owned and schema-versioned; CDC (Debezium) needs no
  app change but couples you to log format.
- Event contains what? → the minimal fact + aggregate id; no PII by default;
  consumers ask the API for details (avoids fan-out of stale copies).
