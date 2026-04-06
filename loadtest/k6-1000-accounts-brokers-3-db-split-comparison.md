# K6 1000 Accounts Replica Comparison (Kafka 3 Brokers + DB Split)

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1745 | 0.1754 | 3.2405 |
| Fastest (s) | 0.1394 | 0.1370 | 0.1429 |
| Slowest (s) | 0.4025 | 0.7036 | 30.2603 |
| Requests/sec | 371.3118 | 372.0231 | 18.0996 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | 0.0275 | 0.0313 | 0.0101 |
| HTTP p99 (s) | 0.0399 | 0.0668 | 0.0110 |
| HTTP error rate (/s) | N/A | 0.1477 | 0.0000 |
| trade_saga p95 (s) | N/A | N/A | N/A |
| trade_saga p99 (s) | N/A | N/A | N/A |
| Outbox backlog max | 506.0000 | 719.0000 | 583.0000 |
| Hikari active max | 0.0000 | 0.0000 | 0.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 687.0000 | 3774.0000 | 7748.0000 |
| Pod CPU sum (cores) | 5.5418 | 12.7463 | 1.6829 |
| Pod Memory sum | 5.93 GiB | 16.78 GiB | 26.47 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
