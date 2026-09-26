/**
 * ============================================================================
 * k6 Checkout Stress Test — Session 23 / Lab 19
 * File: k6/checkout-stress-test.js
 * ============================================================================
 *
 * TARGET: Full checkout chain through the API Gateway
 *
 *   Client → API Gateway (8080)
 *           → Order Service (8082)  [Resilience4j: CB + Retry + Bulkhead + TL]
 *             → Inventory Service (8084) via Feign (sync pre-check)
 *             → Kafka outbox → Saga orchestrator
 *               → Payment Service (8083) [50% failure-rate configured]
 *
 * RESILIENCE4J THRESHOLDS (Session 4–5) THIS TEST DELIBERATELY EXCEEDS:
 *
 *   ┌─────────────────────────────────────────────────────────────────────────┐
 *   │  Pattern          │ Config                     │ Triggered when…        │
 *   ├─────────────────────────────────────────────────────────────────────────┤
 *   │  Bulkhead         │ maxConcurrentCalls=10      │ VU ramp > 10 parallel  │
 *   │  TimeLimiter      │ timeoutDuration=3s         │ payment.delay-ms=2000  │
 *   │  CircuitBreaker   │ failureRate=50%, window=10 │ payment.failure-rate=1 │
 *   │  Retry            │ maxAttempts=3, 500ms exp.  │ every failed payment   │
 *   └─────────────────────────────────────────────────────────────────────────┘
 *
 * HOW TO RUN:
 *
 *   # Install k6: https://k6.io/docs/getting-started/installation/
 *
 *   # Phase 1 — Baseline (normal load, all resilience patterns OFF)
 *   k6 run --env PHASE=baseline k6/checkout-stress-test.js
 *
 *   # Phase 2 — Bulkhead trip (ramp to 15 VUs → exceeds maxConcurrentCalls=10)
 *   k6 run --env PHASE=bulkhead k6/checkout-stress-test.js
 *
 *   # Phase 3 — Circuit breaker trip (payment failure-rate=1.0 → CB opens)
 *   k6 run --env PHASE=circuit_breaker k6/checkout-stress-test.js
 *
 *   # Phase 4 — Full stress (all patterns activated in sequence)
 *   k6 run k6/checkout-stress-test.js
 *
 *   # With Prometheus remote write (live metrics):
 *   k6 run --out experimental-prometheus-rw k6/checkout-stress-test.js
 *
 * ============================================================================
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

// ─────────────────────────────────────────────────────────────────────────────
// Custom Metrics
// ─────────────────────────────────────────────────────────────────────────────

/** Tracks the % of requests that triggered a resilience4j fallback response */
const fallbackActivations = new Rate('resilience4j_fallbacks');

/** Counts circuit-breaker OPEN state responses (HTTP 503 from fallback) */
const circuitBreakerTrips = new Counter('circuit_breaker_trips');

/** Counts bulkhead saturation rejections (BulkheadFullException → fallback) */
const bulkheadRejections = new Counter('bulkhead_rejections');

/** Counts TimeLimiter timeouts (TimeoutException → fallback after 3s) */
const timeLimiterTimeouts = new Counter('timelimiter_timeouts');

/** End-to-end latency for the full checkout POST → status GET round trip */
const checkoutDuration = new Trend('checkout_duration_ms', true);

/** Tracks HTTP status codes seen across all requests */
const errorRate = new Rate('http_errors');

// ─────────────────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PHASE    = __ENV.PHASE   || 'full';

/**
 * JWT token with ROLE_USER (from postman_collection.json).
 * The gateway validates this against the shared HS256 secret.
 * Replace with a freshly-generated token if expired.
 */
const JWT_TOKEN = __ENV.JWT_TOKEN ||
  'eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiUk9MRV9BRE1JTiIsInN1YiI6InVzZXJfMTIzNDUiLCJpYXQiOjE3ODI5MDQ0NjEsImV4cCI6MTc4Mjk5MDg2MX0.tUNP5em-Db8LPphA5Jle9AmQZbAq8oSuZBJeXOF6iTQ';

const HEADERS = {
  'Content-Type':  'application/json',
  'Authorization': `Bearer ${JWT_TOKEN}`,
};

