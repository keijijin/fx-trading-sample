# K6 Balance Bucket Replica Comparison

## サマリー

- ベースラインは `1` replica です。
- 比較対象: `3` replicas, `5` replicas
- accountId: `pool=1000`, `pool=1000`, `pool=1000`

## k6 結果

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| Average latency (s) | 0.1738 | 0.1804 | 1.8266 |
| Fastest (s) | 0.1361 | 0.1378 | 0.1398 |
| Slowest (s) | 0.2917 | 0.7963 | 30.1461 |
| Requests/sec | 375.9083 | 361.0822 | 28.1845 |

| HTTP failed rate | N/A | N/A | N/A |

## Prometheus 指標

| 指標 | 1 replicas | 3 replicas | 5 replicas |
|---|---:|---:|---:|
| HTTP p95 (s) | 0.0166 | 0.0434 | 3.1317 |
| HTTP p99 (s) | 0.0261 | 0.0996 | 13.7081 |
| HTTP error rate (/s) | N/A | N/A | N/A |
| trade_saga p95 (s) | 2.3556 | 6.9437 | 5.2434 |
| trade_saga p99 (s) | 2.4754 | 7.1154 | 13.6150 |
| Outbox backlog max | 395.0000 | 4459.0000 | 83.0000 |
| Hikari active max | 3.0000 | 5.0000 | 8.0000 |
| Hikari pending max | 0.0000 | 0.0000 | 0.0000 |
| Hikari max | 8.0000 | 8.0000 | 8.0000 |
| Kafka lag max | 3602.0000 | 2478.0000 | 303.0000 |
| Pod CPU sum (cores) | 3.3239 | 14.2016 | 1.8693 |
| Pod Memory sum | 6.78 GiB | 15.56 GiB | 25.44 GiB |
| Pod Restarts | 6.0000 | 6.0000 | 10.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
