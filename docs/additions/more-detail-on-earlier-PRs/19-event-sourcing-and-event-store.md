# 19. Event Sourcing and Event Store (PR #19)

> PR #19 — Event Sourcing and Event Store: append-only `event_store`, `DomainEvent`, `version` optimistic guard, `EventStoreService.append/readHistory`. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, Jackson, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 19 | Event Sourcing and Event Store |`.

---

## 1. Purpose — what shipped

PR #19 makes the **past** the source of truth. Instead of overwriting `orders.status = SHIPPED`, it persists *facts that already happened* (`OrderPlacedEvent`, `OrderConfirmedEvent` implementing `DomainEvent` `domain/event/DomainEvent.java:16`) as immutable rows in the append-only `event_store` table (`domain/eventstore/EventStoreEntry.java:23` `@Table(name="event_store")`) with per-aggregate monotonic `version` and `UNIQUE(aggregate_id, version)`. Service `EventStoreService` (`EventStoreService.java:21`) `append(event)` serializes to `payload` JSON (`ObjectMapper` `EventStoreService.java:50`), checks `existsByAggregateIdAndVersion` (`EventStoreService.java:33`) to prevent duplicate version, and `saveAndFlush(...)` the `EventStoreEntry` (`EventStoreEntry.java:61-69`); `readHistory(aggregateId)` (`EventStoreService.java:43`) replays `findByAggregateIdOrderByVersionAsc` then deserializes by `event_type`. `EventStoreEntry` deliberately does NOT extend `BaseEntity` — no mutable version/audit, `updatable=false` on every column (`EventStoreEntry.java:31-49`).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `Order.status` (`Order.java:54`) was mutable in place: `UPDATE orders SET status='SHIPPED' WHERE id=42`. History was lost — after `SHIPPED`, the fact that it was `PLACED` then `CONFIRMED` existed only in logs/audit if captured separately. Two concurrent `updateOrderStatus` (`OrderService.java:156`) could both `UPDATE` to different statuses; last writer won silently (without version on `Order`, already addressed by `BaseEntity.java:53` but still no history). Reconstructing "what happened to order 42 on 2026-09-10?" required joining `audit_log` and `outbox` — neither authoritative for domain facts.

**After:** `EventStoreService.append(new OrderPlacedEvent(aggregateId, version=1, occurredAt, ...))` then `append(new OrderConfirmedEvent(aggregateId, version=2, ...))` writes two immutable rows. `event_store` holds the full sequence; `aggregateId=OrderId as UUID`, `version` strictly increasing, `event_type` discriminant, `payload` JSON, `occurred_at` domain time. `UNIQUE(aggregate_id, version)` (`EventStoreRepository` via DB unique constraint + `existsByAggregateIdAndVersion` check) makes appending `version=2` twice impossible — second caller gets `IllegalStateException` (`EventStoreService.java:34`). Replaying `readHistory(uuid)` returns `List<DomainEvent>` sorted `ORDER BY version ASC` (`EventStoreService.java:44`) — rebuild state without querying `orders`.

### Theory — event sourcing from first principles (100+ lines)

#### 2.1 CRUD vs event-sourced — storing facts vs state

```
CRUD (orders table):   state = current snapshot
  INSERT orders (status PLACED)  → state PLACED
  UPDATE status=CONFIRMED        → previous PLACED lost (unless audit_log)
  UPDATE status=SHIPPED          → CONFIRMED lost
  Read: SELECT status FROM orders WHERE id=42  → final state only

Event-sourced (event_store):  state = fold(left, events, initial)
  INSERT event_store (type OrderPlacedEvent, v1, payload {... PLACED ...})   // fact
  INSERT event_store (type OrderConfirmedEvent, v2, payload {... CONFIRMED ...}) // fact
  INSERT event_store (type OrderShippedEvent, v3, payload {... SHIPPED ...})  // fact
  Read: SELECT * FROM event_store WHERE aggregate_id=? ORDER BY version ASC → replay → derived state = SHIPPED
        (no UPDATE or DELETE ever — append-only)
```

`EventStoreEntry.java:52-55` `@PrePersist guardAppendOnly()` is a safety net: nothing may mutate an appended event. `updatable=false` on every column (`EventStoreEntry.java:31-46`) enforces this at the mapping level; Postgres `REVOKE UPDATE,DELETE` on `event_store` would enforce at DB level.

