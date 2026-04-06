# K6 1000 Accounts Replica Comparison (Partitions=6)

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1739 | 1.9994 | 6.1353 |
| Fastest (s) | 0.1372 | 0.1383 | 0.1400 |
| Slowest (s) | 0.2594 | 30.2359 | 30.2478 |
| Requests/sec | 371.9174 | 27.9474 | 8.0365 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | N/A | N/A | N/A |
| HTTP p99 (s) | N/A | N/A | N/A |
| HTTP error rate (/s) | 0.0000 | 0.0000 | 0.0000 |
| trade_saga p95 (s) | 11.0051 | N/A | N/A |
| trade_saga p99 (s) | 12.1912 | N/A | N/A |
| Outbox backlog max | 2112.0000 | 9.0000 | 5.0000 |
| Hikari active max | 1.0000 | 1.0000 | 1.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 606.0000 | 2782.0000 | 2782.0000 |
| Pod CPU sum (cores) | 1.3596 | 0.6682 | 0.7938 |
| Pod Memory sum | 5.99 GiB | 14.13 GiB | 22.13 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
