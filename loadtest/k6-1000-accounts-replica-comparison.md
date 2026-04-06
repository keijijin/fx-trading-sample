# K6 1000 Accounts Replica Comparison

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1732 | 0.1742 | 2.4420 |
| Fastest (s) | 0.1358 | 0.1355 | 0.1386 |
| Slowest (s) | 0.2668 | 0.4510 | 30.2405 |
| Requests/sec | 375.7408 | 373.7318 | 25.7357 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | N/A | N/A | N/A |
| HTTP p99 (s) | N/A | N/A | N/A |
| HTTP error rate (/s) | 0.0000 | 0.0000 | 0.0000 |
| trade_saga p95 (s) | N/A | N/A | N/A |
| trade_saga p99 (s) | N/A | N/A | N/A |
| Outbox backlog max | 3122.0000 | 2038.0000 | 1712.0000 |
| Hikari active max | 2.0000 | 7.0000 | 1.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 11354.0000 | 19658.0000 | 29601.0000 |
| Pod CPU sum (cores) | 0.9894 | 4.7692 | 1.3088 |
| Pod Memory sum | 6.34 GiB | 13.58 GiB | 21.66 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
