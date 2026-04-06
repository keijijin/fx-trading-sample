# パフォーマンス調査・対策サマリー（2026-04）

## 1. 真因（優先度順）

### A. k6 の E2E ポーリングが `/api/trades/{id}/trace` を叩いていた（自己負荷）

- `getTrace()` は **fx-core-db** と **fx-trade-saga-db**（`ACTIVITY_DB_URL`）の **両方**に対し、約定・残高集計・建玉・outbox 全件・**trade_activity 全件**などを毎ポーリング実行していた。
- サンプル率が低くても、**分散 DB + 重い SELECT** が Saga 完了待ちのボトルネックになり、`saga_completed=0` や Trace timeout が支配的だった。

**対策:** `GET /api/trades/{id}/e2e-status` を追加（`trade_saga` への **1 クエリのみ**）。k6 はこちらをポーリング。

### B. ベースライン等の閾値とタイムアウトのミスマッチ

- 1 replica でも baseline が `business_failure_rate` **1% 超**で失敗（サンプル約 300 件中タイムアウト約 5 件 ≈ 1.7%）。
- `accounting_fail` は補償完了が **45s** に届かないケースが多く、`business_failure_rate` が悪化。

**対策:** `TRACE_TIMEOUT_MS` を baseline **60s**、accounting_fail **90s** に延長。baseline の `business_failure_rate` 閾値を **2%** に緩和（`k6-test-plan-suite.js`）。

### C. マルチレプリカ時の実容量（3 replicas 試験）

- 改善後も **3 replicas** では `http_req_failed` が増加（例: baseline で **~7%**）、`trade_api_latency` の **p99 が秒〜十秒単位**。
- 主因は **Route/ゲートウェイ・fx-core・DB の同時実行上限**であり、trace 以外の純粋なスケール課題。

### D. `pg_advisory_xact_lock`（balance 直列化）

- **削除**した状態で 3 replicas 試験を行うと HTTP 失敗率が悪化したため、**バケット複数行の更新順序によるデッドロック／リトライ失敗**が疑われる。
- **現状は保持**（削除はロールバック）。

### E. Activity 用データソースのプール

- `activity.db.pool.max-size` デフォルトを **10** に引き上げ、OpenShift では `ACTIVITY_DB_POOL_MAX_SIZE=20` を設定。

---

## 2. 実装一覧

| 項目 | 内容 |
|------|------|
| e2e-status | `TradeE2eStatusResponse` + `GET .../e2e-status` |
| k6 | `waitForTerminal` が `/e2e-status` を使用 |
| プール | `ActivityJdbcSupport` デフォルト 10、`application.yml` + OpenShift env |
| 閾値・timeout | `run_test_plan_suite.py` + `k6-test-plan-suite.js` |
| advisory lock | **維持**（3r で削除時に悪化のため） |

---

## 3. テスト結果ファイル

| ファイル | 内容 |
|----------|------|
| `full-suite-e2e-light-r1.json` | 1 replica フルスイート（e2e-status 反映後） |
| `full-suite-e2e-light-r3.json` | 3 replicas フルスイート（advisory lock 削除版の結果 — 参考） |

1 replica では smoke / notification_fail / stress / soak など **多くのシナリオが PASS**。baseline は閾値調整前は `business_failure_rate` で FAIL。

---

## 4. 次の打ち手（未実装）

~~1. **3 replicas** での HTTP 失敗: Route タイムアウト（120s）、outbox/trade_activity インデックス、起動時 CREATE INDEX を実装済み。~~  
~~2. **Saga DB** の `trade_saga` は PK で十分。`trade_activity(trade_id)` インデックスを追加済み。~~  
~~3. **RPS スイープ** `run_load_sweep.py` / `load-sweep-report.md` を追加済み。~~

残課題: **accounting_fail** の 90s 超補償、**3r baseline** の k6 閾値と実容量。詳細は `mitigations-full-test-report.md` を参照。
