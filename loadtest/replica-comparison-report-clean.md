# Replica Comparison Report (Clean Run)

## サマリー

- ベースラインは `1` replica、比較対象は `3` replicas です。
- ベースライン accountId: `ACC-LOAD-R1-001`
- 比較対象 accountId: `ACC-LOAD-R3-001`

## hey 結果

| 指標 | 1 replica | 3 replicas | 差分 |
|---|---:|---:|---:|
| Average latency (s) | 0.1791 | 0.2876 | +60.58% |
| Fastest (s) | 0.1397 | 0.1388 | -0.64% |
| Slowest (s) | 0.8807 | 18.6115 | +2013.26% |
| Requests/sec | 166.3734 | 37.6523 | -77.37% |
| Status counts | 201=5040, 500=9 | 201=1827, 500=25 | - |

## Prometheus 指標

| 指標 | 1 replica | 3 replicas | 差分 |
|---|---:|---:|---:|
| HTTP p95 (s) | N/A | N/A | N/A |
| HTTP p99 (s) | N/A | N/A | N/A |
| HTTP error rate (/s) | 0.2995 | 0.0000 | -100.00% |
| trade_saga p95 (s) | 0.6066 | N/A | N/A |
| trade_saga p99 (s) | 0.6638 | N/A | N/A |
| Outbox backlog max | 32.0000 | 3.0000 | -90.62% |
| Hikari active max | 1.0000 | 1.0000 | +0.00% |
| Hikari pending max | 0.0000 | 0.0000 | N/A |
| Hikari max | 10.0000 | 10.0000 | +0.00% |
| Kafka lag max | 1255.0000 | 1813.0000 | +44.46% |
| Pod CPU sum (cores) | 4.3674 | 1.1847 | -72.87% |
| Pod Memory sum | 6.02 GiB | 14.09 GiB | +134.23% |
| Pod Restarts | 0.0000 | 0.0000 | N/A |

## 読み方

- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。
- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。
- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。
- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。
- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。
- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。
