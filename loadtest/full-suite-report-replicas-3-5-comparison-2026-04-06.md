# フルテスト（3 / 5 replicas）比較サマリー (2026-04-06)

## 実施内容

- スクリプト: `loadtest/run_test_plan_suite.py` / `k6-test-plan-suite.js`
- namespace: `fx-trading-sample`
- 各実行後に **replicas=1** へ戻す（スクリプト既定）

## 判定一覧（k6 threshold）

| シナリオ | 3 replicas | 5 replicas |
|----------|------------|------------|
| smoke | FAIL | FAIL |
| baseline | FAIL | FAIL |
| cover_fail | FAIL | FAIL |
| accounting_fail | FAIL | FAIL |
| notification_fail | FAIL | FAIL |
| stress | FAIL | FAIL |
| soak | FAIL | FAIL |

**全シナリオとも k6 の閾値（レイテンシ・業務失敗率・trace 関連など）を満たさず FAIL。**  
1 replica 時の全 PASS（同日別レポート `full-suite-report-2026-04-06.md`）と対比すると、**分散・混在・尾部遅延**の影響が大きい実行です。HTTP エラー率がゼロでも、**p95/p99・E2E サンプル・業務失敗率**で落ちるケースが多いです。

## 成果物

| ファイル | 内容 |
|----------|------|
| `loadtest/full-suite-report-2026-04-06-r3.json` | 3 replicas 生データ |
| `loadtest/full-suite-report-2026-04-06-r3.md` | 3 replicas 表形式レポート |
| `loadtest/full-suite-report-2026-04-06-r5.json` | 5 replicas 生データ |
| `loadtest/full-suite-report-2026-04-06-r5.md` | 5 replicas 表形式レポート |

## 所見（簡潔）

- **3 replicas**: Prometheus 上の HTTP p95 はシナリオにより **~0.03〜0.11s** でも、k6 集計の **API p95** や **baseline E2E p95** が大きく出ることがあり、閾値と衝突しやすい。
- **5 replicas**: **smoke** で Trace timeout・Saga 失敗カウントが増加、**baseline** の **E2E p95 が 27s 級**など、尾部が顕著。Outbox / Kafka のピークも参照（各 JSON の `prometheus` 欄）。
- 改善の方向は `design/problems-and-mitigations.md` §4 と `design/test-plan.md` §7.4（多レプリカ時の閾値・運用）に沿って、**閾値の分離**・**Prometheus 主判定**・**アーキテクチャ上の限界**の整理が有効です。
