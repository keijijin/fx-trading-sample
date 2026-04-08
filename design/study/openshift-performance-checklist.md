# OpenShift 性能改善チェックリスト

このチェックリストは、`fx-trading-sample` を OpenShift 上で性能改善する際の確認項目を、優先度順に整理したものです。

前提:

- 目的は「単純なスケールアウト」ではなく、「競合点・滞留点・再配置コストの削減」
- 評価は平均値ではなく `p95` / `p99`、`Kafka lag`、`Outbox backlog`、DB 接続、回復時間で行う
- 実測は `warm / spike / cool` の相別で比較する

## 1. 現状把握

- [ ] `fx-core-service` の `HTTP p95/p99`、`POST 201 rate`、`error rate` を基準値として固定する
- [ ] `fx_kafka_consumer_group_lag` を `warm / spike / cool` 相ごとに記録する
- [ ] `fx_outbox_backlog` が実際に詰まっているのか、`0` 近傍なのかを分けて確認する
- [ ] `hikaricp_connections_active` / `pending` をサービス別に確認する
- [ ] 試験直後の instant 値だけでなく `query_range` でも確認する
- [ ] シナリオ間の lag 持ち越しがない状態で測定する
- [ ] `trade-saga-service` だけでなく consumer group 単位で lag を分解して確認する

## 2. Pod リソースと JVM

- [ ] 各 Deployment に `resources.requests` と `resources.limits` を設定する
- [ ] `limits.cpu` が厳しすぎて CFS throttling を起こしていないか確認する
- [ ] 重要サービスで `requests == limits` を採用するか検討する
- [ ] `fx-core-service` と後続 Saga サービスを同一リソースで扱っていないか確認する
- [ ] Kafka / PostgreSQL のリソースをアプリより先に安定確保する
- [ ] JVM ヒープとコンテナ `memory limit` の整合を確認する
- [ ] OOMKill や再起動回数を確認する

## 3. Probe と可用性

- [ ] 各 Deployment に `readinessProbe` を設定する
- [ ] 各 Deployment に `livenessProbe` を設定する
- [ ] 起動に時間がかかるサービスでは `startupProbe` を検討する
- [ ] スパイク時の一時遅延で probe failure が増えていないか確認する

## 4. 配置制御

- [ ] Kafka broker に `podAntiAffinity` を設定する
- [ ] DB Pod と高負荷アプリ Pod の共倒れを避ける配置を検討する
- [ ] `topologySpreadConstraints` でノード偏りを抑える
- [ ] 必要なら `nodeAffinity` で重要 Pod を専用ノードに寄せる
- [ ] Kafka / DB / API / Saga の過密同居を避ける

## 5. Kafka

- [ ] 高頻度 topic の partition 数を replica 数と consumer 数の組み合わせで評価する
- [ ] `consumersCount` を固定観念で増やさず、実測で最適値を決める
- [ ] topic ごとの lag を個別に見る
- [ ] rebalance 頻度を確認する
- [ ] `trade-saga-service` の責務分割余地を確認する
- [ ] producer の `lingerMs` / batch 設定を見直す
- [ ] broker ストレージの IOPS / レイテンシを確認する

## 6. DB と接続予算

- [ ] レプリカ数ごとの JDBC 積み上げを `max_connections` と突き合わせる
- [ ] `fx-core-service` と Saga 系で接続プール上限を分けて管理する
- [ ] API リクエストと Outbox 配信が同一プールを食い潰していないか確認する
- [ ] `fx-trade-saga-db` のような集約 DB の接続集中を監視する
- [ ] 行ロック競合が発生している箇所を特定する
- [ ] `Outbox` の claim / publish / mark の DB 往復を最小化する

## 7. Outbox と Saga

- [ ] `OUTBOX_POLL_PERIOD_MS` が短すぎず長すぎないか確認する
- [ ] Outbox 並列数が API レイテンシを悪化させていないか確認する
- [ ] `Trade Saga Service` が状態管理以外の重い責務を持っていないか確認する
- [ ] `Risk` / `Accounting` / `Settlement` の待ち合わせが過剰でないか確認する
- [ ] 補償系イベントが遅延時に詰まりにくい構造か確認する
- [ ] Consumer 側冪等のコストと重複耐性のバランスを確認する

## 8. ストレージ

- [ ] Kafka に永続ストレージを使う
- [ ] PostgreSQL に永続ストレージを使う
- [ ] 適切な `StorageClass` と IOPS を確認する
- [ ] 一時ディスク前提のベンチ結果を本番判断へ持ち込まない

## 9. 自動スケーリング

- [ ] HPA を CPU だけでなく `Kafka lag` や `Outbox backlog` も見て設計する
- [ ] `fx-core-service` と `trade-saga-service` を別々にスケールできるようにする
- [ ] スケールアウト直後の rebalance 悪化を評価する
- [ ] `3 replicas` 標準運用と `5 replicas` 高並列運用を分けて扱う

## 10. 試験運用

- [ ] `1 / 3 / 5 replicas` 比較を同条件で繰り返せるようにする
- [ ] `warm / spike / cool` の相構成を固定する
- [ ] 純粋性能比較とホットスポット検証を分ける
- [ ] generator 側の `dropped_iterations` を確認する
- [ ] テスト後に lag settle を待ってから次シナリオへ進める

## 優先実施順

1. Kafka lag と consumer 責務を分解して測る
2. `trade-saga-service` の責務分割候補を洗い出す
3. DB 接続予算を replica 数ごとに再計算する
4. OpenShift マニフェストに `resources` / probe / affinity を入れる
5. Kafka / PostgreSQL のストレージと配置を見直す
6. HPA を CPU 以外の指標でも設計する
7. それでも頭打ちなら Debezium CDC や低レイヤ最適化を検討する
