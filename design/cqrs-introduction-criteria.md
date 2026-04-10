# CQRS 導入条件と適用方針

## 1. 目的

本ドキュメントは、`fx-trading-sample` において CQRS を **いつ、どこへ、何のために** 導入するかを整理する。

重要な前提は、CQRS は通常 **書き込み性能そのものを直接改善するための手段ではない** という点である。主目的は、読み取り負荷を分離し、書き込み経路への干渉を減らすことである。

## 2. 現時点の判断

現時点では、主ボトルネックは次である。

- 約定 ACID コア
- Outbox relay
- Kafka lag
- Saga 状態集中

したがって、CQRS は write path 改善の第一優先ではない。ただし、**限定領域では次フェーズから導入してよい**。特に `trace`、`e2e-status`、運用ダッシュボードのような読み取りは、既存イベントをもとに独立 read model を作る価値が高い。以下の条件が増えると導入価値はさらに高まる。

- 取引照会の高頻度化
- `trace` / `e2e-status` 照会の増加
- 監査・ダッシュボード・レポート要求の増加
- 通知や UI 向け read model 要求の増加

## 3. CQRS を導入する目的

1. 読み取り API と書き込み API の責務を分離する
2. 読み取りのために core DB / saga DB を直接叩く回数を減らす
3. 集約済み read model を持ち、照会を軽くする
4. 監査・ダッシュボード・レポート系を独立させる

補足:

- CQRS は Event Sourcing の全面導入を前提としない
- 現行の ACID core を source of truth に据えたままでも成立する
- 本サンプルでは **integration event から materialized view を作る方式** を採る

## 4. 導入対象の優先順位

### 4.1 第一候補

- `GET /api/trades/{tradeId}/trace`
- `GET /api/trades/{tradeId}/e2e-status`
- 運用ダッシュボード
- 監査向け統合照会

理由:

- 既にイベントソースがある
- 読み取りモデルを Kafka 購読で作りやすい
- 書き込み整合性への影響が小さい

### 4.2 第二候補

- 顧客向け取引照会一覧
- 監査ビュー
- コンプライアンス照会
- 通知履歴照会
- 補償履歴照会

### 4.3 後回しにするもの

- 約定可否判定そのもの
- 残高拘束の真実値
- 建玉の ACID 主データ

これらは CQRS の read model に寄せず、ACID 側を source of truth とする。

## 5. 導入判断基準

CQRS 導入を開始する条件は次のいずれか。

1. **読み取りが書き込み DB を圧迫している**
   - read API が増え、`fx-core-db` や `fx-trade-saga-db` を直接叩く頻度が高い
   - 読み取り起因で接続数や IOPS が悪化する

2. **集約済みビュー要求が増えている**
   - `trade + saga + notification + compliance` の統合照会が必要
   - API ごとに複数 DB / テーブルを join 的に集め始めている

3. **UI / レポート要件が増えている**
   - 顧客向け一覧画面
   - 運用向けダッシュボード
   - 監査向け参照系

4. **整合性ラグを許容できる**
   - 数百 ms〜数秒の遅延を業務的に許容できる

5. **既存イベントから十分な read model を構成できる**
   - `TradeExecuted` と downstream 完了イベントで必要な照会情報が揃う
   - command 側 DB へ追加 join を要求しなくて済む

## 6. 導入しない条件

次の要求だけでは CQRS を導入しない。

- 単に write path が遅い
- Kafka lag が高い
- Outbox relay が詰まっている
- 約定 ACID コアが律速している

これらは CQRS ではなく、write path の構造改善で解くべきである。

## 6.1 API Composition との使い分け

PoC や低頻度の統合照会では API Composition も選択肢になる。しかし本番主経路の高頻度照会では、レイテンシ、依存先障害、スケール性の観点から CQRS / materialized view を優先する。

API Composition を許容する場面:

- 運用者向けの低頻度管理 API
- 一時的な統合照会
- read model を作る前の検証段階

CQRS を優先する場面:

- 顧客向け一覧
- `trace` / `e2e-status`
- 監査・レポート
- ダッシュボード

## 7. read model 候補

### 7.1 Trade Trace Read Model

内容:

- `tradeId`
- `orderId`
- `sagaStatus`
- 各 step status
- latest event time
- compensation status

更新元:

- Kafka downstream events
- `TradeExecuted`
- `CoverTradeBooked`
- `RiskUpdated`
- `AccountingPosted`
- `SettlementReserved`
- `NotificationSent`
- `ComplianceChecked`

### 7.2 Trade Summary Read Model

内容:

- 口座別取引一覧
- 通貨ペア
- side
- amount
- createdAt
- latest status

更新元:

- core trade event
- saga terminal events

### 7.3 Operations Dashboard Read Model

内容:

- 当日約定数
- saga completed / failed / compensating
- compensation 発生件数
- topic 別 lag との相関表示

### 7.4 Audit / Compliance Read Model

内容:

- `tradeId`
- `orderId`
- 全イベント履歴
- 現在の Saga 状態
- 補償の発火・完了履歴
- コンプライアンス判定結果

意図:

- Event Sourcing を全面採用せずとも、監査・履歴側では event-centric に扱う

## 8. 更新方式

第一候補は **Kafka 購読による非同期更新** とする。

理由:

- 既存のイベント駆動と整合する
- write DB に同期参照しなくて済む
- service 境界を崩さない

代替:

- DB 複製
- batch 集計

ただし、batch はリアルタイム性が弱く、trace 系には向かない。

補足:

- 現行の Outbox / Kafka イベントは integration event として扱う
- 将来 Event Sourcing を採用する場合でも、現時点では read model 投影用途に限定する

## 9. read model の保存先

候補:

1. 専用 PostgreSQL
2. 既存 saga DB とは別の query DB
3. Elasticsearch / OpenSearch 系

推奨:

- 初期段階では **専用 PostgreSQL** が扱いやすい
- 全文検索や高機能検索が必要になれば検索系ストアを検討する

## 10. 整合性の説明

CQRS 導入時に明示すべきこと:

- read model は source of truth ではない
- write 後すぐは古い値を返す可能性がある
- `trace` / `e2e-status` は eventually consistent である
- 顧客残高の真実値は ACID 側にある

## 11. API 方針

### 書き込み API

- `POST /api/trades`
- source of truth は ACID core

### 読み取り API

- `GET /api/trades/{tradeId}/trace`
- `GET /api/trades/{tradeId}/e2e-status`
- 一覧 API
- dashboard API

## 12. リスク

- read model の遅延説明が必要
- event 欠落時の再構築手順が必要
- source of truth と query model の責務が曖昧になる危険

## 13. 次フェーズの推奨

最初の CQRS 対象は `trace` / `e2e-status` とする。

理由:

- 既に Saga 状態と相性が良い
- 顧客残高の真実値を壊さない
- 監視 / UI / 運用の価値が高い

次点で `Operations Dashboard Read Model` と `Audit / Compliance Read Model` を作る。  
顧客向け一覧はその後でよい。

## 14. 完了条件

1. CQRS の対象 API が限定されている
2. source of truth と read model の責務が定義されている
3. 更新方式と保存先が決まっている
4. eventual consistency の説明が用意されている
