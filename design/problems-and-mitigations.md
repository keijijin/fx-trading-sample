# 問題と対策の整理（これまでの経緯）

本ドキュメントは、FX Trading サンプル（ACID コア + Saga 後続）の検証・運用の過程で **顕在化した問題** と、それに対して **実装・手順・試験設計の面で講じた対策** を一覧化したものです。個別の詳細レポート・JSON は `loadtest/` や過去のデプロイレポートを参照してください。

---

## 1. Saga 補償：並列消費によるイベント順序のずれ

### 問題

- **Risk** と **Accounting** が同一トピック `fx-cover-events` を **別 consumer グループで並列消費**している。
- その結果、**会計失敗（`ACCOUNTING_POST_FAILED`）が、リスク完了（`RISK_UPDATED`）より先に** Trade Saga に届くことがある。
- 補償開始時点で `risk_status` がまだ `PENDING` の場合、`startCompensation` 内の `requestIfCompleted` は **`risk_status == COMPLETED` のときだけ** `REVERSE_RISK_REQUESTED` を Outbox に積むため、**リスク逆転要求が一度も出ない**。
- その結果、補償完了条件（`cancelIfReady`）を満たせず **Saga が `CANCELLED` に到達しにくい**、負荷試験の `accounting_fail` が **E2E タイムアウト**しやすい、という症状が出た。

### 対策

| 区分 | 内容 |
|------|------|
| **アプリ** | `RISK_UPDATED` 処理の末尾で **`enqueueDeferredReverseRiskIfNeeded`** を呼ぶ。`saga_status = COMPENSATING` かつ **`risk_cancel_requested = true`**（補償開始時にリスクが `PENDING` だった場合のみ立つフラグ）かつ **`risk_status = COMPLETED'`** の行を **1 行だけ**更新してフラグを下ろし、そのとき **一度だけ** `REVERSE_RISK_REQUESTED` を Outbox へ積む。 |
| **DB** | `risk_cancel_requested` は `trade_saga` に既存。起動時マイグレーション・インデックスは既存方針に沿う。 |
| **テスト** | 結合テストで会計失敗シナリオの **最終 `saga_status == CANCELLED`** を `Awaitility` で待つ検証を強化。 |
| **可観測性** | メトリクス **`fx_saga_deferred_reverse_risk_enqueued_total`**（遅延リスク逆転の発行回数）を追加。Grafana `FX Trading Overview` に系列を追加。 |
| **負荷** | `accounting_fail` の `TRACE_TIMEOUT_MS` 延長など、補償チェーンの時間的余裕（試験側の調整）。 |

---

## 2. OpenShift デプロイ：レジストリ URL とイメージ運用

### 問題

- `oc registry info`（`--public` なし）が **空**を返す環境があり、`podman` のタグが **`/namespace/image`** のように **無効な参照**になり **push が失敗**した。

### 対策

| 区分 | 内容 |
|------|------|
| **手順** | README に沿い、**`oc registry info --public`** で取得したホストを使う。 |
| **スクリプト** | `scripts/push-openshift-image.sh` を追加。`--public` 固定・ローカルイメージ名 `backend-<name>:latest` を前提に tag/push。 |

---

## 3. 後続処理の遅延：Outbox ポーリング

### 問題

- Outbox のポーリング間隔が長いと、イベント発行が遅れ、**補償チェーン全体の完了**や負荷試験の E2E 待ちに影響する可能性がある。

### 対策

| 区分 | 内容 |
|------|------|
| **マニフェスト** | `openshift/fx-trading-db-separated.yaml` 等で **`OUTBOX_POLL_PERIOD_MS=50`**（例: 100ms から短縮）を各サービスに設定。デプロイ時に `oc apply` または `oc set env` でクラスタと一致させる。 |

---

## 4. 負荷試験：多レプリカ・スパイク時の「失敗」の見え方

### 問題

- **k6 の閾値**（例: `trade_api_latency` p95/p99、スパイク相の `http_req_duration` p95）が、**単一レプリカ**では満たせても、**3〜5 Pod** では **尾部が伸びて exit 99** になりやすい。
- 原因は必ずしもバグではなく、**(1) 単一 DB 上の ACID による競合**、**(2) Route/LB 経由の分散尾部**、**(3) スケール直後の Kafka consumer の再配置**、**(4) k6 の VU 不足による `dropped_iterations`** などが混ざる。
- Prometheus の **試験直後スナップショット**では、多 Pod 時に **Outbox 滞留・Kafka lag** の数字が大きく見えることがある（タイミング・ウィンドウ依存）。

