export type TradeRequestBody = {
  accountId: string;
  currencyPair: string;
  side: "BUY" | "SELL";
  orderAmount: number;
  requestedPrice: number;
  simulateCoverFailure: boolean;
  simulateRiskFailure: boolean;
  simulateAccountingFailure: boolean;
  simulateSettlementFailure: boolean;
  simulateNotificationFailure: boolean;
  simulateComplianceFailure: boolean;
  preTradeComplianceFailure: boolean;
};

export type TradeResponse = {
  tradeId: string;
  orderId: string;
  sagaStatus: string;
  correlationId: string;
};

export type ServiceStatusView = {
  name: string;
  status: string;
};

export type EventView = {
  sourceService: string;
  eventType: string;
  topicName: string;
  status: string;
  createdAt: string | null;
  sentAt: string | null;
};

export type ActivityView = {
  serviceName: string;
  activityType: string;
  activityStatus: string;
  detail: string;
  eventType: string | null;
  topicName: string | null;
  createdAt: string;
};

export type BalanceView = {
  currency: string;
  available: number;
  held: number;
};

export type TradeTraceResponse = {
  tradeId: string;
  orderId: string;
  tradeStatus: string;
  sagaStatus: string;
  correlationId: string;
  balance: BalanceView;
  positionSummary: string;
  services: ServiceStatusView[];
  events: EventView[];
  activities: ActivityView[];
};

export type RequestPreset = {
  id: string;
  label: string;
  description: string;
  body: TradeRequestBody;
};

export const requestPresets: RequestPreset[] = [
  {
    id: "success",
    label: "正常完了",
    description: "TradeExecuted から Saga 完了まで、各サービスが順に追いつく流れを確認します。",
    body: {
      accountId: "ACC-LIVE-001",
      currencyPair: "USD/JPY",
      side: "BUY",
      orderAmount: 1000,
      requestedPrice: 151.25,
      simulateCoverFailure: false,
      simulateRiskFailure: false,
      simulateAccountingFailure: false,
      simulateSettlementFailure: false,
      simulateNotificationFailure: false,
      simulateComplianceFailure: false,
      preTradeComplianceFailure: false,
    },
  },
  {
    id: "cover-failure",
    label: "カバー失敗",
    description: "CoverTradeFailed を起点に補償へ入る様子を実際の処理結果で追跡します。",
    body: {
      accountId: "ACC-LIVE-002",
      currencyPair: "EUR/USD",
      side: "BUY",
      orderAmount: 1500,
      requestedPrice: 1.08,
      simulateCoverFailure: true,
      simulateRiskFailure: false,
      simulateAccountingFailure: false,
      simulateSettlementFailure: false,
      simulateNotificationFailure: false,
      simulateComplianceFailure: false,
      preTradeComplianceFailure: false,
    },
  },
  {
    id: "notification-failure",
    label: "通知だけ失敗",
    description: "TradeNotificationFailed を非致命失敗として扱い、取引本体が継続することを確認します。",
    body: {
      accountId: "ACC-LIVE-003",
      currencyPair: "GBP/JPY",
      side: "BUY",
      orderAmount: 800,
      requestedPrice: 190.1,
      simulateCoverFailure: false,
      simulateRiskFailure: false,
      simulateAccountingFailure: false,
      simulateSettlementFailure: false,
      simulateNotificationFailure: true,
      simulateComplianceFailure: false,
      preTradeComplianceFailure: false,
    },
  },
];

export function formatTimestamp(value: string | null): string {
  if (!value) {
    return "-";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleTimeString("ja-JP", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function serviceTone(status: string): "idle" | "running" | "success" | "warning" | "danger" {
  if (
    status === "NOT_STARTED" ||
    status === "WAITING" ||
    status === "WAITING_COVER" ||
    status === "SKIPPED"
  ) {
    return "idle";
  }
  if (
    status === "PENDING" ||
    status === "EXECUTING" ||
    status === "IN_PROGRESS" ||
    status === "COMPENSATING"
  ) {
    return "running";
  }
  if (
    status === "COMPLETED" ||
    status === "EXECUTED" ||
    status === "SENT" ||
    status === "CHECKED" ||
    status === "BOOKED" ||
    status === "POSTED" ||
    status === "RESERVED"
  ) {
    return "success";
  }
  if (status === "COMPENSATED" || status === "FAILED_NON_FATAL" || status === "RETRY") {
    return "warning";
  }
  return "danger";
}

export function activityTone(status: string): "core" | "event" | "service" | "success" | "warning" | "danger" {
  if (status === "RUNNING" || status === "PENDING") {
    return "service";
  }
  if (status === "NEW" || status === "SENT" || status === "RETRY") {
    return "event";
  }
  if (status === "COMPLETED") {
    return "success";
  }
  if (status === "COMPENSATED" || status === "CANCELLED") {
    return "warning";
  }
  if (status === "FAILED" || status === "ERROR") {
    return "danger";
  }
  return "core";
}

export function isTerminalSagaStatus(status: string): boolean {
  return status === "COMPLETED" || status === "CANCELLED" || status === "FAILED";
}
