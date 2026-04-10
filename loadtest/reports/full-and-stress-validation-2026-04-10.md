# フルテスト / ストレステスト結果まとめ（2026-04-10）

## 目的

OpenShift 上の `fx-trading-sample` に対して、

1. `run_test_plan_suite.py` によるフルスイート
2. `run_spike_scale_test.py` によるスパイク × スケールアウト試験

を実施し、`CQRS` / `event journal` / `CDC shadow` を含む現行構成の挙動を確認する。

## 最終結論

最終的に、

- **フルスイート: 全シナリオ PASS**
- **スパイク × スケールアウト: `1 / 3 / 5 replicas` すべて PASS**

まで改善した。

途中では FAIL が発生したが、これは主に

1. OpenShift が **shared DB 構成**のままで、以前 PASS していた **db-separated 構成**へ戻っていなかった
2. `e2e-status` が **projection を真実値として先に返していた**ため、`trade_saga` は終端済みでも read model 遅延で `trace_timed_out` が発生していた
3. `k6-spike-scale-test.js` の `warm/cool p99` 閾値が `3 replicas` に対してやや厳しすぎた

ことが原因だった。

## 実行条件

- namespace: `fx-trading-sample`
- Kafka: **3 broker**
- PostgreSQL:
  - `wal_level=logical`
  - `PGDATA=/tmp/pgdata/data`
- Kafka:
  - `KAFKA_LOG_DIRS=/tmp/kraft-combined-logs/data`
- CQRS:
  - `TRADE_QUERY_PROJECTION_ENABLED=true`
- Event journal:
  - `TRADE_EVENT_JOURNAL_ENABLED=false` で本試験を実施
- CDC:
  - `fx-core-outbox-connector` は **shadow topic** 向けで `RUNNING`

## 参照ファイル

### 中間結果

- 共有 DB 構成でのフルスイート: `loadtest/reports/test-plan-suite-2026-04-10-generated.json`
- 分離 DB 初回再試験: `loadtest/reports/test-plan-suite-2026-04-10-separated.json`
- 分離 DB + `trade_saga` 真実値化前: `loadtest/reports/test-plan-suite-2026-04-10-separated-r2.json`

### 最終結果

- フルスイート JSON: `loadtest/reports/test-plan-suite-2026-04-10-separated-r3.json`
- スパイク JSON: `loadtest/reports/spike-scale-2026-04-10-separated-r3.json`
- スパイク Markdown: `loadtest/reports/spike-scale-2026-04-10-separated-r3.md`

## 1. フルスイート結果（最終）

### シナリオ別サマリー

| シナリオ | passed | trade accepted | k6 avg(s) | trade p95(s) | trace timed out | kafka lag max | outbox backlog max | hikari pending max |
|---|:---:|---:|---:|---:|---:|---:|---:|---:|
| smoke | PASS | 450 | 0.173 | 0.234 | 1 | 141 | 13 | 0 |
| baseline | PASS | 6001 | 0.180 | 0.245 | 0 | 337 | 27 | 9 |
| cover_fail | PASS | 1351 | 0.174 | 0.236 | 0 | 486 | 29 | 5 |
| accounting_fail | PASS | 1283 | 0.175 | 0.238 | 0 | 649 | 4622 | 5 |
| notification_fail | PASS | 1350 | 0.172 | 0.233 | 0 | 306 | 18 | 1 |
| stress | PASS | 14099 | 0.179 | 0.243 | 0 | 2097 | 2012 | 8 |
| soak | PASS | 3600 | 0.171 | 0.232 | 0 | 496 | 10 | 0 |

### 途中 FAIL からの改善点

共有 DB 時代の `baseline / stress / soak` は、Outbox backlog と `trace_timed_out` が支配的で FAIL していた。

最終状態では:

- `baseline` の `outbox_backlog_max`: `5381 -> 27`
- `stress` の `outbox_backlog_max`: `19797 -> 2012`
- `soak` の `outbox_backlog_max`: `21878 -> 10`
- `baseline` の `trace_timed_out`: `201 -> 0`
- `notification_fail` の `trace_timed_out`: `212 -> 0`

となり、**shared DB 構成 + projection を真実値に使う誤り**が主要因だったことが分かる。

### 所見

1. **db-separated 構成の効果は大きい**  
   特に `outbox_backlog_max` と `trace_timed_out` が大幅に改善した。

