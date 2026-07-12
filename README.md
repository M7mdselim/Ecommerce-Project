<<<<<<< HEAD
# Ecommerce Project — Microservices Architecture & Resilience Patterns (Session 4)

This repository contains a Spring Boot & Spring Cloud microservices training project (configured on the `Task-3` branch and extended in `Session 4`). It implements service discovery, centralized configuration, routing, request rate limiting, logging, JWT-based authentication, and resilience patterns (Circuit Breaker, Retry, Fallback).
=======
<<<<<<< Updated upstream
# Ecommerce Project

=======
# Ecommerce Project — Microservices Architecture & Resilience Patterns (Session 5)

This repository contains a Spring Boot & Spring Cloud microservices training project. Session 5 introduces an advanced asynchronous resilience layer in the `order-service` to manage downstream execution, concurrency, and timeouts using Resilience4j.
>>>>>>> Task-5

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
<<<<<<< HEAD
    Order -->|Feign Client with Circuit Breaker/Retry| Payment
=======
    Order -->|Async Feign Call via Bulkhead/TimeLimiter/CircuitBreaker/Retry| Payment
>>>>>>> Task-5
    
    %% Redis connection
    Redis[(Redis :6379)] <--->|Rate Limiting Keys| Gateway
```

---

## 🔌 Service Port Registry

| Service Name | Port | Database / Backend | Key Functions |
| :--- | :---: | :--- | :--- |
| **`api-gateway`** | `8080` | Redis (`localhost:6379`) | API Gateway, Route Matching, JWT Authentication, Logging, Rate Limiting |
| **`eureka`** | `8761` | In-Memory Registry | Netflix Eureka Service Registry & Discovery |
<<<<<<< HEAD
| **`config-server`** | `8888` | GitHub Repository | Central Config Server (Fetches from external Git repo) |
| **`product`** | `8081` | H2 DB (`jdbc:h2:mem:productdb`) | Product Catalog Management (CRUD) |
| **`order-service`** | `8082` | H2 DB (`jdbc:h2:mem:orderdb`) | Order service with Circuit Breaker & Retry |
| **`payment-service`**| `8083` | H2 DB (`jdbc:h2:mem:paymentdb`) | Payment service with simulated random failure |
=======
| **`config-server`** | `8888` | Local Git Repository | Central Config Server (Fetches configs from `config-repo` directory) |
| **`product`** | `8081` | H2 DB (`jdbc:h2:mem:productdb`) | Product Catalog Management (CRUD) |
| **`order-service`** | `8082` | H2 DB (`jdbc:h2:mem:orderdb`) | Order service with asynchronous execution and Resilience4j Aspect stack |
| **`payment-service`**| `8083` | H2 DB (`jdbc:h2:mem:paymentdb`) | Payment service with dynamic delay and failure rate simulator |
>>>>>>> Task-5

---

## 🛡️ Security & JWT Architecture

<<<<<<< HEAD
This project enforces a **Single Security Trust Boundary** pattern.
=======
This project enforces a **Single Security Trust Boundary** pattern:
>>>>>>> Task-5
1. **Validation at Gateway Only:** The API Gateway validates incoming JWT tokens. Downstream services do **not** perform JWT verification.
2. **Header Cleansing:** The Gateway strips any incoming headers like `X-User-Id` or `X-User-Role` from clients to prevent request-spoofing.
3. **Header Enrichment:** Once the JWT is verified, the Gateway extracts claims (User ID and Role) and injects them into the HTTP headers (`X-User-Id`, `X-User-Role`) passed downstream.

---

## 🚦 Request Rate Limiting

The API Gateway integrates **Spring Cloud Gateway RequestRateLimiter** backed by Redis:
* **Default IP-based Limiting:** `ipKeyResolver` limits calls by client IP to prevent denial of service (DoS).
* **User-based Limiting:** `userKeyResolver` extracts `X-User-Id` to apply custom rate limits per authenticated user.

---

<<<<<<< HEAD
## ⚡ Resilience Patterns (Session 4)

To make `order-service` resilient to `payment-service` failures, we integrated **Resilience4j** with **Spring Boot Starter AOP** and **Spring Cloud OpenFeign**.
=======
## ⚡ Asynchronous Resilience Layer (Session 5)

To protect `order-service` from resource starvation and slow/failing downstream dependencies (`payment-service`), we implemented a non-blocking asynchronous resilience stack using **Resilience4j**, **Spring AOP**, and **Java CompletableFuture**.
>>>>>>> Task-5

### 1. Request Flow & Execution Order
When a request is sent to `POST /api/orders`:
```
<<<<<<< HEAD
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
=======
[Client] -> [OrderController] -> [Bulkhead (1)] -> [TimeLimiter (2)] -> [Circuit Breaker (3)] -> [Retry (4)] -> [Async Task (CompletableFuture)] -> [Payment Service]
```
The aspect execution order is enforced programmatically in [ResilienceAspectConfig.java](file:///order-service/src/main/java/com/microservice/pro/order_service/config/ResilienceAspectConfig.java):
* **Bulkhead (Precedence 1):** Semaphore Bulkhead limits the maximum concurrent active payment processing threads.
* **TimeLimiter (Precedence 2):** Restricts the maximum time allowed for the task execution.
* **Circuit Breaker (Precedence 3):** Monitors failures and short-circuits calls when threshold is crossed.
* **Retry (Precedence 4):** Retries failed calls with exponential backoff before reporting failure.

### 2. Specific Fallback Paths
Each resilience decorator provides a customized fallback path:
* **Bulkhead Fallback:** Executed when concurrent calls exceed limits. Returns `QUEUED` status with message `"System busy. Your order has been queued."` and logs a warning with prefix `[BULKHEAD]`.
* **TimeLimiter Fallback:** Executed when downstream payment takes longer than 2s. Returns `PENDING` status with message `"Payment timed out."` and logs a warning with prefix `[TIMEOUT]`.
* **Circuit Breaker / Retry Fallback:** Executed when retries are exhausted or the circuit is OPEN. Returns `PENDING` status with message `"Payment unavailable."`.

### 3. Dynamic Payment Simulator
The `payment-service` simulates failure rates and processing delays controlled by the Central Config Server properties:
* `payment.failure-rate`: Failure rate fraction (e.g., `0.5` for 50%).
* `payment.delay-ms`: Time in milliseconds to block the thread (e.g., `3000` to simulate a 3-second delay).

---

## ⚙️ Resilience & Actuator Configurations

Configurations are centralized in [order-service.yml](file:///config-repo/order-service.yml) inside the local Git config repository:
```yaml
resilience4j:
  bulkhead:
    instances:
      paymentService:
        max-concurrent-calls: 10
        max-wait-duration: 0ms
  timelimiter:
    instances:
      paymentService:
        timeout-duration: 2s
        cancel-running-future: true
  circuitbreaker:
