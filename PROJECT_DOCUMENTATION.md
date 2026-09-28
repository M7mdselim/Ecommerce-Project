# Ecommerce Microservices Platform — Full Technical Documentation

> **Repository:** `M7mdselim/Ecommerce-Project`  
> **Stack:** Java 21 · Spring Boot 3.x · Spring Cloud 2025 · Kafka · PostgreSQL · Redis · Kubernetes · Istio · k6  
> **Sessions covered:** 1 – 23

---

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Service Catalogue](#2-service-catalogue)
3. [Infrastructure Services](#3-infrastructure-services)
4. [Business Microservices — Deep Dive](#4-business-microservices--deep-dive)
5. [Inter-Service Communication](#5-inter-service-communication)
6. [Security Model (Session 20)](#6-security-model-session-20)
7. [Resilience Patterns](#7-resilience-patterns)
8. [Observability Stack](#8-observability-stack)
9. [Kafka Topics & Event Flows](#9-kafka-topics--event-flows)
10. [Saga Patterns](#10-saga-patterns)
11. [CQRS Implementation (Product Service)](#11-cqrs-implementation-product-service)
12. [Kubernetes Deployment](#12-kubernetes-deployment)
13. [Helm Chart (Product Service)](#13-helm-chart-product-service)
14. [Istio Service Mesh (Session 21)](#14-istio-service-mesh-session-21)
15. [GitOps with ArgoCD](#15-gitops-with-argocd)
16. [Testing Strategy](#16-testing-strategy)
17. [Local Development](#17-local-development)
18. [Environment Variables Reference](#18-environment-variables-reference)
19. [API Reference](#19-api-reference)
20. [Transactional Outbox Pattern & Distributed Tracing (Session 22)](#20-transactional-outbox-pattern--distributed-tracing-session-22)
21. [Performance & Stress Testing — k6 (Session 23)](#21-performance--stress-testing--k6-session-23)
22. [Idempotency Key Pattern (Session 24)](#22-idempotency-key-pattern-session-24)
23. [Technical Debt & Production Readiness](#23-technical-debt--production-readiness)
24. [Design Decisions Log](#24-design-decisions-log)

---

## 1. High-Level Architecture

```
                         ┌─────────────────────────────────┐
                         │          External Client         │
                         │  (Browser / Postman / Mobile)    │
                         └────────────────┬────────────────┘
                                          │ HTTPS
                                          ▼
                         ┌─────────────────────────────────┐
                         │           API Gateway            │
                         │         (Port 8080)              │
                         │  • JWT Validation (HS256)        │
                         │  • Rate Limiting (Redis)         │
                         │  • Route Rewriting               │
                         │  • X-User-Id / X-User-Role hdrs  │
                         └──┬────────┬────────┬────────────┘
                            │        │        │
                mTLS        │        │        │
          ┌─────────────────┘        │        └────────────────┐
          ▼                          ▼                          ▼
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  Product Service │    │  Order Service   │    │Inventory Service │
│  (Port 8081)     │    │  (Port 8082)     │    │  (Port 8084)     │
│  • CRUD Products │    │  • Place Orders  │    │  • Stock Checks  │
│  • Redis Cache   │    │  • Saga Orch.    │    │  • Reservations  │
│  • CQRS DTOs     │    │  • Feign → Inv.  │    │  • Saga Handler  │
└──────────────────┘    └────────┬─────────┘    └──────────────────┘
                                 │ Kafka
                         ┌───────┴──────────┐
                         │                  │
              ┌──────────┴──┐        ┌──────┴──────────┐
              │Payment Svc  │        │Notification Svc  │
              │(Port 8083)  │        │(Port 8085)       │
              │• Process Pmt│        │• Email/SMS Alerts│
              │• Saga Cmds  │        │• Retry + DLT     │
              └─────────────┘        └──────────────────┘

Supporting Infrastructure:
  Config Server (8888) · Eureka Discovery (8761)
  PostgreSQL (5432) · Redis (6379) · Kafka (9092)
  Zipkin (9411) · Prometheus (9090) · Grafana (3000)
```

---

## 2. Service Catalogue

| Service | Port | Language | Database | Communicates With |
|---|---|---|---|---|
| **api-gateway** | 8080 | Java/WebFlux | Redis | All services via Eureka LB |
| **product-service** | 8081 | Java/MVC | PostgreSQL + Redis cache | — |
| **order-service** | 8082 | Java/MVC | H2 (dev) / PostgreSQL | Inventory (Feign), Kafka |
| **payment-service** | 8083 | Java/MVC | — (stateless) | Kafka |
| **inventory-service** | 8084 | Java/MVC | In-memory Map | Kafka |
| **notification-service** | 8085 | Java/MVC | — (stateless) | Kafka consumer |
| **config-server** | 8888 | Java | Git (`Ecommerce-Config-Training`) | All services |
| **discovery-server** | 8761 | Java | In-memory | All services |

---

## 3. Infrastructure Services

### 3.1 Config Server
- **Purpose:** Centralised externalised configuration (Spring Cloud Config Server).
- **Source:** Classpath-based configuration repository (no Git backend in this lab).
- **All services** import config on startup: `optional:configserver:http://localhost:8888`.
- **Fallback:** Each service's local `application.yaml` has `${ENV_VAR:default_value}` fallbacks so they work standalone.

### 3.2 Eureka Discovery Server
- **Purpose:** Service registry. All microservices register themselves and discover peers by name.
- **Used by:** API Gateway for client-side load balancing (`lb://SERVICE-NAME`); Order service for Feign client resolution.
- **Dashboard:** `http://localhost:8761`

### 3.3 PostgreSQL
- **Used by:** Product Service (product catalog + persistent storage).
- **Database:** `microservices_pro`
- **Credentials (dev):** `postgres / postgres`
- **Schema:** Managed by Hibernate `ddl-auto: update`.

### 3.4 Redis
- **Used by:**
  - **Product Service** — Spring Cache `@Cacheable` / `@CacheEvict` on product data.
  - **API Gateway** — Spring Cloud Gateway `RequestRateLimiter` (token bucket, 10 req/s, burst 20).
- **Port:** 6379

### 3.5 Apache Kafka (KRaft mode)
- **Version:** Confluent Platform 7.6.1 (KRaft — no ZooKeeper).
- **Listeners:** `kafka:9092` (internal), `localhost:29092` (host access).
- See [Section 9](#9-kafka-topics--event-flows) for full topic catalogue.

---

## 4. Business Microservices — Deep Dive

### 4.1 Product Service

**Location:** `product/`  
**Port:** 8081  
**Base path:** `/api/v1/products` (internal) — accessed via gateway at `/api/products`

#### Key Classes

| Class | Responsibility |
|---|---|
| `ProductController` | REST endpoints; applies `@Valid` on requests |
| `ProductService` | Business logic; `@Cacheable` / `@CacheEvict` |
| `ProductRepository` | JPA repository + `findByNameContainingIgnoreCase` |
| `CreateProductRequest` | **Command DTO** — write-side with `@NotBlank`, `@NotNull`, `@DecimalMin("0.01")` |
| `ProductResponse` | **Query DTO** — read-side; includes derived `inStock` boolean field |
| `Product` | JPA entity (`@Entity`) mapped to `products` table |

#### CQRS Implementation
The product service implements a **CQRS-lite** pattern:
- **Write side:** `CreateProductRequest` — validates price/name on input; maps to `Product` entity.
- **Read side:** `ProductResponse` record — adds `inStock` (derived: `price > 0`), decouples the API from the entity shape.
- **Cache:** Read queries are cached; any write (`create`, `update`, `delete`) evicts `allEntries`.

#### Endpoints
```
GET    /api/products                   → list all (public, no token)
GET    /api/products/{id}              → get by id (public)
GET    /api/products/search?name=xxx   → search by name (public)
POST   /api/products                   → create (ROLE_ADMIN required)
PUT    /api/products/{id}              → update (ROLE_ADMIN required)
DELETE /api/products/{id}              → delete (ROLE_ADMIN required)
```

#### Validation Rules (`CreateProductRequest`)
| Field | Constraint |
|---|---|
| `name` | `@NotBlank` — must not be empty or whitespace |
| `price` | `@NotNull` + `@DecimalMin("0.01")` — must be > 0 |

---

### 4.2 Order Service

**Location:** `order-service/`  
**Port:** 8082  
**Base path:** `/api/orders`

#### Key Classes

| Class | Responsibility |
|---|---|
| `OrderController` | `POST /api/orders` — place order; `GET /{id}/status` |
| `OrderService` | Business logic; `@Transactional` order & outbox creation; Resilience4j |
| `InventoryClient` | Feign client → Inventory Service stock check |
| `PaymentClient` | Feign client → Payment Service |
| `FeignJwtInterceptor` | Propagates `Authorization` + `X-User-Id` + `X-User-Role` to all Feign calls |
| `InventoryErrorDecoder` | Maps HTTP error codes to domain exceptions |
| `OrderSagaOrchestrator` | Orchestrator Saga: drives the state machine via Kafka |
| `OrderRepository` | JPA repository for `Order` entity |
| `OutboxEvent` | JPA entity representing domain events in `outbox_events` table |
| `OutboxEventRepository` | Native query repository for pending events oldest-first |
| `OutboxEventRelay` | `@Scheduled` background worker relaying pending events to Kafka |

#### Order Placement Flow (Saga & Outbox)
1. Client `POST /api/orders` → `OrderService.createOrder()`
2. Sync stock pre-check via `InventoryClient.checkStock()`
3. **Atomic DB Transaction (`@Transactional`):**
   - Save `Order` entity as `PENDING` to `orders` table.
   - Save `OutboxEvent` containing `OrderPlacedEvent` JSON to `outbox_events` table.
   - *(Eliminates the dual-write problem: DB save and event persistence commit or rollback together)*
4. `OrderService` immediately returns `200 OK` with order status `PENDING`.
5. **Outbox Relay (Asynchronous):**
   - `OutboxEventRelay` polls `findPendingEvents(100)` every 5s.
   - Publishes payload to Kafka `order-events` topic.
   - On ack: marks event `PUBLISHED` with `publishedAt` timestamp.
   - On error: increments `retryCount` and logs failure; retries next tick.
6. `OrderSagaOrchestrator` receives event → sends `ReserveInventoryCommand` to `saga-commands`.
7. Inventory reserves stock → publishes `InventoryResultEvent` to `saga-results`.
8. Orchestrator receives success → sends `ProcessPaymentCommand`.
9. Payment processes → publishes `PaymentResultEvent`.
10. Orchestrator updates order to `CONFIRMED` or triggers compensation.

#### Resilience4j Configuration
```yaml
circuitbreaker.paymentService:
  slidingWindowSize: 10, failureRateThreshold: 50%
  waitDurationInOpenState: 5s

retry.paymentService:
  maxAttempts: 3, waitDuration: 500ms, exponential backoff x2

bulkhead.paymentService:
  maxConcurrentCalls: 10

timelimiter.paymentService:
  timeoutDuration: 3s
```
**Aspect ordering:** Bulkhead(1) → TimeLimiter(2) → CircuitBreaker(3) → Retry(4)

---

### 4.3 Payment Service

**Location:** `payment-service/`  
**Port:** 8083  

#### Dual Mode Operation

| Mode | Trigger | Description |
|---|---|---|
| **REST** | `POST /api/payments` | Synchronous; called by Order Service via Feign |
| **Saga Command Handler** | Kafka `saga-commands` topic | Asynchronous; called by Orchestrator Saga |

#### Configurable Failure Simulation
```yaml
payment:
  failure-rate: 0.5    # 50% chance of failure (for resilience testing)
  delay-ms: 0          # Simulated processing delay in ms
```
Change in `application.yaml` or via Config Server to test circuit breaker / timeout scenarios.

#### Saga Integration
`PaymentSagaCommandHandler` listens on `saga-commands` for `ProcessPaymentCommand`:
- On success → publishes `PaymentResultEvent{success: true}` to `saga-results`
- On failure → publishes `PaymentResultEvent{success: false}` → triggers compensation (inventory release)

---

### 4.4 Inventory Service

**Location:** `inventory-service/`  
**Port:** 8084  
**Base path:** `/api/v1/inventory`

#### Stock Data (In-Memory)
Pre-seeded at startup (no database):
```
PROD-001 → 100 units available
PROD-002 →   5 units available
PROD-003 →   0 units (out of stock)
```

#### Key Classes

| Class | Responsibility |
|---|---|
| `InventoryController` | `GET /api/v1/inventory/check?productId=&quantity=` |
| `InventoryService` | Stock check, reserve, release operations |
| `InventorySagaCommandHandler` | Listens on `saga-commands` for `ReserveInventoryCommand` / `ReleaseInventoryCommand` |
| `InventorySagaHandler` | Choreography saga listener for `order-events` |
| `InventorySecurityConfig` | OAuth2 Resource Server — validates JWT from Order service |

#### Exception Mapping
| Condition | Exception | HTTP Status |
|---|---|---|
| Product ID not found | `ProductNotFoundException` | 404 |
| Not enough stock | `InsufficientStockException` | 409 |
| Service down | `InventoryUnavailableException` | 503 |

---

### 4.5 Notification Service

**Location:** `notification-service/`  
**Port:** 8085  

Purely event-driven — no REST endpoints. Consumes Kafka events and logs simulated notifications.

#### Event Handling
```
Listens on: payment-events, saga-results
Group ID:   notification-service
```

| Event payload contains | Action |
|---|---|
| `PaymentCompleted` or `"success":true` | Sends order confirmation notification |
| `PaymentFailed` or `"success":false` | Sends payment failure alert |

#### Retry + Dead Letter Topic
```java
@RetryableTopic(
    attempts = "3",
    backoff = @Backoff(delay = 1000, multiplier = 2.0)
)
```
- Retry topics: `payment-events-retry-0`, `payment-events-retry-1`
- Dead Letter: `payment-events-dlt` — logged with `@DltHandler` for manual inspection

---

### 4.6 API Gateway

**Location:** `api-gateway/`  
**Port:** 8080  
**Framework:** Spring WebFlux + Spring Cloud Gateway

#### Route Configuration

| Route ID | Path | Backend | Special Filters |
|---|---|---|---|
| `product-service` | `/api/products/**` | `lb://PRODUCT-SERVICE` | RewritePath, RateLimiter |
| `order-service` | `/api/orders/**` | `lb://ORDER-SERVICE` | — |
| `order-service-admin` | `/api/orders/admin/**` | `lb://ORDER-SERVICE` | ROLE_ADMIN enforced |
| `inventory-service` | `/api/v1/inventory/**` | `lb://INVENTORY-SERVICE` | — |

**Path rewrite:** `/api/products/**` → `/api/v1/products/**`

#### Rate Limiting (Redis Token Bucket)
```yaml
redis-rate-limiter:
  replenishRate: 10   # tokens/second
  burstCapacity: 20   # max burst
  requestedTokens: 1  # cost per request
  key-resolver: ipKeyResolver  # keyed per client IP
```

---

## 5. Inter-Service Communication

### 5.1 Synchronous (OpenFeign)

```
Order Service ──Feign──► Inventory Service  (stock check)
Order Service ──Feign──► Payment Service    (payment processing)
```

**Header propagation** (via `FeignJwtInterceptor`):
```
Authorization: Bearer <jwt>
X-User-Id:     <jwt sub claim>
X-User-Role:   <comma-separated roles>
```

### 5.2 Asynchronous (Apache Kafka)

See [Section 9](#9-kafka-topics--event-flows) for full topic documentation.

---

## 6. Security Model (Session 20)

### Architecture

```
Client ──JWT──► API Gateway ──(validated)──► Downstream Services
                     │
                     ├── Validates JWT with HS256 HMAC secret
                     ├── Extracts sub → X-User-Id header
                     ├── Extracts roles → X-User-Role header
                     └── Enforces route-level RBAC
```

### JWT Configuration
- **Algorithm:** HS256 (symmetric HMAC-SHA256)
- **Secret:** `${JWT_SECRET:microservices-pro-course-secret-key-2024-minimum-256-bits}`
- **Token format:** `{"sub":"user1","roles":["ROLE_ADMIN"],"iat":...,"exp":...}`
- **Implementation:** `NimbusReactiveJwtDecoder` in `SecurityConfig.java` (gateway)

> **Why HS256?** This lab has no external IdP (Keycloak/Auth0). In production, RS256 with a JWKS endpoint is the standard.

### Access Control Rules

| Path | Method | Required |
|---|---|---|
| `/api/products/**` | GET | None — public |
| `/actuator/health`, `/actuator/info` | ANY | None — public |
| `/api/orders/admin/**` | ANY | `ROLE_ADMIN` |
| `/api/products/**` | POST / PUT / DELETE | `ROLE_ADMIN` |
| All other routes | ANY | Any valid JWT |

### Inter-Service Auth (Order → Inventory)
The `FeignJwtInterceptor` in Order Service propagates the original JWT to Inventory. The Inventory Service validates it using its own `NimbusJwtDecoder` (same HS256 secret).

### Key Files
| File | Role |
|---|---|
| [`api-gateway/SecurityConfig.java`](file:///d:/Microservices Training/Ecommerce Project/api-gateway/src/main/java/com/microservices/pro/api_gateway/configs/SecurityConfig.java) | JWT decoder, route rules, header injection |
| [`api-gateway/JwtTokenUtil.java`](file:///d:/Microservices Training/Ecommerce Project/api-gateway/src/main/java/com/microservices/pro/api_gateway/configs/JwtTokenUtil.java) | Test token generator (HS256) |
| [`order-service/FeignJwtInterceptor.java`](file:///d:/Microservices Training/Ecommerce Project/order-service/src/main/java/com/microservice/pro/order_service/config/FeignJwtInterceptor.java) | JWT + header propagation to downstream |
| [`inventory-service/InventorySecurityConfig.java`](file:///d:/Microservices Training/Ecommerce Project/inventory-service/src/main/java/com/microservice/pro/inventory_service/config/InventorySecurityConfig.java) | Validates propagated JWT |

---

## 7. Resilience Patterns

### 7.1 Resilience4j (Application Layer — Order Service)

| Pattern | Annotation | Config Key | Behaviour |
|---|---|---|---|
| Circuit Breaker | `@CircuitBreaker` | `paymentService` | Opens after 50% failures in 10-call window |
| Retry | `@Retry` | `paymentService` | 3 attempts, exponential backoff 500ms×2 |
| Bulkhead | `@Bulkhead(SEMAPHORE)` | `paymentService` | Max 10 concurrent calls |
| Time Limiter | `@TimeLimiter` | `paymentService` | 3s timeout per call |

**Fallback chain:** `timeLimiterFallback` → `paymentFallback` → `bulkheadFallback`

### 7.2 Feign Error Decoder (Order Service)

`InventoryErrorDecoder` translates HTTP error codes from Inventory service into typed exceptions, preventing raw `FeignException` from propagating to business logic.

### 7.3 Istio Outlier Detection (Mesh Layer — Session 21)

Operates at the Envoy proxy layer — **language-agnostic**, no code changes needed.

| Setting | Stable | Canary |
|---|---|---|
| `consecutiveGatewayErrors` | 5 | 3 |
| `interval` | 30s | 10s |
| `baseEjectionTime` | 30s | 60s |
| `maxEjectionPercent` | 50% | 100% |

### 7.4 Kafka Retry (Notification Service)

`@RetryableTopic` — 3 attempts with exponential backoff (1s, 2s). Exhausted messages go to Dead Letter Topic.

---

## 8. Observability Stack

### 8.1 Distributed Tracing — Zipkin

- **Library:** Micrometer Tracing + Brave bridge
- **Sampling:** 100% (`probability: 1.0`) — all requests traced
- **Endpoint:** `http://localhost:9411`
- **Services traced:** Gateway, Product, Order

### 8.2 Metrics — Prometheus + Grafana

| Component | URL | Purpose |
|---|---|---|
| Prometheus | `http://localhost:9090` | Scrapes `/actuator/prometheus` every 5s |
| Grafana | `http://localhost:3000` | Dashboards (admin/admin) |

**Scraped services:**
```yaml
- order-service:8082/actuator/prometheus
- product-service:8081/actuator/prometheus
```

**Custom metric (Order Service):**
```java
Counter.builder("orders.created")
    .description("Total number of orders created successfully")
    .register(meterRegistry);
```

### 8.3 Structured Logging — Logback + Logstash

Every service has `logback-spring.xml` configured for JSON output via `logstash-logback-encoder`, enabling log aggregation with ELK/Loki.

### 8.4 Health Probes

All services expose:
```
GET /actuator/health          → aggregate health
GET /actuator/health/liveness → K8s liveness probe
GET /actuator/health/readiness→ K8s readiness probe
```

---

## 9. Kafka Topics & Event Flows

### Topic Catalogue

| Topic | Producer | Consumer(s) | Message Type | Purpose |
|---|---|---|---|---|
| `order-events` | Order Service | Inventory (choreography) | `OrderPlacedEvent` | Triggers inventory saga step |
| `payment-events` | Payment Service | Notification Service | `PaymentCompletedEvent` / `PaymentFailedEvent` | Customer notification trigger |
| `saga-commands` | Order Orchestrator, Inventory | Payment, Inventory | `ReserveInventoryCommand`, `ProcessPaymentCommand`, `ReleaseInventoryCommand` | Orchestrator drives saga steps |
| `saga-results` | Inventory, Payment | Order Orchestrator, Notification | `InventoryResultEvent`, `PaymentResultEvent`, `InventoryReleasedResultEvent` | Saga outcomes reported back |
| `payment-events-retry-0` | Spring Kafka (auto) | Notification | (retry) | First retry attempt |
| `payment-events-retry-1` | Spring Kafka (auto) | Notification | (retry) | Second retry attempt |
| `payment-events-dlt` | Spring Kafka (auto) | Notification `@DltHandler` | (dead letter) | Manual inspection queue |

### Kafka Configuration
- **Serialization:** Producer → `JsonSerializer`; Consumer → `StringDeserializer`
- **Mode:** KRaft (no ZooKeeper)
- **Replication:** Single broker (dev); set to 3 for production

---

## 10. Saga Patterns

The project implements **both** Choreography and Orchestration sagas side-by-side.

### 10.1 Choreography Saga (Event-driven)

```
Order Service ──OrderPlacedEvent──► Inventory Service
                                         │
                            InventoryReservedEvent / Failed
                                         │
                    Payment Service ◄────┘ (listens on order-events)
                          │
               PaymentCompletedEvent / PaymentFailedEvent
                          │
              ◄────────── Notification Service (final step)
```

**Compensation:** On `PaymentFailedEvent`, Inventory Service listens and reverses the reservation.

### 10.2 Orchestrator Saga

```
OrderSagaOrchestrator
    │
    ├─ startSaga() → publishes ReserveInventoryCommand
    │
    ├─ handleInventoryResult():
    │    INVENTORY_RESERVING → success → ProcessPaymentCommand
    │    INVENTORY_RESERVING → fail   → CANCELLED, update DB
    │
    ├─ handlePaymentResult():
    │    PAYMENT_PROCESSING → success → COMPLETED, update DB
    │    PAYMENT_PROCESSING → fail   → ReleaseInventoryCommand (compensate)
    │
    └─ handleInventoryReleased():
         COMPENSATING → CANCELLED, update DB
```

**Saga States:** `STARTED → INVENTORY_RESERVING → INVENTORY_RESERVED → PAYMENT_PROCESSING → COMPLETED | INVENTORY_FAILED | PAYMENT_FAILED | COMPENSATING | CANCELLED`

---

## 11. CQRS Implementation (Product Service)

### Command Side (Write)
```java
// Input DTO — validated at controller boundary
public record CreateProductRequest(
    @NotBlank String name,
    @NotNull @DecimalMin("0.01") BigDecimal price
) {}
```

### Query Side (Read)
```java
// Output DTO — adds derived display field, decoupled from entity
public record ProductResponse(
    Long id,
    String name,
    BigDecimal price,
    boolean inStock      // derived: price != null && price > 0
) {}
```

### Justified vs Unjustified Use (from CQRS_CAPSTONE.md)

| Case | Justified? | Reason |
|---|---|---|
| `ProductResponse` with `inStock` field | ✅ Yes | Different shape from entity; cache-independent; derived field only useful for read clients |
| `deleteProduct()` returning void | ❌ No | No output model needed; no validation shape difference; single-line repository call |

---

## 12. Kubernetes Deployment (Enterprise Platform)

**Namespace:** `ecommerce` (with `istio-injection=enabled`)

### Unified Kustomize Deployment
The entire platform is organized under `k8s/` and can be deployed with a single command:
```bash
kubectl apply -k k8s/
```

### Complete Platform Manifest Catalogue

| Directory / File | Kind | Purpose |
|---|---|---|
| [`k8s/kustomization.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/kustomization.yaml) | Kustomization | Root manifest bundling all resources, configs, and mesh policies |
| [`k8s/platform-config.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/platform-config.yaml) | ConfigMap | Shared platform config: Eureka, PostgreSQL, Kafka, Redis, Zipkin |
| [`k8s/platform-secrets.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/platform-secrets.yaml) | Secret | Shared HMAC-256 JWT secret and PostgreSQL database credentials |
| [`k8s/infrastructure/postgres.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/infrastructure/postgres.yaml) | StatefulSet + Service | PostgreSQL 16 ACID database with 5Gi PersistentVolumeClaim |
| [`k8s/infrastructure/redis.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/infrastructure/redis.yaml) | Deployment + Service | Redis 7 in-memory cache and token-bucket rate limiter store |
| [`k8s/infrastructure/kafka-kraft.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/infrastructure/kafka-kraft.yaml) | StatefulSet + Service | Apache Kafka KRaft broker (no ZooKeeper) with 10Gi PersistentVolumeClaim |
| [`k8s/api-gateway/`](file:///d:/Microservices Training/Ecommerce Project/k8s/api-gateway/) | Deployment, Service, HPA | Edge Ingress router (Port 8080) with auto-scaling (2–10 replicas) |
| [`k8s/product-service/`](file:///d:/Microservices Training/Ecommerce Project/k8s/product-service/) | Deployment, Service, HPA | Product catalog with canary deployment and auto-scaling (2–10 replicas) |
| [`k8s/order-service/`](file:///d:/Microservices Training/Ecommerce Project/k8s/order-service/) | Deployment, Service, HPA | Order orchestrator and Outbox publisher with auto-scaling (2–10 replicas) |
| [`k8s/inventory-service/`](file:///d:/Microservices Training/Ecommerce Project/k8s/inventory-service/) | Deployment, Service, HPA | Inventory stock management with auto-scaling (2–8 replicas) |
| [`k8s/payment-service/`](file:///d:/Microservices Training/Ecommerce Project/k8s/payment-service/) | Deployment, Service, HPA | Payment processor with auto-scaling (2–8 replicas) |
| [`k8s/notification-service/`](file:///d:/Microservices Training/Ecommerce Project/k8s/notification-service/) | Deployment, Service | Asynchronous Kafka event consumer worker |

### Universal Production Hardening
Every business microservice container adheres to standard cloud-native standards:
- **Zero-Downtime Rolling Updates:** `maxSurge: 25%`, `maxUnavailable: 0`
- **Sidecar Injection:** Automated Envoy injection via `sidecar.istio.io/inject: "true"`
- **Probes:** `readinessProbe` (/actuator/health/readiness), `livenessProbe` (/actuator/health/liveness), `startupProbe` (5s interval, 12 retries for slow JVM startup)
- **Auto-Scaling (HPA):** Scales up at 70% average CPU utilization

---

## 13. Helm Charts

The platform provides two Helm solutions: an **Enterprise Umbrella Chart** for the whole platform, and dedicated charts for individual services.

### 13.1 Enterprise Umbrella Chart (`helm/ecommerce-platform/`)
Deploy, upgrade, or rollback the complete multi-service stack with a single release:

```bash
# Production installation (Multi-replica, HPA enabled, strict quotas):
helm install ecommerce ./helm/ecommerce-platform -f ./helm/ecommerce-platform/values-prod.yaml -n ecommerce --create-namespace

# Development installation (Single-replica, lightweight resource requests):
helm install ecommerce ./helm/ecommerce-platform -f ./helm/ecommerce-platform/values-dev.yaml -n ecommerce --create-namespace
```

#### Package Structure
- `templates/_helpers.tpl`: Standardized naming, labels (`app.kubernetes.io/part-of`), and selectors.
- `templates/configmap.yaml` & `secret.yaml`: Centralized environment and security injection.
- `templates/infrastructure/`: Templates for PostgreSQL, Redis, and Kafka KRaft with PVCs.
- `templates/microservices/`: Parameterized templates dynamically looping over `.Values.services`.
- `templates/istio/`: Ingress Gateway, VirtualServices, and strict mTLS rules.

### 13.2 Standalone Service Chart (`helm/product-service-chart/`)
Dedicated chart for CI/CD pipelines deploying `product-service` independently.
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
```

### Environments

| File | Use |
|---|---|
| `values.yaml` | Default / staging |
| `values-prod.yaml` | Production overrides |

---

## 14. Istio Service Mesh (Session 21)

### Resources

| File | Kind | Purpose |
|---|---|---|
| [`namespace.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/istio/namespace.yaml) | Namespace | `istio-injection: enabled` label → auto sidecar |
| [`peer-authentication.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/istio/peer-authentication.yaml) | PeerAuthentication | STRICT mTLS for all pods |
| [`product-service-dr.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/istio/product-service-dr.yaml) | DestinationRule | stable/canary subsets + outlierDetection |
| [`product-service-vs.yaml`](file:///d:/Microservices Training/Ecommerce Project/k8s/istio/product-service-vs.yaml) | VirtualService | 80/20 weighted traffic split |

### Traffic Split Architecture

```
Every incoming request
        │
        ▼  Envoy sidecar (software load balancer)
    80% │──────────────────► product-service  (track=stable, ×8 pods)
        │
    20% └──────────────────► product-service-canary (track=canary, ×2 pods)
```

**Why traffic-based over replica-based:** With `weight: 80/20` in Istio you can run even 1 canary pod and still get exactly 20% of traffic. Replica-count splits are approximate and tied to scaling decisions.

### mTLS Chain
```
Gateway (Envoy) ──SPIFFE cert──► Product (Envoy) ──SPIFFE cert──► Inventory (Envoy)
```
All certificates are short-lived, auto-rotated by Istiod.

### Outlier Detection (circuit breaking at mesh level)
| Behaviour | Stable pods | Canary pods |
|---|---|---|
| Eject after N consecutive 5xx | 5 | 3 (stricter) |
| Scan interval | 30s | 10s (faster) |
| Cooling period | 30s | 60s (longer) |
| Max % ejectable | 50% | 100% (fully removable) |

---

## 15. GitOps with ArgoCD

| ArgoCD App | Watches | Applied Namespace |
|---|---|---|
| `product-service` | `k8s/product-service/` | `ecommerce` |
| `product-service-istio` | `k8s/istio/` | `ecommerce` |

Both apps are configured with `automated.selfHeal: true` and `automated.prune: true` — any drift from the Git state is auto-corrected.

**Changing canary traffic weight without `kubectl`:**
1. Edit `weight` in `k8s/istio/product-service-vs.yaml`
2. Commit and push to the target branch
3. ArgoCD detects the change within 3 minutes and applies it

---

## 16. Testing Strategy

### Test Types

| Type | Tool | Location | Coverage |
|---|---|---|---|
| Unit | JUnit 5 + Mockito | `*/src/test/` | Service logic, fallbacks |
| Parameterized | JUnit 5 `@ParameterizedTest` | `ProductServiceParameterizedTest` | Price/name edge cases |
| Controller (slice) | `@WebMvcTest` + MockMvc | `ProductControllerTest` | HTTP status, validation |
| Integration | `@SpringBootTest` | `ProductServiceIntegrationTest` | create→query→update→cache eviction |
| Outbox Relay | JUnit 5 + Mockito | `OutboxEventRelayTest` | Success, retry count, empty queue |
| Contract (consumer) | Pact JVM | `OrderServiceInventoryContractTest` | Order→Inventory API contract |
| Contract (provider) | Pact JVM | Inventory test | Verifies pact |
| WireMock | `spring-cloud-contract-wiremock` | `WireMockInventoryTest` | Feign client isolation |
| Saga unit | JUnit 5 | `OrderSagaOrchestratorTest` | State machine transitions |
| Stress & Bottleneck | k6 | `k6/checkout-stress-test.js` | Bulkhead, CB, timeouts under load |

### Validation Testing (Task 39)
Tests in `ProductControllerTest` verify all invalid-price scenarios return **400 Bad Request**:
- Negative price (`-5.00`) — `@DecimalMin` rejects
- Zero price (`0`) — `@DecimalMin` rejects
- Null price — `@NotNull` rejects
- Empty name — `@NotBlank` rejects

### JVM Configuration (Java 26 compatibility)
```xml
<argLine>-XX:+EnableDynamicAgentLoading -Dnet.bytebuddy.experimental=true</argLine>
```
Required because Byte Buddy (used by Mockito inline mock maker) does not officially support Java 26.

---

## 17. Local Development

### Start Full Stack (Docker Compose)

```bash
# From project root
docker compose up -d

# Startup order (enforced by healthcheck depends_on):
# postgres, redis, kafka → config-server → discovery-server
# → gateway → product-service, order-service, payment-service,
#            inventory-service, notification-service
# → zipkin, prometheus, grafana (independent)
```

### Service URLs (Local)

| Service | URL |
|---|---|
| API Gateway | http://localhost:8080 |
| Product Service (direct) | http://localhost:8081 |
| Order Service (direct) | http://localhost:8082 |
| Payment Service (direct) | http://localhost:8083 |
| Inventory Service (direct) | http://localhost:8084 |
| Notification Service | http://localhost:8085 |
| Config Server | http://localhost:8888 |
| Eureka Dashboard | http://localhost:8761 |
| Zipkin | http://localhost:9411 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

### Run Individual Services (Maven)

```bash
cd product
.\mvnw.cmd spring-boot:run

cd order-service
.\mvnw.cmd spring-boot:run
```

---

## 18. Environment Variables Reference

| Variable | Default | Used By |
|---|---|---|
| `JWT_SECRET` | `microservices-pro-course-secret-key-2024-minimum-256-bits` | Gateway, Inventory |
| `SPRING_CONFIG_IMPORT` | `optional:configserver:http://localhost:8888` | All services |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | `http://localhost:8761/eureka/` | All services |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Order, Payment, Inventory, Notification |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Gateway, Product |
| `SPRING_DATA_REDIS_PORT` | `6379` | Gateway, Product |
| `SPRING_DATASOURCE_URL` | `jdbc:h2:mem:orderdb` | Order (H2 dev) |
| `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | `http://localhost:9411/api/v2/spans` | All services |
| `payment.failure-rate` | `0.5` | Payment (chaos engineering) |
| `payment.delay-ms` | `0` | Payment (latency simulation) |

---

## 19. API Reference

### Through API Gateway (`localhost:8080`)

#### Products (Public reads, Admin writes)

```http
GET  /api/products
GET  /api/products/{id}
GET  /api/products/search?name=laptop
POST /api/products                          Authorization: Bearer <ADMIN_TOKEN>
PUT  /api/products/{id}                     Authorization: Bearer <ADMIN_TOKEN>
DELETE /api/products/{id}                   Authorization: Bearer <ADMIN_TOKEN>
```

**POST /api/products — Request body:**
```json
{
  "name": "Gaming Laptop",
  "price": 1299.99
}
```

**GET /api/products — Response:**
```json
[{
  "id": 1,
  "name": "Gaming Laptop",
  "price": 1299.99,
  "inStock": true
}]
```

#### Orders (Authenticated)

```http
POST /api/orders                            Authorization: Bearer <USER_TOKEN>
GET  /api/orders/{orderId}/status           Authorization: Bearer <USER_TOKEN>
```

**POST /api/orders — Request body:**
```json
{
  "productId": "PROD-001",
  "quantity": 2,
  "amount": 299.99
}
```

**Response:**
```json
{
  "orderId": "uuid-...",
  "status": "PENDING",
  "message": "Order placed successfully. Processing payment..."
}
```

#### Inventory (Authenticated — internal use)

```http
GET /api/v1/inventory/check?productId=PROD-001&quantity=5
```

---

---

## 20. Transactional Outbox Pattern & Distributed Tracing (Session 22)

### 20.1 The Dual-Write Problem
In microservices architectures, writing to a database and publishing an event to a message broker within the same business operation is a classic **dual-write hazard**:
```java
// VULNERABLE PATTERN:
orderRepository.save(order);             // Step 1: Database transaction commits
kafkaTemplate.send("order-events", evt); // Step 2: Network call to Kafka
```
If Kafka is temporarily unreachable, network partitions occur, or the pod crashes between Step 1 and Step 2, the order is persisted as `PENDING` in the database, but the event is never published. As a consequence, the Saga orchestrator never triggers, inventory is never reserved, payment is never processed, and the customer's order remains permanently hung without failure alerts.

### 20.2 Outbox Architecture & Implementation
To guarantee **at-least-once message delivery** without relying on complex and slow Two-Phase Commit (2PC / XA) protocols, the Transactional Outbox pattern was implemented:

```
┌─────────────────────────── Order Service ───────────────────────────┐
│                                                                     │
│  Client POST /api/orders                                            │
│        │                                                            │
│        ▼                                                            │
│  OrderService.createOrder()                                         │
│        │                                                            │
│        ├───► [BEGIN @Transactional]                                 │
│        │     1. Save Order (status: PENDING) ──► [orders table]     │
│        │     2. Save OutboxEvent (PENDING)   ──► [outbox_events]    │
│        │     [COMMIT TRANSACTION]                                   │
│        │                                                            │
│        ▼                                                            │
│  Return 200 OK to Client                                            │
│                                                                     │
│  OutboxEventRelay (@Scheduled every 5s)                             │
│        │                                                            │
│        ├───► Poll findPendingEvents(limit = 100)                     │
│        ├───► Inject B3 & W3C Tracing Headers                        │
│        ├───► kafkaTemplate.send(producerRecord)      ──► Kafka      │
│        ├───► If SUCCESS: markPublished()                            │
│        └───► If FAILURE: increment retryCount (retried next tick)   │
└─────────────────────────────────────────────────────────────────────┘
```

#### Core Components
- **`OutboxEvent`**: JPA entity mapped to `outbox_events` with fields:
  - `id` (Long, auto-increment primary key)
  - `topic` (`order-events`)
  - `messageKey` (`orderId`)
  - `payload` (JSON serialization of `OrderPlacedEvent`)
  - `eventType` (`OrderPlacedEvent`)
  - `status` (`PENDING` / `PUBLISHED`)
  - `retryCount` (tracks delivery failure attempts for alerting)
  - `traceId` & `spanId` (distributed tracing context)
  - `createdAt` & `publishedAt` (audit timestamps)
- **`OutboxEventRepository`**: Spring Data JPA repository with native query:
  ```sql
  SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit
  ```
- **`OutboxEventRelay`**: Background worker executed every 5,000ms:
  - Decorated with `@Scheduled(fixedDelay = 5000)`.
  - Relays pending records to Kafka KRaft.
  - Constructs `ProducerRecord<String, String>` and populates tracing headers.
  - Handles transient network drops by catching exceptions and incrementing `retryCount` rather than crashing the relay.

### 20.3 Verification & Unit Test Results
Four dedicated unit tests in `OutboxEventRelayTest` validate the relay behavior:
1. `relay_whenKafkaAvailable_publishesEventAndMarksPublished` — Confirms successful Kafka dispatch and state transition to `PUBLISHED`.
2. `relay_whenKafkaUnavailable_keepsEventPendingAndIncrementsRetry` — Verifies resilience during broker downtime (status remains `PENDING`, `retryCount` increments).
3. `relay_whenNoPendingEvents_doesNothing` — Verifies no redundant Kafka calls when outbox queue is clear.
4. `relay_whenTraceContextPresent_injectsTracingHeadersIntoKafkaRecord` — Verifies `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled: 1`, and W3C `traceparent` headers are properly injected into the Kafka `ProducerRecord`.

### 20.4 Distributed Tracing Context Propagation Across Outbox
#### The Asynchronous Context Loss Problem
In synchronous HTTP processing, OpenTelemetry and Micrometer automatically carry the `traceId` and `spanId` via thread-local state. However, in the Transactional Outbox pattern:
1. The client request thread saves the event to the database and terminates.
2. The asynchronous `@Scheduled` thread in `OutboxEventRelay` awakens later on a separate worker thread that possesses no active span context.
3. If dispatched without tracing metadata, downstream consumers (`PaymentService`, `InventoryService`, `NotificationService`) generate **brand-new trace IDs**, breaking the Zipkin distributed trace graph into disconnected fragments.

#### In-Band Trace Header Propagation Solution
1. **Context Capture on Ingress:** `OrderService.createOrder()` accesses the active Micrometer `Tracer` span:
   ```java
   String traceId = null;
   String spanId = null;
   if (tracer != null && tracer.currentSpan() != null) {
       traceId = tracer.currentSpan().context().traceId();
       spanId = tracer.currentSpan().context().spanId();
   }
   ```
2. **Database Persistence:** Trace identifiers are stored directly on the `OutboxEvent` record in `outbox_events` atomically alongside the order.
3. **Kafka Header Injection:** When `OutboxEventRelay` polls and constructs the `ProducerRecord`, it embeds both standard Zipkin B3 headers and W3C trace context headers:
   - `X-B3-TraceId`: Hex string trace ID
   - `X-B3-SpanId`: Hex string span ID
   - `X-B3-Sampled`: `"1"`
   - `traceparent`: `00-${traceId}-${spanId}-01`
4. **End-to-End Lineage:** Downstream consumers seamlessly extract these headers, rendering an unbroken distributed trace across the entire microservice ecosystem in Zipkin.

---

## 21. Performance & Stress Testing — k6 (Session 23)

### 21.1 Overview & Architecture
To validate the system's runtime resilience and identify bottlenecks under concurrent user traffic, an end-to-end stress test was built using Grafana k6:
- **Test Script:** `k6/checkout-stress-test.js`
- **Target Route:** Client → `API Gateway (8080)` → `Order Service (8082)` → `Inventory (8084)` + `Payment (8083)`

### 21.2 Test Stages & Stress Profiles
The test executes 4 staged load phases across 15 Virtual Users (VUs):

| Stage | Name | Target VUs | Duration | Target Resilience Behavior |
|---|---|---|---|---|
| **1** | Baseline | 5 VUs | 60s | Normal healthy operations, p95 < 400ms |
| **2** | Bulkhead Stress | 12 → 15 VUs | 90s | Concurrency exceeds `maxConcurrentCalls: 10`, triggering `BulkheadFullException` |
| **3** | Circuit Breaker Stress | 10 VUs | 60s | 50% simulated payment failures cross the `failureRateThreshold: 50%`, opening Circuit Breaker |
| **4** | Recovery & Cooldown | 2 VUs | 45s | Validates automatic self-healing (Circuit Breaker HALF_OPEN → CLOSED) |

### 21.3 Custom Resilience4j Metrics Collected
The k6 test defines custom Trend and Counter metrics to inspect backend resilience activations directly from test output:
- `circuit_breaker_trips`: Increments when gateway/order returns `503 Service Unavailable` with circuit open payload.
- `bulkhead_rejections`: Increments on concurrency limit rejections.
- `timelimiter_timeouts`: Increments when request exceeds 3,000ms SLA.
- `resilience4j_fallbacks`: Tracks the percentage of traffic gracefully handled by fallback methods.
- `checkout_duration_ms`: End-to-end latency percentile tracking (p50, p90, p95).

### 21.4 Key Finding: Theoretical vs Actual Aspect Order
A critical architectural observation emerged from comparing the theoretical aspect configuration with actual runtime behavior:

```
Theoretical Aspect Stacking (Session 5):
  Bulkhead (Outer, 1) ──► TimeLimiter (2) ──► CircuitBreaker (3) ──► Retry (Inner, 4)

Actual Activation Order Under Stress (Session 23):
  1. Retry fires FIRST on failed downstream calls, injecting retry backoffs (500ms -> 1000ms).
  2. TimeLimiter trips SECOND when cumulative retries exceed 3,000ms.
  3. Bulkhead trips THIRD as delayed, retrying requests consume concurrent execution slots.
  4. Circuit Breaker trips LAST because it requires an accumulated sliding window (minimum 10 calls)
     before calculating failure rate threshold.
```

### 21.5 Running the Stress Test
```bash
# Run full 4-stage test
k6 run k6/checkout-stress-test.js

# Target specific phase via environment variable
k6 run -e PHASE=bulkhead k6/checkout-stress-test.js
k6 run -e PHASE=circuit_breaker k6/checkout-stress-test.js

# Generate HTML report
k6 run --out web-dashboard k6/checkout-stress-test.js
```

---

## 22. Idempotency Key Pattern (Session 24)

### 22.1 The Duplicate Order Problem
In distributed e-commerce architectures, client retries caused by network timeouts, mobile reconnection events, or accidental double clicks on "Place Order" can dispatch identical requests to `POST /api/orders`:
- **Financial Risk:** If each request executes as a new purchase, customers suffer multiple unauthorized credit card charges.
- **Inventory Depletion:** Duplicate Saga orchestrations lock and consume double the warehouse stock.
- **Pipeline Pollution:** Downstream fulfillment, shipping, and ERP pipelines process phantom orders.

### 22.2 Idempotency Architecture
To ensure **exactly-once processing semantics** from the client's perspective, the **Idempotency Key Pattern** was implemented in `order-service`:

```
┌───────────────────────────────── Order Service ─────────────────────────────────┐
│ Client Request                                                                  │
│ POST /api/orders                                                                │
│ Headers: [Idempotency-Key: "idemp-abc-123", Authorization: Bearer ...]          │
│ Body: {"productId": "PROD-001", "quantity": 2, "amount": 299.99}                │
│                           │                                                     │
│                           ▼                                                     │
│               OrderController.createOrder()                                     │
│                           │                                                     │
│                           ▼                                                     │
│               OrderService.createOrder(request, idempotencyKey)                 │
│                           │                                                     │
│       ┌───────────────────┴───────────────────┐                                 │
│       │ Idempotency-Key present in DB?        │                                 │
│       └─────────┬───────────────────┬─────────┘                                 │
│            YES  │                   │  NO (or no header supplied)               │
│                 ▼                   ▼                                           │
│       Deserialize cached      [BEGIN @Transactional]                            │
│       responsePayload         1. Save Order (PENDING)                           │
│       Return cached 200 OK    2. Save OutboxEvent (with trace context)          │
│       (Zero duplicate writes) 3. Save IdempotentRequest (orderId, jsonResponse) │
│                               [COMMIT TRANSACTION]                              │
│                               Return freshly minted OrderResponse               │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 22.3 Implementation Details
- **Entity Model (`IdempotentRequest`)**:
  - `idempotencyKey` (`VARCHAR(255)`, Primary Key)
  - `orderId` (`VARCHAR(255)`, nullable)
  - `responsePayload` (`TEXT`, serialized `OrderResponse` JSON)
  - `status` (`PROCESSED`)
  - `createdAt` (`LocalDateTime`)
- **Repository (`IdempotentRequestRepository`)**:
  - Spring Data JPA repository extending `JpaRepository<IdempotentRequest, String>`.
- **Atomic Transactional Guarantee**:
  - In `OrderService.createOrder()`, the creation of the `Order`, the `OutboxEvent`, and the `IdempotentRequest` are executed inside a single `@Transactional` boundary. If inventory validation fails or an exception occurs, the transaction rolls back cleanly, ensuring that failed attempts do not permanently block subsequent valid attempts with the same key.
- **Fast-Path Replay Cache**:
  - When a duplicate request arrives, `idempotentRequestRepository.findById(idempotencyKey)` intercepts the call before any business logic, deserializes `responsePayload`, and immediately returns the cached `OrderResponse` (HTTP 200). No new orders or outbox events are inserted into the database.
- **Backward Compatibility**:
  - The `Idempotency-Key` header on `OrderController` is marked with `required = false`. Clients omitting the header continue through normal order creation.

### 22.4 Automated Test Suite (Order Service)
Three unit tests in `IdempotencyPatternTest` guarantee idempotency correctness:
1. `createOrder_whenIdempotencyKeyProvided_savesRequestAndReturnsResponse` — Confirms first-time requests persist the `IdempotentRequest` entity alongside the order.
2. `createOrder_whenDuplicateIdempotencyKey_returnsCachedResponseWithoutDuplicateOrderOrOutbox` — Verifies repeated submissions return the cached payload with zero new orders or outbox events.
3. `createOrder_whenNoIdempotencyKey_processesNormally` — Confirms non-idempotent legacy requests process without regression.

### 22.5 Payment Service Idempotency Keys (Session 22 Lab 18 Task 2)
In addition to the order ingress layer, `payment-service` enforces the Idempotency Key Pattern on downstream payment execution (`POST /api/v1/payments` and `POST /api/payments`) to eliminate duplicate billing risk if Resilience4j `@Retry` executes the same payment call twice:
- **`IdempotencyRecord` Entity**:
  - `idempotencyKey` (`VARCHAR(255)`, Primary Key)
  - `orderId` (`VARCHAR(255)`, Not Null)
  - `status` (`PROCESSING` | `COMPLETED` | `FAILED`)
  - `responsePayload` (`TEXT`, stores `transactionId` or failure reason)
  - `createdAt` (`Instant`, used for 24h retention cleanup)
- **`IdempotencyRepository`**: Spring Data JPA repository extending `JpaRepository<IdempotencyRecord, String>` with `findByCreatedAtBefore()`.
- **Payment Controller Semantics**:
  - **New Key:** Stores `IdempotencyRecord` with status `PROCESSING` before executing payment logic. Upon successful charge, transitions status to `COMPLETED` and stores `transactionId`.
  - **Duplicate Key (COMPLETED):** Bypasses payment gateway entirely, returning cached `200 OK` with existing `transactionId`.
  - **Duplicate Key (PROCESSING):** Returns `202 Accepted` ("Payment already in progress"), preventing concurrent double-execution races.
- **Verification (`PaymentControllerTest`)**: 4 unit tests verifying new keys, duplicate completed keys, duplicate processing keys (202 Accepted), and non-idempotent requests. All 4 tests passing (100% green).

---

## 23. Technical Debt & Production Readiness

### 23.1 Resolved Debt
- [x] **Dual-Write Vulnerability (Session 22):** Resolved via Transactional Outbox pattern with atomic database commits and scheduled polling relay.
- [x] **Java 26 / Byte Buddy Mocking:** Resolved via `-Dnet.bytebuddy.experimental=true` in Surefire configurations across microservices.
- [x] **Canary Traffic Decoupling (Session 21):** Resolved via Istio `VirtualService` 80/20 weighted routing, completely decoupling deployment canary testing from replica count ratios.
- [x] **Gateway Security Boundary (Session 20):** Resolved via Spring Security OAuth2 resource server with header propagation (`X-User-Id`, `X-User-Role`).
- [x] **Idempotency Key Pattern (Session 24):** Resolved via `IdempotentRequest` persistence table and atomic transactional caching in `order-service`. Prevents duplicate orders, double billing, and redundant Kafka sagas upon client retries.
- [x] **Distributed Tracing in Outbox (Session 24):** Resolved by capturing active Micrometer `Tracer` span (`traceId`, `spanId`) into `OutboxEvent` and injecting Zipkin B3 (`X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled`) and W3C `traceparent` headers in `OutboxEventRelay`.

### 23.2 Open Technical Debt (Actionable Roadmap)

| Priority | Item | Impact | Recommended Solution |
|---|---|---|---|
| **MEDIUM** | **Outbox CDC Migration** | Polling outbox every 5s introduces a 0–5s event propagation latency and continuous DB polling load. | Deploy **Debezium** Kafka Connect connector to stream database WAL / binlog changes directly to Kafka. |
| **MEDIUM** | **Database Persistence for Order & Inventory** | Order service currently defaults to H2; Inventory uses an in-memory Map. | Migrate both to PostgreSQL with Liquibase or Flyway database migrations. |
| **MEDIUM** | **Asymmetric Token Signing (RS256)** | Gateway and services currently use symmetric HS256 secret sharing. | Transition to RS256 with OpenID Connect provider (Keycloak) and JWKS public key verification. |

---

## 24. Design Decisions Log

| Decision | Rationale |
|---|---|
| **HS256 over RS256** | No IdP in lab; symmetric secret keeps setup self-contained. RS256 + JWKS for production. |
| **KRaft Kafka** | No ZooKeeper dependency; simpler Docker setup; aligns with Kafka 3.x+ direction. |
| **H2 for Order Service in dev** | Fast local iteration; PostgreSQL configured via env vars for staging/prod. |
| **In-memory Map for Inventory** | Focus on saga patterns, not persistence boilerplate. Add JPA in a real system. |
| **Both Saga patterns in same project** | Educational: shows choreography simplicity vs orchestrator visibility trade-off side-by-side. |
| **Istio weight over replica-count split** | Exactly N% traffic regardless of pod count; change split without scaling; decouples resilience from capacity. |
| **Stricter outlier detection for canary** | A bad canary should be ejected faster and more completely than a bad stable pod. |
| **`permitAll()` on GET /api/products** | Product catalog browsing should not require login — improves UX and SEO. |
| **CQRS-lite (not full CQRS)** | Separate read/write DTOs within the same service; no separate read model DB. Justified by different validation shape and derived `inStock` field. |
| **`@RetryableTopic` on Notification** | Notifications to external systems (email/SMS) are inherently unreliable; retry at the Kafka consumer level rather than in application code. |
| **Transactional Outbox over 2PC / XA** | Distributed two-phase commits introduce locking, high latency, and single points of failure. Outbox provides local ACID consistency with at-least-once asynchronous event delivery. |
| **Polling Outbox Relay over CDC for dev** | Polling via `@Scheduled` requires zero additional infrastructure (no Kafka Connect / Debezium cluster) while completely solving the dual-write correctness problem. |
| **k6 over JMeter / Gatling** | Scriptable in standard modern JavaScript; minimal resource footprint; native support for custom real-time metrics (`Counter`, `Trend`, `Rate`) tracking individual resilience aspects. |
| **Idempotency Key in Database Transaction** | Atomic persistence of order, outbox event, and idempotency key guarantees that cached replay payload is only saved when the order successfully commits. |
| **Dual Tracing Headers (B3 + W3C traceparent)** | Populating both Zipkin B3 headers and standard W3C `traceparent` ensures universal interoperability across diverse tracing agents (Micrometer, OpenTelemetry, Istio Envoy). |