#### 2.2 `DomainEvent` contract — identity, version, time

```java
// DomainEvent.java:16
public interface DomainEvent {
    UUID aggregateId();           // 19 — which aggregate (Order id as UUID)
    int version();                // 22 — monotonic sequence per aggregate; UNIQUE(aggregate_id, version)
    LocalDateTime occurredAt();   // 24 — domain time (when the fact happened), not DB insert time
    default String aggregateType() // 27 — derived: OrderPlacedEvent → "OrderPlaced"
    default String eventType()    // 32 — getClass().getSimpleName()
}
```

Event impls are expected to be records (e.g., `record OrderPlacedEvent(UUID aggregateId, int version, LocalDateTime occurredAt, Long orderId, ...) implements DomainEvent` `OrderPlacedEvent.java`) so `aggregateId()`/`version()` are canonical components. `aggregateType` stripping `"Event"` suffix (`DomainEvent.java:28-29`) normalizes storage `aggregateType="OrderPlaced"` vs `eventType="OrderPlacedEvent"` — either queries by aggregate family (`Order`) or exact type.

#### 2.3 `EventStoreEntry` — the log row, not an aggregate

```java
// EventStoreEntry.java:23-50
@Entity @Table(name="event_store")
public class EventStoreEntry {
    @Id @GeneratedValue(IDENTITY) private Long id;                 // 28 — surrogate, insertion order
    @Column(name="aggregate_id", nullable=false, updatable=false) private UUID aggregateId; // 31
    @Column(name="aggregate_type", nullable=false, updatable=false, length=64) private String aggregateType; //34
    @Column(name="event_type", nullable=false, updatable=false, length=64) private String eventType; //37
    @Column(name="version", nullable=false, updatable=false) private int version; //40
    @Column(name="occurred_at", nullable=false, updatable=false) private LocalDateTime occurredAt; //43
    @Column(name="payload", nullable=false, updatable=false, columnDefinition="text") private String payload; //46
    @Column(name="created_at", nullable=false, insertable=false, updatable=false) private LocalDateTime createdAt; //49 DB default
    public static EventStoreEntry from(DomainEvent event, String payload) //61
}
```

Not `BaseEntity`: no `@Version` optimistic lock, no `updatedAt`/`CreatedBy`, no mutable identity — the `version` here is *domain* version, not Hibernate optimistic-lock version. The `UNIQUE(aggregate_id, version)` constraint (in Liquibase `event_store` changeset + `existsByAggregateIdAndVersion` guard `EventStoreService.java:33`) is the invariant: version 2 for aggregate X can be written exactly once, regardless of concurrent writers.

#### 2.4 Append semantics — `EventStoreService.append` atomic guard

```java
// EventStoreService.java:31-39
@Transactional
public void append(DomainEvent event) {
    if (store.existsByAggregateIdAndVersion(event.aggregateId(), event.version())) // 33
        throw new IllegalStateException("Event version "+event.version()+" already exists for aggregate "+event.aggregateId());
    String payload = write(event); // 38 ObjectMapper → JSON
    store.saveAndFlush(EventStoreEntry.from(event, payload)); // 39 INSERT, updatable=false
}
```

Three-layer guard:

1. Application `existsByAggregateIdAndVersion` check (`33`) — friendly error, avoids DB constraint violation log noise.
2. DB `UNIQUE(aggregate_id, version)` — race-proof: two concurrent `append(v2)` both pass `exists==false`, one `INSERT` succeeds, other hits `ConstraintViolationException`/`DataIntegrityViolationException` (`PSQLException duplicate key value violates unique constraint "uq_event_store_aggregate_version"`). Caller converts to `OptimisticLockingFailureException` or `IllegalStateException`.
3. `@Transactional` — ensures payload serialization and insert are atomic with caller's aggregate update if composed (or standalone if caller's `orders` update is elsewhere).

Idempotent retry: caller re-sends same `version` after a timeout — second `append` reliably rejected, exactly-once fact semantics.

#### 2.5 `readHistory` — replay

