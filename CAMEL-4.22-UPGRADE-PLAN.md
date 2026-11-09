# fx-trading-sample — Camel 4.22 アップグレード計画

作成日: 2026-11-09。状態: ソース静的調査に基づく計画。依存更新、ビルド、結合試験、配備は未実施。

作業管理: [WBS・概算工数](CAMEL-4.22-WBS.md) / [ToDoリスト](CAMEL-4.22-TODO.md)。

## 1. 結論と移行経路

本プロジェクトは Quarkus ではなく Spring Boot ベースである。`backend/pom.xml` の宣言値は JDK 21 / Spring Boot 3.3.6 / Camel 4.14.0 である。Camel 4.18 からの更新を前提にしない。

目標候補は Camel Spring Boot 4.22.2 / Spring Boot 4.1.1、最終 runtime は JDK 25 とする。Boot 4.1.1 は Camel 4.22.2 の公開ソースの指定値に基づく出発候補であり、実装着手時に公開済み保守版・依存整合性を再確認して固定する。Boot 3 のまま Camel 4.22 だけを上げる構成は採用しない。

推奨経路は、JDK 21 で現行 baseline → Boot 3.5.x / Camel 4.18.x の一時検証点 → JDK 21 / Boot 4 / Camel 4.22.2 → JDK 25 である。中間点は作業ブランチ上の切り分け用で、本番への逐次リリースは不要である。中間点の具体パッチは互換する BOM を確認して固定する。直接更新案は工程を短縮できるが、失敗の切り分け範囲が広がる。

## 2. 対象・根拠・継承ルール

- `backend/pom.xml`: 親 POM、Java / Boot / Camel、compiler / Boot plugin 管理。
- `backend/common/pom.xml`: Spring Web / Actuator / JDBC、Camel core、Kafka client、Jackson、metrics / tracing。
- サービス8個: fx-core、trade-saga、cover、risk、accounting、settlement、notification、compliance。各 POM の Camel starter と runtime を揃える。
- `common/json/CommonJsonConfiguration.java`、`JsonSupport.java`: Jackson 2 の独自 ObjectMapper Bean と JSON writer。
- `common/outbox/AbstractOutboxPublisherRoute.java`、`common/outbox/OutboxSupport.java`、`common/kafka/KafkaConsumerUris.java`: Outbox / Kafka / 並行処理の主要な共通箇所。fx-core 内にも Outbox 発行ルートがあるため共通クラスだけで完了としない。
- `integration-tests/pom.xml`、`TradeFlowIntegrationTest.java`: Testcontainers を用いて8個の Spring context を起動する結合試験。
- `backend/Dockerfile.service`、`Dockerfile.runtime`、各サービス Dockerfile、`scripts/build-openshift-images.sh`、OpenShift 資材: Java 21 runtime と build 手順。
- `loadtest/` と frontend: 性能・API 契約の回帰対象。frontend の機能改修、DB schema / broker 更新は本計画から分離する。

既存 `.cursor/rules/` を再利用する。`AI_POLICY.mdc` / `01-security-critical.mdc` / `05-output-policy.mdc` は機密と出力方針、`02-finance-domain.mdc` は整合性、`03-camel-kafka.mdc` は冪等・並行、`java-microservice-standards.mdc` / `fx-architecture-standards.mdc` は責務境界、`camel-exception-handling-standards.mdc` / `camel-outbox-publisher-template.mdc` は例外・Outbox の規約である。

JDK 21 / Camel 4.14 固定のルールは実装時に最終採用構成へ更新する。今回の依頼はバージョン更新計画であり、その旧版固定を理由に計画を停止しない。補償判定と取引状態更新を Service / DB に残す既存方針は継承する。

## 3. 作業分解と完了条件

### F0 — baseline とテスト実行保証

- 現行8サービスの依存ツリー、effective POM、image digest、設定、正常・障害応答の合成 fixture を保存する。実データや秘密値は持ち込まない。
- 親 POM には Surefire の明示管理が見当たらない。実解決版を確認し、JUnit Platform を検出する対応版を明示管理する。必要なら Failsafe も設定するが、既存 `*Test` を無意識に二重実行しない。
- Testcontainers / Awaitility の明示固定を棚卸しし、目標 Boot の管理版との整合性を検証する。単に version を削るだけでなく、移行先 Testcontainers の artifact / import の変更も確認する。
- `TradeFlowIntegrationTest` は `disabledWithoutDocker=true` である。Podman の互換 socket または Docker を有効にした隔離環境で、既存7件がスキップされず実行されることを確認する。検出0件・スキップだけで green の build を合格にしない。
- 完了条件: 現行の実行可能な試験結果と件数があり、既存障害を分離できること。

