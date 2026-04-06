はい。
以下に、**FXトレーディング・サンプル（ACIDコア + Saga後続）向けの k6 テストプラン**を、PoCから実務検証に上げやすい形で策定します。

k6 は、しきい値による pass/fail 判定、複数シナリオ、到着率ベースの負荷モデル、HTTP/1.1・HTTP/2・WebSocket・gRPC の試験に対応しています。到着率制御には `constant-arrival-rate` と `ramping-arrival-rate` があり、いずれも open model です。ブラウザ試験も `k6/browser` で実施できます。([Grafana Labs][1])

# 1. テスト目的

本テストの目的は次の3点です。

1. **ACID領域の性能確認**
   約定確定、残高拘束、建玉更新の応答時間・スループット・エラー率を測る。

2. **Saga領域の信頼性確認**
   `TradeExecuted` 以降のカバー、リスク、会計、受渡、通知、補償が、負荷時にも欠落なく進むか確認する。

3. **劣化点の切り分け**
   API入口、Outbox発行、Kafka連携、後続Consumer、補償開始のどこが先に詰まるかを可視化する。

---

# 2. テスト対象

対象は次の境界に分けます。

## 2.1 同期API

* 注文受付 API
* 約定 API
* 参照 API（必要なら）

## 2.2 非同期後続

* Outbox Publisher
* Kafka topic
* Cover Service
* Risk Service
* Accounting Service
* Settlement Service
* Notification Service
* Compliance Service
* Trade Saga Service

## 2.3 補償系

* `ReverseCoverTradeRequested`
* `ReverseRiskRequested`
* `ReverseAccountingRequested`
* `CancelSettlementRequested`
* `SendCorrectionNoticeRequested`

---

# 3. テスト観点

## 3.1 性能

* p95 / p99 レスポンスタイム
* 秒間リクエスト数
* エラー率
* 到着率を維持できるか

## 3.2 信頼性

* 重複約定が起きない
* Outbox からイベント欠落しない
* 同一 `eventId` の二重処理が起きない
* 補償対象だけが補償される

## 3.3 回復性

* 一時的障害後に `RETRY` から回復する
* 非致命障害で本体取引を巻き戻さない
* 致命障害で `COMPENSATING` に遷移する

---

# 4. テスト方式

## 4.1 k6 の使い分け

この案件では、次の使い分けが妥当です。

* **API負荷**: `constant-arrival-rate`
* **ランプ試験**: `ramping-arrival-rate`
* **少量のブラウザ確認**: `k6/browser` は任意
* **gRPC や WebSocket がある場合**: 該当プロトコルで別シナリオ化

k6 の executor は用途に応じて選び、arrival-rate 系では十分な VU を事前確保する必要があります。([Grafana Labs][2])

---

# 5. テストレベル

## レベル1: スモーク

目的は「壊れていないこと」の確認です。

* 1〜5 rps
* 5分
* 正常系のみ
* しきい値は緩め

## レベル2: ベースライン

通常業務相当の負荷です。

* 想定通常レートで 30分
* 正常系 95%、異常系 5%
* p95, p99, error rate を基準化

## レベル3: ストレス

限界点を探します。

* ランプアップで到着率を段階増加
* 飽和点、タイムアウト、再試行増加を観測

## レベル4: 耐久

長時間実行です。

* 2〜8時間
* メモリ増加、Consumer lag、Outbox滞留確認

## レベル5: 障害注入

一部サービス停止や遅延を加えます。

* Cover Service 遅延
* Kafka 一時停止
* DB 遅延
* Notification 失敗

---

# 6. 主要シナリオ

## シナリオA: 約定API性能

**目的**
ACIDコアの応答性能を測る。

**対象**
`POST /trades/execute` のような約定API

**確認項目**

* HTTP応答時間
* 成功率
* 同一注文二重実行防止
* DBロック競合時のふるまい

**合格例**

* `http_req_duration p(95)` が目標以内
* HTTP失敗率が閾値未満
* 重複約定 0件

---

## シナリオB: 通常業務フロー

**目的**
約定後の Saga が通常負荷で最後まで完走するか見る。

**流れ**

1. 約定API投入
2. `TradeExecuted` 発生
3. Cover 成功
4. Risk / Accounting 成功
5. Settlement 成功
6. Notification 成功
7. `trade_saga = COMPLETED`

**確認項目**

* API応答
* Outbox 送信遅延
* Saga 完了率
* 完了までの end-to-end 時間

---

## シナリオC: カバー失敗補償

**目的**
致命失敗で補償が正しく始まるか確認する。