```java
// EventStoreService.java:42-46
@Transactional(readOnly=true)
public List<DomainEvent> readHistory(UUID aggregateId) {
    return store.findByAggregateIdOrderByVersionAsc(aggregateId).stream().map(this::toEvent).toList();
}
```

`EventStoreRepository` (`EventStoreRepository.java`) exposes `findByAggregateIdOrderByVersionAsc`, `existsByAggregateIdAndVersion`. `toEvent` (`EventStoreService.java:57-72`) switches on `entry.getEventType()` (`OrderPlacedEvent`/`OrderConfirmedEvent`) and `objectMapper.readValue(payload, SpecificClass)` — type discriminator is `event_type` column (`Entry:37`), not Jackson `@JsonTypeInfo`. Unknown `event_type` → `IllegalStateException: Unknown event type in store`. Replaying `readHistory` folds events to rebuild aggregate: `OrderState state = OrderState.initial(); for (DomainEvent e: events) state = apply(state, e);`

#### 2.6 `payload` as JSON text — evolution concerns

`payload` (`Entry:46` `columnDefinition="text"`) stores `objectMapper.writeValueAsString(event)` (`EventStoreService.java:51`). This is a *serialized fact* — schema evolution must be backward-compatible. Additions: new optional field with default → old rows deserialize with `null` via `@JsonIgnoreProperties(ignoreUnknown=true)`. Breaking rename: add `@JsonProperty` alias or migrate payloads with a backfill script. `payload` is never queried via `WHERE payload ...` (full scan) — indexing `aggregate_id`/`version` (`uq_event_store_aggregate_version`) serves reads. For queryable fields, project them to `event_store` columns or read models.

#### 2.7 Event sourcing vs event store vs outbox

- **Outbox** (`OrderService.java:125` `OutboxEntry`, `application.yml:203`) — transient bridge to Kafka for integration; row may be deleted after publish. Not the source of truth, not replayed for state.
- **Event store** (`EventStoreEntry.java:23`) — persistent append-only log, source of truth, never deleted, replayable to any version. Outbox facts derive from the same `DomainEvent`, but outbox delivery and event-store history have different retention and consistency requirements.
- **Event sourcing** — pattern: *current state derived solely from event_store history*, no mutable `orders` row. This repo uses **event store as an additive ledger** (`orders` remains authoritative for current state, `event_store` is an audit/history twin). Full event-sourcing would drop `orders` mutability and rebuild from `readHistory` on every load (stronger invariant, higher cost).

#### 2.8 Versioning — domain `version` vs Hibernate `@Version`

`DomainEvent.version()` (`DomainEvent.java:22`) is domain sequence (1,2,3...) per aggregate, writer-maintained (`Order` tracks `nextVersion` internally). `BaseEntity.version` (`BaseEntity.java:53` `@Version`) is Hibernate optimistic-lock for `orders` row. They are orthogonal: `domain version` guarantees *fact uniqueness*; `BaseEntity version` guards *concurrent overwrites* of mutable `orders`. An `event_store` row never conflicts on Hibernate version because it is never updated.

#### 2.9 Interview-ready mental model

> "Event store (`EventStoreEntry.java:23` `event_store`) is append-only (`updatable=false:31-49`, no `BaseEntity`), rows `(aggregateId UUID, aggregateType, eventType, version int, occurredAt, payload text JSON:46, createdAt DB default)`. `DomainEvent.java:16` contract `aggregateId/version/occurredAt/eventType`. `EventStoreService.java:31` `append` serializes via `ObjectMapper:50`, checks `existsByAggregateIdAndVersion:33` then `UNIQUE(aggregate_id,version)` as race gate, then `saveAndFlush(Entry.from(event,payload):61)`. `readHistory:43` `findByAggregateIdOrderByVersionAsc` + `toEvent` switch type→ `readValue(payload)`. `BaseEntity @Version` vs domain `version` distinct. Outbox (PR #31 `OrderService.java:125`) is transient Kafka bridge; event store is durable replayable history — append-only `PrePersist` guard `Entry:52` and `updatable=false` enforce log semantics."

---

## 3. Solution — ASCII

