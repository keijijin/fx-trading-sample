# K6 Unique Accounts Replica Comparison

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1744 | 0.2119 | 2.7841 |
| Fastest (s) | 0.1395 | 0.1390 | 0.1397 |
| Slowest (s) | 0.3077 | 1.2981 | 30.2395 |
| Requests/sec | 371.9137 | 305.9069 | 22.6627 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | N/A | N/A | N/A |
| HTTP p99 (s) | N/A | N/A | N/A |
| HTTP error rate (/s) | 0.0000 | 0.0000 | 0.0000 |
| trade_saga p95 (s) | N/A | 30.0000 | N/A |
| trade_saga p99 (s) | N/A | 30.0000 | N/A |
| Outbox backlog max | 3136.0000 | 8220.0000 | 3551.0000 |
| Hikari active max | 1.0000 | 1.0000 | 1.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 4720.0000 | 5733.0000 | 11931.0000 |
| Pod CPU sum (cores) | 0.6503 | 5.7825 | 1.0915 |
| Pod Memory sum | 5.98 GiB | 14.85 GiB | 24.02 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
