# パフォーマンス調整レポート（2026-04-16）

## 目的

OpenShift 上の `fx-trading-sample` について、2026-04-15 から 2026-04-16 にかけて以下を実施した。

1. フルテスト / 負荷試験で現状ボトルネックを再確認
2. DB CPU、`max_connections`、Hikari pool を段階的に調整
3. 同一の `stress` シナリオを繰り返し実行し、改善 / 悪化を比較
4. 最終的に user-facing な `stress` 条件を PASS できる構成へ到達する

## 実施環境

- namespace: `fx-trading-sample`
- Kafka: 3 brokers
- PostgreSQL: db-separated 構成
- 負荷試験スクリプト: `loadtest/k6-test-plan-suite.js`
- 対象シナリオ: `SCENARIO_MODE=stress`
- stress 条件:
  - `STRESS_START_RATE=20`
  - `STRESS_TARGET_ONE=50`
  - `STRESS_TARGET_TWO=100`
  - `STRESS_TARGET_THREE=150`
  - 各 stage 1 分
  - `TRACE_SAMPLE_RATE=0.005`

## 実施内容サマリー

### 1. 初期確認

- `backend`: `mvn -pl integration-tests -am test` 実行
- `frontend`: `npm run lint` / `npm run build` 実行
- OpenShift 上で `run_test_plan_suite.py`、`run_spike_scale_test.py` を実行

### 2. DB CPU 増強

- `fx-core-db`
  - `requests.cpu: 500m -> 1`
  - `limits.cpu: 1 -> 2`
- `fx-trade-saga-db`
  - `requests.cpu: 500m -> 1`
  - `limits.cpu: 1500m -> 3`

### 3. `max_connections` と Hikari の一次調整

- `fx-core-db`: `max_connections=160 -> 240`
- `fx-trade-saga-db`: `max_connections=300 -> 400`
- `fx-core-service`
  - `DB_POOL_MAX_SIZE=12`
  - `DB_POOL_MIN_IDLE=3`
  - `ACTIVITY_DB_POOL_MAX_SIZE=6`
  - `SAGA_DB_POOL_MAX_SIZE=4`
- `trade-saga-service`
  - `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=6`
- 他サービス
  - `ACTIVITY_DB_POOL_MAX_SIZE=4`

### 4. Hikari の再調整

- `fx-core-service`
  - `DB_POOL_MAX_SIZE=16`
  - `DB_POOL_MIN_IDLE=4`
  - `ACTIVITY_DB_POOL_MAX_SIZE=8`
  - `SAGA_DB_POOL_MAX_SIZE=5`
- `trade-saga-service`
  - `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=8`
  - `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=3`
- 他サービス
  - `ACTIVITY_DB_POOL_MAX_SIZE=5`

### 5. 最終施策: `fx-trade-saga-db` の追加増強

- `fx-trade-saga-db`
  - `requests.cpu: 1 -> 2`
  - `limits.cpu: 3 -> 5`
  - `requests.memory: 1Gi -> 2Gi`
  - `limits.memory: 2Gi -> 4Gi`

## 経緯と結果

### A. 初回フルスイート / 負荷確認

2026-04-15 時点のフルスイートでは、`smoke / baseline / accounting_fail / notification_fail` は PASS した一方、`cover_fail / stress / soak` は FAIL だった。

主要な症状:

- `stress`
  - `trade_accepted=14099`
  - `trace_timed_out=12`
  - `outbox_backlog_max=12196`
  - `hikari_pending_max=7`
- `soak`
  - `trace_timed_out=10`
  - `outbox_backlog_max=12628`

入口 API より後段の非同期処理側で `outbox backlog`、`trace timeout` が積み上がっていた。

### B. DB CPU 増強のみ

DB CPU 増強後の `stress` 単独再試験では、以下の改善を確認した。

- `trade_api_latency p95=265ms`
- `http_req_failed=0.00%`
- `trace_timed_out: 12 -> 1`
- `saga_p95_s: 1.896 -> 0.397`
- `outbox_backlog_max: 12196 -> 10959`

一方で、以下が残った。

- `business_failure_rate=6.25%`
- `kafka_lag_max=501`
- `hikari_pending_max=19`

結論:

- DB CPU 増強は有効
- ただし接続待ち / 後段遅延はまだ残る

### C. `max_connections` + Hikari 一次調整

