# K6 Final Optimized Replica Comparison

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1736 | 0.1867 | 2.3032 |
| Fastest (s) | 0.1381 | 0.1377 | 0.1419 |
| Slowest (s) | 0.3077 | 1.6588 | 30.1504 |
| Requests/sec | 373.7288 | 348.7828 | 20.0445 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | 0.0200 | 0.0678 | 21.3030 |
| HTTP p99 (s) | 0.0806 | 0.2163 | 26.9533 |
| HTTP error rate (/s) | N/A | N/A | N/A |
| trade_saga p95 (s) | 3.0755 | 7.1199 | 26.9151 |
| trade_saga p99 (s) | 3.1961 | 8.1567 | 28.2895 |
| Outbox backlog max | 689.0000 | 4976.0000 | 6.0000 |
| Hikari active max | 2.0000 | 11.0000 | 10.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 30.0000 | 30.0000 | 30.0000 |
| Kafka lag max | 2296.0000 | 1883.0000 | 477.0000 |
| Pod CPU sum (cores) | 6.0432 | 15.7392 | 1.2517 |
| Pod Memory sum | 6.08 GiB | 17.38 GiB | 30.24 GiB |
| Pod Restarts | 6.0000 | 6.0000 | 11.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