**前提**

* 一部リクエストで Cover Service を失敗させる

**期待結果**

* `CoverTradeFailed`
* `trade_saga = COMPENSATING`
* 必要な訂正通知または補償イベント発行
* 最終的に `CANCELLED` または `FAILED`

**確認項目**

* 補償開始率
* 補償漏れ
* 非対象サービスの誤補償がないこと

---

## シナリオD: 会計失敗補償

**目的**
依存後段での失敗に対し、成功済みサービスだけが補償されるか確認する。

**期待結果**

* Cover 成功済み
* Accounting 失敗
* 必要に応じて Cover / Risk の補償
* Settlement は未開始または抑止

---

## シナリオE: 通知失敗

**目的**
非致命失敗で本体が巻き戻らないことを確認する。

**期待結果**

* `TradeNotificationFailed`
* `trade_saga` は `COMPLETED` を維持、または通知だけ再送待ち
* 取引本体は取消されない

---

## シナリオF: Outbox耐久

**目的**
高負荷時の Outbox 滞留と回復性を測る。

**確認項目**

* `NEW`
* `RETRY`
* `ERROR`
* 最古未送信時間
* 出口の Kafka 書き込み追随性

---

# 7. 負荷モデル

## 7.1 前提の置き方（実務のおすすめ）

口座数・顧客数から **そのまま同時アクセス数は出ません**。まず次を合意します。

* **対象母集団**: 全口座か、有効取引口座か、など。
* **MAU / DAU**: 月間・日間の「実際にサービスを使うユーザー」規模（ビジネス前提）。
* **ピークの形**: 相場急変・市場オープンなどで **短時間に集中するか**、終日平準か。

これらが決まったうえで、**到着率（rps）** や **同時セッション（CCU）** を積み上げます。

## 7.2 同時セッションと Little の法則

**同時にオンラインとみなすユーザー数（CCU）** の目安は、例えば次のように置けます。

* **Little の法則（イメージ）**:  
  ピーク時のセッション開始率を \(\lambda\)（人/秒）、平均滞在時間を \(W\)（秒）とすると、**同時セッション数 \(\approx \lambda \times W\)**（定常状態の近似）。
* **経験則（参考のみ）**: ピーク CCU を **DAU の 1〜10%** などと仮置きするやり方もある（金融は市場時間で尖りやすく、**前提なしの数字は使わない**）。

**負荷試験の同時 VU 数**は、この CCU に近いオーダーを上限として置き、**1 ユーザーあたりの操作間隔**から **目標 RPS** に換算します。

## 7.3 PoC 用の到着率ステップ（入口試験）

本リポジトリの PoC では、**FX 本番の絶対値ではなく**、**どのコンポーネントから先に崩れるか**を見ることを主目的とし、次の 3 段階から始めます。

* 小: **10 rps**
* 中: **50 rps**
* 大: **100 rps**

`k6` では `constant-arrival-rate` / `ramping-arrival-rate`（open model）を主に使います。([Grafana Labs][3])

## 7.4 スパイク負荷とスケールアウト検証

**目的**: 平常時の到着率から **短時間だけ注文が跳ね上がる**（スパイク）ときに、**水平スケール（Deployment replicas 増）** によって **入口スループットとエラー率が許容内に収まるか** を見る。

**考え方**:

* **単一約定コア（ACID）** はスケールアウトしても **1 サービス内の競合・ロック**が残るため、**無制限に RPS が伸びるとは限らない**。スパイク試験では **API 入口・Route・コネクションプール・Kafka 遅延**のどこが先に飽和するかを切り分ける。
* **後続 Saga** は非同期のため、スパイク直後は **Outbox 滞留・consumer lag** が一時的に増えても、**一定時間内に回復するか**を Prometheus で併記する。

**実装**: `loadtest/k6-spike-scale-test.js`（warm → spike → cool の 3 相）と `loadtest/run_spike_scale_test.py`（任意の replicas に `oc scale` してから k6 実行・結果 JSON 出力）。

**合格の見方（例・PoC）**:

* スパイク相で `http_req_failed` が許容以下（閾値は環境に合わせて調整）。
* スパイク前後で **受理レートが設定到着率に概ね追随**（k6 の `constant-arrival-rate` が VU 不足で頭打ちになっていないこと）。
* **replicas を増やした場合**に、同一スパイク条件で **p95 悪化・429/5xx・Kafka lag のピークが改善または同等**であること（改善しないならボトルネックがコア DB 等と判断）。

