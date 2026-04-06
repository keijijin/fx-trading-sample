# スパイク × スケールアウト試験レポート

- namespace: `fx-trading-sample`
- 相: warm 45s → spike 25s → cool 45s（`k6-spike-scale-test.js`）
- 到着率: 平常 20/s → スパイク 100/s

## 結果サマリー

| replicas | k6 exit | 閾値 | HTTP p95(s)* | POST 201 rate/s* | Kafka lag max* | Outbox backlog max* |
|---|---:|:---:|---:|---:|---:|---:|
| 1 | 0 | PASS | 0.005627949940677962 | 24.606060606060602 | 771.0 | 10.0 |
| 3 | 99 | FAIL | 0.008057317849999995 | 12.296969696969695 | 912.0 | 17.0 |

* Prometheus は試験直後の `3m` ウィンドウのスナップショットです。

## 解釈メモ

- k6 exit **99** は閾値違反（サマリーの thresholds）を示すことがあります。
- replicas を増やしても HTTP p95 や lag が改善しない場合、**単一約定コアや DB** が限界の可能性があります。

