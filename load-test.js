import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ── Metrics ─────────────────────────────────────────
const latency = new Trend('latency_ms', true);
const errors  = new Rate('errors');

// ── Config ──────────────────────────────────────────
export const options = {
  scenarios: {
    // 1. SPIKE — sudden burst (kills thread pools / queues)
    spike_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '5s',  target: 500 },   // 🚨 sudden spike
        { duration: '20s', target: 500 },
        { duration: '5s',  target: 0 },
      ],
      exec: 'writeHeavy',
    },

    // 2. CONSTANT HIGH LOAD — sustained pressure
    sustained_break: {
      executor: 'constant-vus',
      vus: 300,
      duration: '2m',
      startTime: '40s', // starts after spike
      exec: 'writeHeavy',
    },

    // 3. EXTREME BURST — pure RPS attack
    rps_attack: {
      executor: 'constant-arrival-rate',
      rate: 3000, // 🚨 push beyond your current 1500 RPS
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      startTime: '2m30s',
      exec: 'writeHeavy',
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.2'],  // allow failures — we want breaking
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API = `${BASE_URL}/api/v1`;

// The webhook-receiver container (mendhak/http-https-echo) runs on host port 8888.
// The Spring app calls this URL from INSIDE Docker, so use the container service name.
// Override with WEBHOOK_URL env var if your topology differs.
const WEBHOOK_RECEIVER_URL = __ENV.WEBHOOK_URL || 'http://orbit-webhook-receiver:8080';

const JOB_IDS = [];

// ── Seed minimal jobs + register webhooks ───────────
export function setup() {
  for (let i = 0; i < 20; i++) {
    const res = http.post(`${API}/jobs`, JSON.stringify({
      name: `seed-${i}-${Date.now()}`,
      cronExpression: '0 * * * * *',
      target: 'https://api.internal/test',
      status: 'ACTIVE',
    }), { headers: { 'Content-Type': 'application/json' } });

    if (res.status === 201) {
      const job = JSON.parse(res.body);
      JOB_IDS.push(job.id);

      // FIX: Register a webhook for every seeded job.
      // Without this, WebhookService.dispatch() calls findByJobIdAndActiveTrue()
      // which returns an empty list, so it returns immediately — webhook_deliveries
      // and retry_log are never written regardless of how many executions fire.
      const whRes = http.post(
          `${API}/jobs/${job.id}/webhooks`,
          JSON.stringify({
            url: WEBHOOK_RECEIVER_URL,
            secretKey: 'orbit-load-test-secret',
            eventFilter: 'ALL',
          }),
          { headers: { 'Content-Type': 'application/json' } }
      );
      if (whRes.status !== 201) {
        console.warn(`Webhook registration failed for jobId=${job.id}: ${whRes.status} ${whRes.body}`);
      }
    }
  }
  return JOB_IDS;
}

// ── Aggressive workload ─────────────────────────────
export function writeHeavy(jobIds) {
  const jobId = randomItem(jobIds);

  // 🔥 80% execution writes (Kafka + DB stress)
  if (Math.random() < 0.8) {
    execWrite(jobId);
  } else {
    createJob(); // 🔥 also grow DB continuously
  }
}

// ── Execution path (hot path) ───────────────────────
function execWrite(jobId) {
  const payload = JSON.stringify({
    status: 'SUCCESS',
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
  });

  const start = Date.now();
  const res = http.post(`${API}/jobs/${jobId}/executions`, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '5s', // shorter timeout = faster failure detection
  });
  latency.add(Date.now() - start);

  check(res, { 'exec ok': r => r.status === 202 || r.status === 429 });

  errors.add(res.status >= 500);
}

// ── Create path ─────────────────────────────────────
function createJob() {
  const res = http.post(`${API}/jobs`, JSON.stringify({
    name: `break-${Date.now()}-${Math.random()}`,
    cronExpression: '*/5 * * * * *',
    target: 'https://api.internal/break',
    status: 'ACTIVE',
  }), { headers: { 'Content-Type': 'application/json' } });

  check(res, { 'create ok': r => r.status === 201 || r.status === 429 });

  errors.add(res.status >= 500);
}