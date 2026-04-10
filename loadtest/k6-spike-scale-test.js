/**
 * Warm → Spike → Cool の 3 相で、短時間の注文スパイクを再現する。
 * 水平スケール（Deployment replicas）を上げたときに入口スループット・エラーが許容内か比較する用途。
 *
 * 環境変数（主要）:
 *   FX_CORE_BASE_URL — 必須
 *   SPIKE_BASE_RATE — 平常時到着率 (req/s), 既定 20
 *   SPIKE_PEAK_RATE — スパイク時到着率 (req/s), 既定 100
 *   WARM_SEC, SPIKE_SEC, COOL_SEC — 各相の秒数, 既定 120 / 60 / 120
 *   PREALLOCATED_VUS, MAX_VUS — 平常・冷却相の VU 上限
 *   SPIKE_PREALLOCATED_VUS, SPIKE_MAX_VUS — スパイク相（高めに）
 *   TRACE_SAMPLE_RATE — 0 で POST のみ（スループット重視）, 0〜1
 *   WARM_P95_MS, WARM_P99_MS, SPIKE_P95_MS, SPIKE_P99_MS, COOL_P95_MS, COOL_P99_MS
 *     — http_req_duration 閾値（ms）。多レプリカ・PoC クラスタでは SPIKE を 5000〜8000 などに上げると exit 99 を減らせる。
 */
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { scenario } from "k6/execution";

export const tradeAccepted = new Counter("trade_accepted");
export const traceSampled = new Counter("trace_sampled");
export const traceTimedOut = new Counter("trace_timed_out");
export const businessFailureRate = new Rate("business_failure_rate");
export const tradeApiLatency = new Trend("trade_api_latency");
export const tradeE2eLatency = new Trend("trade_e2e_latency");

const baseUrl = __ENV.FX_CORE_BASE_URL;
if (!baseUrl) {
  throw new Error("FX_CORE_BASE_URL must be set");
}

const warmSec = Number(__ENV.WARM_SEC ?? 120);
const spikeSec = Number(__ENV.SPIKE_SEC ?? 60);
const coolSec = Number(__ENV.COOL_SEC ?? 120);
const baseRate = Number(__ENV.SPIKE_BASE_RATE ?? 20);
const peakRate = Number(__ENV.SPIKE_PEAK_RATE ?? 100);

const preAlloc = Number(__ENV.PREALLOCATED_VUS ?? 150);
const maxVu = Number(__ENV.MAX_VUS ?? 900);
const spikePre = Number(__ENV.SPIKE_PREALLOCATED_VUS ?? 500);
const spikeMax = Number(__ENV.SPIKE_MAX_VUS ?? 2500);

const accountCount = Number(__ENV.ACCOUNT_COUNT ?? 1000);
const accountPrefix = __ENV.ACCOUNT_PREFIX ?? "ACC-SPIKE";
const traceTimeoutMs = Number(__ENV.TRACE_TIMEOUT_MS ?? 60000);
const tracePollIntervalMs = Number(__ENV.TRACE_POLL_INTERVAL_MS ?? 500);
const traceSampleRate = Number(__ENV.TRACE_SAMPLE_RATE ?? 0);

const spikeStartAfter = `${warmSec}s`;
const coolStartAfter = `${warmSec + spikeSec}s`;

const thrWarmP95 = __ENV.WARM_P95_MS ?? "1000";
const thrWarmP99 = __ENV.WARM_P99_MS ?? "3000";
const thrSpikeP95 = __ENV.SPIKE_P95_MS ?? "3000";
const thrSpikeP99 = __ENV.SPIKE_P99_MS ?? "8000";
const thrCoolP95 = __ENV.COOL_P95_MS ?? "1000";
const thrCoolP99 = __ENV.COOL_P99_MS ?? "3000";

const TERMINAL_SAGA_STATUSES = new Set(["COMPLETED", "CANCELLED", "FAILED"]);