**ポッドを増やすと計測だけ悪化する場合の対処（試験運用）**:

* **スケール直後は consumer 再配置・接続プール温まり前**のため、`run_spike_scale_test.py` の **`--sleep-extra-per-replica`** で待機を延ばす。
* k6 の **`SPIKE_MAX_VUS` / `SPIKE_PREALLOCATED_VUS`** を上げ、`dropped_iterations` を減らす（到着率未達の見かけを防ぐ）。
* 多レプリカ・共有 PoC クラスタでは **尾部だけ**が伸びやすい → **`SPIKE_P95_MS` 等で閾値を緩め**、別途 **Prometheus でエラー率・DB・lag** を主判定にする。

**アーキテクチャ上の限界（改善の方向）**:

* 約定は **単一 DB 上の ACID** のため、**レプリカを増やしても 1 秒あたりのコア取引処理が線形に伸びない**ことがある（むしろ競合が増えると遅延が伸びることもある）。
* 本当にスループットを伸ばすには **シャーディング・口座分散・読み取り系分離** など、ドメイン分割が別途必要。

---

# 8. しきい値

k6 の threshold は pass/fail 条件として使えます。SLO をそのまま codify するのに向いています。([Grafana Labs][4])

PoC向けの例は次です。

## 8.1 約定API

* `http_req_failed < 1%`
* `http_req_duration p(95) < 300ms`
* `http_req_duration p(99) < 800ms`

## 8.2 補償シナリオ

* 致命障害時の Saga 補償開始率 = 100%
* 補償対象漏れ = 0
* 誤補償 = 0

## 8.3 耐久

* `ERROR` Outbox 件数が増え続けない
* Consumer lag が回復する
* 2時間後の p95 が開始時比で大きく悪化しない

---

# 9. 観測項目

k6 単体では DB 内部や Kafka lag の深掘りまではできないので、アプリ・Kafka・DB のメトリクスを併用します。k6 自体は built-in metrics と threshold を持ちます。([Grafana Labs][5])

## 9.1 k6側

* `http_req_duration`
* `http_req_failed`
* `iterations`
* `checks`
* カスタムメトリクス

  * `trade_accepted`
  * `saga_completed`
  * `saga_compensated`
  * `saga_failed`

## 9.2 アプリ側

* 約定API処理時間
* DBロック待ち
* Outbox `NEW/RETRY/ERROR`
* `trade_saga` 状態件数
* 補償開始件数
* 補償完了件数

## 9.3 Kafka側

* topic lag
* produce/consume rate
* rebalance 発生
* retry / timeout

## 9.4 DB側

* connection pool 使用率
* slow query
* lock wait
* deadlock
* CPU / IOPS

---

# 10. データ設計

テストデータは3系統に分けます。

## 10.1 正常データ

* 十分な残高
* 許可口座
* 正常な通貨ペア

## 10.2 業務エラーデータ

* 残高不足
* 約定前コンプライアンス NG
* 会計連携失敗対象

## 10.3 補償誘発データ

* Cover 失敗フラグ付き
* Settlement 失敗フラグ付き
* Notification 失敗フラグ付き

同一 `orderId` を accidental に再利用しないよう、k6 側で一意IDを生成します。

---

# 11. 実行順序

1. **スモーク**
2. **ベースライン**
3. **スパイク × スケールアウト（任意だが推奨）**

   * 平常レート → 短時間高レート → 平常に戻す（`k6-spike-scale-test.js`）
   * replicas を 1 と 3（または本番想定値）で **同条件を繰り返し**、入口・lag の差を比較

4. **単独ボトルネック試験**

   * 約定APIだけ
   * Outboxだけ
   * Coverだけ
5. **E2E通常**
6. **E2E障害注入**
7. **耐久**
8. **再測定**

   * 改善後比較

---

# 12. k6 シナリオ構成例

実装イメージとしては、1本のスクリプトに複数 scenario を持たせます。k6 は複数シナリオと executor をサポートしています。([Grafana Labs][6])

* `trade_smoke`
* `trade_baseline`
* `trade_stress`
* `trade_comp_cover_fail`
* `trade_comp_accounting_fail`
* `trade_notification_fail`
* `trade_soak`
* **スパイク・スケール**: `warm` / `spike` / `cool` の 3 相（`k6-spike-scale-test.js`、別スクリプト）

---

# 13. k6 スクリプト骨子

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const tradeAccepted = new Counter('trade_accepted');
export const sagaCompleted = new Counter('saga_completed');
export const sagaCompensated = new Counter('saga_compensated');
export const businessFailure = new Rate('business_failure_rate');
export const e2eLatency = new Trend('trade_e2e_latency');