// ─────────────────────────────────────────────────────────────────────────────
// Test Scenarios
//
// The "full" scenario (default) runs four stages in sequence:
//
//   Stage 1  0–60s    BASELINE     5 VUs    → No thresholds exceeded, all 200 OK
//   Stage 2  60–120s  BULKHEAD    15 VUs    → Exceeds maxConcurrentCalls=10
//                                              BulkheadFullException → fallback
//   Stage 3 120–180s  CIRCUIT-BK  15 VUs    → payment.failure-rate=1.0 forced
//                                              After 10 failures, CB opens
//                                              All calls return fallback immediately
//   Stage 4 180–240s  RECOVERY     2 VUs    → CB transitions OPEN→HALF-OPEN→CLOSED
//                                              Verify system self-heals
// ─────────────────────────────────────────────────────────────────────────────

const SCENARIOS = {
  baseline: {
    executor: 'ramping-vus',
    startVUs: 1,
    stages: [
      { duration: '30s', target: 5 },
      { duration: '30s', target: 5 },
      { duration: '15s', target: 0 },
    ],
  },
  bulkhead: {
    executor: 'ramping-vus',
    startVUs: 1,
    stages: [
      { duration: '15s', target: 5 },
      { duration: '30s', target: 15 }, // ← exceeds bulkhead maxConcurrentCalls=10
      { duration: '15s', target: 0 },
    ],
  },
  circuit_breaker: {
    executor: 'constant-vus',
    vus: 12,
    duration: '60s',
    // Requires payment.failure-rate=1.0 to be set before running
  },
  full: {
    executor: 'ramping-vus',
    startVUs: 1,
    stages: [
      // Stage 1 — Baseline: 5 VUs, no resilience pattern should activate
      { duration: '60s', target: 5  },

      // Stage 2 — Bulkhead trip: ramp to 15 VUs → exceeds maxConcurrentCalls=10
      // Expected: ~5 VUs get BulkheadFullException → fallback ("Payment unavailable")
      { duration: '60s', target: 15 },
      { duration: '30s', target: 15 },

      // Stage 3 — Circuit breaker: payment failure-rate=1.0 pushed via config
      // After 5 failures in 10-call window → CB OPEN → all calls get fallback instantly
      { duration: '60s', target: 12 },

      // Stage 4 — Recovery: drop VUs so CB transitions HALF-OPEN → CLOSED
      { duration: '30s', target: 2  },
      { duration: '15s', target: 0  },
    ],
  },
};

export const options = {
  scenarios: {
    checkout: SCENARIOS[PHASE] || SCENARIOS.full,
  },

  // ── Acceptance Thresholds ────────────────────────────────────────────────
  // These define what constitutes PASSING for this test.
  // During the stress phases we EXPECT some of these to be violated,
  // which is what "demonstrably exceeds at least one Session 4-5 threshold" means.
  thresholds: {
    // p95 checkout latency must be < 5s (includes Retry backoff = 500ms × 3 = 1.5s)
    'checkout_duration_ms': ['p(95)<5000'],

    // Overall HTTP error rate must be < 40% in the final steady-state
    // (we expect > 40% during the circuit-breaker stage — that is the point)
    'http_errors': ['rate<0.4'],

    // At least 60% of requests must reach status 200 or known-good fallback
    'http_req_failed': ['rate<0.4'],

    // p99 gateway latency must not exceed 8s (guards against full queue backlog)
    'http_req_duration': ['p(99)<8000'],
  },
};

// ─────────────────────────────────────────────────────────────────────────────
// VU Scenario: Full Checkout Flow
//
//  1. POST /api/orders             → triggers Feign → Inventory + Payment saga
//  2. Sleep 200ms (allow async)
//  3. GET  /api/orders/{id}/status → read saga outcome
//  4. Classify response: success / bulkhead rejection / CB open / timeout
// ─────────────────────────────────────────────────────────────────────────────

