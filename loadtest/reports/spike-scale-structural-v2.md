# スパイク × スケールアウト試験レポート

- namespace: `fx-trading-sample`
- 相: warm 120s → spike 60s → cool 120s（`k6-spike-scale-test.js`）
- 到着率: 平常 20/s → スパイク 100/s

## 結果サマリー

| replicas | k6 exit | 閾値 | HTTP p95(s)* | POST 201 rate/s* | Kafka lag max* | Outbox backlog max* |
|---|---:|:---:|---:|---:|---:|---:|
| 1 | 0 | PASS | 0.004881030813373859 | 46.884848484848476 | 699.0 | None |
| 3 | 0 | PASS | 0.005059639278510638 | 16.915151515151514 | 1017.0 | None |
| 5 | 0 | PASS | 0.005294123706144066 | 10.95151515151515 | 1035.0 | None |

* Prometheus は試験直後の `3m` ウィンドウのスナップショットです。

## 相別 Prometheus（query_range）

試験ブロック開始時刻を `t0` とし、k6 の warm / spike / cool 秒数と**同じ幅**の Unix 窓で `sum(fx_outbox_backlog_total)` / `max(fx_kafka_consumer_group_lag)` / `sum(hikaricp_connections_active)` を集計（min/max/avg）。

| replicas | 相 | Outbox avg | Outbox max | Kafka lag avg | Kafka lag max | Hikari active avg | Hikari max |
|---|:---:|---:|---:|---:|---:|---:|---:|
| 1 | warm | None | None | 80.33333333333333 | 99.0 | 1.3333333333333333 | 3.0 |
| 1 | spike | None | None | 433.0 | 699.0 | 3.4 | 5.0 |
| 1 | cool | None | None | 193.22222222222223 | 492.0 | 1.8888888888888888 | 5.0 |
| 3 | warm | None | None | 100.88888888888889 | 139.0 | 0.4444444444444444 | 1.0 |
| 3 | spike | None | None | 483.2 | 1017.0 | 0.8 | 3.0 |
| 3 | cool | None | None | 302.44444444444446 | 721.0 | 0.3333333333333333 | 1.0 |
| 5 | warm | None | None | 147.66666666666666 | 183.0 | 0.5555555555555556 | 2.0 |
| 5 | spike | None | None | 561.6 | 1035.0 | 1.4 | 3.0 |
| 5 | cool | None | None | 298.22222222222223 | 941.0 | 0.5555555555555556 | 3.0 |

## 解釈メモ

- k6 exit **99** は閾値違反（サマリーの thresholds）を示すことがあります。
- replicas を増やしても HTTP p95 や lag が改善しない場合、**単一約定コアや DB** が限界の可能性があります。
- **spike 相**で Outbox max や Kafka lag max が突出する場合は、Saga 以外のボトルネック（配信遅延・コンシューマ）を疑う。