### 対策

| 区分 | 内容 |
|------|------|
| **試験設計** | `design/test-plan.md` を更新。**MAU/DAU・Little の法則**に基づく負荷前提の置き方、**スパイク × スケールアウト**試験の位置づけ、多 Pod 時の **試験運用上の待機・閾値**を明記。 |
| **スパイク試験** | `loadtest/k6-spike-scale-test.js`（warm / spike / cool）と `loadtest/run_spike_scale_test.py` を追加。**`SPIKE_P95_MS` 等を環境変数で上書き**可能にし、PoC クラスタでは **6000ms 前後**など現実的な閾値で **exit 99 を「真の劣化」と切り分け**できるようにした。 |
| **運用パラメータ** | `run_spike_scale_test.py` で **`--compare-replicas 1 3 5`** をデフォルト化、**`--sleep-extra-per-replica`** でスケール後の安定待ちを延長、**`SPIKE_MAX_VUS` / `SPIKE_PREALLOCATED_VUS`** を引き上げて到着率未達を抑える。 |
| **解釈** | **水平スケール＝約定コアの TPS が線形に伸びるとは限らない**（単一 DB・ロック）。改善は **シャーディング・ドメイン分割** が別テーマとして必要、と文書化。 |

---

## 5. テストプラン全体（設計書）の見直し

### 問題

- 「口座数」「秒間 RPS」だけでは負荷の前提がブレやすく、**本番想定に近い議論**がしづらかった。

### 対策

| 区分 | 内容 |
|------|------|
| **負荷モデル** | §7 で **前提の置き方（MAU/DAU・ピーク）**、**同時セッションと Little の法則**、PoC の **10/50/100 rps ステップ**、**スパイクとスケールアウト検証**を整理。 |
| **実行順** | スパイク試験を推奨フローに組み込み。 |

---

## 6. 成果物の参照先（例）

| 内容 | 参照ファイルの例 |
|------|------------------|
| デプロイ後フルスイート（1r/3r） | `loadtest/openshift-deploy-full-test-report.md`, `full-suite-post-deploy-r*.json` |
| スパイク 1/3/5 | `loadtest/spike-scale-report-r135.md`, `spike-scale-report-r135.json` |
| 単発実行レポート | `loadtest/test-execution-report-2026-04-06.md` |
| 負荷手順 | `loadtest/README.md` |

---

## 7. 改善余地への対応（実施内容）

1. **Saga 以外のボトルネック**  
   `loadtest/run_spike_scale_test.py` が、k6 の warm / spike / cool と**同じ秒幅**の Unix 窓で Prometheus **`/api/v1/query_range`** を実行し、`sum(fx_outbox_backlog_total)`・`max(fx_kafka_consumer_group_lag)`・`sum(hikaricp_connections_active)` を相別に集計（JSON の `prometheus_by_phase`、Markdown の「相別 Prometheus」表）。試験直後の instant スナップショット（`prometheus_after_test`）も従来どおり。`--disable-phase-prometheus` で range 部分のみ無効化可能。
2. **本番に近い非機能**  
   `loadtest/verify_db_connection_budget.py` で、manifest / 既定に基づく **サービス×DB ホスト×プール**をレプリカ数で積み上げ、`max_connections`（`--pg-max-connections` または `oc exec` による `SHOW max_connections`）と比較する JSON を標準出力する。
3. **ドメイン分割**  
   約定スループット超過時はシャード・口座分散が必要になる旨と、検討フェーズの目安を `design/sharding-and-domain-split-roadmap.md` に整理した。

---

## 8. スケーリング・パラドクスへの対処（構造的改善）

PDF レポート（`loadtest/reports/The_Microservice_Scaling_Paradox.pdf`）および設計ディスカッション（`design/discussion/notebooklm.md`, `chapie.md`）で指摘された「Pod を増やしても速くならない」構造的ボトルネックに対し、以下を整理・実装した。

