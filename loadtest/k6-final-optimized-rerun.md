# K6 Final Optimized Replica Comparison (Rerun)

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1738 | 4.6287 | 6.2710 |
| Fastest (s) | 0.1356 | 0.1408 | 0.1408 |
| Slowest (s) | 0.2440 | 30.2346 | 30.2234 |
| Requests/sec | 374.8539 | 11.6177 | 7.8337 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | 0.0079 | 27.9173 | 19.4228 |
| HTTP p99 (s) | 0.0139 | 29.6241 | 22.2098 |
| HTTP error rate (/s) | N/A | N/A | N/A |
| trade_saga p95 (s) | 1.1863 | 30.0000 | 22.6202 |
| trade_saga p99 (s) | 1.3826 | 30.0000 | 30.0000 |
| Outbox backlog max | 105.0000 | 8.0000 | 1.0000 |
| Hikari active max | 1.0000 | 27.0000 | 11.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 30.0000 | 30.0000 | 30.0000 |
| Kafka lag max | 2942.0000 | 192.0000 | 48.0000 |
| Pod CPU sum (cores) | 4.1381 | 1.4646 | 2.1528 |
| Pod Memory sum | 6.97 GiB | 15.62 GiB | 23.35 GiB |
| Pod Restarts | 6.0000 | 6.0000 | 10.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
