# K6 1000 Accounts Replica Comparison (Kafka 3 Brokers)

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1755 | 0.1753 | 3.5095 |
| Fastest (s) | 0.1340 | 0.1367 | 0.1392 |
| Slowest (s) | 0.3697 | 0.4794 | 30.3054 |
| Requests/sec | 370.5488 | 372.0254 | 16.3485 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | N/A | N/A | N/A |
| HTTP p99 (s) | N/A | N/A | N/A |
| HTTP error rate (/s) | N/A | N/A | N/A |
| trade_saga p95 (s) | N/A | N/A | N/A |
| trade_saga p99 (s) | N/A | N/A | N/A |
| Outbox backlog max | 5680.0000 | 9943.0000 | 6563.0000 |
| Hikari active max | 4.0000 | 1.0000 | 1.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 10.0000 | 10.0000 | 10.0000 |
| Kafka lag max | 588.0000 | 1816.0000 | 10099.0000 |
| Pod CPU sum (cores) | 4.2091 | 5.7749 | 1.3407 |
| Pod Memory sum | 4.85 GiB | 13.26 GiB | 21.55 GiB |
| Pod Restarts | 0.0000 | 0.0000 | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
