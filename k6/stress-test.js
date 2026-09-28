// stress-test.js — Session 23, Lab 19
//
// Stress test: ramps beyond expected capacity to find breaking points
// and observe resilience patterns (Bulkhead -> TimeLimiter -> CircuitBreaker -> Retry).

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate    = new Rate('errors');
const orderLatency = new Trend('order_latency_ms');

export const options = {
  stages: [
    { duration: '20s', target: 5  },  // warm-up
    { duration: '30s', target: 20 },  // exceed Bulkhead (max=10) -> starts rejecting
    { duration: '30s', target: 40 },  // exceed TimeLimiter threshold
    { duration: '30s', target: 60 },  // push Circuit Breaker toward OPEN
    { duration: '20s', target: 0  },  // ramp down -> watch CB transition to HALF_OPEN
  ],
  thresholds: {
    http_req_duration: ['p(99)<5000'],
    'errors':          ['rate<0.80'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT      = __ENV.TEST_JWT  || '';

const headers = {
  'Content-Type':  'application/json',
  ...(JWT ? { 'Authorization': `Bearer ${JWT}` } : {}),
};

export default function () {
  const start = Date.now();

  const payload = JSON.stringify({
    productId:  'PROD-001',
    quantity:   1,
    amount:     100.00,
    customerId: `stress-cust-${__VU}`,
  });

  const res = http.post(`${BASE_URL}/api/orders`, payload, {
    headers,
    timeout: '6s',
  });

  const duration = Date.now() - start;
  orderLatency.add(duration);

  const ok = check(res, {
    'not 5xx server error': (r) => r.status < 500,
    'resilience response or success': (r) =>
      r.status === 200 || r.status === 202 ||
      r.status === 429 || r.status === 503,
  });

  errorRate.add(!ok);
}
