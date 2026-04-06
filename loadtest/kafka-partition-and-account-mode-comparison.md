# Kafka Partition And Account Mode Comparison

## 目的

このレポートは、以下 3 パターンの実行結果を横並びで比較するためのものです。

1. `1000アカウント集中` + `Kafka 1 broker / partitions=1`
2. `1000アカウント集中` + `Kafka 1 broker / partitions=6`
3. `毎回ユニークアカウント` + `Kafka 1 broker / partitions=6`

比較対象の JSON は以下です。

- `loadtest/k6-1000-accounts-replica-comparison.json`
- `loadtest/k6-1000-accounts-partitions-6-comparison.json`
- `loadtest/k6-unique-accounts-replica-comparison.json`

## 主要結果サマリー

### 1. 1000アカウント集中, partitions=1

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 375.74 | 0.173s | 0.267s | 11354 |
| 3 | 373.73 | 0.174s | 0.451s | 19658 |
| 5 | 25.74 | 2.442s | 30.240s | 29601 |

### 2. 1000アカウント集中, partitions=6

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 371.92 | 0.174s | 0.259s | 606 |
| 3 | 27.95 | 1.999s | 30.236s | 2782 |
| 5 | 8.04 | 6.135s | 30.248s | 2782 |

### 3. 毎回ユニークアカウント, partitions=6

| replicas | Requests/sec | Average latency | Slowest | Kafka lag max |
|---|---:|---:|---:|---:|
| 1 | 371.91 | 0.174s | 0.308s | 4720 |
| 3 | 305.91 | 0.212s | 1.298s | 5733 |
| 5 | 22.66 | 2.784s | 30.240s | 11931 |

## 観測結果

## 1. partition 数を 1 から 6 に増やすと、1 replica の lag は大きく改善した

`1000アカウント集中` の `1 replica` では、

- `partitions=1`: `Kafka lag max = 11354`
- `partitions=6`: `Kafka lag max = 606`

まで改善しています。

一方で throughput は

- `375.74 req/s`
- `371.92 req/s`

とほぼ同じです。

つまり、

- `Kafka lag` には効いた
- しかし `入口 throughput` はほとんど伸びなかった

ことが分かります。

## 2. partition を増やしても、1000アカウント集中の 3 / 5 replicas は改善しなかった

`1000アカウント集中` で比較すると、

- `3 replicas`
  - `partitions=1`: `373.73 req/s`
  - `partitions=6`: `27.95 req/s`
- `5 replicas`
  - `partitions=1`: `25.74 req/s`
  - `partitions=6`: `8.04 req/s`

でした。

つまり今回の再試験では、**partition 増加がレプリカ増加時の性能改善にはつながりませんでした**。  
少なくともこの PoC では、`partition 数不足` は一因ではあっても、主因ではない可能性が高いです。

## 3. ユニークアカウント化すると、3 replicas の挙動は改善した

`partitions=6` の比較で、

- `1000アカウント集中, 3 replicas`: `27.95 req/s`
- `ユニークアカウント, 3 replicas`: `305.91 req/s`

となっています。

これは非常に大きな差です。

つまり、**3 replicas で極端に悪化していた主因の 1 つは、1000アカウント固定プールに対する競合**である可能性が高いです。

## 4. それでもユニークアカウント + 5 replicas は改善しなかった

`ユニークアカウント, partitions=6` でも

- `1 replica`: `371.91 req/s`
- `3 replicas`: `305.91 req/s`
- `5 replicas`: `22.66 req/s`

でした。

つまり、

- `アカウント競合だけが悪化要因ではない`
- `5 replicas` で別のボトルネックが顕在化している

と判断できます。

## ボトルネック候補

優先度順に見ると次のとおりです。

### 1. PostgreSQL 単一インスタンス

いまだに強い候補です。

- ACID 領域は単一 PostgreSQL
- `trade_order`
- `trade_execution`
- `account_balance`
- `balance_hold`
- `fx_position`
- `trade_saga`
- `outbox_event`

を更新しています。

Pod を増やしても、結局は単一 DB に書き込みが集中します。

### 2. Saga 依存チェーン

このシステムは

- `TradeExecuted`
- `CoverTradeBooked`
- `RiskUpdated`
- `AccountingPosted`
- `SettlementReserved`

という依存関係を持っています。

そのため、`consumer` の数が増えても、後続が完全並列に伸びるわけではありません。

### 3. Kafka 1 broker のまま

partition を増やしても broker は 1 台なので、

- ネットワーク
- I/O
- broker スレッド
- controller / broker 同居

の集中は残ります。

`Kafka 3 broker` 化の価値はまだ高いです。

### 4. Outbox polling

Outbox は `timer + sql` でポーリングしています。

高負荷時は

- 取得
- claim
- publish
- markSent / markFailed

を繰り返すので、Push 型より不利になりやすいです。

## 何が分かったか

今回の 2 つの再試験で分かったことを簡単に言うと、

- `partition 数不足` は一因
- しかし `partition を増やすだけでは解決しない`
- `1000アカウント固定プール` は 3 replicas 悪化の大きな要因
- `ユニークアカウント` にすると 3 replicas の極端な悪化はかなり緩和
- それでも `5 replicas` では破綻気味なので、**Kafka 以外のボトルネックも強い**

です。

## 現時点の判断

### 1000アカウント集中試験としての解釈

このシナリオは、現実の「一定数の活発口座にアクセスが集中する」状況には近いです。  
この場合、レプリカ数を増やしても、DB / Saga / Kafka のどこかで待ちが発生しやすいです。

### ユニークアカウント試験としての解釈

こちらは水平スケール耐性を見る試験としてより素直です。  
それでも `1 -> 3` で伸び切らず、`5` で悪化するので、**アプリ Pod を増やす前に基盤構成を見直す必要がある**ことを示しています。

## 次の改善案

### 1. Kafka を 3 broker 構成へ変更する

次にやる価値が高いです。

期待できる効果:

- partition 分散
- consumer 並列性の向上
- broker I/O の分散
- lag 緩和

### 2. topic ごとの partition 数を役割に応じて見直す

例えば、

- `fx-trade-events`
- `fx-cover-events`
- `fx-risk-events`
- `fx-accounting-events`

は高頻度系として多め、

- `fx-compensation-events`
- `fx-compliance-events`

は少なめでもよい、という設計が可能です。

### 3. PostgreSQL の観測をさらに追加する

次の指標が必要です。

- SQL 実行時間
- lock wait
- slow query
- transaction duration

### 4. Outbox を Push 型と比較する

現在は polling のため、stress 時に不利になりやすいです。

比較候補:

- Debezium CDC
- PostgreSQL LISTEN/NOTIFY
- DB イベント駆動

### 5. `3 replicas` で最も効く構成を探す

現時点では、`3 replicas` は

- 固定1000アカウントでは悪化
- ユニークアカウントではまだ動く

ので、まずは `3 replicas` を最適化対象として、

- Kafka 3 broker
- partition 見直し
- DB 観測強化

を試すのが効率的です。
