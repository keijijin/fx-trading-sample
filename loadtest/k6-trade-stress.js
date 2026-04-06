import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: Number(__ENV.VUS ?? 30),
  duration: __ENV.DURATION ?? "60s",
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<1000", "p(99)<2000"],
  },
};

const baseUrl = __ENV.FX_CORE_BASE_URL;

if (!baseUrl) {
  throw new Error("FX_CORE_BASE_URL must be set");
}

export default function () {
  const accountId = `ACC-K6-${__VU}-${__ITER}`;
  const payload = JSON.stringify({
    accountId,
    currencyPair: __ENV.CURRENCY_PAIR ?? "USD/JPY",
    side: __ENV.SIDE ?? "BUY",
    orderAmount: Number(__ENV.ORDER_AMOUNT ?? "1.0"),
    requestedPrice: Number(__ENV.REQUESTED_PRICE ?? "151.25"),
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
    },
  });

  check(response, {
    "accepted or completed request": (r) => r.status === 201 || r.status === 409,
  });

  sleep(Number(__ENV.SLEEP_SECONDS ?? "0"));
}