### F1 — Boot 3.5 / Camel 4.18 の一時検証点

- Java 21 を維持し、整合した Boot 3.5.x / Camel 4.18.x の組み合わせで起動する。Boot 3.3→3.4→3.5 と Camel 4.14→4.18 の非推奨 API・設定変更を累積確認する。
- 8サービスを一括して検証し、中間構成が合格したコミットを切り分け用に保持する。
- 完了条件: API / Kafka / DB の現行契約と既存結合試験が維持されること。

### F2 — Boot 4 / Camel 4.22 と JSON 移行

- 親 POM の Boot / Camel BOM と plugin、各子 POM の starter を更新する。Spring Framework、Jackson、Kafka client を旧版に手動固定する override が残っていないか依存ツリーで確認する。
- Boot 4.0 と実採用する4.1系までの migration guide を確認し、モジュール分割に対応する Web / Actuator / JDBC / test starter と import を変更する。
- 推奨案A: Jackson 3 へ統一する。`CommonJsonConfiguration` を `JsonMapper` ベースへ改修し、`JsonSupport`、テスト内 mapper、全サービスの `camel-jackson-starter` を同時に見直す。Camel 側は `camel-jackson3-starter` を候補として採用版の存在・自動構成を確認する。Jackson annotations は一括 package 置換しない。
- 案B: Jackson 2 を明示的に残す一時互換構成。変更量は減るが、MVC と Camel の mapper が分かれ、最終的な3移行が残る。期限・用途を明記し、意図しない2/3の混在を避ける。
- `JsonLibrary.Jackson` を使う各 consumer の実際の data format 解決を確認する。共通 mapper の置換だけで Camel の変換も変わったと仮定しない。
- `TradePayload`、REST 応答、Kafka payload、DB の既存 Outbox payload が新旧双方で読めることを fixture で証明する。BigDecimal の精度・scale、enum、null、日時、未知 field、例外応答を比較する。
- `spring-boot-properties-migrator` は一時診断に使用し、設定修正後に削除する。
- 完了条件: 8サービス起動、REST と Kafka の契約試験、旧 Outbox の再送、結合試験が成功すること。

### F3 — Outbox・Kafka・補償の回帰

- Camel 4.14→4.22 の各 migration guide を確認し、kafka / sql / timer / direct / jackson / core の該当変更を台帳化する。
- `AbstractOutboxPublisherRoute` と fx-core 内の個別ルートを両方試験する。claim、並行 split、発行成功後の SENT 更新、失敗後 RETRY、再起動時の取り残し復旧を確認する。
- Kafka送信と DB 更新の間のクラッシュでは重複が起こり得る。アップグレードを機に exactly-once と主張せず、consumer 側の重複排除と業務更新が原子的であることを試験する。
- `KafkaConsumerUris` は poll / session / heartbeat を明示する。client 更新後も broker が受け付けることを確認し、group protocol / assignor / commit 設定を同時に新方式へ切り替えない。consumer failure 時に offset がどう進むか、`handled` と再配信の関係を検証する。
- `toD("kafka:${header.topicName}?brokers={{kafka.bootstrap.servers}}...")` の placeholder はソースのテンプレート内にある。4.22 の変更が直ちに修正必須であるとは判定しない。topicName に動的 placeholder が混入しないことを確認する。
- Saga は Service / DB 管理である。Camel Saga EIP の記法変更を理由に全面書換えを計画しない。
- 完了条件: 二重残高更新・二重仕訳・補償漏れがなく、通知等の非致命失敗が確定済み取引を誤って rollback しないこと。

### F4 — JDK 25・コンテナ・監視

- JDK 21 で F2/F3 が通った後、compiler release / local JDK / CI / build image / runtime image を25へ揃える。共通 Dockerfile だけでなく各サービス Dockerfile と OpenShift の起動方式を確認する。
- Java 25 対応の compiler / Surefire / coverage / mocking / agent を選び、必要な annotation processor を明示する。まず GC / virtual threads の既定運用を保ち、それらの最適化は別に評価する。
- `java -jar` 起動、Boot repackage、image の linux/amd64 とローカル arm64 の差を確認する。JAR package 成功だけで executable JAR を保証しない。
- metrics / tracing / Loki の依存と `logback-spring.xml` を検証する。Kafka lag、Outbox滞留、DB connection、補償失敗が監視できることを確認する。
- 完了条件: JDK 25 の実コンテナで同じ機能・障害試験を通し、OpenShift 隔離環境の性能基準を満たすこと。