```
CRUD (mutable row):
  orders(id=42)  status PLACED ──UPDATE──► CONFIRMED ──UPDATE──► SHIPPED
                 prior state lost; concurrency last-writer-wins unless @Version

Event-sourced (append-only log) PR #19:

  DomainEvent:  aggregateId UUID (Order id as UUID)  version int (1,2,3...)  occurredAt  eventType  aggregateType
                  │ implements DomainEvent.java:16  record OrderPlacedEvent / OrderConfirmedEvent

  EventStoreService.java:31 append(event)
    ├─ existsByAggregateIdAndVersion(aggregateId, version)  // 33 — friendly check
    ├─ payload = objectMapper.writeValueAsString(event)     // 51
    ├─ EventStoreEntry.from(event, payload)                 // 61  maps aggregateId/type/version/occurredAt/payload
    │     EventStoreEntry.java:23  @Table(event_store)
    │       columns: id IDENTITY  aggregate_id UUID  aggregate_type VARCHAR(64)  event_type VARCHAR(64)
    │                version INT  occurred_at  payload TEXT  created_at
    │       all updatable=false:31-49, PrePersist guard:52
    └─ store.saveAndFlush(entry) ──► INSERT INTO event_store VALUES (...)  // UNIQUE(aggregate_id, version)
            second concurrent INSERT v2 same aggregate → PSQLException duplicate key → rejected

  EventStoreService.java:43 readHistory(aggregateId)
    └─ findByAggregateIdOrderByVersionAsc(agg)  ORDER BY version ASC  // repository
       └─ stream map toEvent:57  switch eventType → objectMapper.readValue(payload, OrderPlacedEvent.class)
          → List<DomainEvent> [v1(OrderPlaced), v2(OrderConfirmed), ...] → fold to state

  Durability & coherence:
    event_store append is @Transactional  // same atomicity as orders mutation if composed in caller
    UNIQUE(aggregate_id, version)  ← true concurrency gate (exists check + DB constraint)
    outbox  OrderService.java:125  OutboxEntry.pending  + Kafka  ← transient bridge, not history (PR #31)
    audit_log  (PR #27)  ← compliance trail, not domain replay
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/event/DomainEvent.java` | `16-35` | Event contract | `aggregateId() UUID`, `version() int`, `occurredAt()`, `aggregateType()`/`eventType()` defaults stripping `"Event"` |
| `src/main/java/com/company/orderapi/domain/event/OrderPlacedEvent.java` | — | Example event record `implements DomainEvent` | Past-tense fact, typed fields, JSON-serializable |
| `src/main/java/com/company/orderapi/domain/event/OrderConfirmedEvent.java` | — | Example event record | `version` monotonic per aggregate |
| `src/main/java/com/company/orderapi/domain/eventstore/EventStoreEntry.java` | `23-99` | Append-only JPA entity | `@Table(event_store)`, `updatable=false:31-49`, `payload:46 text`, `PrePersist guard:52`, factory `from(DomainEvent,payload):61`, NOT `BaseEntity` |
| `EventStoreEntry.java` | `28,52` | `IDENTITY` id, append-only guard | Surrogate `id` for insertion order; `guardAppendOnly()` safety net |
| `src/main/java/com/company/orderapi/domain/eventstore/EventStoreRepository.java` | — | Spring Data repo | `existsByAggregateIdAndVersion(UUID,int)`, `findByAggregateIdOrderByVersionAsc(UUID)` |
| `src/main/java/com/company/orderapi/domain/eventstore/EventStoreService.java` | `21-73` | Append + replay service | `append:31` (`exists:33` + `write:50` + `saveAndFlush:39`), `readHistory:43` readOnly, `write:49`, `toEvent:57` switch on `eventType` |
| `EventStoreService.java` | `33,39` | Concurrency gate | App check + DB `UNIQUE` (Liquibase changeset `event_store` with `UNIQUE(aggregate_id, version)`) |
| `src/main/resources/db/changelog/` | `*event_store*` | Liquibase changeset `event_store` table + unique constraint + `created_at DEFAULT NOW()` | DDL source of truth for `UNIQUE(aggregate_id, version)` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `125` | Outbox companion | Same `DomainEvent` family published to `OutboxEntry.pending` — transient vs durable pairing intuition |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Integration proof | Context validates `event_store` table/columns/unique constraint against Testcontainers Postgres |

