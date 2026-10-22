import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { sleep } from 'k6';

// Each load generator runs a shard. Start all shards at the same START_EPOCH_MS.
// Example: 20 machines x VUS=5000 yields 100,000 requests at the barrier.
const vus = Number(__ENV.VUS || 100);
const admitted = new Counter('admitted');
const limited = new Counter('limited');
const unexpected = new Rate('unexpected_failure');
export const options = {
  scenarios: { burst: { executor: 'per-vu-iterations', vus, iterations: 1, maxDuration: '60s' } },
  thresholds: {
    unexpected_failure: ['rate<0.01'],
    http_req_duration: ['p(99)<3000'],
  },
  // Keep the small JSON body so HTTP 200 business errors cannot count as success.
  discardResponseBodies: false,
};
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

export default function () {
  const start = Number(__ENV.START_EPOCH_MS || 0);
  // Atomics.wait is unavailable in k6; sleep cooperatively until the shared release time.
  if (start && Date.now() < start) {
    const remaining = (start - Date.now()) / 1000;
    sleep(remaining);
  }
  const response = http.get(`${__ENV.BASE_URL || 'http://localhost:8088'}${__ENV.API_PATH || '/api/movie/list?page=1&limit=10'}`, {
    timeout: '10s', headers: __ENV.TOKEN ? { Authorization: __ENV.TOKEN } : {},
  });
  let success = false;
  if (response.status >= 200 && response.status < 300) {
    try { success = response.json('code') === 1; } catch (_) { /* Invalid JSON is a failure. */ }
  }
  admitted.add(success ? 1 : 0);
  limited.add(response.status === 429 ? 1 : 0);
  unexpected.add(!success && response.status !== 429);
}

export function handleSummary(data) {
  return { [__ENV.RESULT_FILE || 'burst-result.json']: JSON.stringify(data, null, 2) };
}