## 4. 必須の試験

既存の7シナリオを維持する:

1. 正常取引の Saga 完了。
2. cover 失敗時の補償。
3. orderId 重複の拒否。
4. accounting 失敗時の補償と settlement 抑止。
5. notification 失敗で取引を rollback しないこと。
6. 状態 API の projection 参照。
7. trace API の projection summary。

追加・補完は失敗モード単位とする:

- 同一 eventId の再配信、複数 consumer / replica での競合。
- Kafka送信後・SENT更新前に publisher を停止し、再送後も業務効果が一度であること。
- DB / broker 接続断と復旧、長時間処理、rebalance、SIGTERM 中の未完了処理。
- 旧 JSON / Outbox payload と新 consumer、切り戻し時の新 producer と旧 consumer の互換性。
- 金額・残高・仕訳・補償状態の照合。UI の正常表示だけで業務合格にしない。

`loadtest/k6-test-plan-suite.js` と既存 runner を再利用する。`run_test_plan_suite.py` は rollout / scale 操作を含むため、隔離 namespace でのみ実行する。現行と同じ dataset / replica / 資源量で p95、throughput、error rate、Kafka lag、DB connection budget を比較する。

性能ゲート案は p95 悪化10%以内、throughput 低下10%以内、業務整合性違反0件、負荷終了後に滞留が baseline と同等時間で解消することとする。これらは合意用の提案値であり実測値ではない。

予定コマンド（未実行）:

```bash
cd /Users/kjin/Camel/fx-trading-sample/backend
mvn -B help:effective-pom -Doutput=target/effective-pom.xml
mvn -B dependency:tree
mvn -B -pl integration-tests -am test
mvn -B clean verify
```

各 stage の Surefire report に既存7件の実行を確認する。利用する Testcontainers socket / image を記録し、container の自動起動を含む試験は隔離環境で行う。

## 5. PR 分割・切り戻し・完了条件

- PR1: 現行 baseline、テスト検出・スキップ防止、fixture。
- PR2: Boot 3.5 / Camel 4.18 の検証点。必須の中間配備にはしない。
- PR3: Boot 4 / Camel 4.22、Jackson、必要な API・設定・test 変更。ビルド可能な単位にまとめる。
- PR4: JDK 25、各 image、OpenShift の回帰・性能検証。
- PR5: README、設計資料、`.cursor/rules/java-microservice-standards.mdc` 等の旧版固定、運用手順の更新。

切り戻しは旧 image / 設定 / consumer group 情報を保持し、publisher と新規取引受付を制御して進行中取引を確認してから行う。Outbox・補償中の行や Kafka offset を安易に初期化しない。DB schema / event schema / broker version は本更新で同時変更しない。新旧 payload の互換性が証明できない場合は rolling 混在をせず、受付停止と drain を伴う計画へ切り替える。

完了条件は、固定した依存関係の記録、8サービスの実コンテナ起動、既存7件と追加失敗モードの実行、金額整合性、性能ゲート、監視、切り戻し試験、文書・ルールの版数一致である。

## 6. 実施済み調査・未確認事項

POM、共通 JSON / Outbox / Kafka、ルート、結合試験、Dockerfile、既存ルールを読んだ。ローカルは JDK 21.0.7 / Maven 3.10.0 / macOS arm64 である。offline effective-pom は sandbox 外の Maven repository lock ファイルを開けず失敗した。現行の build / tests 成功、目標版での依存解決成功は確認していない。

※要確認: 正確な目標保守版、JDK 25 の採用 image、Podman/Docker のテスト環境、OpenShift namespace、現行性能 SLO。これらは実装開始時の環境確定項目であり、計画作成を止める事項ではない。

## 7. 公式根拠

- [Camel 4.22.2 が指定する依存版](https://github.com/apache/camel/blob/camel-4.22.2/parent/pom.xml)
- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot migration guides](https://github.com/spring-projects/spring-boot/wiki)
- [Camel migration guides](https://camel.apache.org/manual/camel-4x-upgrade-guide.html)
- [JDK 25 migration preparation](https://docs.oracle.com/en/java/javase/25/migrate/preparing-migration.html)

## 実績の更新

当初計画と実施結果は区別する。[更新実行レポート](CAMEL-4.22-UPGRADE-REPORT.md)を実績・制約の正本とする。未チェック項目は残件である。
