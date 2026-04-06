# K6 1000 Accounts Analysis

## 結論

`1000` アカウントを使い回す集中負荷試験では、レプリカ数を増やしても素直な性能向上は見られませんでした。

- `1 replica` は最も高い throughput を出した
- `3 replicas` はほぼ横ばい
- `5 replicas` は throughput が大きく落ち、長い待ち時間が発生した

## 観測結果の要約

### 主要な数値

- `1 replica`
  - `Requests/sec`: `375.74`
  - `Average latency`: `0.173s`
  - `Outbox backlog max`: `3122`
  - `Kafka lag max`: `11354`

- `3 replicas`
  - `Requests/sec`: `373.73`
  - `Average latency`: `0.174s`
  - `Outbox backlog max`: `2038`
  - `Kafka lag max`: `19658`

- `5 replicas`
  - `Requests/sec`: `25.74`
  - `Average latency`: `2.442s`
  - `Slowest`: `30.240s`
  - `Outbox backlog max`: `1712`
  - `Kafka lag max`: `29601`

## なぜ 5 replicas で悪化したのか

### 1. 後続の非同期処理が追いついていない

最も強い兆候は `Kafka lag max` の増加です。

- `1 replica`: `11354`
- `3 replicas`: `19658`
- `5 replicas`: `29601`

これは、フロントの入り口よりも後段の consumer 群のほうが先に詰まり始めていることを示しています。`5 replicas` では、入口を増やしたことで取引起点イベントがより短時間に発生し、Kafka と Saga 側の処理待ちが急増した可能性が高いです。

### 2. topic partition 数が少なく、レプリカ増加が並列性に変換されていない

この PoC は Kafka 単一 broker 構成で、topic も実質 PoC 向けです。consumer group のレプリカを増やしても、topic partition 数が少なければ同時に処理できる Pod は増えません。

その結果、

- Pod 数だけ増える
- しかし consumer 並列度は十分に増えない
- Kafka lag が増える
- 全体として end-to-end の待ち時間だけ増える

という形になりやすいです。

### 3. PostgreSQL は単一インスタンスで、同期 ACID 処理の集中先が 1 つ

今回の構成では `fx-core-service` の Pod を増やしても、ACID 領域の永続化先は単一 PostgreSQL です。`Hikari active max` は `10` に張り付いていないため、接続プール枯渇は主因ではなさそうですが、単一 DB が同期確定の集約点であることは変わりません。

### 4. 1000 アカウント集中でも、アカウント群自体は有限でホットスポットを持つ

今回は単一口座集中ではありませんが、`1000` アカウントの固定プールを使い回しています。これは完全ランダムな無限口座よりはホットスポット性が高く、`account_balance` 更新や `fx_position` 更新の競合が一定割合で発生し続けます。

### 5. 5 replicas 時は「速く処理している」のではなく「待っている」

`5 replicas` のときは

- `Requests/sec` が大きく低下
- `Average latency` が大きく増加
- `Slowest` が `30s` 超

である一方、`pod_cpu_sum_cores` は `3 replicas` よりむしろ低いです。

これは CPU を使い切って落ちたというより、

- Kafka
- DB 応答
- 非同期後続
- ルート待ち

のどこかで待たされている時間が増えたことを示唆します。

## どこがボトルネック候補か

優先度順に整理すると次のとおりです。

### 1. Kafka consumer 側の処理能力

最有力候補です。

- `Kafka lag max` がレプリカ数増加とともに悪化
- `5 replicas` で著しく悪化

まずは

- topic partition 数
- consumer group ごとの partition 割り当て
- consumer 1 Pod あたりの処理時間

を確認すべきです。

### 2. Trade Saga / 後続サービスの依存チェーン

このシステムは

- `TradeExecuted`
- `CoverTradeBooked`
- `RiskUpdated`
- `AccountingPosted`
- `SettlementReserved`

という依存を持つため、完全並列ではありません。後続のどれか 1 段が遅くなると、Saga 全体が詰まりやすい構造です。

### 3. 単一 PostgreSQL

現時点で Hikari 指標だけ見ると接続数は枯渇していませんが、

- 単一 DB
- 同一テーブル更新集中
- `SELECT ... FOR UPDATE`

があるため、レプリカ数増加に対する素直な線形スケールは期待しにくいです。

### 4. Outbox Publisher のポーリング方式

`fx_outbox_backlog_max` は `1 -> 3 -> 5 replicas` でむしろ下がっています。
これは「改善した」のではなく、`5 replicas` で入口 throughput 自体が落ちたため、Outbox へ積まれる件数も結果的に減った可能性があります。

つまり、今回の主因は Outbox backlog ではなく、それより前後の経路です。

## 何がボトルネックではなさそうか

### HikariCP 接続プール

- `hikari_max`: `10`
- `hikari_pending_max`: `0`

で、待ち行列は見えていません。
少なくとも今回の試験では、接続プール枯渇は主要因ではなさそうです。

### Pod restart / 不安定再起動

- `pod_restarts`: `0`

なので、再起動ループで性能が崩れたわけではありません。

## 次の改善案

### 1. Kafka partition 数を増やして再試験する

最優先です。

- `fx-trade-events`
- `fx-cover-events`
- `fx-risk-events`
- `fx-accounting-events`
- `fx-settlement-events`
- `fx-notification-events`
- `fx-compliance-events`

の partition 数を増やし、`3 replicas` / `5 replicas` が本当に consumer 並列度へ変換されるようにします。

### 2. ストレス試験を 2 系統に分ける

- `1000` アカウント固定プール負荷
- 毎回ユニークアカウント負荷

これを分けると、

- 競合に強いか
- 水平スケールするか

を分離して判断できます。

### 3. Saga の各段階ごとの処理時間をメトリクス化する

今は `trade_saga` 全体の duration しか弱く、しかも今回の比較では十分に拾えていません。

次は以下を個別に出すと良いです。

- `TradeExecuted -> CoverTradeBooked`
- `CoverTradeBooked -> RiskUpdated`
- `CoverTradeBooked -> AccountingPosted`
- `SettlementStartRequested -> SettlementReserved`

### 4. HTTP latency 指標のクエリを `uri="/api/trades"` 固定前提から見直す

今回 `http_p95_s` / `http_p99_s` が `null` だったため、Grafana / Prometheus 側のクエリか Micrometer 側のラベル条件が一致していない可能性があります。

ダッシュボード改善ポイント:

- `uri` ラベルの実値を確認してクエリ修正
- `expected_response=true` / `false` の分離
- `201` と `409` を分けた可視化

### 5. 5 replicas 以上に上げる前に、単一 broker / 単一 DB の上限を明示する

今の PoC では、

- Kafka: 1 broker
- PostgreSQL: 1 instance

なので、アプリ Pod だけを増やしても意味が薄い局面があります。

次段階では、

- Kafka broker 数
- topic partition 数
- PostgreSQL のリソース

も含めて一緒にスケールさせる必要があります。

## 現時点の判断

今回の `1000` アカウント集中試験から言えることは次です。

- `1 -> 3 replicas` では、入口性能はほぼ改善しない
- `5 replicas` では、現状構成だと悪化する
- 主因は「アプリ Pod 数不足」よりも、`Kafka / Saga 依存チェーン / 単一DB / partition 不足` 側にある可能性が高い

つまり、次にやるべきは「さらに Pod を増やす」ことではなく、

- Kafka 並列性
- Saga 段階別遅延
- DB 競合

を切り分ける追加計測です。
