# K6 1000 Accounts 5 Replicas (Kafka 3 Brokers + DB Split + Activity Async)

## サマリー

- ベースラインは `5` replica です。
- 比較対象: なし
- accountId: `pool=1000`

## k6 結果

| 指標 | 5 replicas |
|---|---:|
| Average latency (s) | 0.4096 |
| Fastest (s) | 0.1421 |
| Slowest (s) | 4.5852 |
| Requests/sec | 155.0270 |

| HTTP failed rate | N/A |

## Prometheus 指標

| 指標 | 5 replicas |
|---|---:|
| HTTP p95 (s) | 1.2031 |
| HTTP p99 (s) | 1.7875 |
| HTTP error rate (/s) | N/A |
| trade_saga p95 (s) | N/A |
| trade_saga p99 (s) | N/A |
| Outbox backlog max | 1132.0000 |
| Hikari active max | 10.0000 |
| Hikari pending max | 14.0000 |
| Hikari max | 10.0000 |
| Kafka lag max | 511.0000 |
| Pod CPU sum (cores) | 13.7187 |
| Pod Memory sum | 24.65 GiB |
| Pod Restarts | 0.0000 |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
