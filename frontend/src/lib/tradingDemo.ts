export type StepTone =
  | "core"
  | "event"
  | "service"
  | "success"
  | "warning"
  | "danger";

export type Step = {
  id: string;
  title: string;
  detail: string;
  lane: "ACID" | "SAGA";
  topic?: string;
  event?: string;
  tone: StepTone;
};

export type ServiceStatusTone =
  | "idle"
  | "running"
  | "success"
  | "warning"
  | "danger";

export type ServiceState = {
  name: string;
  status: string;
  tone: ServiceStatusTone;
};

export type Snapshot = {
  headline: string;
  consistencyLabel: string;
  tradeState: string;
  outboxState: string;
  sagaState: string;
  balance: {
    available: number;
    held: number;
  };
  position: string;
  currentEvent: string;
  guarantees: string[];
  services: ServiceState[];
};

export type Scenario = {
  id: string;
  label: string;
  description: string;
  accent: string;
  steps: Step[];
  snapshots: Snapshot[];
  takeaways: string[];
};

const BASE_BALANCE = {
  available: 100,
  held: 0,
};

export const scenarios: Scenario[] = [
  {
    id: "success",
    label: "正常完了",
    description:
      "約定コアは同期で確定し、その後にカバー・リスク・会計・受渡が Saga で完走する基本ケースです。",
    accent: "#4fd1c5",
    steps: [
      {
        id: "acid-commit",
        title: "約定コアを 1 トランザクションで確定",
        detail:
          "注文受付、約定登録、残高拘束、建玉更新までを FX Core Service 内で同期確定します。",
        lane: "ACID",
        tone: "core",
      },
      {
        id: "outbox",
        title: "TradeExecuted を Outbox へ登録",
        detail:
          "取引本体の commit と同時に outbox_event へ保存し、送信漏れを防ぎます。",
        lane: "ACID",
        event: "TradeExecuted",
        topic: "outbox_event",
        tone: "event",
      },
      {
        id: "publish",
        title: "Outbox Publisher が Kafka へ配信",
        detail:
          "非同期処理の起点は DB commit 後です。ここから先は最終的整合でつながります。",
        lane: "SAGA",
        event: "TradeExecuted",
        topic: "fx-trade-events",
        tone: "event",
      },
      {
        id: "cover-start",
        title: "Cover / Notification / Compliance が起動",
        detail:
          "TradeExecuted を受けて独立サービスが動きますが、会計とリスクはまだ開始しません。",
        lane: "SAGA",
        event: "TradeExecuted",
        topic: "fx-trade-events",
        tone: "service",
      },
      {
        id: "cover-booked",
        title: "CoverTradeBooked を受信",
        detail:
          "カバー約定が確定してから、はじめて Risk と Accounting が起動できるようになります。",
        lane: "SAGA",
        event: "CoverTradeBooked",
        topic: "fx-cover-events",
        tone: "success",
      },
      {
        id: "risk-accounting",
        title: "RiskUpdated と AccountingPosted がそろう",
        detail:
          "Trade Saga Service が両方の完了を見て、Settlement を進めるか判断します。",
        lane: "SAGA",
        event: "RiskUpdated / AccountingPosted",
        topic: "fx-risk-events / fx-accounting-events",
        tone: "success",
      },
      {
        id: "settlement",
        title: "SettlementReserved で業務連携が完了",
        detail:
          "受渡予約まで進み、通知・コンプライアンスもそろえば Saga 全体が完了へ向かいます。",
        lane: "SAGA",
        event: "SettlementReserved",
        topic: "fx-settlement-events",
        tone: "success",
      },
      {
        id: "complete",
        title: "Saga が COMPLETED になる",
        detail:
          "同期確定したコア取引に、後続の最終状態が追いついたタイミングです。",
        lane: "SAGA",
        tone: "success",
      },
    ],
    snapshots: [
      {
        headline: "顧客には約定が確定して見えます。",
        consistencyLabel: "強整合で確定済み",
        tradeState: "EXECUTED",
        outboxState: "NEW",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "USD/JPY の建玉を更新済み",
        currentEvent: "まだ Kafka には出ていない",
        guarantees: [
          "約定、残高拘束、建玉更新は同一 Tx に入る",
          "ここで障害が起きても不整合な中間状態を残さない",
        ],
        services: [
          { name: "Cover", status: "NOT_STARTED", tone: "idle" },
          { name: "Risk", status: "NOT_STARTED", tone: "idle" },
          { name: "Accounting", status: "NOT_STARTED", tone: "idle" },
          { name: "Settlement", status: "NOT_STARTED", tone: "idle" },
          { name: "Notification", status: "NOT_STARTED", tone: "idle" },
          { name: "Compliance", status: "NOT_STARTED", tone: "idle" },
        ],
      },
      {
        headline: "送信保証を DB で持った状態です。",
        consistencyLabel: "強整合の外へ出す準備完了",
        tradeState: "EXECUTED",
        outboxState: "NEW",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "TradeExecuted を outbox_event に保存",
        guarantees: [
          "commit 済みデータと送信予定イベントが同時に残る",
          "送信失敗しても再送できる",
        ],
        services: [
          { name: "Cover", status: "NOT_STARTED", tone: "idle" },
          { name: "Risk", status: "NOT_STARTED", tone: "idle" },
          { name: "Accounting", status: "NOT_STARTED", tone: "idle" },
          { name: "Settlement", status: "NOT_STARTED", tone: "idle" },
          { name: "Notification", status: "NOT_STARTED", tone: "idle" },
          { name: "Compliance", status: "NOT_STARTED", tone: "idle" },
        ],
      },
      {
        headline: "取引本体は確定したまま、非同期の世界へ進みます。",
        consistencyLabel: "ここから最終的整合",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "TradeExecuted -> fx-trade-events",
        guarantees: [
          "同期処理は終わっているので API 応答は速い",
          "後続は各サービスのローカル Tx で進む",
        ],
        services: [
          { name: "Cover", status: "PENDING", tone: "running" },
          { name: "Risk", status: "NOT_STARTED", tone: "idle" },
          { name: "Accounting", status: "NOT_STARTED", tone: "idle" },
          { name: "Settlement", status: "NOT_STARTED", tone: "idle" },
          { name: "Notification", status: "PENDING", tone: "running" },
          { name: "Compliance", status: "PENDING", tone: "running" },
        ],
      },
      {
        headline: "後続サービスは並列ですが、依存関係は守られます。",
        consistencyLabel: "Saga 実行中",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "Cover / Notification / Compliance が処理中",
        guarantees: [
          "通知は取引本体から独立して進む",
          "会計とリスクは Cover 成功後まで待つ",
        ],
        services: [
          { name: "Cover", status: "PENDING", tone: "running" },
          { name: "Risk", status: "WAITING_COVER", tone: "idle" },
          { name: "Accounting", status: "WAITING_COVER", tone: "idle" },
          { name: "Settlement", status: "WAITING", tone: "idle" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "カバーが終わると、依存していた処理が一気に進めます。",
        consistencyLabel: "依存解除",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "CoverTradeBooked -> fx-cover-events",
        guarantees: [
          "Cover 成功が次工程の起動条件になる",
          "Trade Saga Service が進行状態を一元管理する",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "PENDING", tone: "running" },
          { name: "Accounting", status: "PENDING", tone: "running" },
          { name: "Settlement", status: "WAITING", tone: "idle" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "分散処理でも、必要な完了条件をそろえてから次へ進みます。",
        consistencyLabel: "集約判定中",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "RiskUpdated / AccountingPosted を受信",
        guarantees: [
          "部分成功をそのまま完了扱いにしない",
          "両方そろった時だけ Settlement 開始",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "COMPLETED", tone: "success" },
          { name: "Accounting", status: "COMPLETED", tone: "success" },
          { name: "Settlement", status: "PENDING", tone: "running" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "受渡予約まで進むと、業務的な整合はほぼ完成です。",
        consistencyLabel: "完了直前",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "SettlementReserved -> fx-settlement-events",
        guarantees: [
          "各サービスは自分の責務だけをローカル Tx で確定する",
          "分散 2PC を使わず高性能を維持する",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "COMPLETED", tone: "success" },
          { name: "Accounting", status: "COMPLETED", tone: "success" },
          { name: "Settlement", status: "COMPLETED", tone: "success" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "強整合のコアと最終的整合の後続がきれいに合流しました。",
        consistencyLabel: "Saga COMPLETED",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "COMPLETED",
        balance: {
          available: 70,
          held: 30,
        },
        position: "約定結果を全サービスが反映済み",
        currentEvent: "Trade Saga COMPLETED",
        guarantees: [
          "低レイテンシ応答と高信頼な後続連携を両立する",
          "障害時もどこまで終わったかを追跡できる",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "COMPLETED", tone: "success" },
          { name: "Accounting", status: "COMPLETED", tone: "success" },
          { name: "Settlement", status: "COMPLETED", tone: "success" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
    ],
    takeaways: [
      "顧客の約定確定は Kafka 完了待ちではなく、コア Tx の commit で確定します。",
      "Outbox により、DB commit とイベント発行の取りこぼしを分離せずに扱えます。",
      "Saga は遅い代わりに分散 2PC を避け、高スループットと障害局所化を得ます。",
    ],
  },
  {
    id: "cover-failed",
    label: "カバー失敗",
    description:
      "取引本体はすでに強整合で確定したあとに Cover Service が失敗し、Trade Saga Service が補償へ移るケースです。",
    accent: "#f6ad55",
    steps: [
      {
        id: "acid-commit",
        title: "約定コアは先に commit される",
        detail:
          "API 応答時点で、約定・残高拘束・建玉更新は成功しています。",
        lane: "ACID",
        tone: "core",
      },
      {
        id: "publish",
        title: "TradeExecuted が配信される",
        detail:
          "ここまでは正常系と同じです。差が出るのは Cover Service 側の結果です。",
        lane: "SAGA",
        event: "TradeExecuted",
        topic: "fx-trade-events",
        tone: "event",
      },
      {
        id: "cover-failure",
        title: "CoverTradeFailed が返る",
        detail:
          "Cover が失敗したため、Saga は COMPLETED に進めず補償開始条件を満たします。",
        lane: "SAGA",
        event: "CoverTradeFailed",
        topic: "fx-cover-events",
        tone: "danger",
      },
      {
        id: "compensating",
        title: "Trade Saga が補償対象を判定",
        detail:
          "成功済みだけを戻し、未着手は起動せず、処理中は cancel 要求フラグで制御します。",
        lane: "SAGA",
        event: "Reverse*Requested",
        topic: "fx-compensation-events",
        tone: "warning",
      },
      {
        id: "notice",
        title: "訂正通知を送る",
        detail:
          "ロールバックではなく、顧客向けには訂正通知という業務イベントで打ち消します。",
        lane: "SAGA",
        event: "SendCorrectionNoticeRequested",
        topic: "fx-compensation-events",
        tone: "warning",
      },
      {
        id: "cancelled",
        title: "Saga は CANCELLED / FAILED で終わる",
        detail:
          "取引本体がいったん確定していたとしても、後続の失敗と補償結果は明示的に残ります。",
        lane: "SAGA",
        tone: "danger",
      },
    ],
    snapshots: [
      {
        headline: "顧客側の約定はもう見えている段階です。",
        consistencyLabel: "コアは強整合で確定済み",
        tradeState: "EXECUTED",
        outboxState: "NEW",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "まだ後続の結果は未確定",
        guarantees: [
          "コアは正常終了している",
          "後続失敗でも DB ロールバックはしない",
        ],
        services: [
          { name: "Cover", status: "NOT_STARTED", tone: "idle" },
          { name: "Risk", status: "NOT_STARTED", tone: "idle" },
          { name: "Accounting", status: "NOT_STARTED", tone: "idle" },
          { name: "Settlement", status: "NOT_STARTED", tone: "idle" },
          { name: "Notification", status: "NOT_STARTED", tone: "idle" },
          { name: "Compliance", status: "NOT_STARTED", tone: "idle" },
        ],
      },
      {
        headline: "失敗しても、どのサービスが原因か追えるように進行を分けています。",
        consistencyLabel: "Saga 実行開始",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "TradeExecuted -> fx-trade-events",
        guarantees: [
          "Outbox により通知漏れを抑止する",
          "後続サービス失敗は Saga が吸収する",
        ],
        services: [
          { name: "Cover", status: "PENDING", tone: "running" },
          { name: "Risk", status: "WAITING_COVER", tone: "idle" },
          { name: "Accounting", status: "WAITING_COVER", tone: "idle" },
          { name: "Settlement", status: "WAITING", tone: "idle" },
          { name: "Notification", status: "PENDING", tone: "running" },
          { name: "Compliance", status: "PENDING", tone: "running" },
        ],
      },
      {
        headline: "補償が必要な失敗として、Saga の位相が切り替わります。",
        consistencyLabel: "致命失敗を検知",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "COMPENSATING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "CoverTradeFailed -> fx-cover-events",
        guarantees: [
          "CoverTradeFailed は致命失敗として扱う",
          "補償対象は trade_saga の状態から限定する",
        ],
        services: [
          { name: "Cover", status: "FAILED", tone: "danger" },
          { name: "Risk", status: "NOT_STARTED", tone: "idle" },
          { name: "Accounting", status: "NOT_STARTED", tone: "idle" },
          { name: "Settlement", status: "NOT_STARTED", tone: "idle" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "成功済みだけを戻すので、全部を機械的に取り消しません。",
        consistencyLabel: "補償範囲を限定中",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "COMPENSATING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "補償対象を評価中",
        currentEvent: "Reverse*Requested -> fx-compensation-events",
        guarantees: [
          "NOT_STARTED には何もしない",
          "PENDING は cancel 要求フラグで抑止する",
        ],
        services: [
          { name: "Cover", status: "COMPENSATING", tone: "warning" },
          { name: "Risk", status: "SKIPPED", tone: "idle" },
          { name: "Accounting", status: "SKIPPED", tone: "idle" },
          { name: "Settlement", status: "SKIPPED", tone: "idle" },
          { name: "Notification", status: "COMPLETED", tone: "success" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "顧客向けには『取消』ではなく『訂正通知』として見せます。",
        consistencyLabel: "業務的打消しを実行",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "COMPENSATING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "補償イベントを送信中",
        currentEvent: "SendCorrectionNoticeRequested",
        guarantees: [
          "補償は逆取引・取消仕訳・訂正通知で表現する",
          "インフラ都合の rollback を業務へ漏らさない",
        ],
        services: [
          { name: "Cover", status: "COMPENSATED", tone: "warning" },
          { name: "Risk", status: "SKIPPED", tone: "idle" },
          { name: "Accounting", status: "SKIPPED", tone: "idle" },
          { name: "Settlement", status: "SKIPPED", tone: "idle" },
          { name: "Notification", status: "COMPENSATING", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "失敗は隠さず残しつつ、補償済みの範囲も追跡できます。",
        consistencyLabel: "CANCELLED / FAILED",
        tradeState: "EXECUTED_WITH_COMPENSATION",
        outboxState: "SENT",
        sagaState: "CANCELLED",
        balance: BASE_BALANCE,
        position: "補償後の業務結果を記録済み",
        currentEvent: "CorrectionNoticeSent",
        guarantees: [
          "どこまで進んで何を戻したかが状態で分かる",
          "障害時の説明責任と再処理判断がしやすい",
        ],
        services: [
          { name: "Cover", status: "COMPENSATED", tone: "warning" },
          { name: "Risk", status: "SKIPPED", tone: "idle" },
          { name: "Accounting", status: "SKIPPED", tone: "idle" },
          { name: "Settlement", status: "SKIPPED", tone: "idle" },
          { name: "Notification", status: "COMPENSATED", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
    ],
    takeaways: [
      "Saga の失敗は『なかったこと』ではなく『補償済みの失敗』として残すのが重要です。",
      "Trade Saga Service は失敗を受けたあと、成功済みだけを戻すため過剰補償を避けられます。",
      "この分離により、コア取引の低レイテンシを保ったまま高信頼化できます。",
    ],
  },
  {
    id: "notification-failed",
    label: "通知だけ失敗",
    description:
      "TradeNotificationFailed は非致命失敗として扱い、取引本体を巻き戻さず再送や運用対応に回すケースです。",
    accent: "#90cdf4",
    steps: [
      {
        id: "acid-commit",
        title: "コア取引は通常どおり commit",
        detail:
          "約定、残高拘束、建玉更新までは正常系と完全に同じです。",
        lane: "ACID",
        tone: "core",
      },
      {
        id: "trade-executed",
        title: "TradeExecuted を配信",
        detail:
          "Cover、Notification、Compliance が同時に起動します。",
        lane: "SAGA",
        event: "TradeExecuted",
        topic: "fx-trade-events",
        tone: "event",
      },
      {
        id: "notification-failed",
        title: "TradeNotificationFailed が返る",
        detail:
          "通知は失敗しても、取引本体を巻き戻す根拠にはなりません。",
        lane: "SAGA",
        event: "TradeNotificationFailed",
        topic: "fx-notification-events",
        tone: "warning",
      },
      {
        id: "cover-success",
        title: "一方で Cover は成功する",
        detail:
          "致命失敗ではないため、カバー・リスク・会計・受渡はそのまま続行されます。",
        lane: "SAGA",
        event: "CoverTradeBooked",
        topic: "fx-cover-events",
        tone: "success",
      },
      {
        id: "risk-accounting",
        title: "Risk と Accounting が完了",
        detail:
          "通知だけ failed でも、業務上必須の後続は前に進めます。",
        lane: "SAGA",
        event: "RiskUpdated / AccountingPosted",
        topic: "fx-risk-events / fx-accounting-events",
        tone: "success",
      },
      {
        id: "settlement",
        title: "SettlementReserved まで進む",
        detail:
          "Trade Saga Service は warning を保持しつつ、完了条件を評価します。",
        lane: "SAGA",
        event: "SettlementReserved",
        topic: "fx-settlement-events",
        tone: "success",
      },
      {
        id: "complete",
        title: "取引本体は完了、通知は再送対象",
        detail:
          "UI 上は warning を残しつつ、トレード自体は完了として扱います。",
        lane: "SAGA",
        tone: "warning",
      },
    ],
    snapshots: [
      {
        headline: "強整合のコアは正常に閉じています。",
        consistencyLabel: "ACID commit 完了",
        tradeState: "EXECUTED",
        outboxState: "NEW",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "後続処理はこれから",
        guarantees: [
          "通知チャネル障害で約定を失敗にしない",
          "本体価値と周辺チャネル価値を分離する",
        ],
        services: [
          { name: "Cover", status: "NOT_STARTED", tone: "idle" },
          { name: "Risk", status: "NOT_STARTED", tone: "idle" },
          { name: "Accounting", status: "NOT_STARTED", tone: "idle" },
          { name: "Settlement", status: "NOT_STARTED", tone: "idle" },
          { name: "Notification", status: "NOT_STARTED", tone: "idle" },
          { name: "Compliance", status: "NOT_STARTED", tone: "idle" },
        ],
      },
      {
        headline: "通知系も起動しますが、失敗しても最優先ではありません。",
        consistencyLabel: "Saga 起動",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "TradeExecuted を配信済み",
        guarantees: [
          "後続はサービス単位に疎結合",
          "通知はカバーや受渡の前提条件ではない",
        ],
        services: [
          { name: "Cover", status: "PENDING", tone: "running" },
          { name: "Risk", status: "WAITING_COVER", tone: "idle" },
          { name: "Accounting", status: "WAITING_COVER", tone: "idle" },
          { name: "Settlement", status: "WAITING", tone: "idle" },
          { name: "Notification", status: "PENDING", tone: "running" },
          { name: "Compliance", status: "PENDING", tone: "running" },
        ],
      },
      {
        headline: "ここで warning は出ても、致命失敗にはしません。",
        consistencyLabel: "非致命失敗を記録",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING_WITH_WARNING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "TradeNotificationFailed",
        guarantees: [
          "通知失敗では取引本体を巻き戻さない",
          "再送・運用通知へ分岐できる",
        ],
        services: [
          { name: "Cover", status: "PENDING", tone: "running" },
          { name: "Risk", status: "WAITING_COVER", tone: "idle" },
          { name: "Accounting", status: "WAITING_COVER", tone: "idle" },
          { name: "Settlement", status: "WAITING", tone: "idle" },
          { name: "Notification", status: "FAILED_NON_FATAL", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "取引継続に必須な経路だけは止めません。",
        consistencyLabel: "本体処理は続行",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING_WITH_WARNING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "CoverTradeBooked",
        guarantees: [
          "致命/非致命の線引きを明示する",
          "障害の影響範囲を小さく保つ",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "PENDING", tone: "running" },
          { name: "Accounting", status: "PENDING", tone: "running" },
          { name: "Settlement", status: "WAITING", tone: "idle" },
          { name: "Notification", status: "RETRY_PENDING", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "重要経路がそろえば、Saga は前へ進めます。",
        consistencyLabel: "必要条件を充足",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "PENDING_WITH_WARNING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "建玉更新済み",
        currentEvent: "RiskUpdated / AccountingPosted",
        guarantees: [
          "Trade Saga は warning と fatal を区別して管理する",
          "重要経路の進行は妨げない",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "COMPLETED", tone: "success" },
          { name: "Accounting", status: "COMPLETED", tone: "success" },
          { name: "Settlement", status: "PENDING", tone: "running" },
          { name: "Notification", status: "RETRY_PENDING", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "受渡まで完了すれば、トレードとしては完了扱いにできます。",
        consistencyLabel: "完了可能",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "COMPLETED_WITH_WARNING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "全業務処理完了、通知だけ再送待ち",
        currentEvent: "SettlementReserved",
        guarantees: [
          "周辺障害で取引完了を妨げない",
          "warning は運用対象として残す",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "COMPLETED", tone: "success" },
          { name: "Accounting", status: "COMPLETED", tone: "success" },
          { name: "Settlement", status: "COMPLETED", tone: "success" },
          { name: "Notification", status: "RETRY_PENDING", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
      {
        headline: "トレードは成立、通知だけが別の回復戦略に乗る状態です。",
        consistencyLabel: "COMPLETED_WITH_WARNING",
        tradeState: "EXECUTED",
        outboxState: "SENT",
        sagaState: "COMPLETED_WITH_WARNING",
        balance: {
          available: 70,
          held: 30,
        },
        position: "取引成立済み",
        currentEvent: "再送または手動通知へ",
        guarantees: [
          "全失敗を同じ重さで扱わない設計が可用性を上げる",
          "業務重要度で制御を変えると UX も説明しやすい",
        ],
        services: [
          { name: "Cover", status: "COMPLETED", tone: "success" },
          { name: "Risk", status: "COMPLETED", tone: "success" },
          { name: "Accounting", status: "COMPLETED", tone: "success" },
          { name: "Settlement", status: "COMPLETED", tone: "success" },
          { name: "Notification", status: "FAILED_NON_FATAL", tone: "warning" },
          { name: "Compliance", status: "COMPLETED", tone: "success" },
        ],
      },
    ],
    takeaways: [
      "通知失敗を致命障害にしないことで、取引の可用性を大きく落とさずに済みます。",
      "一方で warning を残すため、運用や再送の入口は必ず必要です。",
      "『何を守るためのトランザクションか』を UX 上も見せると理解しやすくなります。",
    ],
  },
];
