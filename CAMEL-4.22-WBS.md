# fx-trading-sample — Camel 4.22 更新 WBS

作成日: 2026-11-09。正本: [アップグレード計画](CAMEL-4.22-UPGRADE-PLAN.md)。実行管理: [ToDo](CAMEL-4.22-TODO.md)。作業実績は更新レポートを参照する。

## 前提と見積範囲

現行は JDK 21 / Spring Boot 3.3.6 / Camel 4.14.0である。8サービスと共通モジュールをBoot 4 / Camel 4.22系へ更新し、Jackson 3への移行、JDK 25、実コンテナ・OpenShiftでの検証までを含む。具体的な版は元計画の候補を着手時に再確認して固定する。DB schema・event schema・brokerの更新、frontend機能追加、native化は含めない。

概算は実作業の人日であり、1人日=8時間とする。担当は役割で示す。既存不具合の大規模修正、環境・レビュー・本番作業の待ちは除外する。コード調査に基づく予備見積であり、build・依存解決は未検証である。F-01後に再見積する。

## WBS

### F-01 baseline・試験実行保証 — 2〜3人日

- 担当: 開発、試験。先行: なし。元計画: F0。
- F-01.1: 8サービスの実依存、現行image、起動方式、合成payloadのfixtureを記録する。
- F-01.2: Surefire / JUnit / Testcontainersの実解決版とテスト検出を確認する。
- F-01.3: Podman互換socketまたはDocker環境で既存7シナリオを実行する。
- 成果物・完了条件: baseline報告、依存記録、試験環境。7件が未検出・全skipの状態を成功にしないこと。

### F-02 中間検証点・影響整理 — 2〜3人日

- 担当: 開発、試験。先行: F-01。元計画: F1。
- F-02.1: Boot 3.5 / Camel 4.18の整合する保守版を固定する。
- F-02.2: JDK 21のまま8サービスを起動し、非推奨API・設定を整理する。
- F-02.3: 現行から目標までのmigration guideをcomponent別に対応付ける。
- 成果物・完了条件: 中間構成の検証コミット、影響台帳、既存7件の結果。本番への中間配備は不要である。

### F-03 Boot 4・Camel 4.22・Jackson 3 — 3〜5人日

- 担当: 開発、試験。先行: F-02。元計画: F2。
- F-03.1: 親と子POMのBOM / plugin / starterを更新する。
- F-03.2: 共通ObjectMapper Bean、JsonSupport、各サービスのJackson data format、試験内mapperを整合させる。
- F-03.3: Bootの自動構成・import・設定propertyを移行する。
- F-03.4: REST・Kafka・既存OutboxのJSONを新旧fixtureで比較する。
- 成果物・完了条件: JDK 21で8サービス起動、契約試験、結合試験成功。金額精度、enum、null、日時、未知field、エラー応答を確認すること。

### F-04 Outbox・Kafka互換修正 — 2〜4人日

- 担当: 開発、試験。先行: F-03。元計画: F3。
- F-04.1: 共通Outboxとfx-core内の別ルートのclaim / split / 発行 / 状態更新を検証する。
- F-04.2: consumerのpoll / timeout / assignor / offset / handledと再配信を確認する。
- F-04.3: 既存payloadの再送と新旧consumerの互換性を確認する。
- 成果物・完了条件: 必要な互換修正、再送試験結果。DBとKafka間の重複可能性をconsumerの冪等処理で吸収できること。

### F-05 業務整合性・障害試験 — 3〜5人日

- 担当: 試験、開発、業務レビュー。先行: F-04。元計画: F3 / §4。
- F-05.1: 既存7件の正常・補償・重複・projection試験を維持する。
- F-05.2: 同一event再配信・複数replica競合・送信後SENT更新前の停止を試験する。
- F-05.3: DB / broker切断、rebalance、SIGTERM、再起動復旧を試験する。
- F-05.4: 残高、仕訳、Saga、Outboxの状態を照合する。
- 成果物・完了条件: 業務整合性報告。二重業務更新・補償漏れ0件、非致命通知失敗による誤rollbackなし。

### F-06 JDK 25・image・監視 — 2〜3人日

- 担当: 開発、基盤、試験。先行: F-05。元計画: F4。
- F-06.1: compiler / CI / build image / runtime imageを25へ揃える。
- F-06.2: 共通および各サービスDockerfile、Boot repackage、java -jar、CPU architectureを確認する。
- F-06.3: test / coverage / agent / annotation processorとmetrics / tracing / loggingを確認する。
- 成果物・完了条件: JDK 25の8サービス実コンテナ起動と必須回帰成功。GCやvirtual threadsの最適化は別工程とする。

### F-07 OpenShift・性能・切り戻し — 2〜4人日

- 担当: 基盤、試験、開発。先行: F-06。元計画: F4 / §5。
- F-07.1: 隔離namespaceでreplica / resource / datasetを固定し、既存k6 suiteを再実行する。
- F-07.2: latency / throughput / error / lag / Outbox滞留 / DB接続予算を比較する。
- F-07.3: 旧image・設定への切り戻しと進行中取引のdrainを試験する。
- 成果物・完了条件: 負荷・運用試験報告。金額整合性違反0件、採用SLO維持、lag回復確認、offsetやOutboxを初期化せず復旧できること。

### F-08 文書・規約・受入 — 1〜2人日

- 担当: 開発、基盤、業務レビュー。先行: F-07。元計画: §5。
- F-08.1: README / 設計資料 / build・運用手順 / .cursor/rulesの旧版固定を更新する。
- F-08.2: 採用構成、JSON互換性、全試験証跡、切り戻し条件をレビューする。
- 成果物・完了条件: 再現可能な配布物・受入報告。8サービス、共通モジュール、文書・規約の版数が一致すること。

## 工数・依存関係・変動要因

必須合計は **17〜29人日**。不確実性の予備20〜30%を加え、予算目安は **21〜38人日**とする。工数は期間の保証ではない。

依存順序は F-01 → F-02 → F-03 → F-04 → F-05 → F-06 → F-07 → F-08 である。JSON fixture・障害注入手順・隔離環境・image候補は先行準備できるが、8サービスを跨ぐ合否判定は必要である。

F-03はBootのmajor更新とJackson移行、F-05は永続状態と補償の検証であり、見積幅が大きい。既存結合試験が実行できない場合、旧payloadが新構成で読めない場合、混在稼働を維持できない場合は再見積する。

切り戻し互換性が不成立の場合はrolling混在を避け、受付停止とdrainを伴う方式を選定する。本番停止時間は実測するまで確約しない。

## api-endpointとの難易度比較

総作業量と業務リスクは本プロジェクトの方が大きいと評価する。Boot major更新、Jackson、JDK 25、8サービスの永続状態・補償を同時に検証するためである。api-endpointは主系の版数差が大きく、HTTP/streamingと製品版混在の調査に不確実性がある。大容量処理の再設計が必要になれば工数差は縮まる。

比較には範囲差がある。api-endpointはJDK 21まで、FXは25までを含む。FXのJDK 25工程F-06を除いても **15〜26人日**であり、現時点ではFXを重いと判断する。製品サポート待ちが発生すれば、経過期間はapi-endpointの方が長くなる場合がある。

## 実績の更新

当初計画と実施結果は区別する。[更新実行レポート](CAMEL-4.22-UPGRADE-REPORT.md)を実績・制約の正本とする。未チェック項目は残件である。
