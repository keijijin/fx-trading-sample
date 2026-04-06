# OpenShift デプロイとフルテスト結果サマリー

実施日: 2026-04-05  
クラスタ / namespace: `fx-trading-sample`  
対象: 補償レース対策（遅延 `REVERSE_RISK_REQUESTED`）・Outbox ポーリング短縮・Grafana メトリクス反映を含む **全バックエンドイメージ**の再ビルド・プッシュ・ロールアウト後の検証。

## 1. デプロイで実施したこと

| 手順 | 内容 |
|------|------|
| ビルド | `backend` で `mvn -DskipTests package` |
| イメージ | 8 サービスを `podman build` → タグ `backend-<name>:latest` |
| レジストリ | `oc registry info --public` の URL へ `podman login` 後、各 `latest` を push |
| マニフェスト | `openshift/observability-stack.yaml` を `oc apply`（Grafana ダッシュボード更新を含む） |
| ロールアウト | `fx-core-service` / `trade-saga-service` / `cover-service` / `risk-service` / `accounting-service` / `settlement-service` / `notification-service` / `compliance-service` を `rollout restart` し完了まで待機 |
| 負荷テスト後 | `run_test_plan_suite.py` が **replicas=1** に戻す処理まで実行済み |

## 2. フルスイート判定（k6 threshold）

| シナリオ | 1 replica | 3 replicas |
|----------|-----------|------------|
| smoke | **PASS** | FAIL |
| baseline | **PASS** | FAIL |
| cover_fail | **PASS** | FAIL |
| accounting_fail | **PASS** | FAIL |
| notification_fail | **PASS** | FAIL |
| stress | **PASS** | FAIL |
| soak | **PASS** | **PASS** |

### 2.1 1 replica（推奨ベースライン）

全シナリオ **PASS**。特に **`accounting_fail` が PASS** となり、会計失敗補償経路（並列消費後の遅延リスク逆転含む）が **E2E 検証の閾値内**で収束していることを確認。

- `accounting_fail` 例: サンプル 281 件、補償完了 281、API p95 ≈ 0.23s、E2E p95 ≈ 1.05s。

### 2.2 3 replicas

分散・サンプル混在による **k6 閾値超過**で多くが FAIL。`soak` のみ PASS。  
HTTP レイテンシ自体は Prometheus 上は低い一方、`baseline` / `accounting_fail` で **API p95 が大きく出る**（k6 集計側の尾部）、`stress` では **E2E p95 が約 4s** など、3 レプリカ時の尾部・混在シナリオ向けに閾値やテスト設計の別途調整が有効。

## 3. 成果物（詳細データ）

| ファイル | 内容 |
|----------|------|
| `loadtest/full-suite-post-deploy-r1.json` | 1 replica 全シナリオ生データ |
| `loadtest/full-suite-post-deploy-r3.json` | 3 replicas 全シナリオ生データ |
| `loadtest/full-suite-post-deploy-r1.md` | 1 replica 表形式レポート |
| `loadtest/full-suite-post-deploy-r3.md` | 3 replicas 表形式レポート |

## 4. 所見

- **本番相当の単一レプリカ構成**では、今回のデプロイ後に **計画スイートを全 PASS** できた。
- **3 replicas** は PoC クラスタのリソース・k6 閾値・トレースサンプルの組み合わせで **FAIL が多くなる**のは過去比較と同様。スループット検証としては JSON / Prometheus 列を参照し、閾値は別途（多レプリカ専用）に切り出すのが現実的。
- Grafana の **Deferred Reverse Risk** 系列（`fx_saga_deferred_reverse_risk_enqueued_total`）で、レース回避経路の発火頻度を継続監視できる。
