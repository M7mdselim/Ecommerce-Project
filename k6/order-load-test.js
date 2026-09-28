// order-load-test.js — Session 23, Lab 19
//
// Staged load test against the full Order -> Inventory -> Payment chain.
// Establishes a baseline: requests/sec, P95/P99 latency, error rate.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '30s', target: 5  },  // ramp up to 5 VUs
    { duration: '1m',  target: 10 },  // sustain at 10 VUs — expected baseline
    { duration: '30s', target: 0  },  // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<800', 'p(99)<2000'],
    http_req_failed:   ['rate<0.05'],   // <5% errors at baseline load
    'errors':          ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT      = __ENV.TEST_JWT  || '';

const headers = {
  'Content-Type':  'application/json',
  ...(JWT ? { 'Authorization': `Bearer ${JWT}` } : {}),
};

export default function () {
  const payload = JSON.stringify({
    productId: 'PROD-001',
    quantity:  1,
    amount:    100.00,
    customerId: `cust-${__VU}`,
  });

  const res = http.post(`${BASE_URL}/api/orders`, payload, { headers });

  const ok = check(res, {
    'order accepted (200 or 202)': (r) => r.status === 200 || r.status === 202,
    'response has orderId':        (r) => {
      try { return JSON.parse(r.body).orderId !== undefined; }
      catch { return false; }
    },
  });

  errorRate.add(!ok);
  sleep(0.5);
}
