# OpenShift 性能改善レビュー表

対象:

- `openshift/fx-trading-stack.yaml`
- `openshift/fx-trading-db-separated.yaml`
- `README.md`
- `loadtest/reports/partition-scaling-study-2026-04-08.md`
- `loadtest/reports/COMBINED-RUN-2026-04-05.md`
- `backend/*/src/main/resources/application.yml`

前提:

- このレビューは主に **manifest / 設定 / 既存レポートの静的確認** に基づく
- 実クラスタの CPU throttling、IOPS、rebalance 頻度、sidecar 実装有無のような **実行時情報は未確認**
- ステータスは `確認済` / `要修正` / `未確認` の 3 区分
- 本書は **改善反映前のレビュー記録** として残す。後続の manifest 更新結果は Git 履歴と差分で確認する

## 総評

- **良い点**: Kafka 3 broker、`KAFKA_NUM_PARTITIONS=12`、DB 分離マニフェスト、`OUTBOX_POLL_PERIOD_MS=50`、`fx-core-service` の接続プール縮小、observability 配線は入っている
- **主な課題**: OpenShift マニフェストに `resources` / probe / affinity / HPA がなく、Kafka と PostgreSQL が永続ストレージ未使用
- **最優先修正**: `fx-trade-saga-db` への接続集中、Kafka `emptyDir`、DB 永続化欠如、マニフェストの運用設定不足

## レビュー表

