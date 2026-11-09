# fx-trading-sample — Camel 4.22 更新実行レポート

実施日: 2026-11-09。ブランチ: `upgrade/camel-4.22`。最終検証結果は本レポートの「検証結果」に記載する。追加のOpenShift配備と疎通結果は [OpenShift配備レポート](CAMEL-4.22-OPENSHIFT-REPORT.md) に記録した。本番相当負荷試験は未実施である。

## 採用構成と変更

- JDK 21 / Boot 3.3.6 / Camel 4.14.0 → **JDK 25 / Spring Boot 4.1.1 / Camel 4.22.2 / Jackson 3**。
- 親POM、common、8サービスのstarter、compiler / Surefire / Boot pluginを更新した。
- Jacksonのmapperと例外・TypeReferenceのimportを移行し、Camelはjackson3 starterを使用する。既存annotationsのpackageは変更しない。
- TestcontainersはBoot管理の2.0.5のモジュール構成へ更新した。コンテナがないときに結合試験をskipする設定を除去し、結合試験モジュールではテスト未検出も失敗させる。
- 各runtimeをTemurin 25へ変更し、USER 185で実行する。共通multi-stage DockerfileのCOPY対象を起動可能な `*-exec.jar` に修正した。通常JARは他モジュールからの依存用に残す。
- DB schema、event schema、Kafka broker本体、業務補償ロジックは変更していない。
- 計画のBoot 3.5 / Camel 4.18中間点は作成せず、現行ブランチを基点に直接移行した。最終構成での契約・結合試験を採用判断の根拠とする。

## 検証結果

最終ソースで `TESTCONTAINERS_RYUK_DISABLED=true` を指定し、`bash scripts/verify-camel-upgrade.sh -s <一時Central設定>` を実行した。clean verifyは **BUILD SUCCESS**、全11 Mavenモジュールが成功した。

- 既存 `TradeFlowIntegrationTest`: **7件成功、失敗0、error0、skip0**。
- 追加 `JsonContractTest`: **3件成功、失敗0、error0、skip0**。旧JSONの省略booleanとdecimal scale、Outbox writerのround-trip、実Camel data formatを確認した。
- 合計: **10件成功、失敗0、error0、skip0**。各試験XMLをscriptでも集計した。
- 最終build完了: 2026-11-09 11:05 JST。所要2分53秒。初回downloadや修正・試行時間は含まない。
- 8サービスそれぞれの通常JARと起動用exec JARの生成を確認した。

検証環境はmacOS arm64、Maven 3.10.0、Homebrew JDK 25.0.5.0.3、Podman 5.8.2。PostgreSQL 16とKafka native 3.8.0をTestcontainersで起動し、8個のSpring application contextを実行した。

更新前はMavenのbuildが成功しても、Ryuk起動失敗により既存7件がすべてskipとなった。更新前の業務baselineが合格したとは主張しない。更新後はskipを禁止して実際に実行する。

コンテナの確認として `Dockerfile.runtime` からfx-coreの実行可能JARを含むimageをbuildし、USER 185でJavaを実行した。image内のJavaはTemurin 25.0.5であった。これはimage生成とJDK実行の確認であり、8サービスを個別コンテナとして全配備した試験ではない。最終clean buildで生成したJARを用いてimageを再buildし、同じ実行ユーザーでJDK起動を再確認した。

## 実際に詰まった点と対処

