# 負荷試験 統合レポート（2026-04-05）

OpenShift クラスタ（`fx-trading-sample`）に対し、**テストプラン フルスイート**（`run_test_plan_suite.py`）と **スパイク × スケールアウト**（`run_spike_scale_test.py`、replicas 1 / 3 / 5）を順に実行した結果の要約です。詳細数値は同ディレクトリの JSON / 個別 Markdown を参照してください。

## 成果物

| 種別 | ファイル |
|------|------------|
| フルスイート（JSON） | `test-plan-suite-2026-04-05.json` |
| スパイク（JSON） | `spike-scale-2026-04-05.json` |
| スパイク（Markdown） | `spike-scale-2026-04-05.md` |

## 1. テストプラン フルスイート

- **コマンド**: `python3 loadtest/run_test_plan_suite.py --namespace fx-trading-sample --replicas 1 --output loadtest/reports/test-plan-suite-2026-04-05.json`
- **前提**: 全アプリ Deployment を **1 レプリカ**にスケール後、シナリオを順実行（終了時に再び 1 に戻す）。

### シナリオ別サマリー

| シナリオ | k6 判定 | trade p95 (s) | trade accepted | Kafka lag max* | saga p95 (s)* |
|----------|---------|---------------|----------------|----------------|---------------|
| smoke | PASS | 0.226 | 450 | 42 | 0.340 |
| baseline | PASS | 0.232 | 6001 | 400 | 0.349 |
| cover_fail | PASS | 0.225 | 1351 | 227 | 0.248 |
| accounting_fail | PASS | 0.226 | 1351 | 219 | 0.353 |
| notification_fail | PASS | 0.227 | 1350 | 186 | 0.346 |
| stress | PASS | 0.227 | 14099 | **1391** | 0.352 |
| soak | PASS | 0.224 | 3601 | 593 | 0.350 |

\* Prometheus は各シナリオの `window` で取得したスナップショット。

**総括**: 全 7 シナリオで `passed: true`。ストレス相における **Kafka consumer lag のピーク（1391）** が他シナリオより大きく、高負荷時はコンシューマ側の遅れが観測されやすいことが分かります。

**補足（Outbox）**: 各シナリオの `outbox_backlog_max` は **null**（Prometheus 側で当該ウィンドウに有効な系列が得られなかった、またはゲージが 0 のみ）。Outbox 滞留の有無は Grafana / 生クエリでの追跡を推奨します。

## 2. スパイク × スケールアウト試験

- **コマンド**: `python3 loadtest/run_spike_scale_test.py --namespace fx-trading-sample --compare-replicas 1 3 5 --output loadtest/reports/spike-scale-2026-04-05.json`（`--sleep-after-scale 20 --sleep-extra-per-replica 5`、`--spike-p95-ms 6000` 等）
- **相**: warm 120s → spike 60s → cool 120s、到着率 20/s → 100/s。

### 要約表

| replicas | k6 | HTTP p95 (3m スナップ)* | POST 201 rate/s* | Kafka lag max* |
|----------|----|-------------------------|------------------|----------------|
| 1 | PASS | 0.0045 | 48.0 | 657 |
| 3 | PASS | 0.165 | 17.6 | 1111 |
| 5 | PASS | 0.191 | 45.9 | 1354 |

\* 試験**直後**の `3m` ウィンドウ。多 Pod 時はヒストグラムの集約により **p95 が単一 Pod 時と比べて大きく見える**ことがあります（解釈は `spike-scale-2026-04-05.md` の相別表と併用）。

### 相別 `query_range`（抜粋）

- **Kafka lag**: spike 相で 657（r=1）〜 982（r=5）付近。cool 相で最大 **1354**（r=5）まで伸びており、負荷ピーク後も **遅れが残る時間帯**がある。
- **Hikari active（sum）**: spike 時に **31〜47** 付近（レプリカ数に応じて増加）。
- **Outbox（sum）**: 本実行では **サンプル 0**（同上、メトリクス未収集または常時 0 の可能性）。相別監視の枠組みは動作済み。

## 3. JDBC / `max_connections` 積み上げ（参考）

`verify_db_connection_budget.py --pg-max-connections 160` に基づく理論値の例:

- **replicas = 1**: `fx-trade-saga-db` への合計 **90**（最も大きいホスト）。
- **replicas = 5**: 同 **450**（manifest の `max_connections=160` 単体と比較すると **超過**）。実運用ではプール縮小・DB 側上限引き上げ・シャード分割などが論点（`design/sharding-and-domain-split-roadmap.md`）。

## 4. 所見

1. **機能・閾値**: フルスイートおよびスパイク（1/3/5）いずれも **k6 exit 0**（閾値 PASS）。
2. **Saga 以外の観測**: Kafka lag がストレス時およびスパイク後の cool で大きくなる。相別表は `spike-scale-2026-04-05.md` を参照。
3. **接続数**: レプリカを増やすほど saga 集約 DB への JDBC 積み上げが急増するため、`max_connections` との突き合わせをデプロイ前に行うこと。

---

*実行環境: OpenShift（`oc whoami`: admin）、namespace `fx-trading-sample`。*
