// PR #29 - load test for the read-heavy catalogue endpoint (k6).
//
// Run:
//   docker compose up -d                 # postgres + redis
//   ./mvnw spring-boot:run                # API on :8080 (default profile)
//   k6 run scripts/k6-load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  // 20 virtual users for 30s = steady catalogue-read load.
  vus: 20,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],      // <1% errors (resilience in action)
    http_req_duration: ['p(95)<300'],    // 95% of reads under 300ms (cache pays)
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-api-key-orderapi';

export default function () {
  const headers = { 'X-API-Key': API_KEY };
  const res = http.get(`${BASE_URL}/api/v1/products?page=0&size=20`, { headers });
  check(res, {
    'catalogue list returns 200': (r) => r.status === 200,
    'rate-limit headers present': (r) => r.headers['X-RateLimit-Remaining'] !== undefined,
  });
  // Think-time: each VU pauses between requests like a real user.
  sleep(0.1);
}