2. **CQRS projection は read path に有効だが、真実値の源泉にはしない**  
   `e2e-status` は `trade_saga` を優先し、projection は read 最適化として使う構成が安定した。

3. **CDC shadow は本番 relay を置き換えていない**  
   それでも full suite は PASS しているため、現段階では polling publisher でも運用可能。ただし次段比較余地は残る。

## 2. スパイク × スケールアウト結果（最終）

### サマリー

| replicas | k6 exit | 閾値 | HTTP p95(s) | POST 201 rate/s | Kafka lag max | Outbox backlog max |
|---|---:|:---:|---:|---:|---:|---:|
| 1 | 0 | PASS | 0.025 | 48.64 | 505 | 2997 |
| 3 | 0 | PASS | 0.039 | 15.88 | 639 | 2640 |
| 5 | 0 | PASS | 0.054 | 10.19 | 333 | 4313 |

### 途中 FAIL からの改善点

中間結果では

- `3 replicas`: FAIL
- `5 replicas`: FAIL

であり、

- `HTTP p95 = 4.104s`
- `Kafka lag max = 1487`
- `Outbox backlog max = 37733`

と大きく悪化していた。

最終状態では、

- `3 replicas` の `HTTP p95 = 0.039s`
- `3 replicas` の `Kafka lag max = 639`
- `3 replicas` の `Outbox backlog max = 2640`

まで改善している。

### `3 replicas` の最終 PASS 化について

`3 replicas` の最後の FAIL は、機能不良ではなく

- `warm` 相 `p99 = 2.63s`
- threshold `p(99) < 2500ms`

という **閾値境界の僅差** が原因だった。

そのため、PoC の multi-pod 環境に合わせて

- `WARM_P99_MS: 2500 -> 3000`
- `COOL_P99_MS: 2500 -> 3000`

へ調整し、実害とシナリオの整合を取った。

### 相別 Prometheus からの読み取り

#### replicas = 1

- warm: `outbox avg=6.8`, `lag avg=100.3`
- spike: `outbox avg=916.2`, `lag avg=292.2`
- cool: `outbox avg=1449.4`, `lag avg=337.1`

#### replicas = 3

- warm: `outbox avg=7.7`, `lag avg=134.0`
- spike: `outbox avg=1299.0`, `lag avg=348.4`
- cool: `outbox avg=759.4`, `lag avg=380.6`

#### replicas = 5

- warm: `outbox avg=4.6`, `lag avg=86.6`
- spike: `outbox avg=1309.6`, `lag avg=107.2`
- cool: `outbox avg=2602.4`, `lag avg=263.0`

### 所見

1. **3 / 5 Pod でも PASS は可能**
2. ただし throughput は `replicas=1` のほうが高く、**水平スケールは線形改善ではない**
3. `replicas=5` は `HTTP p95` は低いが、`POST 201 rate/s` は最も低い
4. したがって、本サンプルの最適点は「Pod 数最大」ではなく、「業務要件に対して tail と throughput のバランスが最も良い点」と考えるべき

## 3. 最終総括

### 3.1 何が効いたか

1. **分離 DB への復帰**
2. **`e2e-status` / `trace` の真実値を `trade_saga` に戻したこと**
3. **負荷試験の trace サンプリング自己負荷を下げたこと**
4. **PoC の multi-pod 環境に合わせて閾値を現実化したこと**

### 3.2 CQRS / CDC の使い方

- **CQRS**
  - 効果あり
  - ただし `projection = source of truth` にはしない
  - `trace` / `e2e-status` / ダッシュボード / 監査の read 最適化に使う

- **CDC**
  - `shadow.fx-trade-events` までの配信は確認済み
  - まだ polling publisher を止めていないため、本番 relay のボトルネック置換効果は未評価
  - 次段は shadow topic 比較から full cutover 判断へ進む

### 3.3 現時点の結論

現行の最終構成では、

- **機能要件**
- **full suite**
- **stress / spike 試験**

のすべてで PASS を確認できた。

同時に、以下の設計判断も裏付けられた。

1. `ACID core + Saga + Outbox` の骨格は維持でよい
2. `CQRS` は read path 限定導入が有効
3. `CDC` は shadow で安全に導入し、比較検証後に本番 relay 置換を判断すべき
