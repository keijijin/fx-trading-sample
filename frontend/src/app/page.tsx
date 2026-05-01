"use client";

import { useEffect, useMemo, useState } from "react";
import {
  activityTone,
  formatTimestamp,
  isTerminalSagaStatus,
  requestPresets,
  serviceTone,
  type TradeRequestBody,
  type TradeResponse,
  type TradeTraceResponse,
  type ServiceStatusView,
} from "@/lib/liveTrace";
import styles from "./page.module.css";

const toneClassMap = {
  core: styles.toneCore,
  event: styles.toneEvent,
  service: styles.toneService,
  success: styles.toneSuccess,
  warning: styles.toneWarning,
  danger: styles.toneDanger,
} as const;

const serviceToneClassMap = {
  idle: styles.serviceIdle,
  running: styles.serviceRunning,
  success: styles.serviceSuccess,
  warning: styles.serviceWarning,
  danger: styles.serviceDanger,
} as const;

type FlowTone = "idle" | "running" | "success" | "warning" | "danger";

type FlowStep = {
  key: string;
  label: string;
  detail: string;
  status: string;
  tone: FlowTone;
};

type ArchitectureNode = {
  id: string;
  label: string;
  detail: string;
  x: number;
  y: number;
  tone: FlowTone;
  status: string;
  compact?: boolean;
};

type ArchitectureEdge = {
  from: string;
  to: string;
  tone: FlowTone;
};

function findServiceStatus(trace: TradeTraceResponse | null, names: string[]): string {
  if (!trace) {
    return "NOT_STARTED";
  }
  const service = trace.services.find((item) => names.includes(item.name));
  return service?.status ?? "NOT_STARTED";
}

function eventStatus(trace: TradeTraceResponse | null, eventType: string): string {
  if (!trace) {
    return "NOT_STARTED";
  }
  const matches = trace.events.filter((event) => event.eventType === eventType);
  if (matches.some((event) => event.status === "ERROR")) {
    return "ERROR";
  }
  if (matches.some((event) => event.status === "RETRY")) {
    return "RETRY";
  }
  if (matches.some((event) => event.status === "SENT")) {
    return "SENT";
  }
  if (matches.some((event) => event.status === "IN_PROGRESS")) {
    return "IN_PROGRESS";
  }
  if (matches.some((event) => event.status === "NEW")) {
    return "NEW";
  }
  return "NOT_STARTED";
}

function multiEventStatus(trace: TradeTraceResponse | null, eventTypes: string[]): string {
  if (!trace) {
    return "NOT_STARTED";
  }
  const statuses = eventTypes.map((eventType) => eventStatus(trace, eventType));
  if (statuses.some((status) => status === "ERROR")) {
    return "ERROR";
  }
  if (statuses.some((status) => status === "RETRY")) {
    return "RETRY";
  }
  if (statuses.some((status) => status === "IN_PROGRESS")) {
    return "IN_PROGRESS";
  }
  if (statuses.some((status) => status === "NEW")) {
    return "NEW";
  }
  if (statuses.some((status) => status === "SENT")) {
    return "SENT";
  }
  return "NOT_STARTED";
}

function flowToneForStatus(status: string): FlowTone {
  if (
    status === "NOT_STARTED" ||
    status === "-" ||
    status === "WAITING" ||
    status === "WAITING_COVER" ||
    status === "SKIPPED"
  ) {
    return "idle";
  }
  if (
    status === "PENDING" ||
    status === "IN_PROGRESS" ||
    status === "NEW" ||
    status === "EXECUTING" ||
    status === "COMPENSATING"
  ) {
    return "running";
  }
  if (
    status === "COMPLETED" ||
    status === "EXECUTED" ||
    status === "BOOKED" ||
    status === "POSTED" ||
    status === "RESERVED" ||
    status === "CHECKED" ||
    status === "SENT"
  ) {
    return "success";
  }
  if (status === "COMPENSATED" || status === "CANCELLED" || status === "FAILED_NON_FATAL" || status === "RETRY") {
    return "warning";
  }
  return "danger";
}

function mergeTone(...tones: FlowTone[]): FlowTone {
  if (tones.includes("danger")) {
    return "danger";
  }
  if (tones.includes("warning")) {
    return "warning";
  }
  if (tones.includes("running")) {
    return "running";
  }
  if (tones.includes("success")) {
    return "success";
  }
  return "idle";
}

