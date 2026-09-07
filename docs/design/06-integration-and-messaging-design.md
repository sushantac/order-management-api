# 06 — Integration & Messaging Design

## 6.1 Eventing architecture
Two complementary stores:
1. **Event store** (`event_store`): append-only record of domain facts
   (`aggregate_id`, `version`, payload). Uniqueness of `(aggregate_id, version)`
   guarantees no duplicate fact - the event-sourcing integrity primitive.
   Records are immutable; no optimistic-lock/audit columns.
2. **Outbox** (`outbox`): the reliable-publish mechanism to Kafka. A row is
   written **in the same transaction** as the business write, so an event
   exists if and only if the order exists (no 2PC required).

## 6.2 Kafka topics & message contracts
| Topic | Payload (JSON) | Notes |
|---|---|---|
| `order-events` | `OrderPlacedMessage {orderId, orderNumber, totalAmount, occurredAt}` | key = order id ⇒ per-order ordering; **no PII** |
| `order-events.DLT` | same schema | poison messages after bounded retry |

Schema evolution: JSON is version 1; moving to Avro/Protobuf + Schema Registry
is a serializer swap behind the same topic contract (documented, not yet done).

## 6.3 Publishing flow (outbox pattern)
1. Business transaction commits `outbox(status=PENDING)`.
2. Poller claims oldest batch with `SELECT … FOR UPDATE SKIP LOCKED`
   (safe across instances), publishes, marks PUBLISHED **after broker ack**.
3. Failures increment `attempts` and stay PENDING (retried next poll).
4. Forward plan: max-attempts threshold + alert/dead-row handling (v1 retries
   indefinitely - recorded limitation).

## 6.4 Consumption flow & guarantees
- Consumer group `order-service`; manual offsets (`Acknowledgment`) committed
  only after successful processing.
- Delivery is **at-least-once**: duplicates are possible after crash-between-
  process-and-ack, so consumers must be **idempotent** (dedupe by event id -
  forward plan includes an explicit dedupe implementation on the consumer).
- Poison handling: a message that fails processing is retried a bounded
  number of times then published to `order-events.DLT` by a
  `DefaultErrorHandler`/`DeadLetterPublishingRecoverer`, so one bad record
  never blocks the group.

## 6.5 Interface with integrations (consumer guidance)
- Integrators consume `order-events`, dedupe by `orderId`, commit after their
  side effect, and fetch any customer detail they need from the API (payloads
  deliberately carry no personal data).
- Local replay/dev tooling: `kafka-console-consumer` against compose.

## 6.6 Design decisions (rationale)
- Outbox over transactional-outbox-via-CDC or publish-in-transaction: app-owned,
  testable, DB-portable; publish-in-transaction breaks atomicity when the
  broker is down.
- Manual ack over auto-commit: correctness on redelivery; DLT over infinite
  retry: poison isolation and group health.
