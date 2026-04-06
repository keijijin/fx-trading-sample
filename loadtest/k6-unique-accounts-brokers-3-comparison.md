# K6 Unique Accounts Replica Comparison (Kafka 3 Brokers)

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1744 | 8.2127 | 7.6810 |
| Fastest (s) | 0.1364 | 0.1395 | 0.1391 |
| Slowest (s) | 0.3784 | 132.5629 | 30.2367 |
| Requests/sec | 372.7533 | 9.6774 | 6.5733 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | N/A | N/A | N/A |
| HTTP p99 (s) | N/A | N/A | N/A |
| HTTP error rate (/s) | N/A | N/A | N/A |
| trade_saga p95 (s) | 30.0000 | 30.0000 | N/A |
| trade_saga p99 (s) | 30.0000 | 30.0000 | N/A |
| Outbox backlog max | 8808.0000 | 6120.0000 | 5.0000 |
| Hikari active max | 3.0000 | 1.0000 | 1.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 16017.0000 | 3585.0000 | 4484.0000 |
| Pod CPU sum (cores) | 1.2134 | 1.2564 | 1.0822 |
| Pod Memory sum | 5.33 GiB | 14.14 GiB | 22.40 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
