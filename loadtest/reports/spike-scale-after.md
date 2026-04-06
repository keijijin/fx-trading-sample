# スパイク × スケールアウト試験レポート

- namespace: `fx-trading-sample`
- 相: warm 120s → spike 60s → cool 120s（`k6-spike-scale-test.js`）
- 到着率: 平常 20/s → スパイク 100/s

## 結果サマリー

| replicas | k6 exit | 閾値 | HTTP p95(s)* | POST 201 rate/s* | Kafka lag max* | Outbox backlog max* |
|---|---:|:---:|---:|---:|---:|---:|
| 1 | 0 | PASS | 0.0075846989250000045 | 42.030303030303024 | 634.0 | None |
| 3 | 0 | PASS | 0.1450110691324997 | 13.618181818181816 | 2167.0 | None |
| 5 | 0 | PASS | 0.12644428370249997 | 10.296720079513225 | 1836.0 | None |

* Prometheus は試験直後の `3m` ウィンドウのスナップショットです。

## 相別 Prometheus（query_range）

試験ブロック開始時刻を `t0` とし、k6 の warm / spike / cool 秒数と**同じ幅**の Unix 窓で `sum(fx_outbox_backlog_total)` / `max(fx_kafka_consumer_group_lag)` / `sum(hikaricp_connections_active)` を集計（min/max/avg）。

| replicas | 相 | Outbox avg | Outbox max | Kafka lag avg | Kafka lag max | Hikari active avg | Hikari max |
|---|:---:|---:|---:|---:|---:|---:|---:|
| 1 | warm | None | None | 78.33333333333333 | 100.0 | 8.555555555555555 | 10.0 |
| 1 | spike | None | None | 411.8 | 634.0 | 14.2 | 20.0 |
| 1 | cool | None | None | 244.66666666666666 | 598.0 | 10.555555555555555 | 19.0 |
| 3 | warm | None | None | 71.0 | 102.0 | 14.222222222222221 | 19.0 |
| 3 | spike | None | None | 706.0 | 1776.0 | 21.8 | 32.0 |
| 3 | cool | None | None | 773.3333333333334 | 2167.0 | 13.777777777777779 | 28.0 |
| 5 | warm | None | None | 77.0 | 140.0 | 13.555555555555555 | 21.0 |
| 5 | spike | None | None | 358.8 | 1193.0 | 16.0 | 23.0 |
| 5 | cool | None | None | 821.3333333333334 | 1836.0 | 15.555555555555555 | 23.0 |

## 解釈メモ

- k6 exit **99** は閾値違反（サマリーの thresholds）を示すことがあります。
- replicas を増やしても HTTP p95 や lag が改善しない場合、**単一約定コアや DB** が限界の可能性があります。
- **spike 相**で Outbox max や Kafka lag max が突出する場合は、Saga 以外のボトルネック（配信遅延・コンシューマ）を疑う。