export const options = {
  scenarios: {
    trade_baseline: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '30m',
      preAllocatedVUs: 100,
      maxVUs: 300,
      exec: 'tradeBaseline',
      tags: { test_type: 'trade_api' }
    },
    trade_stress: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { target: 50, duration: '5m' },
        { target: 100, duration: '5m' },
        { target: 150, duration: '5m' },
      ],
      exec: 'tradeStress',
      tags: { test_type: 'trade_api' }
    }
  },
  thresholds: {
    'http_req_failed{test_type:trade_api}': ['rate<0.01'],
    'http_req_duration{test_type:trade_api}': ['p(95)<300', 'p(99)<800'],
  },
};

function makePayload(mode = 'normal') {
  return JSON.stringify({
    orderId: `ord-${__VU}-${__ITER}-${Date.now()}`,
    accountId: `acc-${__VU}`,
    currencyPair: 'USDJPY',
    side: 'BUY',
    amount: 10000,
    testMode: mode
  });
}

export function tradeBaseline() {
  const res = http.post('http://fx-core-service:8080/api/trades/execute', makePayload(), {
    headers: { 'Content-Type': 'application/json' },
  });

  const ok = check(res, {
    'status is 202/200': (r) => r.status === 200 || r.status === 202,
  });

  if (ok) tradeAccepted.add(1);
  if (!ok) businessFailure.add(1);
}

export function tradeStress() {
  tradeBaseline();
}
```

---

# 14. 追加で必要な確認

k6 は主に入口試験なので、Saga の最終結果確認には次のどちらかを併用します。

* 状態照会APIを叩く
* DB / 監視系メトリクスを別途確認する

PoC なら、`GET /api/trades/{tradeId}/status` のような照会APIを用意し、`COMPLETED / COMPENSATING / CANCELLED / FAILED` を確認できるようにするのが実用的です。

---

# 15. このシステムでの重要な判定基準

このFXサンプルでは、単に API が速いだけでは不十分です。合格条件は次です。

* 約定APIが閾値内
* 重複約定 0
* Outbox 欠落 0
* Kafka遅延が回復可能
* 致命障害時に補償が必ず始まる
* 非致命障害で取引本体を巻き戻さない
* 耐久試験で `RETRY` / lag / lock wait が増え続けない

---

# 16. 推奨する最初の実施順

最初の1セットはこれで十分です。

1. 約定APIベースライン（計画書の到着率ステップに合わせた分数）
2. **スパイク試験（warm → spike → cool）を replicas 1 と 3 で各 1 回**（`run_spike_scale_test.py --compare-replicas 1 3`）
3. Cover失敗混在 10%
4. Notification失敗混在 10%
5. 2時間ソーク（または PoC 短縮版）
6. ストレスで飽和点確認

---

# 17. 設計書向け要約

**本テストプランでは、k6 を用いて FX Core Service の約定APIに対する到着率ベースの性能試験を実施し、あわせて Saga 後続処理の完了率・補償開始率・非致命障害時の継続性を検証する。
負荷の前提は、口座数ではなく MAU/DAU やピーク集中度から同時セッション・到着率を積み上げる実務的な置き方を推奨する。
試験はスモーク、ベースライン、**スパイク×スケールアウト**、ストレス、耐久、障害注入の各段階に分け、しきい値により pass/fail を自動判定する。
また、k6 の結果に加え、Outbox 滞留、Kafka lag、trade_saga 状態、DBロック待ちを併せて観測することで、性能と信頼性を同時に評価する。** ([Grafana Labs][4])

必要なら次に、**このテストプランをそのまま実行できる k6 スクリプト一式**まで具体化します。

[1]: https://grafana.com/docs/k6/latest/?utm_source=chatgpt.com "Grafana k6 documentation"
[2]: https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/?utm_source=chatgpt.com "Executors | Grafana k6 documentation"
[3]: https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/?utm_source=chatgpt.com "Constant arrival rate | Grafana k6 documentation"
[4]: https://grafana.com/docs/k6/latest/using-k6/thresholds/?utm_source=chatgpt.com "Thresholds | Grafana k6 documentation"
[5]: https://grafana.com/docs/k6/latest/using-k6/metrics/reference/?utm_source=chatgpt.com "Built-in metrics | Grafana k6 documentation"
[6]: https://grafana.com/docs/k6/latest/using-k6/scenarios/?utm_source=chatgpt.com "Scenarios | Grafana k6 documentation"