1. **Jackson 3の既定値で旧イベントが読めない。** simulation booleanを省略したrecordの読込が `FAIL_ON_NULL_FOR_PRIMITIVES` により失敗した。共通JsonMapperでこのfeatureを無効にし、Jackson 2と同じfalse補完を維持した。単にimportを置換するだけでは解消しない。
2. **RESTとCamelのmapperは別途確認が必要。** 追加試験で、mapper Beanを登録するだけでは通常のJSON DSLが独立した既定mapperを使い、旧JSONを読めないことを確認した。共通の `tradePayloadJson` data formatへ互換mapperを明示注入し、全consumer・projectionルートから名前参照する構成へ修正した。実CamelContextで同じdata formatを試験する。decimalのscaleと省略flagを確認する。
3. **日時設定の移動で起動失敗。** `spring.jackson.serialization.write-dates-as-timestamps` はJackson 3で使用できず、`spring.jackson.datatype.datetime.write-dates-as-timestamps` に変更した。POMのbuildだけでは検出できず、Spring contextの起動で判明した。
4. **結合試験がgreenでもskipだった。** 旧設定の `disabledWithoutDocker=true` を除去した。rootless PodmanではRyukのsocket起動に失敗したため、今回のローカル試験に限り `TESTCONTAINERS_RYUK_DISABLED=true` を設定した。コード上でRyukを無条件無効化していない。CIでは原則有効にし、ローカルで無効化する場合は残留コンテナを確認する。
5. **非同期projectionの待機競合。** 既存テストがprojection作成前にnull参照して即時失敗する場合があった。Awaitility内でassertNotNullしてから状態を検証するよう修正した。状態の期待値を緩めたり、例外を握りつぶしたりしていない。
6. **Logbackの条件付きroot設定。** root内部にifをネストした既存構成を見直し、consoleを無条件に設定し、Loki appenderの追加を外側の条件で行う構成にした。起動・Camel・Kafkaのconsoleログを確認する。Lokiへの実送信は未検証である。
7. **imageとJARの取り違え。** multi-stage Dockerfileが通常JARをCOPYしていたため、classifier付きの起動可能JARへ修正した。release 25のJARをJava 21のimageで実行しないよう、各Dockerfileを更新した。

## 再実行方法

JDK 25をJAVA_HOMEへ設定し、Docker API互換環境を起動してから実行する。

```bash
bash scripts/verify-camel-upgrade.sh
```

rootless PodmanでRyukが使えない管理されたローカル環境では、必要に応じて環境変数 `TESTCONTAINERS_RYUK_DISABLED=true` を指定する。scriptは既存7シナリオ、Kafkaレコードキー試験1件、イベント順序試験2件、JSON契約3件の件数・失敗・error・skipを確認する。検証では一時Maven設定によりCentralへ接続した。個人settingsや秘密値は登録しない。

runtime imageの再build例はbackendディレクトリで次のとおりである。

```bash
podman build -f Dockerfile.runtime --build-arg JAR_FILE=fx-core-service/target/fx-core-service-0.1.0-SNAPSHOT-exec.jar -t localhost/fx-core-camel422:validation .
podman run --rm --entrypoint java localhost/fx-core-camel422:validation -version
```

## 配備前の残件・苦労が見込まれる点

- 送信成功後・SENT更新前の強制停止、複数replicaでの同時claim、同一eventの再配信、DB / broker障害・復旧、rebalance、SIGTERMの追加障害試験。今回の既存7シナリオで全てを代替したとは扱わない。
- OpenShiftの隔離namespaceで8サービスを実imageとして配備し、readiness、監視、trace、Loki、DB connection budgetを確認する。
- 同一dataset・replica・resourceでk6のlatency / throughput / lag / Outbox滞留を比較し、SLOを判定する。
- 新旧payloadの双方向互換性、旧consumerへの切り戻し、進行中取引のdrainを検証する。offsetやOutboxを初期化して整合性を合わせない。
- UBI 21からTemurin 25へ配布元とOSが変わる。社内image基準、サポート、脆弱性管理、CA証明書、診断手順を配備前に確認する。imageの実証はarm64であり、OpenShift向けamd64を別途検証する。
- JDK 25上のTomcat停止時にAsynchronousCloseExceptionによるaccept終了ログが出た。ログを抑制して隠さず、試験・JVMの終了結果と区別して扱う。OpenShiftのSIGTERM試験で終了時間と処理中リクエストへの影響を確認する。
- native image、frontendのE2E、実際の本番切替は未実施である。

## 難易度比較

