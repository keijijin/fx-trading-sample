# Load Test Scripts

`loadtest/` には、ストレステストとレプリカ比較のためのスクリプトを置いています。

## ファイル

- `trade-request.json`
  - `hey` の POST body 例
- `k6-trade-stress.js`
  - `k6` で `POST /api/trades` を継続的に叩くスクリプト
- `k6-1000-accounts-stress.js`
  - `1000` アカウントを共有プールとして使い回しながら集中リクエストするスクリプト
- `run_replica_comparison.py`
  - `oc -n fx-trading-sample scale deployment ...` でレプリカ数を切り替え
  - `hey` で負荷をかける
  - Prometheus から観測値を取る
  - JSON レポートを出力する
- `k6-spike-scale-test.js` / `run_spike_scale_test.py`
  - **warm → spike → cool** の 3 相でスパイク負荷を再現（`constant-arrival-rate` を `startTime` で直列配置）
  - `--compare-replicas 1 3` でレプリカ数を変えて **同条件を繰り返し**、スパイク時のスループット・Prometheus スナップショットを JSON/Markdown に出力
  - **相別 `query_range`**: k6 ブロック開始時刻を基準に、各相と同じ秒幅の Unix 窓で `sum(fx_outbox_backlog)` / `max(fx_kafka_consumer_group_lag)` / `sum(hikaricp_connections_active)` を取得（JSON の `prometheus_by_phase`）。`--prometheus-range-step` で step を変更。`--disable-phase-prometheus` で無効化可能
  - 既定は `TRACE_SAMPLE_RATE=0`（POST のみ。E2E まで見る場合は `--trace-sample-rate 0.05` など）
- `verify_db_connection_budget.py`
  - `openshift/fx-trading-db-separated.yaml` 想定で **サービス×DB ホスト×Hikari 最大接続**をレプリカ数で積み上げ、`max_connections` と比較する JSON を出力（`--pg-max-connections` または `oc exec` で `SHOW max_connections`）

## 最近の運用メモ

- `run_test_plan_suite.py` の **baseline** は、現在は **純粋な正常系**です。以前の `BASELINE_FAILURE_PERCENT=5` による失敗混在は除去しました。
- シナリオ間の Kafka lag 持ち越しを避けるため、`run_test_plan_suite.py` は各シナリオ後に **lag settle 待ち**を行います（既定: `max(fx_kafka_consumer_group_lag) <= 100` まで最大 180 秒待機）。
- Outbox backlog の Prometheus 指標は実際の scrape 名に合わせて **`fx_outbox_backlog`** を使用します。
- CDC を併用する場合、現行の `openshift/fx-kafka-connect-cdc.yaml` は **shadow topic** 向けです。polling publisher と並行比較し、本番 topic への full cutover は別段階で行ってください。

## 事前条件

- `oc`
- `hey`
- `python3`
- OpenShift 上で `fx-trading-sample` namespace が動作中
- `fx-prometheus` Route が公開されていること

## `k6` の例

`k6` がローカルに入っている場合:

```bash
FX_CORE_BASE_URL="http://fx-core-service-fx-trading-sample.apps.cluster-dhnjd.dynamic.redhatworkshops.io" \
VUS=30 \
DURATION=60s \
ORDER_AMOUNT=1 \
k6 run loadtest/k6-trade-stress.js
```

このスクリプトは `accountId` を `ACC-K6-${__VU}-${__ITER}` で毎回変えるため、残高枯渇による 409 を避けやすくしています。

## `1000` アカウント集中負荷の例

`1000` アカウントを共有プールとして使い回し、同一アカウント群へ継続的に集中アクセスさせる場合:

```bash
FX_CORE_BASE_URL="http://fx-core-service-fx-trading-sample.apps.cluster-dhnjd.dynamic.redhatworkshops.io" \
VUS=100 \
DURATION=120s \
ACCOUNT_COUNT=1000 \
ACCOUNT_SELECTION_MODE=round_robin \
ORDER_AMOUNT=1 \
k6 run loadtest/k6-1000-accounts-stress.js
```

ランダムに 1000 アカウントへ散らしたい場合:

```bash
FX_CORE_BASE_URL="http://fx-core-service-fx-trading-sample.apps.cluster-dhnjd.dynamic.redhatworkshops.io" \
VUS=100 \
DURATION=120s \
ACCOUNT_COUNT=1000 \
ACCOUNT_SELECTION_MODE=random \
ORDER_AMOUNT=1 \
k6 run loadtest/k6-1000-accounts-stress.js
```

このスクリプトの特徴:

