# Baseline RPS スイープ

- namespace: `fx-trading-sample`
- replicas: **3**
- duration / step: `60s`

| RATE (req/s) | k6 PASS | p95 API (s) | http_fail (rate) | saga_completed | trace_timeout | fx_core_tx_p95 (Prom) | kafka_lag_max |
|---|---:|---:|---|---|---|---|---|
| 15 | False | 0.3923 | None | 48 | 3 | 0.12228726246666663 | 152.0 |
| 30 | False | 0.3621 | None | 82 | 7 | 0.19964886842500018 | 274.0 |
| 50 | False | 0.3228 | None | 122 | 5 | 0.12129305708888884 | 218.0 |

Prometheus は各ステップ実行後の瞬間クエリのため、ピークと一致しない場合があります。