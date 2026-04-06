# Targeted Baseline / Notification Fail Comparison v2

## 対象

- 実装後の focused rerun 比較
- 改善内容:
  - `fx_position` 非同期 projection 化
  - `trade_saga` seed を `fx-core` から分離
  - `completeIfReady()` 条件付き更新化
  - `fx_trade_saga_close_out_*` メトリクス追加
  - Kafka consumer 設定見直し
  - Outbox poller 調整 (`50ms`, `300 rows`)

## 結果比較

| シナリオ | replicas | before avg(s) | after avg(s) | before p95(s) | after p95(s) | before req/s | after req/s | before trace timeout | after trace timeout |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `baseline` | 3 | 3.2786 | 1.2159 | 6.7614 | 9.1255 | 14.4318 | 15.0328 | 52 | 61 |
| `notification_fail` | 3 | 6.5022 | 9.7326 | 24.2728 | 25.2118 | 3.4886 | 4.0106 | 36 | 62 |
| `baseline` | 5 | 4.7053 | 1.8643 | 15.8587 | 7.4238 | 4.7995 | 9.5436 | 15 | 41 |
| `notification_fail` | 5 | 110.5535 | 3.2918 | 783.1207 | 18.0456 | 0.1714 | 3.4220 | 18 | 63 |

## 所見

- `baseline @ 3 replicas`: 平均応答は `3.2786s -> 1.2159s` まで改善した一方、p95 は `6.7614s -> 9.1255s` で悪化し、tail latency はまだ重い。
- `baseline @ 5 replicas`: 平均応答は `4.7053s` へ、p95 は `15.8587s` へ改善し、throughput も `4.7995 -> 9.5436 req/s` と改善。
- `notification_fail @ 3 replicas`: 逆に `6.5022s -> 9.7326s` と悪化し、close-out 最適化だけでは支え切れていない。
- `notification_fail @ 5 replicas`: 劇的に改善し、平均応答は `110.5535s -> 3.2918s`、p95 は `783.1207s -> 18.0456s`、req/s は `0.1714 -> 3.4220` へ回復。
- ただし全ケースで `Saga完了=0` のままで、tail 側ではまだ `trace timeout` が多い。支配要因は close-out ではなく、依然として前段詰まりと後続収束遅延の複合。
- `trade-saga-service` では consumer group rebalance ログが継続して出ており、Kafka consumer 安定化はまだ不十分。

## 結論

- 今回の実装は **入口経路の平均応答を改善する方向には効いた**。特に `baseline @ 5r` と `notification_fail @ 5r` で改善が確認できた。
- しかし **p95/p99 と Saga 完了率はまだ基準未達** で、`3 / 5 replicas` を pass へ戻すには足りない。
- 次の本命は、`fx-core` の残る同期 ACID 更新をさらに削ることと、`trade-saga-service` の consumer group / topic 配置を見直して rebalance を減らすこと。
