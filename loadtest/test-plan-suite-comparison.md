# FX Trading Test Plan Replica Comparison

## 判定サマリー

| シナリオ | 1 replicas | 3 replicas | 5 replicas |
|---|---|---|---|
| スモーク (`smoke`) | PASS | FAIL | FAIL |
| ベースライン (`baseline`) | PASS | FAIL | FAIL |
| Cover失敗補償 (`cover_fail`) | PASS | FAIL | FAIL |
| 会計失敗補償 (`accounting_fail`) | PASS | FAIL | FAIL |
| 通知失敗非致命 (`notification_fail`) | PASS | FAIL | FAIL |
| ストレス (`stress`) | PASS | FAIL | FAIL |
| 耐久(短縮版) (`soak`) | PASS | FAIL | FAIL |

## baseline / notification_fail 詳細

| 指標 | baseline 1r | baseline 3r | baseline 5r |
|---|---:|---:|---:|
| 平均応答(s) | 0.1678 | 2.8354 | 5.3576 |
| p95(s) | 0.2298 | 5.7154 | 14.4409 |
| req/s | 49.4265 | 18.1992 | 14.2397 |
| Trace検証数 | 304 | 130 | 104 |
| Trace timeout | N/A | 107 | 88 |

| 指標 | notification_fail 1r | notification_fail 3r | notification_fail 5r |
|---|---:|---:|---:|
| 平均応答(s) | 0.1673 | 3.5500 | 7.1952 |
| p95(s) | 0.2280 | 14.8225 | 26.1239 |
| req/s | 14.7892 | 1.8353 | 4.2323 |
| Trace検証数 | 289 | 41 | 99 |
| Trace timeout | N/A | 27 | 68 |

## 観測ポイント

- 今回の比較は sampled trace 検証版の同一スイートで揃えています。
- `baseline` と `notification_fail` の PASS/FAIL は、計測系の自己負荷を除去した後の結果です。
