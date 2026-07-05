# Ecommerce Project — Microservices Architecture & Resilience Patterns (Session 4)

This repository contains a Spring Boot & Spring Cloud microservices training project (configured on the `Task-3` branch and extended in `Session 4`). It implements service discovery, centralized configuration, routing, request rate limiting, logging, JWT-based authentication, and resilience patterns (Circuit Breaker, Retry, Fallback).

---

## 🏗️ Architecture Overview

The system is organized into a **4-tier microservices architecture** where the API Gateway acts as the single entry point and security trust boundary. 

```mermaid
graph TD
    Client[Client Request] -->|REST / API| Gateway[API Gateway :8080]
    
    %% Config & Discovery
    Config[Config Server :8888] -.->|Provides Config| Gateway
    Config -.->|Provides Config| Product[Product Service :8081]
    Config -.->|Provides Config| Order[Order Service :8082]
    Config -.->|Provides Config| Payment[Payment Service :8083]
    
    Eureka[Eureka Server :8761] <--->|Service Registration & Discovery| Gateway
    Eureka <--->|Register| Product
    Eureka <--->|Register| Order
    Eureka <--->|Register| Payment

    %% Downstream Traffic Routing
    Gateway -->|Routes with JWT enrichment| Product
    Gateway -->|Routes| Order
    Order -->|Feign Client with Circuit Breaker/Retry| Payment
    
    %% Redis connection
    Redis[(Redis :6379)] <--->|Rate Limiting Keys| Gateway
```

---

## 🔌 Service Port Registry

| Service Name | Port | Database / Backend | Key Functions |
| :--- | :---: | :--- | :--- |
| **`api-gateway`** | `8080` | Redis (`localhost:6379`) | API Gateway, Route Matching, JWT Authentication, Logging, Rate Limiting |
| **`eureka`** | `8761` | In-Memory Registry | Netflix Eureka Service Registry & Discovery |
| **`config-server`** | `8888` | GitHub Repository | Central Config Server (Fetches from external Git repo) |
| **`product`** | `8081` | H2 DB (`jdbc:h2:mem:productdb`) | Product Catalog Management (CRUD) |
| **`order-service`** | `8082` | H2 DB (`jdbc:h2:mem:orderdb`) | Order service with Circuit Breaker & Retry |
| **`payment-service`**| `8083` | H2 DB (`jdbc:h2:mem:paymentdb`) | Payment service with simulated random failure |

---

## 🛡️ Security & JWT Architecture

This project enforces a **Single Security Trust Boundary** pattern.
1. **Validation at Gateway Only:** The API Gateway validates incoming JWT tokens. Downstream services do **not** perform JWT verification.
2. **Header Cleansing:** The Gateway strips any incoming headers like `X-User-Id` or `X-User-Role` from clients to prevent request-spoofing.
3. **Header Enrichment:** Once the JWT is verified, the Gateway extracts claims (User ID and Role) and injects them into the HTTP headers (`X-User-Id`, `X-User-Role`) passed downstream.

---

## 🚦 Request Rate Limiting

The API Gateway integrates **Spring Cloud Gateway RequestRateLimiter** backed by Redis:
* **Default IP-based Limiting:** `ipKeyResolver` limits calls by client IP to prevent denial of service (DoS).
* **User-based Limiting:** `userKeyResolver` extracts `X-User-Id` to apply custom rate limits per authenticated user.

---

## ⚡ Resilience Patterns (Session 4)

To make `order-service` resilient to `payment-service` failures, we integrated **Resilience4j** with **Spring Boot Starter AOP** and **Spring Cloud OpenFeign**.

### 1. Request Flow & Execution Order
When a request is sent to `POST /api/orders`:
```
[Client] -> [OrderController] -> [Circuit Breaker (Outer)] -> [Retry (Inner)] -> [PaymentClient (Feign)] -> [Payment Service]
```

### 2. Retry with Exponential Backoff
If `payment-service` fails (throws a `RuntimeException`), the inner Retry aspect intercepts the exception and performs retries as follows:
* **Attempt 1:** Fails immediately.
* **Wait:** 500ms
* **Attempt 2:** Fails immediately.
* **Wait:** 1000ms (500ms * multiplier 2)
* **Attempt 3:** Fails.
* **Result:** Retry propagates the error to the Circuit Breaker.

### 3. Circuit Breaker Configuration
We protect the payment call using a dedicated Circuit Breaker instance named `paymentService`:
* **Sliding Window:** `COUNT_BASED` with size `10`
* **Failure Threshold:** `50%` (if 5 out of 10 calls fail, the circuit trips)
* **Wait In Open State:** `5 seconds` (duration before trying again)
* **Half-Open permitted calls:** `3` calls to check health
* **Auto Transition:** Enabled (from `OPEN` to `HALF_OPEN`)

### 4. Graceful Degradation (Fallback)
If all 3 retry attempts fail, or if the Circuit Breaker is `OPEN`, the fallback method is invoked:
* **Fallback Response:** Returns status `PENDING` with message `"Payment service unavailable. Order will be processed later."`

---

## ⚙️ Resilience Configuration (application.yaml)

Configured inside `order-service/src/main/resources/application.yaml`:
```yaml
resilience4j:
  circuitbreaker:
    circuit-breaker-aspect-order: 1 # Outer aspect
    instances:
      paymentService:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        register-health-indicator: true
  retry:
    retry-aspect-order: 2 # Inner aspect
    instances:
      paymentService:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.lang.RuntimeException
        ignore-exceptions:
          - java.lang.IllegalArgumentException
```

---

## 📊 Actuator Health & Metrics Endpoints

We exposed key metrics to monitor Circuit Breaker states:
* **Health Endpoint:** `GET http://localhost:8082/actuator/health` (Exposes detailed state of all circuit breakers when `show-details: always` is configured).
* **Circuit Breakers Endpoint:** `GET http://localhost:8082/actuator/circuitbreakers` (Lists active circuit breaker instances and stats).

---

## 🛠️ How to Run & Verify

### 1. Pre-requisites
* **Java 21**
* **Redis** (running on `localhost:6379` for rate limiting at the gateway)

### 2. Startup Order
1. **Config Server** (Port `8888`)
2. **Eureka Registry** (Port `8761`)
3. **Payment Service** (Port `8083`)
4. **Order Service** (Port `8082`)
5. **API Gateway** (Port `8080`)

### 3. API endpoints

#### Create an Order
```bash
curl -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"prod_101", "quantity": 1, "amount": 150.00}'
```

#### Expected Log Output (Order Service Console):
If payment fails, you will see the retry attempts and fallback trigger logs:
```
2026-07-05 02:49:19.794 INFO  [order-service] Calling payment service for Order ID: ...
2026-07-05 02:49:19.820 INFO  [order-service] [RETRY] Attempt #1
2026-07-05 02:49:20.325 INFO  [order-service] [RETRY] Attempt #2
2026-07-05 02:49:21.330 WARN  [order-service] [RETRY] Failed after attempt #3
2026-07-05 02:49:21.332 ERROR [order-service] Payment fallback triggered. Error message: ...
```

---

## 🧪 Running Automated Tests

Run the unit and integration tests from the root of each service:
```bash
# In order-service
mvn clean test

# In payment-service
mvn clean test
```
The test suite covers:
* Successful payment mapping to `CONFIRMED`.
* Failed payment retrying 3 times and returning `PENDING` fallback.
* Circuit Breaker tripping to `OPEN` and short-circuiting downstream HTTP calls.
