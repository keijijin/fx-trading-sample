# K6 1000 Accounts Replica Comparison (Kafka 3 Brokers + DB Split + Activity Async)

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1738 | 0.1748 | 0.1931 |
| Fastest (s) | 0.1347 | 0.1356 | 0.1379 |
| Slowest (s) | 0.3416 | 0.7574 | 1.8160 |
| Requests/sec | 375.9718 | 373.6317 | 336.8399 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | 0.0109 | 0.0299 | 0.1437 |
| HTTP p99 (s) | 0.0175 | 0.0600 | 0.3751 |
| HTTP error rate (/s) | N/A | N/A | N/A |
| trade_saga p95 (s) | N/A | N/A | N/A |
| trade_saga p99 (s) | N/A | N/A | N/A |
| Outbox backlog max | 87.0000 | 1551.0000 | 3599.0000 |
| Hikari active max | 1.0000 | 1.0000 | 3.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 752.0000 | 2018.0000 | 707.0000 |
| Pod CPU sum (cores) | 7.4158 | 12.8864 | 14.4177 |
| Pod Memory sum | 6.35 GiB | 17.29 GiB | 28.71 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 2.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