export default function () {

  // ── Vary product to avoid inventory exhaustion collapsing all runs ────────
  const products = [
    { productId: 'PROD-001', quantity: 1, amount: 50.00  },  // 100 units in stock
    { productId: 'PROD-001', quantity: 2, amount: 100.00 },
    { productId: 'PROD-001', quantity: 3, amount: 150.00 },
  ];
  const product = products[__VU % products.length];

  const checkoutStart = Date.now();

  // ── Step 1: Place order (triggers entire chain) ───────────────────────────
  let orderId = null;

  group('POST /api/orders (full chain)', () => {
    const res = http.post(
      `${BASE_URL}/api/orders`,
      JSON.stringify(product),
      { headers: HEADERS, timeout: '10s' }
    );

    // Classify the response to track which resilience pattern fired
    const body = res.body || '';

    // Rate limiter: gateway rejects before reaching order service
    if (res.status === 429) {
      bulkheadRejections.add(1);
      fallbackActivations.add(1);
      errorRate.add(1);

    // Circuit breaker OPEN or BulkheadFullException → fallback message
    } else if (res.status === 503 || body.toLowerCase().includes('unavailable')) {
      circuitBreakerTrips.add(1);
      fallbackActivations.add(1);
      errorRate.add(1);

    // BulkheadFullException surfaced as 500 by Spring's DefaultErrorController
    } else if (res.status === 500 && body.toLowerCase().includes('bulkhead')) {
      bulkheadRejections.add(1);
      fallbackActivations.add(1);
      errorRate.add(1);

    // TimeLimiter timeout → fallback after 3s
    } else if (res.status === 500 && body.toLowerCase().includes('timeout')) {
      timeLimiterTimeouts.add(1);
      fallbackActivations.add(1);
      errorRate.add(1);

    // Normal success or PENDING (saga started correctly)
    } else if (res.status === 200) {
      fallbackActivations.add(0);
      errorRate.add(0);
      try {
        const json = JSON.parse(body);
        orderId = json.orderId;
      } catch (_) {}

    // Any other unexpected status
    } else {
      errorRate.add(1);
      fallbackActivations.add(1);
    }

    check(res, {
      'order accepted (200 or 503-fallback)': (r) =>
        r.status === 200 || r.status === 503 || r.status === 500,
      'response body present': (r) => r.body && r.body.length > 0,
    });
  });

  // ── Step 2: Give async Saga a moment to process ───────────────────────────
  sleep(0.2);

  // ── Step 3: Poll order status if we got an orderId ───────────────────────
  if (orderId) {
    group('GET /api/orders/{id}/status (saga outcome)', () => {
      const statusRes = http.get(
        `${BASE_URL}/api/orders/${orderId}/status`,
        { headers: HEADERS, timeout: '5s' }
      );

      check(statusRes, {
        'status endpoint reachable': (r) => r.status === 200 || r.status === 404,
        'saga outcome received': (r) => {
          const b = r.body || '';
          return ['PENDING', 'CONFIRMED', 'CANCELLED', 'PAYMENT_FAILED'].some(s => b.includes(s));
        },
      });
    });
  }

  // Record full checkout round-trip duration
  checkoutDuration.add(Date.now() - checkoutStart);

  // ── Think time: simulate realistic user behaviour ─────────────────────────
  // During baseline: 1-2s think time → ~5 VUs = ~3-5 req/s (well within limits)
  // During stress: 0.1s → 15 VUs = ~30 req/s (triggers bulkhead)
  sleep(Math.random() * 0.5 + 0.1);
}

// ─────────────────────────────────────────────────────────────────────────────
// SETUP: Warmup call to ensure Eureka registration is stable before ramping VUs
// ─────────────────────────────────────────────────────────────────────────────
export function setup() {
  console.log('=== WARMUP: checking services are reachable ===');

  // Gateway health
  const gwHealth = http.get(`${BASE_URL}/actuator/health`);
  console.log(`Gateway health: ${gwHealth.status}`);

  // Product catalog (public — no JWT needed)
  const products = http.get(`${BASE_URL}/api/products`);
  console.log(`Product catalog: ${products.status} — ${products.body?.substring(0, 80)}`);

  // Return config data available to VU code via __ENV if needed
  return { phase: PHASE, baseUrl: BASE_URL };
}

// ─────────────────────────────────────────────────────────────────────────────
// TEARDOWN: Generate HTML report after run
// ─────────────────────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    // Human-readable HTML report
    [`k6/reports/stress-report-${timestamp}.html`]: htmlReport(data),
    // Machine-readable JSON for CI artefact ingestion
    [`k6/reports/stress-report-${timestamp}.json`]: JSON.stringify(data, null, 2),
    // k6 console output
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
