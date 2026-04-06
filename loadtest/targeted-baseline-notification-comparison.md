# Targeted Baseline / Notification Fail Rerun

## 条件

- 対象: `baseline`, `notification_fail`
- 対象レプリカ: `3`, `5`
- 反映済み変更:
  - `notification_fail` close-out 最適化
  - `trade_saga completeIfReady()` の条件付き更新化
  - `fx_trade_saga_close_out_*` メトリクス追加
  - `fx-core` ACID 区間の一部短縮
- 備考: focused rerun のため、短縮条件で実行

## 結果

| シナリオ | replicas | 判定 | 平均応答(s) | p95(s) | req/s | 受理数 | Trace検証数 | Trace timeout | Saga完了 | Saga失敗 |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `baseline` | 3 | FAIL | 3.2786 | 6.7614 | 14.4318 | 1299 | 64 | 52 | 0 | 52 |
| `notification_fail` | 3 | FAIL | 6.5022 | 24.2728 | 3.4886 | 314 | 57 | 36 | 0 | 36 |
| `baseline` | 5 | FAIL | 4.7053 | 15.8587 | 4.7995 | 432 | 18 | 15 | 0 | 15 |
| `notification_fail` | 5 | FAIL | 110.5535 | 783.1207 | 0.1714 | 145 | 23 | 18 | 0 | 18 |

## 所見

- `baseline` は `3 / 5 replicas` とも改善せず、`Saga完了=0` のままでした。
- `notification_fail` も `3 / 5 replicas` で `Saga完了=0` のままで、`trace` timeout が多数残っています。
- 特に `notification_fail @ 5 replicas` は `http_req_failed=81.59%`, `trade_api_latency p95=783.1207s`, `http_req_duration p95=12m41s` まで悪化しており、close-out より前段で処理が崩壊しています。
- したがって、今回の close-out 最適化は `1 replica` 側の無駄を減らすには有効でも、`3 / 5 replicas` を支配する主ボトルネックの解消には至っていません。
- 追加観測では `trade-saga-service` に consumer group rebalance が出ており、スケール時の後続安定化コストも残っています。

## 結論

- `notification_fail` の close-out は軽くしたが、実際には `fx-core` 前段の重さとスケール時の Saga 側収束遅延が先に限界へ達する。
- `baseline` と `notification_fail` を落としている支配要因は、依然として `fx-core` 入口の遅延と、その後ろに積み上がる Saga / consumer 側の滞留である。