```java
// DomainEvent.java:16 — every event satisfies this
public interface DomainEvent {
    UUID aggregateId(); int version(); LocalDateTime occurredAt();
    default String aggregateType(){ String s=getClass().getSimpleName(); return s.endsWith("Event")?s.substring(0,s.length()-5):s; }
    default String eventType(){ return getClass().getSimpleName(); }
}

// EventStoreEntry.java:23,61 — log row, immutable
@Entity @Table(name="event_store")
public class EventStoreEntry {
    @Column(name="aggregate_id", nullable=false, updatable=false) private UUID aggregateId; // 31
    @Column(name="payload", nullable=false, updatable=false, columnDefinition="text") private String payload; //46
    @PrePersist void guardAppendOnly(){} // 52
    public static EventStoreEntry from(DomainEvent event, String payload) { //61
        EventStoreEntry e=new EventStoreEntry(); e.aggregateId=event.aggregateId(); e.aggregateType=event.aggregateType();
        e.eventType=event.eventType(); e.version=event.version(); e.occurredAt=event.occurredAt(); e.payload=payload; return e;
    }
}

// EventStoreService.java:31 — atomic append
@Transactional public void append(DomainEvent event) {
    if (store.existsByAggregateIdAndVersion(event.aggregateId(), event.version())) throw new IllegalStateException(...); //33
    String payload=objectMapper.writeValueAsString(event); // 51
    store.saveAndFlush(EventStoreEntry.from(event, payload)); //39
}
@Transactional(readOnly=true) public List<DomainEvent> readHistory(UUID aggregateId) { //43
    return store.findByAggregateIdOrderByVersionAsc(aggregateId).stream().map(this::toEvent).toList();
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Verify event_store DDL (unique constraint)
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d event_store" | grep -E "aggregate_id|version|payload|UNIQUE|event_type"
# Expect: aggregate_id uuid not null, version integer not null, event_type varchar(64), payload text, UNIQUE(aggregate_id, version)

# Run event-store tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest -Dspring.profiles.active=test

# Check append guard (duplicate version rejected)
grep -rn "existsByAggregateIdAndVersion\|UNIQUE.*aggregate_id.*version" src/main --include="*.java" --include="*.sql" | head

# Show payload serialization (DomainEvent → JSON text)
grep -n "ObjectMapper\|writeValueAsString\|payload" src/main/java/com/company/orderapi/domain/eventstore/EventStoreService.java

# Replay proof via psql
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT aggregate_id, version, event_type, occurred_at, left(payload,80) FROM event_store ORDER BY aggregate_id, version;"

# Verify NOT BaseEntity (no version/audit mutation surface)
grep -n "extends BaseEntity\|updatable.*false" src/main/java/com/company/orderapi/domain/eventstore/EventStoreEntry.java
# Expect: no extends BaseEntity, every @Column has updatable=false

# Full integration (if OrderPlacedEvent wiring via outbox is present)
curl -s -X POST http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"customerId":1,"lines":[{"productId":1,"quantity":1}]}' | jq .orderNumber
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT count(*) FROM outbox; SELECT count(*) FROM event_store;"

# Liquibase changelog entry for event_store
grep -rn "event_store\|uq_event_store" src/main/resources/db/changelog --include="*.xml" --include="*.sql" | head
```

