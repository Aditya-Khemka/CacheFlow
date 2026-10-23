// k6 load test for CacheFlow.
//
// Baseline (straight to origin):
//   k6 run -e BASE_URL=http://localhost:9000 loadtest.js
// Through the proxy (use CacheFlow's actual port and path style):
//   k6 run -e BASE_URL=http://localhost:8080 loadtest.js
//
// Optional knobs: -e VUS=50 -e DURATION=30s -e KEYS=100

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9000';
const KEYS = parseInt(__ENV.KEYS || '100'); // size of the key space (drives hit rate)

export const options = {
  vus: parseInt(__ENV.VUS || '50'),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

export default function () {
  const id = 1 + Math.floor(Math.random() * KEYS);
  const res = http.get(`${BASE_URL}/item/${id}`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
