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
        const payload = await response.json();
        if (!response.ok) {
          throw new Error(payload?.message ?? "trace fetch failed");
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