| 領域 | 項目 | ステータス | 根拠 | コメント / 対応方針 |
|---|---|---|---|---|
| 観測 | Prometheus / Tempo / Loki / Grafana の配線 | 確認済 | `README.md` の Observability 節、各 Deployment の `OTEL_EXPORTER_OTLP_ENDPOINT` / `LOKI_URL` | 計測基盤はある。性能議論を observability と結び付けやすい |
| 観測 | 相別 `query_range` ベースの負荷確認 | 確認済 | `loadtest/README.md`、`spike-scale-2026-04-05.md` | instant 値だけでなく相別で見ている点は良い |
| Kafka | 3 broker 構成 | 確認済 | `openshift/fx-trading-stack.yaml` の `StatefulSet fx-kafka replicas: 3` | 単一 broker より妥当 |
| Kafka | `KAFKA_NUM_PARTITIONS=12` | 確認済 | `openshift/fx-trading-stack.yaml` | 直近レポートの方針と整合 |
| Kafka | `consumersCount` の実効値 | 確認済 | `KafkaConsumerUris.java` の既定値 `1`、manifest に上書きなし | 実装上は `1` 側に寄っている |
| Kafka | `consumersCount` に関する文書整合性 | 要修正 | `README.md` / `design/design.md` では `2` 記載が残存、負荷試験レポートは `1` 推奨 | 実装・レポート・設計書の記述を統一したい |
| Kafka | broker 永続ストレージ | 要修正 | `openshift/fx-trading-stack.yaml` の `kafka-data: emptyDir` | 性能だけでなく耐久性でも弱い。本番相当評価には PVC が必要 |
| Kafka | broker 配置制御 | 要修正 | `podAntiAffinity` / `topologySpreadConstraints` の記述なし | 同一ノード偏在やノイジーネイバーを防げない |
| Kafka | rebalance 抑制 | 確認済 | 各 `application.yml` の `CooperativeStickyAssignor` | 良いが、動的スケール時の実効性は runtime で継続確認が必要 |
| DB | サービス別 DB 分離 | 確認済 | `openshift/fx-trading-db-separated.yaml` | 構造的ボトルネック対策として妥当 |
| DB | `fx-core-db` の接続上限明示 | 確認済 | `fx-core-db` に `max_connections=160`、`log_lock_waits=on`、`deadlock_timeout=100ms` | core DB 側の明示設定は良い |
| DB | `fx-trade-saga-db` の接続上限明示 | 要修正 | `fx-trade-saga-db` に `max_connections` 指定なし | 集約 DB なのに core DB より設定が弱い |
| DB | Saga 集約 DB の接続予算 | 要修正 | `COMBINED-RUN-2026-04-05.md` で `replicas=5` 時に理論値 `450` | `fx-trade-saga-db` は最優先の接続予算見直し対象 |
| DB | PostgreSQL 永続ストレージ | 要修正 | 各 DB Deployment に PVC 設定なし | PoC としては良いが、性能・耐久性レビューでは弱い |
| DB | DB 配置制御 | 要修正 | affinity / spread 記述なし | DB と高負荷アプリの同居回避ができていない |
| 接続 | `fx-core-service` プール縮小 | 確認済 | `DB_POOL_MAX_SIZE=8`、`DB_POOL_MIN_IDLE=2` | Pod 増加時の接続爆発抑制として妥当 |
| 接続 | `ACTIVITY_DB_POOL_MAX_SIZE=20` | 確認済 | `openshift/fx-trading-db-separated.yaml` | ただし `fx-trade-saga-db` 集中の一因でもあるので継続監視が必要 |
| Outbox | `OUTBOX_POLL_PERIOD_MS=50` | 確認済 | `openshift/fx-trading-db-separated.yaml` | 調整済み構成として妥当 |
| Outbox | 単一 DB スタック側の poll 設定 | 要修正 | `openshift/fx-trading-stack.yaml` は `100` のまま | 旧 manifest のまま残っており、比較時に混乱しやすい |
| Outbox | 部分インデックス / DB 往復削減 | 確認済 | `fx-postgres-init`、既存レポート | アプリ側最適化は進んでいる |
| Pod | `resources.requests/limits` | 要修正 | `openshift/observability-stack.yaml` 以外で記述なし | CPU throttling や QoS を設計できていない |
| Pod | `readinessProbe` / `livenessProbe` | 要修正 | OpenShift manifest に probe 記述なし | Actuator 側で probes は有効化済みなので manifest へ追加したい |
| Pod | `startupProbe` | 要修正 | 記述なし | Java 起動や初回依存待ちの保護がない |
| Pod | `fx-core-service` / Saga サービスの差別化 | 要修正 | ほぼ同じ manifest パターン | 重要度と負荷特性に応じた差別化が不足 |
| スケール | HPA | 要修正 | `HorizontalPodAutoscaler` なし | lag / backlog を使う以前に HPA の枠組みがない |
| スケール | スケール時 lag 悪化の認識 | 確認済 | `partition-scaling-study-2026-04-08.md` | Pod 増で自動改善しない理解は整理済み |
| 実測 | Kafka lag が主要ボトルネック | 確認済 | `partition-scaling-study-2026-04-08.md`、`spike-scale-2026-04-05.md` | OpenShift 側改善も Kafka / consumer から着手すべき |
| 実測 | Outbox backlog が主因かどうか | 確認済 | 既存レポートでは主因ではない | いまは Outbox より Kafka / consumer / DB 接続が先 |
| 実測 | CPU throttling | 未確認 | manifest 無設定、実クラスタ値未取得 | Prometheus / cAdvisor で確認したい |
| 実測 | ストレージ IOPS / レイテンシ | 未確認 | PVC 自体が未設定 | まず PVC 化し、その上で測定が必要 |
| 実測 | service mesh / sidecar オーバーヘッド | 未確認 | manifest に mesh 記述なし | クラスタ共通注入の有無は実環境で確認が必要 |

## 優先度付きアクション

### P0

1. `fx-trade-saga-db` の `max_connections` とプール予算を再設計する
2. Kafka を `emptyDir` から PVC へ移す
3. 各 PostgreSQL Deployment に PVC を追加する
4. OpenShift マニフェストへ `resources.requests/limits` を追加する
5. OpenShift マニフェストへ `readinessProbe` / `livenessProbe` を追加する

### P1

1. Kafka broker に `podAntiAffinity` と `topologySpreadConstraints` を入れる
2. 重要サービスの配置制御を追加する
3. `README.md` / `design/design.md` の `consumersCount=2` 記述を現行方針に合わせて更新する
4. `openshift/fx-trading-stack.yaml` の旧設定を `db-separated` 側と整合させる

### P2

1. HPA を CPU ではなく `Kafka lag` / `Outbox backlog` も含めて設計する
2. `trade-saga-service` の責務分割を検討する
3. CPU throttling、rebalance、IOPS を runtime で再計測する

## 一言まとめ

このリポジトリは、**アプリケーション側の構造改善はかなり進んでいる一方、OpenShift 運用マニフェストはまだ PoC 段階**です。  
次の性能改善は、Pod 数を増やすことではなく、**DB 接続予算、Kafka / DB の永続ストレージ、resources / probe / 配置制御の整備**から着手するのが最も効果的です。