export default function Home() {
  const [presetId, setPresetId] = useState(requestPresets[0].id);
  const [requestBody, setRequestBody] = useState<TradeRequestBody>(
    requestPresets[0].body,
  );
  const [trade, setTrade] = useState<TradeResponse | null>(null);
  const [trace, setTrace] = useState<TradeTraceResponse | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isPolling, setIsPolling] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedPreset = useMemo(
    () => requestPresets.find((item) => item.id === presetId) ?? requestPresets[0],
    [presetId],
  );

  useEffect(() => {
    if (!trade?.tradeId) {
      return;
    }

    let cancelled = false;

    const poll = async () => {
      try {
        const response = await fetch(`/api/trades/${trade.tradeId}/trace`, {
          cache: "no-store",
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
          const msg =
            (typeof payload?.message === "string" && payload.message) ||
            (typeof payload?.error === "string" && payload.error) ||
            `trace fetch failed (HTTP ${response.status})`;
          throw new Error(msg);
        }
        if (cancelled) {
          return;
        }
        setTrace(payload as TradeTraceResponse);
        setError(null);
        setIsPolling(!isTerminalSagaStatus(payload.sagaStatus));
      } catch (pollError) {
        if (!cancelled) {
          setError(
            pollError instanceof Error
              ? pollError.message
              : "トレースの取得に失敗しました。",
          );
          setIsPolling(false);
        }
      }
    };

    void poll();
    const timerId = window.setInterval(() => {
      void poll();
    }, 1000);

    return () => {
      cancelled = true;
      window.clearInterval(timerId);
    };
  }, [trade?.tradeId]);

  const totalBalance = trace
    ? trace.balance.available + trace.balance.held
    : 0;
  const availableRate =
    totalBalance === 0 ? 0 : (trace!.balance.available / totalBalance) * 100;
  const latestActivity = trace?.activities.at(-1) ?? null;
  const liveFlow = useMemo<FlowStep[]>(() => {
    const acidStatus = trade
      ? trace?.tradeStatus ?? trade.sagaStatus ?? "EXECUTING"
      : "NOT_STARTED";
    const outboxStatus = eventStatus(trace, "TradeExecuted");
    const coverStatus = findServiceStatus(trace, ["Cover", "Cover Service"]);
    const riskStatus = findServiceStatus(trace, ["Risk", "Risk Service"]);
    const accountingStatus = findServiceStatus(trace, ["Accounting", "Accounting Service"]);
    const settlementStatus = findServiceStatus(trace, ["Settlement", "Settlement Service"]);
    const notificationStatus = findServiceStatus(trace, ["Notification", "Notification Service"]);
    const complianceStatus = findServiceStatus(trace, ["Compliance", "Compliance Service"]);
    const sagaStatus = trace?.sagaStatus ?? trade?.sagaStatus ?? "NOT_STARTED";

    return [
      {
        key: "acid",
        label: "ACID Commit",
        detail: "注文確定・残高拘束",
        status: acidStatus,
        tone: flowToneForStatus(acidStatus),
      },
      {
        key: "outbox",
        label: "Outbox",
        detail: "TradeExecuted 発行",
        status: outboxStatus,
        tone: flowToneForStatus(outboxStatus),
      },
      {
        key: "cover",
        label: "Cover",
        detail: "カバー約定",
        status: coverStatus,
        tone: flowToneForStatus(coverStatus),
      },
      {
        key: "risk",
        label: "Risk",
        detail: "リスク反映",
        status: riskStatus,
        tone: flowToneForStatus(riskStatus),
      },
      {
        key: "accounting",
        label: "Accounting",
        detail: "会計仕訳",
        status: accountingStatus,
        tone: flowToneForStatus(accountingStatus),
      },
      {
        key: "settlement",
        label: "Settlement",
        detail: "受渡予約",
        status: settlementStatus,
        tone: flowToneForStatus(settlementStatus),
      },
      {
        key: "notification",
        label: "Notification",
        detail: "顧客通知",
        status: notificationStatus,
        tone: flowToneForStatus(notificationStatus),
      },
      {
        key: "compliance",
        label: "Compliance",
        detail: "事後審査",
        status: complianceStatus,
        tone: flowToneForStatus(complianceStatus),
      },
      {
        key: "saga",
        label: "Saga",
        detail: "最終状態",
        status: sagaStatus,
        tone: flowToneForStatus(sagaStatus),
      },
    ];
  }, [trade, trace]);
  const completedFlowCount = liveFlow.filter((step) =>
    ["success", "warning"].includes(step.tone),
  ).length;
  const liveProgressPercent =
    liveFlow.length === 0 ? 0 : Math.round((completedFlowCount / liveFlow.length) * 100);
  const architecture = useMemo(() => {
    const acidStatus = trade
      ? trace?.tradeStatus ?? trade.sagaStatus ?? "EXECUTING"
      : "NOT_STARTED";
    const outboxStatus = multiEventStatus(trace, [
      "TradeExecuted",
      "CoverTradeBooked",
      "CoverTradeFailed",
      "RiskUpdated",
      "RiskUpdateFailed",
      "AccountingPosted",
      "AccountingPostFailed",
      "SettlementReserved",
      "SettlementReserveFailed",
      "TradeNotificationSent",
      "TradeNotificationFailed",
      "ComplianceChecked",
      "ComplianceCheckFailed",
    ]);
    const tradeTopicStatus = eventStatus(trace, "TradeExecuted");
    const coverTopicStatus = multiEventStatus(trace, [
      "CoverTradeBooked",
      "CoverTradeFailed",
      "CoverTradeReversed",
    ]);
    const riskTopicStatus = multiEventStatus(trace, [
      "RiskUpdated",
      "RiskUpdateFailed",
      "RiskReversed",
    ]);
    const accountingTopicStatus = multiEventStatus(trace, [
      "AccountingPosted",
      "AccountingPostFailed",
      "AccountingReversed",
    ]);
    const settlementTopicStatus = multiEventStatus(trace, [
      "SettlementStartRequested",
      "SettlementReserved",
      "SettlementReserveFailed",
      "SettlementCancelled",
    ]);
    const notificationTopicStatus = multiEventStatus(trace, [
      "TradeNotificationSent",
      "TradeNotificationFailed",
      "CorrectionNoticeSent",
    ]);
    const complianceTopicStatus = multiEventStatus(trace, [
      "ComplianceChecked",
      "ComplianceCheckFailed",
    ]);
    const compensationTopicStatus = multiEventStatus(trace, [
      "ReverseCoverTradeRequested",
      "ReverseRiskRequested",
      "ReverseAccountingRequested",
      "CancelSettlementRequested",
      "SendCorrectionNoticeRequested",
    ]);

    const coverStatus = findServiceStatus(trace, ["Cover", "Cover Service"]);
    const riskStatus = findServiceStatus(trace, ["Risk", "Risk Service"]);
    const accountingStatus = findServiceStatus(trace, ["Accounting", "Accounting Service"]);
    const settlementStatus = findServiceStatus(trace, ["Settlement", "Settlement Service"]);
    const notificationStatus = findServiceStatus(trace, ["Notification", "Notification Service"]);
    const complianceStatus = findServiceStatus(trace, ["Compliance", "Compliance Service"]);
    const sagaStatus = trace?.sagaStatus ?? trade?.sagaStatus ?? "NOT_STARTED";

    const nodes: ArchitectureNode[] = [
      {
        id: "client",
        label: "Client / Channel",
        detail: "UI 操作",
        x: 6,
        y: 52,
        status: trade ? "ACTIVE" : "NOT_STARTED",
        tone: trade ? "success" : "idle",
        compact: true,
      },
      {
        id: "gateway",
        label: "API Gateway",
        detail: "Next.js API",
        x: 17,
        y: 52,
        status: trade ? "ACTIVE" : "NOT_STARTED",
        tone: trade ? "success" : "idle",
        compact: true,
      },
      {
        id: "core",
        label: "FX Core",
        detail: "ACID 領域",
        x: 30,
        y: 52,
        status: acidStatus,
        tone: flowToneForStatus(acidStatus),
      },
      {
        id: "coreDb",
        label: "Core DB",
        detail: "trade / balance",
        x: 43,
        y: 20,
        status: acidStatus,
        tone: flowToneForStatus(acidStatus),
        compact: true,
      },
      {
        id: "outbox",
        label: "outbox_event",
        detail: "event store",
        x: 43,
        y: 78,
        status: outboxStatus,
        tone: flowToneForStatus(outboxStatus),
        compact: true,
      },
      {
        id: "publisher",
        label: "Outbox Publisher",
        detail: "parallel / claimAndLoad",
        x: 56,
        y: 52,
        status: outboxStatus,
        tone: flowToneForStatus(outboxStatus),
      },
      {
        id: "tradeTopic",
        label: "Kafka trade-events",
        detail: "TradeExecuted",
        x: 68,
        y: 80,
        status: tradeTopicStatus,
        tone: flowToneForStatus(tradeTopicStatus),
      },
      {
        id: "cover",
        label: "Cover Service",
        detail: "カバー約定",
        x: 79,
        y: 70,
        status: coverStatus,
        tone: flowToneForStatus(coverStatus),
      },
      {
        id: "notification",
        label: "Notification",
        detail: "顧客通知",
        x: 79,
        y: 18,
        status: notificationStatus,
        tone: flowToneForStatus(notificationStatus),
      },
      {
        id: "compliance",
        label: "Compliance",
        detail: "事後審査",
        x: 79,
        y: 34,
        status: complianceStatus,
        tone: flowToneForStatus(complianceStatus),
      },
      {
        id: "coverTopic",
        label: "Kafka cover-events",
        detail: "cover result",
        x: 89,
        y: 62,
        status: coverTopicStatus,
        tone: flowToneForStatus(coverTopicStatus),
      },
      {
        id: "risk",
        label: "Risk",
        detail: "リスク反映",
        x: 85,
        y: 46,
        status: riskStatus,
        tone: flowToneForStatus(riskStatus),
      },
      {
        id: "accounting",
        label: "Accounting",
        detail: "会計仕訳",
        x: 95,
        y: 46,
        status: accountingStatus,
        tone: flowToneForStatus(accountingStatus),
      },
      {
        id: "riskTopic",
        label: "Kafka risk-events",
        detail: "risk result",
        x: 84,
        y: 28,
        status: riskTopicStatus,
        tone: flowToneForStatus(riskTopicStatus),
        compact: true,
      },
      {
        id: "accountingTopic",
        label: "Kafka accounting-events",
        detail: "accounting result",
        x: 96,
        y: 26,
        status: accountingTopicStatus,
        tone: flowToneForStatus(accountingTopicStatus),
        compact: true,
      },
      {
        id: "saga",
        label: "Trade Saga Service",
        detail: "状態管理 / 補償判定",
        x: 88,
        y: 74,
        status: sagaStatus,
        tone: flowToneForStatus(sagaStatus),
      },
      {
        id: "sagaDb",
        label: "trade_saga DB",
        detail: "state store",
        x: 98,
        y: 14,
        status: sagaStatus,
        tone: flowToneForStatus(sagaStatus),
        compact: true,
      },
      {
        id: "settlementTopic",
        label: "Kafka settlement-events",
        detail: "settlement flow",
        x: 98,
        y: 68,
        status: settlementTopicStatus,
        tone: flowToneForStatus(settlementTopicStatus),
      },
      {
        id: "settlement",
        label: "Settlement",
        detail: "受渡予約",
        x: 98,
        y: 84,
        status: settlementStatus,
        tone: flowToneForStatus(settlementStatus),
      },
      {
        id: "compTopic",
        label: "Kafka compensation-events",
        detail: "補償要求",
        x: 92,
        y: 92,
        status: compensationTopicStatus,
        tone: flowToneForStatus(compensationTopicStatus),
      },
      {
        id: "notificationTopic",
        label: "Kafka notification-events",
        detail: "notification result",
        x: 92,
        y: 8,
        status: notificationTopicStatus,
        tone: flowToneForStatus(notificationTopicStatus),
        compact: true,
      },
      {
        id: "complianceTopic",
        label: "Kafka compliance-events",
        detail: "compliance result",
        x: 70,
        y: 4,
        status: complianceTopicStatus,
        tone: flowToneForStatus(complianceTopicStatus),
        compact: true,
      },
    ];

    const toneById = Object.fromEntries(nodes.map((node) => [node.id, node.tone])) as Record<
      string,
      FlowTone
    >;

    const edge = (from: string, to: string): ArchitectureEdge => ({
      from,
      to,
      tone: mergeTone(toneById[from], toneById[to]),
    });

    const edges: ArchitectureEdge[] = [
      edge("client", "gateway"),
      edge("gateway", "core"),
      edge("core", "coreDb"),
      edge("core", "outbox"),
      edge("outbox", "publisher"),
      edge("publisher", "tradeTopic"),
      edge("tradeTopic", "cover"),
      edge("tradeTopic", "notification"),
      edge("tradeTopic", "compliance"),
      edge("cover", "coverTopic"),
      edge("coverTopic", "risk"),
      edge("coverTopic", "accounting"),
      edge("coverTopic", "saga"),
      edge("risk", "riskTopic"),
      edge("riskTopic", "saga"),
      edge("accounting", "accountingTopic"),
      edge("accountingTopic", "saga"),
      edge("notification", "notificationTopic"),
      edge("notificationTopic", "saga"),
      edge("compliance", "complianceTopic"),
      edge("complianceTopic", "saga"),
      edge("saga", "sagaDb"),
      edge("saga", "settlementTopic"),
      edge("settlementTopic", "settlement"),
      edge("settlement", "saga"),
      edge("saga", "compTopic"),
      edge("compTopic", "cover"),
      edge("compTopic", "risk"),
      edge("compTopic", "accounting"),
      edge("compTopic", "settlement"),
      edge("compTopic", "notification"),
    ];

    return { nodes, edges };
  }, [trade, trace]);

  const handlePresetChange = (nextPresetId: string) => {
    const preset = requestPresets.find((item) => item.id === nextPresetId);
    if (!preset) {
      return;
    }
    setPresetId(nextPresetId);
    setRequestBody(preset.body);
  };

  const toggleFlag = (key: keyof TradeRequestBody) => {
    setRequestBody((current) => ({
      ...current,
      [key]: !current[key],
    }));
  };

  const submitTrade = async () => {
    setIsSubmitting(true);
    setIsPolling(true);
    setError(null);
    setTrade(null);
    setTrace(null);

    try {
      const response = await fetch("/api/trades", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(requestBody),
      });

      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.message ?? "取引の送信に失敗しました。");
      }

      setTrade(payload as TradeResponse);
    } catch (submitError) {
      setError(
        submitError instanceof Error
          ? submitError.message
          : "取引の送信に失敗しました。",
      );
      setIsPolling(false);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className={styles.page}>
      <main className={styles.main}>
        <section className={styles.hero}>
          <div className={styles.heroCopy}>
            <p className={styles.eyebrow}>FX Transaction Explorer</p>
            <h1>実リクエストを投げて、分散トランザクションを追跡する UI</h1>
            <p className={styles.lead}>
              この画面はバックエンドへ実際に注文を送り、`FX Core Service`
              が返した `tradeId` を使って `trade_saga`、`outbox_event`、
              `trade_activity` を追跡します。表示は紙芝居ではなく、実システムの進行そのものです。
            </p>
          </div>

          <div className={styles.heroPanels}>
            <article className={styles.summaryCard}>
              <span className={styles.cardLabel}>Live Request</span>
              <h2>本当に取引 API を叩く</h2>
              <p>
                `POST /api/trades` を実行し、ACID 領域の確定と Outbox 登録を
                実際に発生させます。
              </p>
            </article>

            <article className={styles.summaryCard}>
              <span className={styles.cardLabel}>Live Trace</span>
              <h2>各サービスの進行を DB から追う</h2>
              <p>
                Saga 状態、サービス状態、イベント発行履歴、処理アクティビティを
                ポーリングして可視化します。
              </p>
            </article>
          </div>
        </section>

        <section className={styles.heroStats}>
          <article className={styles.heroStatCard}>
            <span className={styles.cardLabel}>Progress</span>
            <strong>{liveProgressPercent}%</strong>
            <p>現在のトレードに対して、フロー全体のどこまで進んだかを可視化します。</p>
          </article>
          <article className={styles.heroStatCard}>
            <span className={styles.cardLabel}>Latest</span>
            <strong>{latestActivity?.activityType ?? "待機中"}</strong>
            <p>{latestActivity?.detail ?? "ボタンを押すと、最新の処理がここに出ます。"}</p>
          </article>
          <article className={styles.heroStatCard}>
            <span className={styles.cardLabel}>Terminal</span>
            <strong>{trace?.sagaStatus ?? trade?.sagaStatus ?? "-"}</strong>
            <p>完了・取消・失敗までの到達状況をリアルタイムで確認できます。</p>
          </article>
        </section>

        <section className={styles.controls}>
          <div className={styles.scenarioTabs}>
            {requestPresets.map((item) => {
              const isActive = item.id === selectedPreset.id;

              return (
                <button
                  key={item.id}
                  type="button"
                  className={`${styles.scenarioTab} ${
                    isActive ? styles.scenarioTabActive : ""
                  }`}
                  onClick={() => handlePresetChange(item.id)}
                >
                  <span>{item.label}</span>
                  <small>{item.description}</small>
                </button>
              );
            })}
          </div>

          <article className={styles.controlPanel}>
            <div className={styles.progressHeader}>
              <div>
                <p className={styles.cardLabel}>Request Builder</p>
                <h2>{selectedPreset.label} を実行する</h2>
              </div>
              <p className={styles.stepCounter}>`podman compose` 稼働中の backend を利用</p>
            </div>

            <p className={styles.stepDetail}>{selectedPreset.description}</p>

            <div className={styles.formGrid}>
              <div className={styles.metricPanel}>
                <span>Account</span>
                <strong>{requestBody.accountId}</strong>
              </div>
              <div className={styles.metricPanel}>
                <span>Pair</span>
                <strong>{requestBody.currencyPair}</strong>
              </div>
              <div className={styles.metricPanel}>
                <span>Side</span>
                <strong>{requestBody.side}</strong>
              </div>
              <div className={styles.metricPanel}>
                <span>Amount / Price</span>
                <strong>
                  {requestBody.orderAmount} @ {requestBody.requestedPrice}
                </strong>
              </div>
            </div>

            <div className={styles.toggleGrid}>
              {(
                [
                  ["simulateCoverFailure", "Cover 失敗"],
                  ["simulateRiskFailure", "Risk 失敗"],
                  ["simulateAccountingFailure", "Accounting 失敗"],
                  ["simulateSettlementFailure", "Settlement 失敗"],
                  ["simulateNotificationFailure", "Notification 失敗"],
                  ["simulateComplianceFailure", "Compliance 失敗"],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  className={`${styles.toggleButton} ${
                    requestBody[key] ? styles.toggleButtonActive : ""
                  }`}
                  onClick={() => toggleFlag(key)}
                >
                  {label}
                </button>
              ))}
            </div>

            <div className={styles.actionRow}>
              <button
                type="button"
                className={styles.submitButton}
                onClick={() => void submitTrade()}
                disabled={isSubmitting}
              >
                {isSubmitting ? "送信中..." : "この条件でトランザクションを実行"}
              </button>

              <div className={styles.statusLine}>
                <span className={styles.liveBadge}>
                  {isPolling ? "LIVE POLLING" : "IDLE"}
                </span>
                {trade && (
                  <span className={styles.mutedText}>tradeId: {trade.tradeId}</span>
                )}
              </div>
            </div>

            {error && <p className={styles.errorText}>{error}</p>}
          </article>
        </section>

        <section className={styles.flowCard}>
          <div className={styles.sectionHeader}>
            <div>
              <p className={styles.cardLabel}>Live Flow</p>
              <h2>トランザクション進行フロー</h2>
            </div>
            <div className={styles.flowMeta}>
              <span className={styles.liveBadge}>
                <span className={styles.pulseDot} />
                {isPolling ? "REALTIME" : "STANDBY"}
              </span>
              <span className={styles.smallNote}>{liveProgressPercent}% complete</span>
            </div>
          </div>

          <div className={styles.progressBar}>
            <div
              className={styles.progressFill}
              style={{ width: `${liveProgressPercent}%` }}
            />
          </div>

          <div className={styles.flowGrid}>
            {liveFlow.map((step, index) => (
              <div key={step.key} className={styles.flowSegment}>
                <article
                  className={`${styles.flowNode} ${
                    step.tone === "success"
                      ? styles.flowNodeSuccess
                      : step.tone === "running"
                        ? styles.flowNodeRunning
                        : step.tone === "warning"
                          ? styles.flowNodeWarning
                          : step.tone === "danger"
                            ? styles.flowNodeDanger
                            : styles.flowNodeIdle
                  }`}
                >
                  <span className={styles.flowStepNo}>{index + 1}</span>
                  <div>
                    <h3>{step.label}</h3>
                    <p>{step.detail}</p>
                  </div>
                  <strong>{step.status}</strong>
                </article>
                {index < liveFlow.length - 1 && <div className={styles.flowConnector} />}
              </div>
            ))}
          </div>

          <div className={styles.flowLegend}>
            <span className={`${styles.legendChip} ${styles.flowNodeIdle}`}>未開始</span>
            <span className={`${styles.legendChip} ${styles.flowNodeRunning}`}>進行中</span>
            <span className={`${styles.legendChip} ${styles.flowNodeSuccess}`}>完了</span>
            <span className={`${styles.legendChip} ${styles.flowNodeWarning}`}>補償 / 非致命</span>
            <span className={`${styles.legendChip} ${styles.flowNodeDanger}`}>失敗</span>
          </div>
        </section>

        <section className={styles.architectureCard}>
          <div className={styles.sectionHeader}>
            <div>
              <p className={styles.cardLabel}>System Map</p>
              <h2>全体アーキテクチャと現在位置</h2>
            </div>
            <div className={styles.flowMeta}>
              <span className={styles.smallNote}>
                実データをもとに各ノードを色分けしています
              </span>
            </div>
          </div>

          <div className={styles.architectureCanvas}>
            <svg className={styles.architectureSvg} viewBox="0 0 100 100" preserveAspectRatio="none">
              {architecture.edges.map((edge, index) => {
                const from = architecture.nodes.find((node) => node.id === edge.from);
                const to = architecture.nodes.find((node) => node.id === edge.to);
                if (!from || !to) {
                  return null;
                }
                return (
                  <line
                    key={`${edge.from}-${edge.to}-${index}`}
                    x1={from.x}
                    y1={from.y}
                    x2={to.x}
                    y2={to.y}
                    className={`${styles.architectureEdge} ${
                      edge.tone === "success"
                        ? styles.architectureEdgeSuccess
                        : edge.tone === "running"
                          ? styles.architectureEdgeRunning
                          : edge.tone === "warning"
                            ? styles.architectureEdgeWarning
                            : edge.tone === "danger"
                              ? styles.architectureEdgeDanger
                              : styles.architectureEdgeIdle
                    }`}
                  />
                );
              })}
            </svg>

            {architecture.nodes.map((node) => (
              <article
                key={node.id}
                className={`${styles.architectureNode} ${
                  node.compact ? styles.architectureNodeCompact : ""
                } ${
                  node.tone === "success"
                    ? styles.architectureNodeSuccess
                    : node.tone === "running"
                      ? styles.architectureNodeRunning
                      : node.tone === "warning"
                        ? styles.architectureNodeWarning
                        : node.tone === "danger"
                          ? styles.architectureNodeDanger
                          : styles.architectureNodeIdle
                }`}
                style={{ left: `${node.x}%`, top: `${node.y}%` }}
              >
                <h3>{node.label}</h3>
                <p>{node.detail}</p>
                <strong>{node.status}</strong>
              </article>
            ))}
          </div>
        </section>

        <section className={styles.contentGrid}>
          <article className={styles.timelineCard}>
            <div className={styles.sectionHeader}>
              <div>
                <p className={styles.cardLabel}>Activity Timeline</p>
                <h2>実際に起きた処理の履歴</h2>
              </div>
              <p className={styles.smallNote}>`trade_activity` をポーリング表示</p>
            </div>

            <div className={styles.timeline}>
              {!trace && (
                <div className={styles.emptyState}>
                  まだ実行されていません。ボタンを押すと、ここに各サービスの実行履歴が流れます。
                </div>
              )}

              {trace?.activities.map((activity, index) => (
                <div
                  key={`${activity.serviceName}-${activity.activityType}-${activity.createdAt}-${index}`}
                  className={`${styles.timelineItem} ${styles.timelineDone}`}
                >
                  <div className={styles.timelineMarker}>
                    <span>{index + 1}</span>
                  </div>

                  <div className={styles.timelineBody}>
                    <div className={styles.timelineTopRow}>
                      <span className={styles.laneBadge}>{activity.serviceName}</span>
                      <span
                        className={`${styles.toneBadge} ${
                          toneClassMap[activityTone(activity.activityStatus)]
                        }`}
                      >
                        {activity.activityStatus}
                      </span>
                    </div>

                    <h3>{activity.activityType}</h3>
                    <p>{activity.detail}</p>

                    <div className={styles.metaRow}>
                      <span className={styles.metaChip}>
                        At: {formatTimestamp(activity.createdAt)}
                      </span>
                      {activity.eventType && (
                        <span className={styles.metaChip}>
                          Event: {activity.eventType}
                        </span>
                      )}
                      {activity.topicName && (
                        <span className={styles.metaChip}>
                          Topic: {activity.topicName}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </article>

          <div className={styles.dashboardColumn}>
            <article className={styles.statusCard}>
              <div className={styles.sectionHeader}>
                <div>
                  <p className={styles.cardLabel}>Live Snapshot</p>
                  <h2>{trace ? "現在のトランザクション状態" : "実行前"}</h2>
                </div>
                {trace && (
                  <span className={styles.consistencyBadge}>{trace.sagaStatus}</span>
                )}
              </div>

              <div className={styles.snapshotGrid}>
                <div className={styles.metricPanel}>
                  <span>Trade State</span>
                  <strong>{trace?.tradeStatus ?? "-"}</strong>
                </div>
                <div className={styles.metricPanel}>
                  <span>Saga</span>
                  <strong>{trace?.sagaStatus ?? "-"}</strong>
                </div>
                <div className={styles.metricPanel}>
                  <span>Latest Activity</span>
                  <strong>{latestActivity?.activityType ?? "-"}</strong>
                </div>
                <div className={styles.metricPanel}>
                  <span>Outbox Sent / Pending</span>
                  <strong>
                    {trace?.events.filter((item) => item.status === "SENT").length ?? 0} /{" "}
                    {trace?.events.filter((item) => item.status !== "SENT").length ?? 0}
                  </strong>
                </div>
              </div>

              {trace && (
                <div className={styles.balancePanel}>
                  <div className={styles.balanceHeader}>
                    <h3>残高と建玉</h3>
                    <p>{trace.positionSummary}</p>
                  </div>

                  <div className={styles.balanceBar}>
                    <div
                      className={styles.availableBar}
                      style={{ width: `${availableRate}%` }}
                    />
                    <div
                      className={styles.heldBar}
                      style={{ width: `${100 - availableRate}%` }}
                    />
                  </div>

                  <div className={styles.balanceLegend}>
                    <span>
                      Available: {trace.balance.available.toFixed(2)}{" "}
                      {trace.balance.currency}
                    </span>
                    <span>
                      Held: {trace.balance.held.toFixed(2)} {trace.balance.currency}
                    </span>
                  </div>
                </div>
              )}
            </article>

            <article className={styles.statusCard}>
              <div className={styles.sectionHeader}>
                <div>
                  <p className={styles.cardLabel}>Service States</p>
                  <h2>各マイクロサービスの状態</h2>
                </div>
              </div>

              <div className={styles.serviceGrid}>
                {(trace?.services ?? []).map((service) => (
                  <div key={service.name} className={styles.serviceCard}>
                    <span>{service.name}</span>
                    <strong className={serviceToneClassMap[serviceTone(service.status)]}>
                      {service.status}
                    </strong>
                  </div>
                ))}
              </div>
            </article>

            <article className={styles.statusCard}>
              <div className={styles.sectionHeader}>
                <div>
                  <p className={styles.cardLabel}>Event Stream</p>
                  <h2>Outbox と発行イベント</h2>
                </div>
              </div>

              {!trace && <div className={styles.emptyState}>まだイベントはありません。</div>}

              {trace && (
                <div className={styles.eventList}>
                  {trace.events.map((event, index) => (
                    <div
                      key={`${event.sourceService}-${event.eventType}-${event.createdAt}-${index}`}
                      className={styles.eventItem}
                    >
                      <div className={styles.timelineTopRow}>
                        <span className={styles.laneBadge}>{event.sourceService}</span>
                        <span
                          className={`${styles.toneBadge} ${toneClassMap[activityTone(event.status)]}`}
                        >
                          {event.status}
                        </span>
                      </div>
                      <h3>{event.eventType}</h3>
                      <p>{event.topicName}</p>
                      <div className={styles.metaRow}>
                        <span className={styles.metaChip}>
                          Created: {formatTimestamp(event.createdAt)}
                        </span>
                        <span className={styles.metaChip}>
                          Sent: {formatTimestamp(event.sentAt)}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </article>

            <article className={styles.statusCard}>
              <div className={styles.sectionHeader}>
                <div>
                  <p className={styles.cardLabel}>Run Summary</p>
                  <h2>この UI が示していること</h2>
                </div>
              </div>

              <ul className={styles.bulletList}>
                <li>実際の `tradeId` をキーに、バックエンドの進行を追跡しています。</li>
                <li>`ACID` の commit と `Saga` の追いつき方を同じ画面で確認できます。</li>
                <li>表示中の履歴は `trade_activity` と `outbox_event` から構成した実データです。</li>
                {trade && <li>現在の `tradeId` は `{trade.tradeId}` です。</li>}
              </ul>
            </article>
          </div>
        </section>
      </main>
    </div>
  );
}
