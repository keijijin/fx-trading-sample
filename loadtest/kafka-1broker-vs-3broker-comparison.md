# Kafka 1 Broker vs 3 Broker Comparison

## 目的

このレポートは、`fx-trading-sample` 環境で Kafka を

- `1 broker`
- `3 broker`

にした場合の差を、同じ `k6` 試験条件で比較するものです。

比較対象:

- `1000アカウント集中`
- `毎回ユニークアカウント`

参照レポート:

- `loadtest/k6-1000-accounts-replica-comparison.md`
- `loadtest/k6-1000-accounts-partitions-6-comparison.md`
- `loadtest/k6-unique-accounts-replica-comparison.md`

## 1. Kafka 構成の違い

### 旧構成

- Kafka: `1 broker`
- topic partition:
  - 初期は `1`
  - 後に `6` へ増加
- replication factor: `1`

### 新構成

- Kafka: `3 brokers`
  - `fx-kafka-0`
  - `fx-kafka-1`
  - `fx-kafka-2`
- topic partition: `6`
- replication factor: `3`
- min ISR: `2`

## 2. 1000アカウント集中の比較

### 1 broker, partitions=6

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 371.92 | 0.174s | 0.259s | 606 |
| 3 | 27.95 | 1.999s | 30.236s | 2782 |
| 5 | 8.04 | 6.135s | 30.248s | 2782 |

### 3 brokers, partitions=6

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 370.55 | 0.175s | 0.370s | 588 |
| 3 | 372.03 | 0.175s | 0.479s | 1816 |
| 5 | 16.35 | 3.510s | 30.305s | 10099 |

### 解釈

今回の再試験では、**1000アカウント集中** シナリオにおいて、Kafka 3 broker 化で `3 replicas` は大きく改善しました。

改善点:

- `3 replicas`: `27.95 req/s -> 372.03 req/s`
- `Average latency`: `1.999s -> 0.175s`
- `Kafka lag max`: `2782 -> 1816`

ただし `5 replicas` は依然として悪化しています。

- `8.04 req/s -> 16.35 req/s` へは改善
- しかし `1 replica` / `3 replicas` と比べるとまだ大きく遅い

## 3. 毎回ユニークアカウントの比較

### 1 broker, partitions=6

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 371.91 | 0.174s | 0.308s | 4720 |
| 3 | 305.91 | 0.212s | 1.298s | 5733 |
| 5 | 22.66 | 2.784s | 30.240s | 11931 |

### 3 brokers, partitions=6

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 372.75 | 0.174s | 0.378s | 16017 |
| 3 | 9.68 | 8.213s | 132.563s | 3585 |
| 5 | 6.57 | 7.681s | 30.237s | 4484 |

この結果では、**ユニークアカウント** シナリオでは Kafka 3 broker 化後に `3 / 5 replicas` が大きく悪化しました。

改善どころか、

- `3 replicas`: `305.91 req/s -> 9.68 req/s`
- `5 replicas`: `22.66 req/s -> 6.57 req/s`

となっており、現時点では broker 数増加以外の構成変化、consumer 再配置、または別要因の影響を疑う必要があります。

## 4. 今回分かったこと

### 1. 1000アカウント集中では Kafka 3 broker 化が効いた

特に `3 replicas` の改善は大きく、`1 broker` で見えていた consumer 側の詰まりに対して、broker の分散が効果を持った可能性があります。

### 2. しかし 5 replicas は依然として破綻気味

Kafka 3 broker 化後でも

- `5 replicas`
  - `16.35 req/s`
  - `3.510s avg`

であり、十分にスケールしたとは言えません。

### 3. ユニークアカウントでは逆に大きく悪化した

この点は追加調査が必要です。  
少なくとも「Kafka 3 broker 化だけで一貫して改善する」とは言えない結果でした。

## 5. ボトルネック候補の整理

優先度順:

1. `PostgreSQL` 単一インスタンス
2. `trade_saga` の依存チェーン
3. consumer group の再配置 / partition と consumer のバランス
4. Outbox polling
5. 固定アカウントプール競合

つまり、Kafka は改善対象ですが、単独での解決策ではありません。

## 6. 次にやるべきこと

### 優先度高

- `3 broker + ユニークアカウント` の異常悪化理由を深掘る
- PostgreSQL の lock wait / transaction duration 可視化
- Saga 段階別 latency のメトリクス化
- consumer group ごとの partition 割当と処理偏り確認

### 優先度中

- Outbox の push 型比較
- topic ごとの partition 数の再設計
- broker ごとのディスク / ネットワーク / leader 偏り確認

## 7. 結論

Kafka を `3 broker` にすること自体は妥当で、可用性面でも必要です。  
今回の結果からは、

- `1000アカウント集中` では `3 broker` 化が `3 replicas` に効いた
- しかし `5 replicas` ではまだ不足
- `ユニークアカウント` では別要因による大きな悪化があり、Kafka 以外の切り分けが必要

と判断できます。

したがって、現時点の結論は

- `Kafka 3 broker 化 = 効果あり`
- `Kafka 3 broker 化 = 単独では不十分`
- `次は DB / Saga / consumer 配置の切り分けが必要`

です。