>>>>>>> Task-5
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
<<<<<<< HEAD
    retry-aspect-order: 2 # Inner aspect
=======
>>>>>>> Task-5
    instances:
      paymentService:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
<<<<<<< HEAD
        retry-exceptions:
          - java.lang.RuntimeException
        ignore-exceptions:
          - java.lang.IllegalArgumentException
=======

management:
  health:
    circuitbreakers:
      enabled: true
    bulkheads:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,circuitbreakers,bulkheads,metrics
>>>>>>> Task-5
```

---

<<<<<<< HEAD
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
=======
## 🛠️ Startup & Verification Guide

### 1. Pre-requisites
* **Java 21**
* **Redis** (running on `localhost:6379`)

### 2. Startup order
>>>>>>> Task-5
1. **Config Server** (Port `8888`)
2. **Eureka Registry** (Port `8761`)
3. **Payment Service** (Port `8083`)
4. **Order Service** (Port `8082`)
5. **API Gateway** (Port `8080`)

<<<<<<< HEAD
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
=======
### 3. Verification Scripts
Helper PowerShell scripts are located in the [test-scripts](file:///test-scripts/) directory:
* **Timeout Verification:** [timeout_test.ps1](file:///test-scripts/timeout_test.ps1) sets `payment.delay-ms` to `3000` to trigger the timeout fallback.
* **Bulkhead Concurrency Verification:** [concurrent_test.ps1](file:///test-scripts/concurrent_test.ps1) fires 15 concurrent HTTP requests to trigger the bulkhead queueing fallback.
>>>>>>> Task-5

---

## 🧪 Running Automated Tests

<<<<<<< HEAD
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
=======
Run the test suite using the Maven Wrapper from the root of `order-service` or `payment-service`:
```bash
# In order-service
.\mvnw.cmd clean test

# In payment-service
.\mvnw.cmd clean test
```
The test suite validates:
* **Bulkhead Limitation:** Programmatic concurrent task executor validates bulkhead rejection and fallback message mapping.
* **TimeLimiter Timeout:** WireMock injects delays to trigger timeout fallback.
* **Retry & Circuit Breaker:** Validates exponential backoff retries and subsequent circuit opening.
>>>>>>> Stashed changes
>>>>>>> Task-5