```java
// Appending events (two facts for same aggregate, versions 1 then 2)
UUID orderId = UUID.randomUUID();
DomainEvent placed = new OrderPlacedEvent(orderId, 1, LocalDateTime.now(), /* order fields */);
DomainEvent confirmed = new OrderConfirmedEvent(orderId, 2, LocalDateTime.now().plusMinutes(5));
eventStoreService.append(placed);    // INSERT v1
eventStoreService.append(confirmed); // INSERT v2
assertThatThrownBy(() -> eventStoreService.append(confirmed)) // duplicate v2
    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");

// Replay to derived state
List<DomainEvent> history = eventStoreService.readHistory(orderId); // [v1, v2] ORDER BY version ASC
OrderState state = history.stream().reduce(OrderState.EMPTY, this::apply, (a,b)->b);
assertThat(state.status()).isEqualTo(OrderStatus.CONFIRMED);
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Append-only `event_store` separate table | `EventStoreEntry.java:23` not `orders` mutation | Overwrite `orders.status` | History preserved; `UNIQUE(aggregate_id, version)` prevents duplicate facts; replayable | Extra table + INSERT per fact; current state must fold or still read `orders` |
| `payload` as JSON `text` | `EventStoreEntry.java:46` `columnDefinition="text"` + `ObjectMapper:51` | Binary/Avro/Protobuf + schema registry | Human-readable `psql`, zero infra, Jackson already on classpath | No queryable payload fields; evolution requires `@JsonIgnoreProperties` |
| `DomainEvent` interface with `version()` | `DomainEvent.java:22` per-aggregate `version` | Global sequence / timestamp ordering | Per-aggregate `UNIQUE(aggregate_id, version)` is race-proof and replay-ordered without global lock | Writer must track `nextVersion` per aggregate |
| `exists` check + DB `UNIQUE` | `EventStoreService.java:33` + DB constraint | Only DB constraint or only app check | Friendly `IllegalStateException` + true concurrency gate (two `exists==false` → one constraint fails, no lost fact) | Two queries for append (exists + insert); single insert path would handle constraint-exception mapping |
| Discriminator `event_type` column + switch | `EventStoreEntry.java:37` + `toEvent:57` switch | Jackson `@JsonTypeInfo` polymorphic | Explicit column `event_type` indexed queryable; switch exhaustiveness visible | New event type requires `toEvent` case addition |
| Not extending `BaseEntity` | `EventStoreEntry.java:23` standalone `@Entity` | Extend `BaseEntity` (`@Version`, audit) | Log entries never updated → `@Version`/audit mutations wrong semantics; `updatable=false` enforces immutability | Duplicate `created_at` mapping (`insertable=false, updatable=false` `Entry:49`) |
| Outbox (`OrderService.java:125`) vs event store | Both (different retention) | One table for both | Outbox deleted after Kafka publish; event store retained for replay/time-travel — separate lifecycles | Two writes per business fact when both needed |

---

## 7. How to verify

```bash
# Liquibase created event_store with UNIQUE
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d event_store"
# Expect: aggregate_id uuid not null, version integer not null, UNIQUE(aggregate_id, version)

# JPA mapping immutability: every column updatable=false
grep -c "updatable = false" src/main/java/com/company/orderapi/domain/eventstore/EventStoreEntry.java
# Expect: 7

# Service guards: exists check before insert
grep -n "existsByAggregateIdAndVersion" src/main/java/com/company/orderapi/domain/eventstore/EventStoreService.java
# Expect: EventStoreService.java:33

# Deserialization switch covers known types
grep -n "case \"OrderPlaced\|case \"OrderConfirmed\|toEvent" src/main/java/com/company/orderapi/domain/eventstore/EventStoreService.java

# Integration test proves append + replay round-trip
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -i "event_store\|EventStore" | head
./mvnw test -Dtest=EventStoreServiceTest -Dspring.profiles.active=test 2>&1 | tail -n 30  # if dedicated test exists

# Raw payload valid JSON
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT payload::jsonb FROM event_store LIMIT 1;" 2>&1 | head

# Config: no special profile — same DataSource/Liquibase as orders
grep -n "event_store\|EventStore" src/main/resources/application.yml | head
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New domain fact → `record MyEvent(UUID aggregateId, int version, LocalDateTime occurredAt, ...) implements DomainEvent` (`DomainEvent.java:16`); append via `EventStoreService.append(event)` (`EventStoreService.java:31`); auto-provide `nextVersion = eventStore.findByAggregateIdOrderByVersionAsc(id).size() + 1` or track on aggregate. Add `case "MyEvent" → objectMapper.readValue(payload, MyEvent.class)` in `toEvent:57`. Ensure `updatable=false` (`Entry:31-49`) and DB `UNIQUE(aggregate_id, version)` — writer must own version.
- **Operate:** `SELECT aggregate_id, max(version), count(*) FROM event_store GROUP BY aggregate_id HAVING count(*) != max(version)` finds gaps (missed append). `payload::jsonb` indexing never — add projected columns if you need `WHERE payload ->> 'status'`. Time-travel: `readHistory(id).stream().limit(version)` rebuilds state at version V. Outbox `outbox.poll-millis: 2000` (`application.yml:204`) lag ≠ event_store lag — different tables, different retention.
- **Interview:** "PR #19: `EventStoreEntry.java:23` `event_store` append-only (`updatable=false:31-49`, `PrePersist guard:52`, NOT `BaseEntity`), columns `aggregate_id UUID`, `version int`, `event_type`, `payload text JSON:46`, `occurred_at`. `DomainEvent.java:16` `aggregateId/version/occurredAt`. `EventStoreService.java:31` `append` serialize `ObjectMapper:50` + `exists:33` + `UNIQUE(aggregate_id,version)` gate + `Entry.from:61` `saveAndFlush`; `readHistory:43` `ORDER BY version ASC` + `toEvent:57` switch `eventType → readValue`. Domain `version` vs `BaseEntity @Version` orthogonal. Outbox `OrderService.java:125` transient; event store durable replayable."

