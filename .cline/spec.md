# Order Management API - Complete Learning Specification
## 28 PRs - Each Teaching One API Development Aspect

---

## 1. PURPOSE

This is a **learning journey** through building a production-grade API. Each pull request teaches **one specific aspect** of API development. By the end, you'll understand every piece of a modern Java API, with deep coverage of JPA including foreign keys, cascading, fetch strategies, and production-ready enterprise features.

---

## 2. TECHNOLOGY STACK

| Category | Technology | Version | Purpose |
|----------|------------|---------|---------|
| **Language** | Java | 21 LTS | Virtual threads, records, pattern matching |
| **Framework** | Spring Boot | 3.2.1 | Application framework |
| **Build** | Maven | 3.9+ | Dependency management |
| **Database** | PostgreSQL | 16 | Primary + event store |
| **Migration** | Liquibase | 4.24+ | Schema versioning |
| **Cache** | Redis | 7 | Caching, rate limiting, distributed locks |
| **Messaging** | Apache Kafka | 3.6+ | Event streaming |
| **API** | REST + GraphQL | - | Dual API approach |
| **Security** | Spring Security, OAuth2 | - | AuthN/AuthZ |
| **Resilience** | Resilience4j | 2.2+ | Circuit breakers, retries, bulkheads |
| **Testing** | JUnit 5, Testcontainers, Pact | Latest | Unit, integration, contract |
| **Observability** | Prometheus, Grafana, Jaeger | Latest | Metrics, dashboards, tracing |
| **Deployment** | Kubernetes, ArgoCD | 1.28+ | Orchestration, GitOps |
| **Policy** | Open Policy Agent (OPA) | Latest | Policy as code |

---

## 3. DOMAIN MODEL

### 3.1 Core Entities and Relationships
┌─────────────────┐ ┌──────────────────┐ ┌─────────────────┐
│ Customer │1───────│ Address │ │ Product │
├─────────────────┤ ├──────────────────┤ ├─────────────────┤
│ id: Long │ │ id: Long │ │ id: Long │
│ email: String │ │ street: String │ │ name: String │
│ fullName: String│ │ city: String │ │ description: Str│
│ phoneNumber: Str│ │ state: String │ │ price: BigDecimal│
│ addresses: List │ │ postalCode: Str │ │ stockQuantity: │
│ orders: List │ │ country: String │ │ categories: Set │
└─────────────────┘ │ isDefault: bool │ └─────────────────┘
│ │ addressType: Enum│ │
│1 └──────────────────┘ │*
│ │ │
│ │ │
│* │ │
┌─────────────────┐ ┌──────────────────┐ ┌─────────────────┐
│ Order │1───────│ OrderItem │───────1│ Category │
├─────────────────┤ ├──────────────────┤ ├─────────────────┤
│ id: Long │ │ id: Long │ │ id: Long │
│ customer: Cust │ │ order: Order │ │ name: String │
│ orderDate: Date │ │ product: Product │ │ description: Str│
│ status: Enum │ │ quantity: Int │ │ products: Set │
│ totalAmount: Bd │ │ unitPrice: Bd │ └─────────────────┘
│ shippingAddr: │ │ totalPrice: Bd │
│ billingAddr: │ └──────────────────┘
│ items: List │
│ payment: Paymnt │1───────1┌──────────────────┐
└─────────────────┘ │ Payment │
├──────────────────┤
│ id: Long │
│ order: Order │
│ amount: BigDecimal│
│ paymentMethod: En│
│ status: Enum │
│ transactionId: Str│
│ paymentDate: Date│
└──────────────────┘

text

### 3.2 Domain Events (Event Sourcing)