export const options = {
  scenarios: {
    warm: {
      executor: "constant-arrival-rate",
      rate: baseRate,
      timeUnit: "1s",
      duration: `${warmSec}s`,
      preAllocatedVUs: preAlloc,
      maxVUs: maxVu,
      exec: "tradePhase",
      startTime: "0s",
    },
    spike: {
      executor: "constant-arrival-rate",
      rate: peakRate,
      timeUnit: "1s",
      duration: `${spikeSec}s`,
      preAllocatedVUs: spikePre,
      maxVUs: spikeMax,
      exec: "tradePhase",
      startTime: spikeStartAfter,
    },
    cool: {
      executor: "constant-arrival-rate",
      rate: baseRate,
      timeUnit: "1s",
      duration: `${coolSec}s`,
      preAllocatedVUs: preAlloc,
      maxVUs: maxVu,
      exec: "tradePhase",
      startTime: coolStartAfter,
    },
  },
  thresholds: {
    "http_req_failed{scenario:warm}": ["rate<0.02"],
    "http_req_failed{scenario:spike}": ["rate<0.08"],
    "http_req_failed{scenario:cool}": ["rate<0.02"],
    "http_req_duration{scenario:warm}": [`p(95)<${thrWarmP95}`, `p(99)<${thrWarmP99}`],
    "http_req_duration{scenario:spike}": [`p(95)<${thrSpikeP95}`, `p(99)<${thrSpikeP99}`],
    "http_req_duration{scenario:cool}": [`p(95)<${thrCoolP95}`, `p(99)<${thrCoolP99}`],
    trade_accepted: ["count>0"],
  },
};

function pickAccountId() {
  const accountNo = ((__ITER + __VU) % accountCount) + 1;
  return `${accountPrefix}-${String(accountNo).padStart(4, "0")}`;
}

function makePayload() {
  const ph = scenario.name;
  return {
    orderId: `ORD-SPIKE-${ph}-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 100000)}`,
    accountId: pickAccountId(),
    currencyPair: __ENV.CURRENCY_PAIR ?? "USD/JPY",
    side: __ENV.SIDE ?? "BUY",
    orderAmount: Number(__ENV.ORDER_AMOUNT ?? "1.0"),
    requestedPrice: Number(__ENV.REQUESTED_PRICE ?? "151.25"),
    simulateCoverFailure: false,
    simulateRiskFailure: false,
    simulateAccountingFailure: false,
    simulateSettlementFailure: false,
    simulateNotificationFailure: false,
    simulateComplianceFailure: false,
    preTradeComplianceFailure: false,
  };
}

function traceFromE2ePayload(body) {
  const sagaStatus = body.sagaStatus ?? body.saga_status;
  const notificationStatus = body.notificationStatus ?? body.notification_status;
  return {
    sagaStatus,
    saga_status: sagaStatus,
    services: [{ name: "Notification", status: notificationStatus }],
  };
}

function shouldSampleTrace() {
  if (traceSampleRate <= 0) {
    return false;
  }
  if (traceSampleRate >= 1) {
    return true;
  }
  return Math.random() < traceSampleRate;
}

function waitForTerminal(tradeId, startedAtMs) {
  traceSampled.add(1);
  const deadline = Date.now() + traceTimeoutMs;
  while (Date.now() < deadline) {
    const statusResponse = http.get(`${baseUrl}/api/trades/${tradeId}/e2e-status`, {
      headers: { Accept: "application/json" },
      tags: { endpoint: "trade-e2e-status" },
      timeout: `${Math.max(tracePollIntervalMs, 1000)}ms`,
    });

    if (statusResponse.status === 200) {
      const trace = traceFromE2ePayload(statusResponse.json());
      const sagaStatus = trace.sagaStatus ?? trace.saga_status;
      if (TERMINAL_SAGA_STATUSES.has(sagaStatus)) {
        tradeE2eLatency.add(Date.now() - startedAtMs, { scenario: scenario.name });
        businessFailureRate.add(sagaStatus !== "COMPLETED");
        return;
      }
    }
    sleep(tracePollIntervalMs / 1000);
  }
  traceTimedOut.add(1);
  businessFailureRate.add(true);
}

export function tradePhase() {
  const payload = makePayload();
  const startedAtMs = Date.now();
  const response = http.post(`${baseUrl}/api/trades`, JSON.stringify(payload), {
    headers: { "Content-Type": "application/json" },
    tags: { endpoint: "create-trade" },
    timeout: "30s",
  });

  const accepted = check(response, {
    "trade accepted": (r) => r.status === 201,
  });

  if (!accepted) {
    businessFailureRate.add(true);
    return;
  }

  tradeApiLatency.add(response.timings.duration, { phase: scenario.name });
  tradeAccepted.add(1);

  if (!shouldSampleTrace()) {
    return;
  }

  const body = response.json();
  waitForTerminal(body.tradeId, startedAtMs);
}