---

## 9. Interview lens — Q&A

**Q1: What makes `event_store` append-only at every layer?**
A: Entity `updatable=false` on every column (`Entry:31-49`), `@PrePersist guardAppendOnly():52`, no `BaseEntity` mutable version/audit, DB `UNIQUE(aggregate_id, version)` prevents overwrite as insert, and optional `REVOKE UPDATE,DELETE` on the table (§2.1).

**Q2: How does `version` prevent duplicate facts under concurrency?**
A: Per-aggregate `DomainEvent.version():22` with app `existsByAggregateIdAndVersion:33` friendly check plus DB `UNIQUE(aggregate_id, version)` race gate — two concurrent `append(v2)` one constraint violation → no duplicate fact (§2.4).

**Q3: How is `payload` stored and evolved?**
A: `ObjectMapper.writeValueAsString:51` → `payload text:46` JSON. Evolution: add optional field + `@JsonIgnoreProperties(ignoreUnknown=true)` so old rows deserialize; breaking rename needs alias or backfill. No `WHERE payload` query — add columns for queryable fields (§2.6).

**Q4: Event store vs outbox vs audit?**
A: Event store (`Entry:23`) durable replayable source of truth, never deleted. Outbox (`OrderService.java:125`) transient bridge to Kafka, deleted after publish, same `DomainEvent` but not replay source. Audit log is compliance trail. Different retention (§2.7).

**Q5: `DomainEvent.version` vs `BaseEntity.version`?**
A: Domain `version` (`DomainEvent.java:22`) is business sequence per aggregate, uniqueness constraint. `BaseEntity.version:53` is Hibernate optimistic-lock for mutable `orders` row. Event-store rows never updated so Hibernate version irrelevant (§2.8).

**Q6: How is history replayed?**
A: `EventStoreService.readHistory:43` `findByAggregateIdOrderByVersionAsc` → `toEvent:57` switch `event_type` to typed class → `List<DomainEvent>` sorted → fold `reduce(initial, apply)` to derived state; `limit(version)` time-travels (§2.5).

---

## 10. Honest limits & next step → PR #20

Event-store INSERT per fact adds write amplification; large aggregates (1000 events) replay cost is `O(events)` unless snapshotting; `payload` as `text` not queryable; Jackson evolution can break old rows if `ignoreUnknown` not set. This PR stores facts, the next enforces *transactional atomicity* of those facts with business state: PR #20's service layer (`OrderService.java:76` `@Transactional`, `OrderService.java:83` `placeOrder` atomicity, `placeOrder` transaction that would ideally include the event-store append in the same commit).

See [`20-service-layer.md`](./20-service-layer.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Persist immutable fact | `EventStoreEntry.from` + `saveAndFlush` | `EventStoreEntry.java:61` + `EventStoreService.java:39` | Append-only, updatable=false |
| Prevent duplicate version | `exists` + `UNIQUE(aggregate_id,version)` | `EventStoreService.java:33` + Liquibase `event_store` | Race-proof exactly-once fact |
| Replay aggregate history | `readHistory` ORDER BY version | `EventStoreService.java:43` | Sorted fold to derived state |
| Transient Kafka bridge | `OutboxEntry.pending` + poll | `OrderService.java:125` + `application.yml:204` | Not replay source, deleted after publish |
| Business current-state read | `orders` table (`Order.java:42`) | `OrderRepository` | Additive twin — orders still authoritative for fast current read |