```java
// Base Interface
public interface DomainEvent {
    UUID getAggregateId();
    String getAggregateType();
    int getVersion();
    LocalDateTime getOccurredAt();
}

// Order Events
public record OrderPlacedEvent(UUID orderId, Long customerId, List<OrderItem> items, 
    Money total, Address shippingAddress, Address billingAddress, 
    LocalDateTime occurredAt) implements DomainEvent {}

public record OrderConfirmedEvent(UUID orderId, LocalDateTime confirmedAt) 
    implements DomainEvent {}

public record OrderShippedEvent(UUID orderId, String trackingNumber, 
    LocalDateTime shippedAt) implements DomainEvent {}

public record OrderDeliveredEvent(UUID orderId, LocalDateTime deliveredAt) 
    implements DomainEvent {}

public record OrderCancelledEvent(UUID orderId, String reason, 
    LocalDateTime cancelledAt) implements DomainEvent {}

// Payment Events
public record PaymentProcessedEvent(UUID orderId, String transactionId, 
    PaymentStatus status, Money amount, LocalDateTime processedAt) 
    implements DomainEvent {}

public record PaymentFailedEvent(UUID orderId, String reason, 
    LocalDateTime failedAt) implements DomainEvent {}

// Stock Events
public record StockReservedEvent(UUID orderId, List<StockReservation> reservations, 
    LocalDateTime reservedAt) implements DomainEvent {}

public record StockReleasedEvent(UUID orderId, List<StockReservation> reservations, 
    String reason, LocalDateTime releasedAt) implements DomainEvent {}
4. PR SEQUENCE - LEARN ONE ASPECT AT A TIME
PR #1: Project Setup
Aspect Learned: Spring Boot project structure and dependency management

Deliverables:

□ pom.xml with Spring Boot 3.2.1, Java 21, and all dependencies
□ Maven wrapper (mvnw, mvnw.cmd)
□ OrderManagementApiApplication.java
□ application.yml with basic config
□ .gitignore for Java projects
□ README.md with project overview
Key Questions to Answer:

How does a Spring Boot project structure work?

What are the key dependencies for a JPA project?

How does the Maven wrapper work?

PR #2: Database Schema with Foreign Keys (Liquibase)
Aspect Learned: Database design, foreign keys, and relationships

Deliverables:

□ db/changelog/db.changelog-master.xml
□ Foreign Key Constraints defined at database level:
customer_id → customers(id) with CASCADE on delete

order_id → orders(id) with CASCADE on delete

product_id → products(id) with RESTRICT on delete

category_id → categories(id) with RESTRICT on delete

shipping_address_id → addresses(id) with SET NULL

billing_address_id → addresses(id) with SET NULL

□ All 8 tables with proper constraints
□ Indexes on all foreign key columns
□ docker-compose.yml with PostgreSQL
Key Questions to Answer:

Why define foreign keys at database level vs. just JPA?

What is the difference between CASCADE, RESTRICT, and SET NULL?

Why index foreign key columns?

Why This Matters: Database-level foreign keys ensure data integrity even if application-level checks fail.

PR #3: JPA Entities with Foreign Key Mappings
Aspect Learned: JPA foreign key mappings (@JoinColumn, @JoinTable)

Deliverables:

□ BaseEntity.java with @MappedSuperclass
□ Customer ↔ Address: @OneToMany with @JoinColumn(name = "customer_id")
□ Customer ↔ Order: @OneToMany(mappedBy = "customer")
□ Order ↔ OrderItem: @OneToMany(mappedBy = "order") with orphanRemoval = true
□ Order → Customer: @ManyToOne with @JoinColumn(name = "customer_id")
□ Order → Address (Shipping): @ManyToOne with @JoinColumn(name = "shipping_address_id")
□ Order → Address (Billing): @ManyToOne with @JoinColumn(name = "billing_address_id")
□ OrderItem → Order: @ManyToOne with @JoinColumn(name = "order_id")
□ OrderItem → Product: @ManyToOne with @JoinColumn(name = "product_id")
□ Product → Category: @ManyToMany with @JoinTable and @JoinColumn
Key Questions to Answer:

What is the difference between @JoinColumn and mappedBy?

Why use orphanRemoval = true?

What is the @JoinTable for many-to-many relationships?

Why This Matters: Correct foreign key mappings are essential for JPA relationships.

PR #4: Cascading Strategies
Aspect Learned: JPA cascading (CascadeType)

Deliverables:

□ CascadeType.PERSIST on Customer → Addresses
□ CascadeType.ALL on Order → OrderItems
□ CascadeType.ALL on Order → Payment
□ CascadeType.MERGE on Product → Categories
□ CascadeType.PERSIST on Customer → Orders
□ JUnit tests verifying cascading behavior
Key Questions to Answer:

What is JPA cascading and why use it?

What are the different CascadeType options?

When should you use CascadeType.ALL vs. specific types?

Why This Matters: Cascading controls how operations propagate through relationships.

PR #5: Fetch Types
Aspect Learned: Fetch strategies (LAZY vs EAGER)

Deliverables:

□ @OneToMany(fetch = FetchType.LAZY) on Customer → Addresses
□ @OneToMany(fetch = FetchType.LAZY) on Order → OrderItems
□ @ManyToOne(fetch = FetchType.LAZY) on Order → Customer
□ @ManyToMany(fetch = FetchType.LAZY) on Product → Categories
□ @ManyToOne(fetch = FetchType.LAZY) on OrderItem → Product
□ Hibernate SQL logging to show fetch behavior
□ Tests demonstrating LAZY vs EAGER differences
Key Questions to Answer:

What is the difference between LAZY and EAGER fetching?

Why default to LAZY in production?

What is the "N+1 problem" and how does fetch type affect it?

Why This Matters: Fetch strategies are critical for performance.

PR #6: Fetch Joins & Entity Graphs (N+1 Fix)
Aspect Learned: Fixing N+1 queries with JOIN FETCH and @EntityGraph

Deliverables:

□ Method that causes N+1 queries (demonstration)
□ JOIN FETCH solution in JPQL
□ @EntityGraph(attributePaths = {...}) solution
□ @NamedEntityGraph on entities
□ Hibernate query count logging
□ NPlusOneDemoTest.java showing before/after
Key Questions to Answer:

What is the N+1 problem and how to identify it?

What is the difference between JOIN FETCH and @EntityGraph?

When to use @EntityGraph vs JOIN FETCH?

Why This Matters: N+1 is the most common JPA performance issue.

PR #7: Batch Fetching
Aspect Learned: Batch fetching for performance

Deliverables:

□ @BatchSize annotation on collections
□ spring.jpa.properties.hibernate.default_batch_fetch_size
□ spring.jpa.properties.hibernate.jdbc.fetch_size
□ Performance comparison tests
Key Questions to Answer:

What is batch fetching?

How does @BatchSize work?

When to use batch fetching vs. JOIN FETCH?

Why This Matters: Batch fetching reduces database round trips.

PR #8: Optimistic Locking
Aspect Learned: @Version and optimistic locking

Deliverables:

□ @Version on all entities
□ version column in all tables
□ @Retryable for OptimisticLockingFailureException
□ Concurrent update test
□ OptimisticLockingTest.java with 100 concurrent updates
Key Questions to Answer:

What is optimistic locking and how does it work?

Why use @Version?

What is the difference between optimistic and pessimistic locking?

Why This Matters: Optimistic locking prevents lost updates in concurrent scenarios.

PR #9: Pessimistic Locking
Aspect Learned: @Lock and pessimistic locking

Deliverables:

□ @Lock(LockModeType.PESSIMISTIC_WRITE) on repository method
□ SELECT ... FOR UPDATE in JPQL
□ @Lock(LockModeType.PESSIMISTIC_READ) for read operations
□ Deadlock handling demonstration
□ Performance comparison with optimistic locking
Key Questions to Answer:

What is pessimistic locking and when to use it?

What are LockModeType options?

What are the tradeoffs of pessimistic vs optimistic locking?

Why This Matters: Pessimistic locking ensures exclusive access in high-contention scenarios.

PR #10: Auditing
Aspect Learned: @CreatedDate, @LastModifiedDate, @CreatedBy, @LastModifiedBy

Deliverables:

□ @EnableJpaAuditing configuration
□ AuditorAware bean for current user
□ @CreatedDate on createdAt
□ @LastModifiedDate on updatedAt
□ @CreatedBy on createdBy
□ @LastModifiedBy on updatedBy
□ Auditing tests verifying fields are populated
Key Questions to Answer:

How does JPA auditing work?

Why use auditing?

How to inject current user into AuditorAware?

Why This Matters: Auditing provides traceability for all data changes.

PR #11: Custom Queries
Aspect Learned: @Query and custom JPQL queries

Deliverables:

□ @Query with JPQL for complex queries
□ @Query with native SQL
□ @Query with pagination
□ @Query with SpEL expressions
□ Integration tests for all custom queries
Key Questions to Answer:

When to use @Query vs. method naming?

What is JPQL and how is it different from SQL?

When to use native SQL vs. JPQL?

Why This Matters: Custom queries handle complex business logic.

PR #12: Specifications & QueryDSL
Aspect Learned: Dynamic queries with Specifications

Deliverables:

□ Specification pattern for dynamic filtering
□ JpaSpecificationExecutor on repositories
□ Specification<T> for complex search
□ Integration tests for dynamic queries
Key Questions to Answer:

What is JPA Specification?

When to use Specification?

How to build dynamic queries?

Why This Matters: Dynamic queries handle complex search requirements.

PR #13: Entity Lifecycle Callbacks
Aspect Learned: @PrePersist, @PreUpdate, @PreRemove

Deliverables:

□ @PrePersist on Order (generate order number)
□ @PreUpdate on Payment (update payment date)
□ @PreRemove on Customer (prevent deletion if orders exist)
□ @PostLoad for logging
□ Tests verifying callbacks work
Key Questions to Answer:

What are JPA lifecycle callbacks?

Order of execution for different callbacks?

When to use @PrePersist vs. @PreUpdate?

Why This Matters: Lifecycle callbacks handle business logic around persistence events.

PR #14: Schema Generation & Validation
Aspect Learned: ddl-auto options

Deliverables:

□ spring.jpa.hibernate.ddl-auto=validate in production
□ spring.jpa.hibernate.ddl-auto=update in development
□ Schema validation tests
□ Liquibase schema validation
Key Questions to Answer:

What is ddl-auto and what options exist?

Why use validate in production?

What is the danger of update in production?

Why This Matters: Schema generation affects both development and production.

PR #15: SQL Logging & Debugging
Aspect Learned: Hibernate SQL logging and debugging

Deliverables:

□ spring.jpa.show-sql=true
□ spring.jpa.properties.hibernate.format_sql=true
□ spring.jpa.properties.hibernate.use_sql_comments=true
□ logging.level.org.hibernate.SQL=DEBUG
□ logging.level.org.hibernate.orm.jdbc.bind=TRACE
□ logging.level.org.hibernate.stat=DEBUG
□ spring.jpa.properties.hibernate.generate_statistics=true
Key Questions to Answer:

How to log SQL generated by JPA?

How to log parameter bindings?

How to get query statistics?

Why This Matters: SQL logging is essential for debugging and performance tuning.

PR #16: Second Level Cache
Aspect Learned: JPA second-level caching

Deliverables:

□ hibernate.cache.use_second_level_cache=true
□ @Cacheable on Product entity
□ @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
□ Ehcache or Caffeine configuration
□ Cache hit/miss statistics
□ Integration tests for caching
Key Questions to Answer:

What is second-level cache vs. first-level cache?

How to configure second-level cache?

When to use second-level cache?

Why This Matters: Second-level cache improves performance of read-heavy applications.

PR #17: DTO Projections
Aspect Learned: DTO projections with JPA

Deliverables:

□ interface based projections
□ class based projections with constructor expressions
□ @Query with new in JPQL
□ Performance comparison with entity queries
Key Questions to Answer:

What are JPA projections?

Why use projections vs. entities?

What is the difference between interface and class projections?

Why This Matters: Projections improve performance by selecting only needed fields.

PR #18: JPA Events & Listeners
Aspect Learned: @EntityListeners

Deliverables:

□ AuditingEntityListener for auditing
□ Custom EntityListener for business rules
□ @PostPersist listener for event publishing
□ Tests verifying listener behavior
Key Questions to Answer:

What are entity listeners?

When to use @EntityListeners?

How do listeners compare to lifecycle callbacks?

Why This Matters: Entity listeners decouple persistence logic from business logic.

PR #19: Event Sourcing & Event Store
Aspect Learned: Event sourcing and event store

Deliverables:

□ DomainEvent.java interface
□ OrderPlacedEvent.java, OrderConfirmedEvent.java
□ EventStoreRepository.java
□ EventStoreService.java
□ event_store table (added to Liquibase)
□ Tests for event storage and retrieval
Key Questions to Answer:

What is event sourcing?

How is it different from traditional CRUD?

What are the benefits of event sourcing?

Why This Matters: Event sourcing provides an audit trail and enables temporal queries.

PR #20: Service Layer
Aspect Learned: Transaction management and ACID

Deliverables:

□ OrderService.java with @Transactional
□ placeOrder() method with stock deduction
□ @Retryable for optimistic locking
□ Payment simulation with 10% failure rate
□ OrderServiceTest.java with @Transactional rollback
Key Questions to Answer:

What is ACID and why does it matter?

How does @Transactional work?

What is optimistic locking and why use it?

Why This Matters: Service layer is where business logic and transactions live.

PR #21: DTOs (Java Records)
Aspect Learned: Java Records and data transfer objects

Deliverables:

□ OrderRequest.java record with validation
□ OrderResponse.java record
□ CustomerRequest.java, CustomerResponse.java
□ ProductRequest.java, ProductResponse.java
□ OrderMapper.java for mapping entities to DTOs
Key Questions to Answer:

Why use DTOs instead of exposing entities?

What are Java Records and why use them?

How do you map entities to DTOs?

Why This Matters: DTOs separate API contracts from domain models.

PR #22: REST Controllers
Aspect Learned: REST API design and CRUD

Deliverables:

□ CustomerController.java with GET, POST, PUT, DELETE
□ ProductController.java with CRUD
□ CategoryController.java with CRUD
□ OrderController.java with POST /api/v1/orders
□ Pagination with Pageable
□ Sorting with Sort
□ Field filtering: ?fields=id,name,email
□ Resource inclusion: ?include=orders,addresses
□ ETag support with If-Match
□ PATCH operations with JSON Patch
□ Bulk operations: POST /api/v1/orders/bulk
□ Integration tests with MockMvc
Key Questions to Answer:

What are REST principles?

How do you design RESTful endpoints?

What HTTP methods map to which CRUD operations?

Why This Matters: REST is the foundation of API design.

PR #23: Validation
Aspect Learned: Bean Validation and custom validators

Deliverables:

□ Validation annotations: @NotNull, @Size, @Email
□ Custom validator: @ValidStock
□ Custom validator: @ValidOrderRequest (cross-field)
□ Validation groups: Create, Update
□ Integration tests for validation errors
Key Questions to Answer:

How does Bean Validation work?

When to use custom validators?

What are validation groups for?

Why This Matters: Validation ensures data integrity and improves user experience.

PR #24: Exception Handling & Idempotency
Aspect Learned: RFC 7807 Problem Details and idempotency

Deliverables:

□ GlobalExceptionHandler.java with @ControllerAdvice
□ RFC 7807 Problem Details responses
□ Pattern-matching switch for exception types
□ Error catalog with codes and documentation
□ ErrorResponse.java with hints
□ idempotency_keys table (Liquibase)
□ IdempotencyKeyRepository.java
□ IdempotencyService.java
□ Header: Idempotency-Key
□ Tests for retry scenarios
Key Questions to Answer:

What is RFC 7807 Problem Details?

Why use @ControllerAdvice?

What is idempotency and why does it matter?

Why This Matters: Consistent error handling and idempotency improve API reliability.

PR #25: OpenAPI Documentation
Aspect Learned: API documentation with OpenAPI

Deliverables:

□ springdoc-openapi dependency
□ @Operation annotations on controllers
□ @ApiResponse annotations
□ openapi.yaml generated file
□ Swagger UI at /swagger-ui.html
□ Postman collection export
□ API versioning strategy (v1/v2 coexistence)
□ Deprecation headers (Deprecation, Sunset)
Key Questions to Answer:

Why document APIs?

What is OpenAPI and Swagger?

How do you generate documentation from code?

Why This Matters: Good documentation makes APIs usable.

PR #26: Security (OAuth2 & JWT)
Aspect Learned: OAuth2 and JWT authentication

Deliverables:

□ SecurityConfig.java with OAuth2 Resource Server
□ JWT validation with JwtDecoder
□ JWT with JWE encryption
□ OAuth2 scopes: order_read, order_write
□ @PreAuthorize on methods
□ JwtAuthenticationConverter.java
□ ApiKeyAuthenticationFilter.java
□ Security integration tests
□ CORS configuration
□ Security headers: HSTS, CSP, X-Content-Type-Options
Key Questions to Answer:

What is OAuth2 and how does it work?

What is JWT and why use it?

What is the difference between authentication and authorization?

Why This Matters: Security is critical for any production API.

PR #27: PII & GDPR
Aspect Learned: Data privacy and compliance

Deliverables:

□ PIIRedactionFilter.java for logs
□ SensitiveDataSerializer.java for responses
□ GDPR: DELETE /api/v1/customers/{id}/data (right to erasure)
□ GDPR: GET /api/v1/customers/{id}/portability (data portability)
□ Audit trail implementation
□ PCI-DSS compliant payment handling
Key Questions to Answer:

What is PII and why protect it?

What are GDPR requirements?

What are the OWASP Top 10 items addressed?

Why This Matters: Compliance is required by law and best practice.

PR #28: Caching (Redis)
Aspect Learned: Caching patterns

Deliverables:

□ Redis Docker container in docker-compose
□ RedisConfig.java with @EnableCaching
□ @Cacheable on product retrieval
□ @CacheEvict on product updates
□ Cache TTL configuration
□ Cache hit/miss metrics
□ Integration tests with Redis
Key Questions to Answer:

What is caching and why use it?

What is cache-aside pattern?

How do you invalidate cache?

Why This Matters: Caching improves performance and reduces database load.

PR #29: Resilience Patterns
Aspect Learned: Circuit breakers and rate limiting

Deliverables:

□ Resilience4j dependencies
□ CircuitBreaker on payment gateway
□ RateLimiter per API key
□ Headers: X-RateLimit-Remaining, X-RateLimit-Reset
□ Retry with exponential backoff
□ Bulkhead for thread pool isolation
□ Chaos testing with latency injection
□ Load Test Script: scripts/k6-load-test.js
Key Questions to Answer:

What is a circuit breaker and why use it?

How does rate limiting work?

What is a bulkhead pattern?

Why This Matters: Resilience patterns make APIs robust and reliable.

PR #30: Virtual Threads & Concurrency
Aspect Learned: Java 21 virtual threads

Deliverables:

□ spring.threads.virtual.enabled=true
□ Concurrency test: 100 simultaneous "buy last unit" requests
□ StructuredTaskScope for fan-out
□ Distributed lock with Redisson
□ Replace synchronized with ReentrantLock
□ Performance comparison tests
Key Questions to Answer:

What are virtual threads and why use them?

What is structured concurrency?

What is the pinning problem?

Why This Matters: Virtual threads improve concurrency and scalability.

PR #31: Kafka (Event-Driven)
Aspect Learned: Event-driven architecture

Deliverables:

□ Kafka Docker container in docker-compose
□ Outbox pattern with polling publisher
□ OrderEventProducer.java
□ Avro/Protobuf schema with Schema Registry
□ OrderEventConsumer.java
□ Manual offset management
□ Dead-letter topic
□ Integration tests with @EmbeddedKafka
Key Questions to Answer:

What is event-driven architecture?

Why use the outbox pattern?

What are delivery semantics?

Why This Matters: Event-driven architecture enables decoupling and scalability.

PR #32: Observability
Aspect Learned: Logging, metrics, and tracing

Deliverables:

□ JSON structured logging with Logback
□ MDC correlation/trace IDs
□ Micrometer metrics
□ @Timed annotations
□ OpenTelemetry tracing
□ Jaeger exporter
□ Health indicators
□ SLO definitions
□ AlertManager rules
Key Questions to Answer:

What is structured logging?

Why use distributed tracing?

What are SLIs, SLOs, and SLAs?

Why This Matters: Observability helps understand system behavior.

PR #33: Docker & Kubernetes
Aspect Learned: Containerization and deployment

Deliverables:

□ Multi-stage Dockerfile
□ docker-compose.yml with all services
□ Kubernetes Deployment with resource limits
□ Kubernetes Service
□ ConfigMap and Secrets (with Sealed Secrets)
□ HorizontalPodAutoscaler
□ Liveness/Readiness probes
□ Ingress with TLS (cert-manager)
□ Graceful shutdown configuration
□ Kustomize overlays: dev, test, prod
Key Questions to Answer:

What is containerization?

What is Kubernetes and why use it?

How do probes work?

Why This Matters: Containers enable consistent deployment.

PR #34: CI/CD & GitOps
Aspect Learned: Automation and deployment pipeline

Deliverables:

□ GitHub Actions CI workflow
□ Trunk-based development
□ ArgoCD/Flux configuration
□ Kustomize overlays per environment
□ Promotion workflow (dev → test → uat → staging → prod)
□ Sealed Secrets for encrypted secrets
□ Feature flags
□ Contract testing with Pact
Key Questions to Answer:

What is GitOps?

How do you handle secrets in Git?

What is progressive delivery?

Why This Matters: CI/CD automates deployment and reduces errors.

PR #35: Enterprise Features (Optional)
Aspect Learned: Multi-tenancy, i18n, and advanced features

Deliverables:

□ Multi-tenancy: Schema per tenant or discriminator column
□ Internationalization (i18n): messages.properties
□ Feature flags
□ Developer portal with API key self-service
□ CSV/Excel/PDF export endpoints
□ Scheduled jobs (Quartz)
□ Background processing
□ Database backup strategy
Key Questions to Answer:

What is multi-tenancy?

How do you handle internationalization?

What is progressive delivery?

Why This Matters: Enterprise features make APIs suitable for large organizations.

5. COMPLETE FILE STRUCTURE
text
order-management-api/
├── pom.xml
├── mvnw
├── README.md
├── spec.md
├── docker-compose.yml
├── Dockerfile
├── .gitignore
├── .env.example
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/company/orderapi/
│   │   │       ├── OrderManagementApiApplication.java
│   │   │       │
│   │   │       ├── domain/
│   │   │       │   ├── aggregate/
│   │   │       │   │   ├── Order.java
│   │   │       │   │   ├── Customer.java
│   │   │       │   │   └── Product.java
│   │   │       │   ├── valueobject/
│   │   │       │   │   ├── Money.java
│   │   │       │   │   ├── OrderNumber.java
│   │   │       │   │   ├── Email.java
│   │   │       │   │   └── Address.java
│   │   │       │   ├── event/
│   │   │       │   │   ├── DomainEvent.java
│   │   │       │   │   ├── OrderPlacedEvent.java
│   │   │       │   │   ├── OrderConfirmedEvent.java
│   │   │       │   │   └── PaymentProcessedEvent.java
│   │   │       │   ├── repository/
│   │   │       │   │   ├── OrderRepository.java
│   │   │       │   │   ├── CustomerRepository.java
│   │   │       │   │   └── ProductRepository.java
│   │   │       │   └── service/
│   │   │       │       ├── OrderService.java
│   │   │       │       ├── CustomerService.java
│   │   │       │       └── EventStoreService.java
│   │   │       │
│   │   │       ├── api/
│   │   │       │   ├── rest/
│   │   │       │   │   ├── controller/
│   │   │       │   │   │   ├── OrderController.java
│   │   │       │   │   │   ├── CustomerController.java
│   │   │       │   │   │   └── ProductController.java
│   │   │       │   │   ├── dto/
│   │   │       │   │   │   ├── OrderRequest.java
│   │   │       │   │   │   ├── OrderResponse.java
│   │   │       │   │   │   └── CustomerResponse.java
│   │   │       │   │   └── mapper/
│   │   │       │   │       └── OrderMapper.java
│   │   │       │   ├── graphql/
│   │   │       │   │   ├── OrderGraphQLController.java
│   │   │       │   │   └── resolver/
│   │   │       │   │       ├── OrderQueryResolver.java
│   │   │       │   │       └── OrderMutationResolver.java
│   │   │       │   └── exception/
│   │   │       │       ├── GlobalExceptionHandler.java
│   │   │       │       └── ErrorResponse.java
│   │   │       │
│   │   │       ├── security/
│   │   │       │   ├── SecurityConfig.java
│   │   │       │   ├── JwtAuthenticationConverter.java
│   │   │       │   ├── ApiKeyAuthenticationFilter.java
│   │   │       │   └── PIIRedactionFilter.java
│   │   │       │
│   │   │       ├── infrastructure/
│   │   │       │   ├── config/
│   │   │       │   │   ├── AuditConfig.java
│   │   │       │   │   ├── RedisConfig.java
│   │   │       │   │   └── Resilience4jConfig.java
│   │   │       │   ├── persistence/
│   │   │       │   │   ├── eventstore/
│   │   │       │   │   │   ├── EventStoreRepository.java
│   │   │       │   │   │   └── EventStoreService.java
│   │   │       │   │   ├── outbox/
│   │   │       │   │   │   ├── OutboxRepository.java
│   │   │       │   │   │   └── OutboxPublisher.java
│   │   │       │   │   └── readmodel/
│   │   │       │   │       ├── OrderSummaryRepository.java
│   │   │       │   │       └── OrderProjection.java
│   │   │       │   ├── messaging/
│   │   │       │   │   ├── OrderEventProducer.java
│   │   │       │   │   ├── OrderEventConsumer.java
│   │   │       │   │   └── KafkaConfig.java
│   │   │       │   └── resilience/
│   │   │       │       ├── CircuitBreakerConfig.java
│   │   │       │       └── RetryConfig.java
│   │   │       │
│   │   │       └── observability/
│   │   │           ├── OrderMetrics.java
│   │   │           ├── SLOController.java
│   │   │           └── HealthIndicators.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── application-prod.yml
│   │       ├── logback-spring.xml
│   │       ├── openapi.yaml
│   │       │
│   │       └── db/
│   │           └── changelog/
│   │               ├── db.changelog-master.xml
│   │               └── v1.0/
│   │                   ├── 01_create_tables.sql
│   │                   ├── 02_create_event_store.sql
│   │                   ├── 03_create_read_model.sql
│   │                   └── 04_create_indexes.sql
│   │
│   └── test/
│       ├── java/
│       │   └── com/company/orderapi/
│       │       ├── unit/
│       │       ├── integration/
│       │       ├── contract/
│       │       └── performance/
│       └── resources/
│           ├── testcontainers.properties
│           └── pact/
│
├── k8s/
│   ├── base/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   ├── configmap.yaml
│   │   └── hpa.yaml
│   ├── overlays/
│   │   ├── dev/
│   │   ├── test/
│   │   ├── uat/
│   │   ├── staging/
│   │   └── prod/
│   └── istio/
│       ├── virtual-service.yaml
│       └── destination-rule.yaml
│
├── scripts/
│   ├── start-local.sh
│   ├── stop-local.sh
│   ├── reset-db.sh
│   ├── demo-commands.sh
│   ├── k6-load-test.js
│   └── chaos-test.sh
│
├── docs/
│   ├── architecture.md
│   ├── api-guide.md
│   └── adr/
│       ├── 001-event-sourcing.md
│       ├── 002-cqrs.md
│       └── 003-virtual-threads.md
│
├── .github/
│   └── workflows/
│       ├── ci.yml
│       ├── performance.yml
│       └── security.yml
│
└── observability/
    ├── prometheus.yml
    ├── grafana-dashboards/
    │   ├── operational.json
    │   ├── business.json
    │   └── slo.json
    └── alerts/
        └── alert-rules.yml
6. INTERVIEW QUESTIONS TO PREPARE
Architecture Questions
Why Event Sourcing + CQRS?

Why DDD over traditional CRUD?

Why virtual threads over reactive programming?

Why dual API (REST + GraphQL)?

How do you handle distributed transactions?

JPA Questions
What is the difference between @JoinColumn and mappedBy?

When to use CascadeType.ALL vs specific types?

What is the difference between optimistic and pessimistic locking?

How do you fix the N+1 problem?

What is the difference between LAZY and EAGER fetching?

Security Questions
What is the difference between authentication and authorization?

How does OAuth2 work?

What is JWT and how is it used?

What is PII and how do you protect it?

What are the OWASP Top 10?

Resilience Questions
What is a circuit breaker pattern?

How does rate limiting work?

What is a bulkhead pattern?

What is the difference between retry and circuit breaker?

Deployment Questions
What is containerization?

What is Kubernetes and why use it?

What is GitOps?

How do you handle secrets in Kubernetes?

7. SUCCESS CRITERIA
Must Complete (Critical):
□ All 35 PRs completed
□ All tests passing (80%+ coverage)
□ Application runs locally
□ API endpoints functional
□ Event sourcing working
□ Kafka events flowing
□ Kubernetes deployment works
□ CI/CD pipeline green
Nice to Have:
□ GraphQL subscriptions
□ WebSocket notifications
□ Multi-tenancy
□ Internationalization
□ Developer portal
Documentation:
□ README with architecture diagram
□ API documentation
□ Deployment guide
□ "What I simplified and why" section
8. COMMANDS FOR AGENT
text
Read the spec completely. Build the project following the 35 PR sequence.
Each PR teaches ONE specific aspect of API development.
Create one pull request per PR.
Start with PR #1 and work sequentially.
After creating each PR, stop and wait for my review.
Only proceed to the next PR after I approve.

For each PR:
1. Complete all deliverables
2. Ensure all tests pass
3. Write clear PR description explaining the concept learned
4. Include code comments explaining key design decisions
5. Add questions answered section to PR description

All code must be production-grade with tests.
Use Java 21 features (records, pattern matching, virtual threads).

Begin with PR #1: Project Setup now.
9. SUMMARY
Metric	Value
Total PRs	35
Agent Time	~4-5 hours
Your Review Time	~5 min per PR
Learning Time	1 concept per PR
Total Calendar Time	3-5 days
Each PR teaches ONE aspect. You'll understand every piece of API development. 🚀

END OF SPECIFICATION