### 提案と実装状況の一覧

| # | ディスカッション提案 | 状態 | 備考 |
|---|----------------------|------|------|
| 1 | Kafka 3 broker | **実装済** | `fx-trading-stack.yaml` StatefulSet ×3 |
| 2 | パーティション 6（自動作成） | **実装済** | `KAFKA_NUM_PARTITIONS=6` |
| 3 | DB 分離（サービス別） | **実装済** | `fx-trading-db-separated.yaml` |
| 4 | `trade_activity` 非同期バッチ化 | **実装済** | `ConcurrentLinkedQueue` + `@Scheduled` flush（250ms）|
| 5 | ACID 短縮（batch hold / 直接 EXECUTED INSERT） | **実装済** | `batchUpdate` + `BALANCE_BUCKET_COUNT=16` |
| 6 | 残高バケット化 | **実装済** | ハッシュ 16 バケットで行ロック分散 |
| 7 | Outbox ポーリング間隔短縮 | **実装済** | `OUTBOX_POLL_PERIOD_MS=50` |
| 8 | Outbox split 並列化 + 接続プール制限 | **実装済** | `parallelProcessing()` + `executorService(Executors.newFixedThreadPool(4))`。無制限並列ではストレス時に Hikari pool を占有し API がタイムアウトするため、4 スレッドに制限 |
| 9 | Kafka コンシューマ多重化 | **実装済** | `consumersCount=2` |
| 10 | Kafka プロデューサ linger | **実装済** | `lingerMs=5` |
| 11 | Outbox ポーリング用部分インデックス | **実装済** | `idx_outbox_event_poll (source_service, status, created_at) WHERE status IN ('NEW','RETRY')` |
| 12 | Outbox publish パスの DB 往復削減 | **実装済** | `UPDATE...RETURNING *` で claim + findById を 1 往復に統合。markSent/markFailed から冗長な findById を除去 |
| 13 | SENT 済みイベントの定期クリーンアップ | **実装済** | 60 秒ごとに 5 分経過の SENT を最大 500 件削除 |
| 14 | Debezium CDC（Outbox Push 化） | **未実装** | PoC 規模ではインフラ要件が過大。ポーリング最適化で代替 |
| 15 | シャーディング（約定コア分割） | **設計文書のみ** | `design/sharding-and-domain-split-roadmap.md` |
| 16 | CQRS（読み取りモデル分離） | **未実装** | 書き込みボトルネックには直接効かないため見送り。読み取り系が要件になった段階で導入検討 |

### 本ラウンドの施策（#11〜#13）の狙い

ディスカッションの核心は「**流量ではなく競合点を減らす**」こと。Pod 増加→DB 接続爆発→Kafka lag→Saga 詰まり、という連鎖の中で、まだ手付かずだった **Outbox テーブルの構造的コスト**を削減する。

1. **インデックス追加**: ポーリング `SELECT` がテーブル肥大時にフルスキャンするのを防ぐ。`WHERE status IN ('NEW','RETRY')` の部分インデックスで SENT/ERROR 行を除外。
2. **DB 往復削減**: 1 イベントあたりの Outbox 配信が **claim(UPDATE) → findById(SELECT) → Kafka → markSent(findById+UPDATE) = 4〜5 往復** だったのを、**claimAndLoad(UPDATE RETURNING) → Kafka → markSent(UPDATE) = 2 往復** に半減。
3. **SENT クリーンアップ**: テーブルの肥大化を防ぎ、ポーリング・インデックスの効率を維持。

### 未実装施策の位置づけ

- **Debezium CDC**: 最も効果が大きい Outbox 改善だが、Debezium Connect クラスタの運用負荷と PoC 規模のバランスから見送り。本番移行時に検討。
- **シャーディング**: 単一約定コアの TPS 上限を超える場合に必要。現 PoC では DB 分離 + バケット化で対処。
- **CQRS**: 現在のボトルネックは **書き込み側**（約定 ACID + Outbox 配信）であり、読み取り分離は効かない。参照 API / ダッシュボード / レポーティングの要件が出た段階で部分導入を推奨。

---

*本書は会話・実装・負荷試験の経緯を要約したものであり、数値は実行環境・日時により変動します。*
