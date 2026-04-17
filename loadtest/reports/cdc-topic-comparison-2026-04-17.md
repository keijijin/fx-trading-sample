# CDC 本番 topic / shadow topic 比較試験（2026-04-17）

## 目的

`Kafka Connect + Debezium` を用いた CDC 経路が、現行の polling publisher による **本番 topic** 配信と整合するかを確認する。

今回確認したい論点は次の 3 点。

1. CDC connector が **正しい source DB** を読んでいるか
2. 本番 topic と shadow topic に **同一 trade** が流れるか
3. payload の意味内容が一致しているか

## 前提

- Kafka Connect connector: `fx-core-outbox-connector`
- connector status: `RUNNING`
- task status: `RUNNING`
- 本番 topic: `fx-trade-events`
- shadow topic: `shadow.fx-trade-events`

## 途中で判明した問題

比較試験の初回実行では、

- 本番 topic: 5 件取得
- shadow topic: 0 件

となった。

原因を調べたところ、Debezium connector の `database.hostname` が **`fx-postgres`** のままで、現在の本番構成で `fx-core-service` が書き込んでいる **`fx-core-db`** を読んでいなかった。

そのため、比較試験の前提として次を修正した。

```text
database.hostname: fx-postgres -> fx-core-db
```

## 実施方法

1. `fx-core-outbox-connector` の source DB を `fx-core-db` に修正
2. `fx-trade-events` と `shadow.fx-trade-events` に対して `auto.offset.reset=latest` の consumer を待機
3. 比較用 trade を 5 件投入
4. 両 topic の取得結果を確認

## 結果

### 1. 修正前

- 本番 topic: 5 件取得
- shadow topic: 0 件取得

結論:

- connector は `RUNNING` でも、**source DB が誤っていれば比較試験は成立しない**

### 2. 修正後

取得結果:

#### 本番 topic (`fx-trade-events`)

```json
{
  "tradeId": "TRD-cca4016a-f3b5-4016-b863-8644a3564250",
  "orderId": "ORD-9e614c49-dfd1-443d-b43f-3affa0924bb5",
  "accountId": "ACC-CDC-CMP2-004",
  "currencyPair": "USD/JPY",
  "side": "BUY",
  "orderAmount": 1000.0,
  "executedAmount": 1000.0,
  "executedPrice": 150.25000000,
  "correlationId": "CORR-4cfbc8af-cab3-46c8-b16e-e2513e90c7e7"
}
```

#### shadow topic (`shadow.fx-trade-events`)

```json
{
  "schema": { "type": "string", "optional": false },
  "payload": "{\"tradeId\":\"TRD-cca4016a-f3b5-4016-b863-8644a3564250\",\"orderId\":\"ORD-9e614c49-dfd1-443d-b43f-3affa0924bb5\",\"accountId\":\"ACC-CDC-CMP2-004\",\"currencyPair\":\"USD/JPY\",\"side\":\"BUY\",\"orderAmount\":1000.0,\"executedAmount\":1000.0,\"executedPrice\":150.25000000,\"correlationId\":\"CORR-4cfbc8af-cab3-46c8-b16e-e2513e90c7e7\",...}"
}
```

### 3. 比較結果

- `tradeId`: 一致
- `orderId`: 一致
- `accountId`: 一致
- `currencyPair`: 一致
- `side`: 一致
- `orderAmount`: 一致
- `executedAmount`: 一致
- `executedPrice`: 一致
- `correlationId`: 一致

結論:

- **本番 topic と shadow topic で、同一 trade の意味内容は一致**
- shadow topic は Debezium / Connect の `JsonConverter` 形式で wrapper されるが、payload の実体は同じ

## 補足

今回の `auto.offset.reset=latest` 比較では、待機 timing の関係で 5 件すべてではなく **最後の 1 件** が確実に比較できた。

これは比較方法の限界であり、CDC 経路が 1 件も流れないわけではない。  
むしろ今回の結果は、

1. source DB を正しく向ける必要がある
2. 1 件単位では本番 topic / shadow topic の payload が一致する

ことを示している。

## 改善提案

次段では、より厳密な比較のために以下を推奨する。

1. **比較専用 consumer group** を用意し、一定時間内の複数件を確実に採取する
2. `event_id` と `tradeId` の両方で比較する
3. `payload` 一致だけでなく **順序** と **欠落** を自動判定するスクリプトを追加する
4. `shadow topic` と本番 poller の差分比較を CI / loadtest 手順へ組み込む

## 結論

今回の CDC 比較試験により、次が確認できた。

1. **connector の source DB は `fx-core-db` に向ける必要がある**
2. 修正後、**本番 topic と shadow topic に同一 trade のイベントが流れる**
3. payload の意味内容は一致しており、**CDC shadow 比較の土台は成立している**

したがって、次段の本命は **複数件・順序・欠落比較の自動化** である。
