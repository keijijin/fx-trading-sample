# fx-trading-sample — OpenShift配備結果

実施日: 2026-11-09。配備先: **fx-trading-camel422**。既存namespaceのアプリ・データは変更していない。

## 結果

- 全21 DeploymentとKafka StatefulSetがReady。内訳はbackend 8、PostgreSQL 8、frontend 1、namespace内監視4、Kafka 1。
- JDK 25 / Spring Boot 4.1.1 / Camel 4.22.2。11 imageをクラスタのamd64 node上でbinary Docker buildした。
- Prometheusの8サービス全targetがUP。稼働中の自前image 18コンテナがbuild済みdigestと一致した。
- DB 8個とKafkaの計9 PVCがBound。単一DBへ集約せず、既存のサービス別DB構成を維持した。
- 公開HTTPS RouteからfrontendとAPI healthが **HTTP 200**。
- 全サービスReady後の合成取引: 作成 **HTTP 201**、Sagaとnotificationが **COMPLETED**。
- 合成cover失敗: 作成 **HTTP 201**、補償後Sagaが **CANCELLED**。
- frontendのAPI proxy経由でも完了取引traceが **HTTP 200 / COMPLETED**。
- 公開RouteへのHTTPS疎通は証明書検証を有効にしてHTTP 200を確認した。
- Secret値・生ログ・クラスタ固有hostnameはGitに保存していない。

## 実際に必要になった対応

1. PVC直下のlost+foundによりPostgreSQL initdbが失敗した。PGDATAをmount point内のサブディレクトリへ変更した。
2. RWO PVCを使うDBの更新をRecreateへ変更し、新旧DB Podの同時接続・別nodeへのattach待ちを避けた。PVCを削除して解決していない。
3. 元のRouteはHTTPのみだったため、HTTPSアクセスが503になった。edge TLSに揃えた。
4. Sagaのhealth自体はUPだったが、1秒probeでSIGTERM再起動を繰り返した。timeoutを5秒へ調整し、全Deploymentの収束を確認した。
5. 監視は同じnamespace内へ配置した。Prometheusをstatic scrapeにし、他namespaceを読むcluster-wide RBACを新設していない。

## 未収束の合成試行と残件

起動・再起動中に投入した最初の合成取引1件は、SagaのaccountingがPENDINGのまま残った。後続のReady状態での新規取引と補償は成功したが、最初の試行を成功件数へ含めていない。

再起動中のイベント再配送、topic間順序と状態遷移の調査が必要である。Camel更新による回帰か既存の競合かは切り分け未完了である。offset reset、再送による二重処理、DBの手動書換えで隠していない。したがって、無停止更新・障害復旧の受入完了とはしない。

継続負荷、複数replica競合、DB / broker障害注入、旧版への切り戻し、全監視指標とtraceの受入は残件である。

## 再配備と追加検証（2026-11-09）

Kafkaレコードキーの回帰とSagaのイベント順序の競合を修正し、8サービスのimageを再配備した。全21 DeploymentがReadyで、再配備したサービスのdigestはbuild結果と一致した。

- 合成の正常取引: **COMPLETED**。cover失敗: **CANCELLED**。
- 6トピックで取引のレコードキーがtradeIdと一致し、同一トピック内で同じパーティションに配置された。
- accountingのrolling restart中に投入した合成取引10件: すべて **COMPLETED**。
- fx-coreの `/actuator/health` は約3〜5秒かかり、既定1秒のstartup probeで新Podが起動できなかった。timeoutを10秒へ変更した。

修正前は同じ試験で3件がPENDINGのまま停止し、`CoverTradeBooked` の後着による状態の上書きを処理順で確認した。前回の「起動・再起動中の1件」は記録が残らず同一原因とは断定しないが、修正後は再現していない。rolling restartでは旧Podが新PodのReadyまで処理を続けるため、この試験はPod停止による処理中断の試験ではない。既に停止した合成取引はDBを手動で書き換えず、そのまま残している。

補償開始後に完了したステップの取消を修正した `trade-saga-service` を再配備し、digestの一致を確認した。合成のcompliance失敗取引は **CANCELLED** となり、正常取引、cover失敗の補償、6トピックのレコードキー、accountingのrolling restart中の10取引も成功した。作業中にクラスタが一時的に到達不能となったが、復旧後に全21 DeploymentとKafkaがReadyへ戻ったことを確認した。

資材: [openshift/camel422](openshift/camel422/README.md)。検証集計: [validation-summary.json](openshift/camel422/validation-summary.json)。実際のURLは `oc get route -n fx-trading-camel422` で取得できる。
