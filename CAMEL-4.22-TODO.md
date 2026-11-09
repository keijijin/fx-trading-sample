# fx-trading-sample — Camel 4.22 更新 ToDo

作成日: 2026-11-09。対応: [WBS](CAMEL-4.22-WBS.md) / [計画書](CAMEL-4.22-UPGRADE-PLAN.md)。チェック済み項目のみ実施済み。実行証跡を残してからチェックし、非該当は理由を記録する。

## F-01 baseline・試験基盤

- [ ] 現行8サービスのcommit / image / 実依存 / 起動手順を記録する。
- [x] Maven cache書込が可能な環境とPodman互換socketまたはDockerを用意する。
- [x] Surefire / JUnit / Testcontainersの実解決版とテスト検出を確認する。
- [ ] 既存7シナリオを実行し、未検出・skipを区別して記録する。
- [ ] REST / Kafka / Outboxの合成JSON fixtureと性能baselineを保存する。
- [ ] 既存障害を更新起因の不具合と分離して記録する。

## F-02 中間検証（実施対象外として閉じる）

Boot 3.5 / Camel 4.18の中間コミットは作成せず、現行ブランチを基点に最終構成へ直接移行した。中間配備も行っていない。代わりにF-03以降の実起動・JSON契約・全サービス結合試験で採否を判断する。詳細は実行レポートを参照する。

## F-03 Boot 4・Camel 4.22・JSON

- [x] 目標Boot / Camel保守版とBOM / pluginを固定する。
- [x] 親POM、common、全サービスのstarterを更新する。
- [x] CommonJsonConfiguration、JsonSupport、テストmapperをJackson 3へ整合させる。
- [x] 全consumerのJackson data formatと実際に使われるmapperを確認する。
- [x] annotationsの不用意な一括package置換を避ける。
- [ ] 金額精度・scale、enum、null、日時、未知field、エラーJSONを比較する。
- [ ] 旧Outbox payloadの読込・再送を確認する。
- [x] 自動構成・import・設定propertyを修正し、一時migratorを除去する。
- [ ] JDK 21で8サービスの起動・契約試験・結合試験を通す。

## F-04 Outbox・Kafka

- [x] 共通Outboxとfx-core独自ルートの両方を確認する。
- [ ] claim / parallel split / publish / SENT / RETRYの状態遷移を確認する。
- [ ] poll / heartbeat / session / assignor / commitの実効設定を記録する。
- [ ] consumer失敗時のhandled / offset / 再配信を確認する。
- [x] 動的topicと静的placeholderが目標版で正しく解決されることを確認する。
- [ ] 新旧producer / consumerのpayload互換性を確認する。

## F-05 業務整合性

- [x] 正常取引のSaga完了を確認する。
- [x] cover失敗時の補償を確認する。
- [x] orderId重複拒否を確認する。
- [x] accounting失敗時の補償とsettlement抑止を確認する。
- [x] notification失敗で確定取引がrollbackされないことを確認する。
- [x] 状態API・trace APIのprojection参照を確認する。
- [ ] 同一event再配信と複数replica競合で二重業務更新がないことを確認する。
- [ ] Kafka送信後・SENT更新前に停止し、再起動後の冪等性を確認する。
- [ ] DB / broker切断、rebalance、SIGTERM、再起動後の復旧を確認する。
- [ ] 残高・仕訳・Saga・Outboxを照合し、不整合0件を確認する。

## F-06 JDK 25・runtime・監視

- [ ] JDK 25のbuild / runtime imageとcompiler / CIを揃える。
- [ ] 共通と各サービスのDockerfile、Boot repackage、java -jarを確認する。
- [ ] arm64 / amd64の対象を明示して実行する。
- [ ] test / coverage / agent / annotation processorの対応を確認する。
- [ ] logging / metrics / tracing / Kafka lag / Outbox滞留を確認する。
- [ ] JDK 25の実コンテナで機能・障害回帰を通す。

## F-07〜F-08 配備・受入

- [ ] 負荷runnerのscale / rollout先を隔離namespaceへ固定する。
- [ ] dataset / replica / resource / SLOを固定し、k6 suiteを実行する。
- [ ] latency / throughput / error / lag / DB接続予算をbaselineと比較する。
- [ ] 新旧混在の可否を判定し、不可能なら受付停止・drain方式を準備する。
- [ ] 旧image・設定への切り戻しと進行中取引の復旧を実施する。
- [ ] Kafka offsetやOutboxを初期化せず、業務整合性が保たれることを確認する。
- [ ] README・設計資料・運用手順・.cursor/rulesの旧版固定を更新する。
- [ ] 全8サービスの証跡と残件をレビューし、受入条件を満たす。

## 証跡の記録方法

各WBS IDについて、担当、実施日、commit / image、JDK、環境、コマンド、実行件数、skip件数、状態照合結果、報告ファイルを記録する。秘密値や実取引データは使わず合成fixtureを用いる。既存7件の成功だけでは、新しい障害試験や切り戻しの完了を代替しない。

## 実績の更新

当初計画と実施結果は区別する。[更新実行レポート](CAMEL-4.22-UPGRADE-REPORT.md)を実績・制約の正本とする。未チェック項目は残件である。
