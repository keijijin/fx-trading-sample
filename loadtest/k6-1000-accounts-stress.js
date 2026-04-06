import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: Number(__ENV.VUS ?? 100),
  duration: __ENV.DURATION ?? "120s",
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<1500", "p(99)<3000"],
  },
};

const baseUrl = __ENV.FX_CORE_BASE_URL;

if (!baseUrl) {
  throw new Error("FX_CORE_BASE_URL must be set");
}

const accountCount = Number(__ENV.ACCOUNT_COUNT ?? 1000);
const selectionMode = __ENV.ACCOUNT_SELECTION_MODE ?? "round_robin";
const accountPrefix = __ENV.ACCOUNT_PREFIX ?? "ACC-HOT";
const orderAmount = Number(__ENV.ORDER_AMOUNT ?? "1.0");
const requestedPrice = Number(__ENV.REQUESTED_PRICE ?? "151.25");
const currencyPair = __ENV.CURRENCY_PAIR ?? "USD/JPY";
const side = __ENV.SIDE ?? "BUY";
const sleepSeconds = Number(__ENV.SLEEP_SECONDS ?? "0");

const accountIds = Array.from(
  { length: accountCount },
  (_, index) => `${accountPrefix}-${String(index + 1).padStart(4, "0")}`,
);

function pickAccountId() {
  if (selectionMode === "random") {
    return accountIds[Math.floor(Math.random() * accountIds.length)];
  }

  return accountIds[(__ITER + __VU) % accountIds.length];
}

export default function () {
  const payload = JSON.stringify({
    accountId: pickAccountId(),
    currencyPair,
    side,
    orderAmount,
    requestedPrice,
    simulateCoverFailure: (__ENV.SIMULATE_COVER_FAILURE ?? "false") === "true",
    simulateRiskFailure: (__ENV.SIMULATE_RISK_FAILURE ?? "false") === "true",
    simulateAccountingFailure:
      (__ENV.SIMULATE_ACCOUNTING_FAILURE ?? "false") === "true",
    simulateSettlementFailure:
      (__ENV.SIMULATE_SETTLEMENT_FAILURE ?? "false") === "true",
    simulateNotificationFailure:
      (__ENV.SIMULATE_NOTIFICATION_FAILURE ?? "false") === "true",
    simulateComplianceFailure:
      (__ENV.SIMULATE_COMPLIANCE_FAILURE ?? "false") === "true",
    preTradeComplianceFailure:
      (__ENV.PRE_TRADE_COMPLIANCE_FAILURE ?? "false") === "true",
  });

  const response = http.post(`${baseUrl}/api/trades`, payload, {
    headers: {
      "Content-Type": "application/json",
    },
    tags: {
      endpoint: "create-trade",
      account_pool: `${accountCount}`,
      selection_mode: selectionMode,
    },
  });

  check(response, {
    "request accepted or rejected by business rule": (r) =>
      r.status === 201 || r.status === 409,
  });

  sleep(sleepSeconds);
}
