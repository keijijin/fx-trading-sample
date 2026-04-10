# CDC shadow topic 検証レポート（2026-04-10）

## 目的

`fx-trading-sample` に追加した `Kafka Connect + Debezium` により、`outbox_event` から Kafka への CDC 配信が成立するかを確認する。

今回は本番 topic への切替ではなく、**shadow topic** へ配信する安全側の構成で検証した。

対象 topic:

- `shadow.fx-trade-events`

## 実施内容

### 1. OpenShift 側の追加構成

以下を `openshift/` に追加・適用した。

- `openshift/fx-kafka-connect-cdc.yaml`
  - `fx-kafka-connect` Deployment
  - `fx-kafka-connect` Service
  - Debezium PostgreSQL connector 設定 ConfigMap
  - connector 登録 Job

### 2. PostgreSQL の logical decoding 有効化

Debezium task は初回起動時、次の理由で失敗した。

```text
ERROR: logical decoding requires wal_level >= logical
```

そのため、OpenShift 上の `fx-postgres` に次を設定した。

- `wal_level=logical`
- `max_replication_slots=10`
- `max_wal_senders=10`

また、PVC 直下の `lost+found` 回避のため、次も設定した。

- `PGDATA=/tmp/pgdata/data`

### 3. Kafka 永続ディレクトリ修正

Kafka でも PVC 直下に `lost+found` があり、`KAFKA_LOG_DIRS` がそれを topic directory と誤認して起動失敗した。

そのため、次へ変更した。

- `KAFKA_LOG_DIRS=/tmp/kraft-combined-logs/data`

### 4. Kafka 3 broker 構成

最終的に、Kafka は **3 broker** へ戻して検証した。

- `fx-kafka-0`
- `fx-kafka-1`
- `fx-kafka-2`

### 5. connector 状態

最終確認時点での connector 状態:

- connector: `RUNNING`
- task: `RUNNING`

## 実データ確認

### 手順

1. `fx-core-service` に対して `POST /api/trades` を 1 件発行
2. `fx-kafka-0` 上で `shadow.fx-trade-events` を consumer

### 結果

`shadow.fx-trade-events` から 1 件取得できた。

取得 payload には少なくとも以下が含まれていた。

- `tradeId`
- `orderId`
- `accountId`
- `currencyPair`
- `side`
- `orderAmount`
- `executedAmount`
- `executedPrice`
- `correlationId`

これにより、次の経路が動作していることを確認した。

```text
Business Tx -> outbox_event -> PostgreSQL WAL -> Debezium -> Kafka Connect -> shadow.fx-trade-events
```

## 結論

今回の検証では、**CDC 自体は OpenShift 上で実働確認できた**。

特に重要なのは次の点である。

1. `outbox_event` から Debezium による change capture が成立した
2. connector を安全に試すため、shadow topic 経由で検証できた
3. Kafka 3 broker 構成でも connector は `RUNNING` を維持した

## 現時点の位置づけ

今回の構成は **本番 topic 切替前の shadow 検証段階** である。  
したがって、次段では以下を行う。

1. shadow topic と poller publish の payload / 順序 / 欠落を比較する
2. read model への feed に CDC を使う場合の整合確認を行う
3. poller を止めた full cutover 試験を別途行う

## 補足

本検証は CDC 経路の成立確認を目的としたものであり、**polling publisher の停止や本番 topic 切替はまだ行っていない**。