接続待ち緩和を狙って pool をかなり絞った結果、`outbox_backlog` 自体は大きく減ったが、受理率が悪化した。

結果:

- `trade_accepted=7795`
- `http_req_failed=42.44%`
- `trade_api_latency p95=248ms`
- `outbox_backlog_max=38`
- `hikari_pending_max=52`
- `kafka_lag_max=709`

Prometheus の status breakdown では `500` ではなく `409` が大半で、サーバ障害よりも競合・業務拒否を誘発した。

結論:

- pool を絞りすぎる調整は不採用

### D. Hikari 再調整

一次調整を戻し寄りにしたところ、入口側はほぼ正常化した。

結果:

- `trade_accepted=14017`
- `http_req_failed=0.42%`
- `trade_api_latency p95=248ms`
- `hikari_pending_max=1`
- `status_breakdown`: `201` のみ観測

ただし、後段の非同期完了側は依然として重かった。

- `business_failure_rate=78.16%`
- `saga_p95_s=30.0`
- `kafka_lag_max=1000`
- `fx-trade-saga-db` の CPU 使用率が高止まり

結論:

- 入口の接続待ちは解消
- 次の単一ボトルネックは `fx-trade-saga-db`

### E. `fx-trade-saga-db` 追加増強後

最終的に `fx-trade-saga-db` の CPU / memory を追加増強し、同じ `stress` を再実行したところ PASS した。

#### k6 結果

- `k6 exit_code=0`
- `trade_accepted=14099`
- `http_req_failed=0.00%`
- `business_failure_rate=0.00%`
- `trade_api_latency p95=240ms`
- `trade_api_latency p99=264ms`
- `trade_e2e_latency p95=13953.95ms`

#### Prometheus 結果

- `http_p95_s=0.024`
- `http_p99_s=0.069`
- `saga_p95_s=13.85`
- `outbox_backlog_max=569`
- `hikari_pending_max=0`
- `kafka_lag_max=608`
- `fx_core_tx_p95_s=0.0092`

#### Pod 使用率

試験後の `oc adm top pod` では、`fx-trade-saga-db` が約 `4997m CPU` を使用しており、追加 CPU を実際に消費して性能改善へ寄与した。

## 比較表

| 段階 | k6判定 | trade accepted | http_req_failed | business_failure_rate | trade p95 | outbox backlog max | hikari pending max | kafka lag max |
|---|:---:|---:|---:|---:|---:|---:|---:|---:|
| 初回 full suite 内 stress | FAIL | 14099 | 0.00% | n/a | 246ms | 12196 | 7 | 345 |
| DB CPU 増強 | FAIL | 14099 | 0.00% | 6.25% | 265ms | 10959 | 19 | 501 |
| `max_connections` + pool 一次調整 | FAIL | 7795 | 42.44% | 99.64% | 248ms | 38 | 52 | 709 |
| Hikari 再調整 | FAIL | 14017 | 0.42% | 78.16% | 248ms | 178 | 1 | 1000 |
| `fx-trade-saga-db` 追加増強 | **PASS** | **14099** | **0.00%** | **0.00%** | **240ms** | **569** | **0** | **608** |

## 成果

1. `stress` 条件を最終的に PASS できる構成へ到達した。
2. ボトルネックは段階ごとに移動し、最終的に `fx-trade-saga-db` の CPU が決定的制約だったことを確認した。
3. `DB CPU 増強 -> 接続調整 -> 再調整 -> saga DB 増強` の順で、単一障害点を順番に潰す有効なチューニング手順を実証できた。
4. `max_connections` と Hikari pool は単独で最適化するのではなく、後段 DB の処理能力と一体で調整すべきことが分かった。

## 現時点の結論

- user-facing な `stress` 条件については、現行の最終構成で PASS
- ただし `saga_p95_s=13.85`、`kafka_lag_max=608` であり、後段非同期処理はまだ改善余地あり
- 次段の候補:
  - `trade-saga-service` と projection 更新経路の負荷分離
  - Kafka consumer / topic 側の並列度と backpressure 設定見直し
  - `fx-trade-saga-db` の I/O / shared buffers を含む DB 設定見直し

## 参照ファイル

- フルスイート結果: `loadtest/reports/test-plan-suite-2026-04-15-generated.json`
- スパイク比較結果: `loadtest/reports/spike-scale-2026-04-15-generated.json`
- 本調整の記録: `openshift/fx-trading-db-separated.yaml`