- `ACC-HOT-0001` から `ACC-HOT-1000` までの `1000` アカウントを固定プールとして使用
- 各リクエストはその `1000` アカウントのどれかに着地
- 同一アカウント群に対する競合やロック影響を観測しやすい
- `ACCOUNT_SELECTION_MODE=round_robin` なら偏りを抑えやすい
- `ACCOUNT_SELECTION_MODE=random` ならホットスポットの揺らぎも見られる

## スパイク × スケールアウトの例

```bash
python3 loadtest/run_spike_scale_test.py \
  --namespace fx-trading-sample \
  --compare-replicas 1 3 \
  --warm-sec 120 --spike-sec 60 --cool-sec 120 \
  --base-rate 20 --peak-rate 100 \
  --output loadtest/spike-scale-report.json
```

`spike-scale-report.json` と同じ stem の `.md` に表形式サマリーが出ます（**相別 range** 表を含む）。

**DB 接続バジェット（積み上げ）の例:**

```bash
python3 loadtest/verify_db_connection_budget.py --replicas 5 --pg-max-connections 160
# OpenShift 上の Postgres に問い合わせる例（fx-core-db は openshift マニフェストで max_connections=160）
python3 loadtest/verify_db_connection_budget.py --replicas 5 \
  --oc-namespace fx-trading-sample --oc-deployment fx-core-db
```

シャード・口座分散がいつ必要になるかの整理は `design/sharding-and-domain-split-roadmap.md` を参照。

デフォルトは **replicas 1 / 3 / 5** を順に試します。スケールアウト直後の **Kafka 安定待ち**は `--sleep-extra-per-replica`（レプリカ数に応じて加算）で調整できます。多ポッドで k6 の **spike 相 p95 だけ**閾値を緩める例:

```bash
python3 loadtest/run_spike_scale_test.py \
  --namespace fx-trading-sample \
  --compare-replicas 1 3 5 \
  --spike-p95-ms 6000 --spike-p99-ms 12000 \
  --spike-max-vus 3500 --spike-preallocated-vus 800 \
  --sleep-after-scale 20 --sleep-extra-per-replica 5
```

## レプリカ比較の例

```bash
python loadtest/run_replica_comparison.py \
  --namespace fx-trading-sample \
  --duration 30s \
  --window 45s \
  --concurrency 30 \
  --output loadtest/replica-comparison-report-generated.json
```

## 出力される指標

- `hey`
  - 平均応答時間
  - 最速 / 最遅
  - リクエスト毎秒
  - ステータスコード分布

- Prometheus
  - `http_p95_s`
  - `http_p99_s`
  - `http_error_rate`
  - `saga_p95_s`
  - `saga_p99_s`
  - `outbox_backlog_max`
  - `hikari_active_max`
  - `hikari_pending_max`
  - `hikari_max`
  - `kafka_lag_max`
  - `pod_cpu_sum_cores`
  - `pod_memory_sum_bytes`
  - `pod_restarts`

## CDC shadow 検証

OpenShift 上で `Kafka Connect + Debezium` を適用済みの場合、`outbox_event` の CDC 配信を shadow topic で確認できます。

### 1. Connector 状態確認

```bash
oc port-forward svc/fx-kafka-connect 18082:8083
curl -s http://localhost:18082/connectors
curl -s http://localhost:18082/connectors/fx-core-outbox-connector/status
```

期待値:

- connector: `RUNNING`
- task: `RUNNING`

### 2. Trade を 1 件発行

```bash
curl -s -X POST "http://localhost:18080/api/trades" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "ACC-CDC-001",
    "currencyPair": "USD/JPY",
    "side": "BUY",
    "orderAmount": 1000.0,
    "requestedPrice": 150.25,
    "simulateCoverFailure": false,
    "simulateRiskFailure": false,
    "simulateAccountingFailure": false,
    "simulateSettlementFailure": false,
    "simulateNotificationFailure": false,
    "simulateComplianceFailure": false,
    "preTradeComplianceFailure": false
  }'
```

### 3. shadow topic 確認

```bash
oc exec pod/fx-kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic shadow.fx-trade-events \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 15000
```

ここで `TradeExecuted` 相当の payload が 1 件確認できれば、

```text
Business Tx -> outbox_event -> WAL -> Debezium -> Kafka Connect -> shadow topic
```

の経路が成立しています。

詳細な検証結果は `loadtest/reports/cdc-shadow-validation-2026-04-10.md` を参照してください。

## 注意

- `trade-request.json` の `accountId` を固定したまま高負荷をかけると、残高枯渇で `409` が増えます。
- 純粋な性能比較をしたい場合は、`run_replica_comparison.py` のようにレプリカごとに別口座を使うのが安全です。
- `k6-1000-accounts-stress.js` は「1アカウント集中」ではなく「1000アカウント集中」なので、ホットスポット傾向を見つつ、極端な単一行ロック偏りをやや緩和できます。
