# テスト実行レポート（2026-04-06）

## 1. 実施内容

| 種別 | コマンド / 内容 |
|------|-------------------|
| 結合テスト | `mvn -pl integration-tests -am test`（`TradeFlowIntegrationTest`） |
| スパイク × スケール | `python3 loadtest/run_spike_scale_test.py`（replicas **1** と **3** を順に実行） |

クラスタ: `fx-trading-sample`（OpenShift）

---

## 2. 結合テスト結果

| 項目 | 結果 |
|------|------|
| テストクラス | `com.example.fx.integration.TradeFlowIntegrationTest` |
| テスト数 | **5** |
| 失敗 | **0** |
| エラー | **0** |
| 所要（目安） | 約 35s（Surefire ログ上） |

**判定: PASS**

---

## 3. スパイク負荷試験結果（要約）

**設定（短縮実行）**

- 相: warm **45s** → spike **25s** → cool **45s**（合計 115s + グレース）
- 到着率: 平常 **20/s** → スパイク **100/s**
- `TRACE_SAMPLE_RATE=0`（POST のみ）

**k6 閾値**

| replicas | k6 exit | 閾値 | 備考 |
|---:|---:|:---:|------|
| 1 | **0** | **PASS** | 全相で `http_req_failed` および遅延閾値を満たす |
| 3 | **99** | **FAIL** | **`http_req_duration{scenario:spike}` の p(95) が閾値 3000ms を超過** |

**3 replicas 時の k6 サマリー（抜粋）**

- `http_req_duration{scenario:spike}`: **p(95) ≈ 4.14s**（閾値 3s を超過 → exit 99）
- `http_req_duration{scenario:warm}` / `cool`: p(95) は約 0.23s 台で閾値内
- `dropped_iterations`: **24**（到着率維持のため VU が不足したイテレーション）
- HTTP エラー率: **0%**（失敗はレイテンシ閾値のみ）

**試験直後の Prometheus スナップショット（`3m` ウィンドウ・参考値）**

| replicas | HTTP p95(s) | POST 201 rate/s | Kafka lag max | Outbox backlog max | fx_core tx p95(s) |
|---:|---:|---:|---:|---:|---:|
| 1 | 0.0056 | 24.6 | 771 | 10 | 0.0048 |
| 3 | 0.0081 | 12.3 | 912 | 17 | 0.0070 |

※ POST rate は試験終了直後の瞬間値であり、スパイク尖りのみを表すとは限りません。

**詳細 JSON / MD**

- `loadtest/test-run-report-spike.json`
- `loadtest/test-run-report-spike.md`

---

## 4. 所見

1. **結合テストは緑** — 補償・E2E 経路の回帰は今回の実行範囲では検出されませんでした。
2. **スパイク試験では replicas=1 は閾値内、replicas=3 はスパイク相の API 遅延尾部で FAIL** — エラーではなく **k6 の p95 上限**によるものです。
3. **水平スケールしてもスパイク時の p95 が自動改善するとは限らない** — 約定コアは単一 DB 上の ACID であり、複数 Pod でも **ロック・トランザクション競合**が残ります。加えて **Route / 分散環境での尾部**が乗ると、1 レプリカ時より見かけ上悪化することもあります。
4. `dropped_iterations` が出ているため、**100 req/s を維持するには `SPIKE_MAX_VUS` / `SPIKE_PREALLOCATED_VUS` の増加**を検討する余地があります（k6 側のキャパシティ）。

---

## 5. 改善プラン（優先度順）

1. **閾値の運用分離（短期）**  
   - 多レプリカ比較用に、`k6-spike-scale-test.js` の **spike 相だけ** p95 閾値を緩める、または **環境変数で上書き**可能にする（例: `SPIKE_P95_MS=5000`）。  
   - **目的**: 「失敗＝バグ」と「失敗＝PoC クラスタの尾部・閾値厳しすぎ」を分離する。

2. **k6 リソース（短期）**  
   - `SPIKE_MAX_VUS` / `SPIKE_PREALLOCATED_VUS` を段階的に上げ、`dropped_iterations` をゼロ近傍にし、**到着率 100/s が実際に達成されているか**を確認する。

3. **観測の精密化（中期）**  
   - `run_spike_scale_test.py` で、試験開始・終了時刻に基づき **Prometheus `query_range`** で **spike 相のみ**の `rate` / `histogram_quantile` を取る（現状は試験後スナップショット中心）。

4. **アーキテクチャ上の期待値の整理（継続）**  
   - **スケールアウトは入口の並列度には効くが、単一コア TPS の上限は別**という前提で、テストプラン §7.4 の「合格の見方」と整合させる。  
   - 本番相当の検証では **シャーディング・読み取り系分離**などを別テーマとして切り出す。

---

## 6. 次のアクション例

- CI: `mvn -pl integration-tests -am test` を必須ゲートに維持する。  
- 負荷: 本番リリース前に **スパイク試験を `--peak-rate` 固定で replicas 別に再実行**し、改善プラン 1〜2 を反映したうえでトレンド比較する。
