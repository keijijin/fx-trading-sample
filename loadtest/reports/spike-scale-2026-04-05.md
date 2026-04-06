# スパイク × スケールアウト試験レポート

- namespace: `fx-trading-sample`
- 相: warm 120s → spike 60s → cool 120s（`k6-spike-scale-test.js`）
- 到着率: 平常 20/s → スパイク 100/s

## 結果サマリー

| replicas | k6 exit | 閾値 | HTTP p95(s)* | POST 201 rate/s* | Kafka lag max* | Outbox backlog max* |
|---|---:|:---:|---:|---:|---:|---:|
| 1 | 0 | PASS | 0.004546475497949522 | 48.01818181818181 | 657.0 | None |
| 3 | 0 | PASS | 0.16512312443421026 | 17.63668430335097 | 1111.0 | None |
| 5 | 0 | PASS | 0.19060064352051276 | 45.866388688553414 | 1354.0 | None |

* Prometheus は試験直後の `3m` ウィンドウのスナップショットです。

## 相別 Prometheus（query_range）

試験ブロック開始時刻を `t0` とし、k6 の warm / spike / cool 秒数と**同じ幅**の Unix 窓で `sum(fx_outbox_backlog_total)` / `max(fx_kafka_consumer_group_lag)` / `sum(hikaricp_connections_active)` を集計（min/max/avg）。

| replicas | 相 | Outbox avg | Outbox max | Kafka lag avg | Kafka lag max | Hikari active avg | Hikari max |
|---|:---:|---:|---:|---:|---:|---:|---:|
| 1 | warm | None | None | 94.44444444444444 | 113.0 | 5.555555555555555 | 8.0 |
| 1 | spike | None | None | 513.6 | 657.0 | 7.8 | 11.0 |
| 1 | cool | None | None | 191.77777777777777 | 581.0 | 6.111111111111111 | 9.0 |
| 3 | warm | None | None | 132.33333333333334 | 171.0 | 13.777777777777779 | 18.0 |
| 3 | spike | None | None | 470.6 | 902.0 | 31.2 | 44.0 |
| 3 | cool | None | None | 346.8888888888889 | 1111.0 | 14.444444444444445 | 36.0 |
| 5 | warm | None | None | 158.88888888888889 | 226.0 | 12.777777777777779 | 16.0 |
| 5 | spike | None | None | 597.0 | 982.0 | 29.6 | 47.0 |
| 5 | cool | None | None | 512.1111111111111 | 1354.0 | 13.88888888888889 | 32.0 |

## 解釈メモ

- k6 exit **99** は閾値違反（サマリーの thresholds）を示すことがあります。
- replicas を増やしても HTTP p95 や lag が改善しない場合、**単一約定コアや DB** が限界の可能性があります。
- **spike 相**で Outbox max や Kafka lag max が突出する場合は、Saga 以外のボトルネック（配信遅延・コンシューマ）を疑う。

