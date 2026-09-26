# k6 Stress Tests — Session 23 / Lab 19

## Scripts

| File | Purpose |
|---|---|
| [`checkout-stress-test.js`](./checkout-stress-test.js) | Full checkout chain stress test (4 stages) |

## How to Run

### Prerequisites

```bash
# Install k6
winget install k6 --source winget   # Windows
brew install k6                      # macOS
# Linux: https://k6.io/docs/getting-started/installation/
```

### Start the Stack First

```bash
docker compose up -d
# Wait until all services show (healthy)
docker compose ps
```

### Run Scenarios

```bash
# Full 4-stage test (default — triggers all resilience patterns)
k6 run k6/checkout-stress-test.js

# Isolated phases
k6 run --env PHASE=baseline        k6/checkout-stress-test.js   # 5 VUs, no CB
k6 run --env PHASE=bulkhead        k6/checkout-stress-test.js   # 15 VUs → trips bulkhead
k6 run --env PHASE=circuit_breaker k6/checkout-stress-test.js   # trips CB (set failure-rate=1.0)

# Custom gateway URL
k6 run --env BASE_URL=http://my-cluster:30080 k6/checkout-stress-test.js

# Generate live Prometheus metrics
k6 run --out experimental-prometheus-rw k6/checkout-stress-test.js
```

### Reports

HTML + JSON reports are written to `k6/reports/` after each run.

## Resilience4j Thresholds Under Test (Session 4–5)

| Pattern | Threshold | Triggered at |
|---|---|---|
| Bulkhead | `maxConcurrentCalls=10` | VU count > 10 |
| Circuit Breaker | `failureRateThreshold=50%`, window=10 | 5+ failures in 10 calls |
| TimeLimiter | `timeoutDuration=3s` | Retry chain > 3s total |
| Retry | `maxAttempts=3`, 500ms exponential | Every payment failure |

## Forcing Specific Failure Modes

```bash
# Trip circuit breaker fast: set failure-rate=1.0 in payment-service config
# Then in docker-compose, add to payment-service environment:
#   PAYMENT_FAILURE_RATE: "1.0"

docker compose up -d --force-recreate payment-service

# Reset to default:
#   PAYMENT_FAILURE_RATE: "0.5"
docker compose up -d --force-recreate payment-service
```

## Zipkin Correlation

Open Zipkin at `http://localhost:9411` and search for:
- Service: `order-service`
- Tag: `error=BulkheadFullException` → Bulkhead rejections
- Tag: `error=CallNotPermittedException` → Circuit breaker trips
- Tag: `error=TimeoutException` → TimeLimiter timeouts
- Duration > 3000ms → Retry + TimeLimiter chains