FXはBootのmajor更新、Jacksonの既定値変更、8サービスの永続状態・補償、JDKとruntimeを跨ぐため、API側より検証と切り戻しの範囲が広い。実際にも既存JSONの読込互換性と非同期試験の競合が表面化した。API側はHTTP・streamingの本番相当試験に残る不確実性が大きいため、今回のローカル作業時間だけで本番移行の総工数は判断できない。

関連: [計画](CAMEL-4.22-UPGRADE-PLAN.md) / [WBS](CAMEL-4.22-WBS.md) / [ToDo](CAMEL-4.22-TODO.md)。当初計画を残し、本レポートを実績の正本とする。

## 追加修正: Kafkaレコードキーの回帰

実施日: 2026-11-09。Camel 4.22では `KafkaConstants.KEY` の値が `kafka.KEY` から `CamelKafkaKey` へ変更された。Outbox発行ルート2箇所が `"kafka.KEY"` を直書きしていたため、例外なくレコードキーがnullとなり、tradeId単位の順序保証が失われていた。

- 共通ルートとfx-core独自ルートを `KafkaConstants.KEY` 参照へ変更し、`common` に `camel-kafka` を追加した。
- 結合試験 `outboxEventsShouldUseTradeIdAsKafkaKey` を追加した。修正前は `fx-trade-events` / `fx-cover-events` の両方でキーがnullとなり失敗することを確認した。
- 修正後の `scripts/verify-camel-upgrade.sh`: BUILD SUCCESS、**11件成功(TradeFlow 8、JsonContract 3)、失敗0、error0、skip0**。

修正を含むimageは `fx-trading-camel422` へ配備済みである。既にKafkaへ送信済みのキーなしレコードは修正されない。

## 追加修正: Sagaのイベント到着順序

実施日: 2026-11-09。別トピックの `CoverTradeBooked` が `RiskUpdated` / `AccountingPosted` より後に処理されると、完了済みのrisk / accountingを無条件に `PENDING` へ戻し、Sagaが完了しなかった。OpenShiftの再起動試験で3件が停止し、Sagaの処理順で原因を確認した。当該コードは今回のCamel更新では変更しておらず、既存の競合である。

- `CoverTradeBooked` は `NOT_STARTED` のステップだけを `PENDING` へ更新する。
- coverが最後に届いた場合も完了判定を行う。
- 結合試験 `lateCoverEventShouldNotRegressCompletedStepsAndShouldCompleteSaga` を追加した。修正前は失敗することを確認した。
- 修正後の `scripts/verify-camel-upgrade.sh`: BUILD SUCCESS、**12件成功(TradeFlow 9、JsonContract 3)、失敗0、error0、skip0**。

既にPENDINGで停止した合成取引はDBを手動で書き換えず、そのまま残している。

## 追加修正: 補償開始後に完了したステップの取消

実施日: 2026-11-09。補償の開始時点で未完了のcover / risk / accountingが後から完了すると取消されず、精算まで開始され、SagaはCOMPENSATINGのまま閉じなかった。

- 補償開始時に、`NOT_STARTED` と `PENDING` のステップへ取消予約を付ける。ステップの完了時に予約があれば一度だけ取消を要求する。
- 補償中は精算を開始しない。coverの結果が未着の間はSagaをCANCELLEDにしない。
- 補償開始時は先に行ロックを取り、同時に完了したステップの取消漏れと二重要求を防ぐ。
- 結合試験 `coverArrivingAfterCompensationStartedShouldBeReversed` を追加した。修正前は失敗することを確認した。
- 修正後の `scripts/verify-camel-upgrade.sh`: BUILD SUCCESS、**13件成功(TradeFlow 10、JsonContract 3)、失敗0、error0、skip0**。

再配備後の結果は [OpenShift配備レポート](CAMEL-4.22-OPENSHIFT-REPORT.md) に記録した。

## 追加の配備実績

本書の「未実施」「残件」は先行ローカル検証時点の記録である。現在の配備・疎通・判明した制約は [OpenShift配備レポート](CAMEL-4.22-OPENSHIFT-REPORT.md) を参照する。
