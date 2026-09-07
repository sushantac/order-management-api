# 09 — Integration Events (Kafka)

## The guarantee integrators should rely on

When an order is placed successfully, the API publishes an event to the Kafka
topic `order-events`. The event is written **in the same database transaction**
as the order (transactional outbox) and delivered with **at-least-once**
semantics: an event exists iff its order exists, and the broker will never
lose it silently. There can be **duplicates** - consumers must be idempotent.

## Order event message

Topic: `order-events` (1 partition in the learning setup; scale partitions for
throughput).

JSON payload (`OrderPlacedMessage`):
```json
{
  "orderId": 1,
  "orderNumber": "ORD-1A2B3C4D5E",
  "totalAmount": 10.00,
  "occurredAt": "2026-09-07T10:15:30"
}
```
Design notes for consumers:
- **No PII** in the payload by design (no customer e-mail/address). Consumers
  that need details should call the API (read scope) rather than copy personal
  data into their own systems.
- The message key is the aggregate id (`orderId`) so ordering is guaranteed per
  order.

## Delivery details for integrators

| Property | Value |
|---|---|
| Broker | Kafka (KRaft), `docker-compose` service `kafka` |
| Consumer group (reference) | `order-service` |
| Acks | Manual - the API commits the offset only after processing succeeds |
| Redelivery | After a crash before ack, the message is redelivered (at-least-once) |
| Poison handling | Unprocessable messages go to the **dead-letter topic** `order-events.DLT` after a short retry budget - they never block the group |
| Payload format | JSON (schema evolution would move to Avro + Schema Registry) |

## Practical consumer guidance

1. Make consumption **idempotent**: key your store by `orderId`/`orderNumber`;
   ignore repeats.
2. On success, commit the offset *after* your side effect.
3. Never `ack` a message you could not process - let the dead-letter path handle
   it so the topic keeps flowing.
4. Monitor consumer lag per group.

## What events are published today
- `order-events`: fired after `POST /api/v1/orders` (single and bulk both route
  through the same order service). There is no per-status event stream yet;
  status changes via PATCH do not publish events in this build.

## Running locally to try it
```bash
docker compose up -d postgres kafka
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
# then place an order (see 01-orders.md) and consume:
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order-events --from-beginning
```
