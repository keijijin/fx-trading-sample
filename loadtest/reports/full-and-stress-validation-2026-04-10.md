# フルテスト / ストレステスト結果まとめ（2026-04-10）

## 目的

OpenShift 上の `fx-trading-sample` に対して、

1. `run_test_plan_suite.py` によるフルスイート
2. `run_spike_scale_test.py` によるスパイク × スケールアウト試験

を実施し、`CQRS` / `event journal` / `CDC shadow` を含む現行構成の挙動を確認する。

## 実行条件

- namespace: `fx-trading-sample`
- Kafka: **3 broker**
- PostgreSQL: `wal_level=logical`
- Kafka Connect / Debezium: 稼働中
- CDC connector: **shadow topic** 向け
- `fx-core-service`: `TRADE_QUERY_PROJECTION_ENABLED=true`, `TRADE_EVENT_JOURNAL_ENABLED=true`

## 参照ファイル

- フルスイート JSON: `loadtest/reports/test-plan-suite-2026-04-10-generated.json`
- スパイク JSON: `loadtest/reports/spike-scale-2026-04-10-generated.json`
- スパイク Markdown: `loadtest/reports/spike-scale-2026-04-10-generated.md`

## 1. フルスイート結果

### シナリオ別サマリー

| シナリオ | passed | trade accepted | k6 avg(s) | trade p95(s) | kafka lag max | outbox backlog max | hikari pending max |
|---|:---:|---:|---:|---:|---:|---:|---:|
| smoke | PASS | 449 | 0.178 | 0.241 | 76 | 19 | 0 |
| baseline | FAIL | 5951 | 0.215 | 0.295 | 33 | 5381 | 1 |
| cover_fail | FAIL | 1264 | 0.224 | 0.307 | 36 | 6022 | 2 |
| accounting_fail | FAIL | 751 | 0.215 | 0.297 | 49 | 6412 | 0 |
| notification_fail | FAIL | 1271 | 0.217 | 0.294 | 57 | 6776 | 7 |
| stress | FAIL | 13992 | 0.257 | 0.385 | 56 | 19797 | 44 |
| soak | FAIL | 3601 | 0.254 | 0.381 | 83 | 21878 | 8 |

### 所見

1. **正常系 smoke は良好**  
   `trade accepted=449`、`trade p95=0.241s`、`kafka lag max=76` で収まっており、基本動作は安定している。

2. **baseline 以降は Outbox backlog が急増**  
   `baseline` で `5381`、`stress` で `19797`、`soak` で `21878` まで伸びており、現時点では downstream よりも **outbox relay / 後続追随** の詰まりが顕著に見える。

3. **Kafka lag は中程度だが backlog に埋もれている**  
   `stress` でも `kafka lag max=56` と、以前の構成に比べると極端ではない。一方で Outbox backlog の伸びが大きく、**いまの律速は Kafka 単独ではない**。

4. **Hikari pending は stress で悪化**  
   `hikari_pending_max=44` まで上がっており、write path と relay/後続の同時進行で接続待ちが増えている。

5. **FAIL の主因はシナリオ期待値と E2E 追随遅延**  
   smoke を除くシナリオで `passed=false` だが、これは単純な HTTP エラー増だけでなく、`trace_timed_out` や `saga_failed`、補償系期待値との乖離が主因である。

## 2. スパイク × スケールアウト結果

### サマリー

| replicas | k6 exit | thresholds | HTTP p95(s) | POST 201 rate/s | kafka lag max | outbox backlog max |
|---|---:|:---:|---:|---:|---:|---:|
| 1 | 0 | PASS | 0.196 | 44.48 | 65 | 29623 |
| 3 | 99 | FAIL | 4.104 | 38.47 | 1487 | 37733 |
| 5 | 99 | FAIL | 0.881 | 14.98 | 1258 | 41648 |

### 相別 Prometheus からの読み取り

#### replicas = 1

- warm: `outbox avg=21700.9`, `lag avg=81.6`
- spike: `outbox avg=25024.2`, `lag avg=44.8`
- cool: `outbox avg=28935.4`, `lag avg=51.8`

解釈:

- 単一 Pod でも Outbox backlog は高い
- ただし lag は相対的に低く、HTTP p95 も `0.196s` に収まる

#### replicas = 3

- warm: `outbox avg=30085.3`, `lag avg=312.4`
- spike: `outbox avg=32387.2`, `lag avg=1028.0`
- cool: `outbox avg=32576.3`, `lag avg=1339.7`

解釈:

- replicas を 3 に増やすと **lag が急増**
- `cool` でも lag が回復しきらず、後続詰まりが残留
- `HTTP p95=4.104s` まで悪化し、明確な閾値違反

#### replicas = 5

- warm: `outbox avg=38131.6`, `lag avg=1262.2`
- spike: `outbox avg=39545.0`, `lag avg=1256.8`
- cool: `outbox avg=18345.2`, `lag avg=1124.4`

解釈:

- 5 Pod では `HTTP p95` は 3 Pod より改善したが、`POST 201 rate/s=14.98` と throughput が大きく落ちる
- `outbox backlog max=41648` で最大
- `lag` も 1 Pod より大きく、単純な水平スケールの効果は見られない

## 3. 総合所見

### 3.1 確認できたこと

1. **3 broker Kafka + CDC shadow + CQRS read model 構成でも、基本動作は維持できる**
2. **Smoke レベルでは十分安定**
3. **`e2e-status` / `trace` の read path は前段の段階検証どおり改善方向**

### 3.2 顕在化した課題

1. **Outbox backlog の肥大**
   - full suite でも spike 試験でも顕著
   - CDC connector は shadow topic 向けのため、本番 relay の polling publisher はまだ律速要因のまま

2. **多 Pod 時の Kafka lag 増大**
   - 1 Pod から 3 / 5 Pod で悪化
   - rebalance / consumer 側追随 / downstream 処理速度差が疑わしい

3. **接続待ちの増加**
   - stress で `hikari_pending_max=44`
   - 後続 / relay / write path が同時に接続を消費している

4. **水平スケールの非線形性**
   - `1 -> 3 -> 5` で素直に良くならない
   - 以前の知見どおり、共有資源が律速している

## 4. 結論

今回の結果から、現時点のボトルネックは次の順で読むのが妥当である。

1. **Outbox relay / backlog**
2. **Kafka consumer lag**
3. **接続待ち**
4. **約定コアそのもの**

特に重要なのは、**CDC を導入しても、それを shadow topic で動かしている限り、本番 relay の bottleneck は残る**という点である。  
したがって、次の改善フェーズは以下になる。

1. `shadow topic` と本番 poller の差分比較
2. CDC を read model 供給に限定するか、本番 topic へ full cutover するか判断
3. Outbox polling publisher を止めた場合の backlog / lag / p95 変化を測る

## 5. 次アクション

1. **CDC full cutover の比較試験**  
   poller 停止前後で `outbox backlog` と `kafka lag` を比較する

2. **topic 別 lag 監視の追加**  
   `fx-trade-events` / `fx-cover-events` / `fx-risk-events` などを個別に見る

3. **Outbox と downstream の責務分割再検討**  
   relay が集中しているなら topic / aggregate 単位の分割を進める
