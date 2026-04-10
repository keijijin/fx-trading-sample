# Kafka 運用プロファイル設計

## 1. 目的

本ドキュメントは、`fx-trading-sample` における Kafka の運用プロファイルを定義し、固定値チューニングではなく **条件に応じた使い分け** を可能にすることを目的とする。

本サンプルでは、次の知見が得られている。

- `consumersCount` を増やせば常に改善するわけではない
- partition 増設は負荷条件と replicas に依存する
- Kafka lag は CPU より重要な運用指標である
- rebalance コストがスパイク時に顕在化する

## 2. 基本方針

1. `consumer count` は固定観念で増やさず、lag と rebalance で決める
2. `partition count` は throughput のみでなく、運用上の安定性で決める
3. CPU / memory ではなく `consumer lag` を主指標にする
4. 標準運用と高並列運用を分ける

## 3. 現時点の観測結果

### 3.1 既知の傾向

- `consumersCount=2 -> 1` で lag が改善したケースがある
- `partitions=12` は `5 replicas` に対しては有効だった
- `1 / 3 replicas` では partition 増設だけで改善しないケースがある
- Outbox より Kafka / consumer 側が先に詰まることがある

### 3.2 ここから導く原則

- `consumer` を増やす前に `rebalance` を疑う
- `partition` を増やす前に downstream の実処理能力を確認する
- `lag` は topic 別 / consumer group 別に見る

## 4. 標準プロファイル

### 4.1 Profile A: 標準運用

用途:

- 通常 PoC
- `replicas=3` 前後
- 安定性優先

推奨:

- `consumersCount=1`
- 中程度の partition 数
- `CooperativeStickyAssignor`
- `lingerMs=5` 維持

向いている条件:

- スケール頻度が高い
- rebalance コストを抑えたい
- downstream の処理が完全には均質でない

### 4.2 Profile B: 高並列運用

用途:

- `replicas=5` 以上を想定
- 高い burst を吸収したい

推奨:

- partition 増設
- `consumersCount=1` を基準に開始し、必要時のみ拡張
- topic ごとの lag を監視

注意:

- 増設だけでは改善しない
- group 再配置と idle consumer 発生を確認する

### 4.3 Profile C: 失敗系 / 補償重点運用

用途:

- `accounting_fail` など補償系試験
- 順序や依存関係を重視

推奨:

- 補償系 topic は通常系と分けて扱う
- compensation lag を別監視する
- 補償系は過剰並列化しない

## 5. 推奨設定項目

### 5.1 producer 側

- `requestRequiredAcks=all`
- `lingerMs=5`
- payload / key は deterministic にする
- `tradeId` もしくは集約キーで順序を保つ

### 5.2 consumer 側

- `partition.assignment.strategy=CooperativeStickyAssignor`
- `consumersCount` は既定 `1`
- `maxPollRecords` は処理時間と合わせて見直す
- `maxPollIntervalMs` は最長処理時間を超えるよう設定する
- `sessionTimeoutMs` / `heartbeatIntervalMs` は false positive eviction を避ける

### 5.3 broker 側

- replication factor を明示
- `min.insync.replicas` を明示
- persistent storage を使う
- broker の anti-affinity を有効化する

## 6. topic 設計方針

### 6.1 topic 分割

現行 topic 群:

- `fx-trade-events`
- `fx-cover-events`
- `fx-risk-events`
- `fx-accounting-events`
- `fx-settlement-events`
- `fx-notification-events`
- `fx-compliance-events`
- `fx-compensation-events`

今後の方針:

- 通常系と補償系を明確に分ける
- 重い topic は consumer group を分離する
- state 更新系と通知系を同一 group に詰め込まない

### 6.2 key 設計

- 基本は `tradeId`
- shard 導入後は `tradeId` と `shardId` の関係を明示
- key が変わる場合は順序保証範囲も定義する

## 7. rebalance 対策

### 7.1 まずやること

- `CooperativeStickyAssignor` を維持
- スケール直後の lag を観測する
- graceful shutdown を徹底する
- termination と session timeout の整合を取る

### 7.2 次に検討すること

- `group.instance.id` による static membership
- `consumer group` の責務分離
- topic ごとの scaler 導入

## 8. 運用時の主要指標

必須:

- `fx_kafka_consumer_group_lag`
- topic 別 lag
- consumer rebalance 回数
- consumer restart 回数
- broker disk usage / latency

併記:

- `fx_outbox_backlog`
- `hikaricp_connections_active`
- `http p95/p99`

## 9. スケーリング方針

### 9.1 HPA/KEDA の考え方

- CPU ではなく `lagThreshold` を中心に考える
- replicas は partition 数を無視して増やさない
- `allowIdleConsumers=false` を基本線にする
- `limitToPartitionsWithLag` は topic 特性に応じて使う

### 9.2 scale up 条件

- lag が持続的に上がる
- downstream 処理時間が一定
- rebalance が支配的でない

### 9.3 scale out ではなく構造変更へ進む条件

- replicas を増やしても lag が回復しない
- scale 直後の rebalance で悪化する
- topic 責務分割が必要

## 10. 次フェーズでやること

1. topic 別 lag ダッシュボードを作る
2. consumer group 別の責務を整理する
3. static membership を検証する
4. 補償系 topic の独立運用可否を評価する

## 11. 完了条件

1. 標準 / 高並列 / 補償重視の 3 profile が定義されている
2. 各 profile の前提条件が明文化されている
3. lag ベース運用の判断基準が決まっている
4. rebalance 対策の優先順位が決まっている
