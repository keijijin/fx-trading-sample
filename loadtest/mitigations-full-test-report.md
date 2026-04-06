# 未実装対策の実装結果とフルテストレポート

実施日: 2026-04-05（クラスタ: `fx-trading-sample`）

## 1. 実装した対策

| # | 内容 | 配置 |
|---|------|------|
| 1 | **OpenShift Route のタイムアウト** `haproxy.router.openshift.io/timeout=120s`（POST 約定がデフォルト 30s で切れるリスクの低減） | `openshift/fx-trading-stack.yaml` の `fx-core-service` Route + デプロイ時 `oc patch` 適用済み |
| 2 | **DB インデックス** `idx_outbox_event_aggregate_id`（fx-core-db）、`idx_trade_activity_trade_id`（saga DB） | `backend/db/init.sql`、`openshift/fx-trading-stack.yaml` の ConfigMap SQL |
| 3 | **既存 PVC 向けマイグレーション**（起動時 `CREATE INDEX IF NOT EXISTS`） | `BalanceBucketSchemaSupport`（fx-core）、`TradeSagaSchemaSupport`（trade-saga） |
| 4 | **負荷スイープ** baseline を RATE 15/30/50・60s・replicas=3 で実行し Prometheus と併記 | `loadtest/run_load_sweep.py`、`render_load_sweep_report.py` |

補足: `trade_saga.trade_id` は既に PRIMARY KEY のため追加インデックスは不要。

## 2. フルテスト（`run_test_plan_suite.py`）

| シナリオ | 1 replica | 3 replicas |
|----------|-----------|------------|
| smoke | PASS | PASS |
| baseline | PASS | **FAIL** |
| cover_fail | PASS | PASS |
| accounting_fail | **FAIL** | **FAIL** |
| notification_fail | PASS | PASS |
| stress | PASS | PASS |
| soak | PASS | PASS |

- **1 replica**: ほぼ全シナリオ PASS。`accounting_fail` のみ **FAIL**（サンプル 255 件中 **33 件が 90s 内に補償完了に到達できず**タイムアウト）。
- **3 replicas**: `baseline` が **FAIL**（k6 閾値。E2E p95 悪化・混在失敗パスのノイズ）。`accounting_fail` も同様にタイムアウト要因が残る。

結果 JSON:

- `loadtest/full-suite-mitigations-r1.json`
- `loadtest/full-suite-mitigations-r3.json`

## 3. 数値サマリー（抜粋）

### 1 replica

- **baseline**: `trade_api_latency` p95 ≈ **0.23s**、`saga_completed`（サンプル内）308、trace タイムアウト 3。
- **accounting_fail**: 補償完了サンプル 141、タイムアウト 33（**90s でも補償チェーンが間に合わないケース**が残存）。

### 3 replicas

- **baseline**: API p95 ≈ **0.32s**、E2E p95 ≈ **4.5s**（サンプル）、Prometheus `fx_core_tx_p95_s` ≈ **0.10s**。
- **smoke**: E2E p95 ≈ **1.84s**（1r 比で悪化）。

## 4. RPS スイープ（3 replicas, 60s/step）

出力: `loadtest/load-sweep-report.json` / `loadtest/load-sweep-report.md`

- 各レートで k6 は閾値 **exit 99**（baseline の mixed 失敗・E2E サンプル条件など）。
- Prometheus では `fx_core_tx_p95_s` が **約 0.12〜0.20s** 台、`kafka_lag_max` **約 150〜270** 程度で推移（スナップショット値）。

## 5. 真因メモ（本対策後も残る課題）

1. **accounting_fail**: 補償フロー全体が **90s を超える**ケースがあり、E2E 検証のタイムアウトと衝突。対策案: `TRACE_TIMEOUT_MS` のさらなる延長、補償経路の非同期化・並列度、Kafka/Consumer の詰まり解消。
2. **3 replicas baseline FAIL**: 主に **k6 の閾値**（`trade_api_latency` p99 / `business_failure_rate`）と **分散環境での E2E 尾部**。Route 延長とインデックスで HTTP 切断は抑えつつ、**スループット上限**は別途（DB 接続・パーティション・コア Tx）。

## 6. デプロイ手順メモ

- イメージ: `fx-core-service` / `trade-saga-service` をビルド・プッシュ済み想定。
- Route 未反映の場合:  
  `oc -n fx-trading-sample patch route fx-core-service --type=merge -p '{"metadata":{"annotations":{"haproxy.router.openshift.io/timeout":"120s"}}}'`
- ConfigMap `fx-postgres-init` を `init.sql` と同期して再適用する場合は **既存 DB は init 再実行されない**ため、アプリ起動時の **CREATE INDEX IF NOT EXISTS** に依存。
