# Camel 4.22 — isolated OpenShift deployment

Namespace: `fx-trading-camel422`。JDK 25 / Spring Boot 4.1.1 / Camel 4.22.2を配置する。

## 構成

- 8個のbackend、サービス別PostgreSQL 8個、単一Kafka broker、Next.js frontend。
- PostgreSQL 8 PVCとKafka 1 PVCを利用する。StorageClassはクラスタのdefaultを使用する。
- Prometheus / Loki / Tempo / Grafanaはnamespace内に配置する。Prometheusは8サービスをstatic scrapeし、cluster-wide RBACやkube-state-metricsを作成しない。cluster全体のnode / pod監視はこの構成の対象外である。
- Routeはedge TLS。DB password / Grafana passwordはSecretに保持する。

## 再配備

JDK 25、Maven、Python 3、認証済みocが必要である。

```bash
oc apply -f openshift/camel422/namespace.yaml
python3 scripts/prepare-camel422-openshift.py
oc apply -f openshift/camel422/builds.yaml
cd backend
mvn -B package -DskipTests
cd ..
```

各backendは `backend/<service>` をbinary inputとしてbuildする。各ディレクトリのDockerfileは `target/*-exec.jar` を使用する。通常JARと取り違えない。

```bash
oc start-build fx-core-service --from-dir=backend/fx-core-service --follow --wait -n fx-trading-camel422
```

他の7 backendも同様に実行する。`fx-postgres` / `fx-kafka` のcontextは `openshift-images/postgres` / `openshift-images/kafka`、`frontend` は `frontend` である。frontendのbinary inputから `.env*` / node_modules / .next を除外する。

全11 buildがCompleteになった後、次を実行する。

```bash
oc apply -f openshift/camel422/applications.yaml
oc get deployment,statefulset,pvc,route -n fx-trading-camel422
```

namespace・build定義・アプリをまとめた `oc apply -k openshift/camel422` も利用できるが、Secretとbinary imageの準備は別途必要である。

`prepare-camel422-openshift.py` は既存Secretを保持する。接続ユーザーは元の分離DB設定に合わせて `fx` とする。usernameを変更する場合はACTIVITY_DB_USERNAME等の接続設定も合わせて変更する。

## 実証で必要になった修正

- PostgreSQLのPGDATAは `/tmp/pgdata/pgdata`。PVC直下にlost+foundがあるため、mount pointそのものをPGDATAにしない。
- PostgreSQL DeploymentはRecreate。単一PVCへ新旧DB Podを同時接続させない。
- PodのService Linkを無効化し、DNS / 明示envを使用する。
- Sagaは1秒のhealth probeでSIGTERM再起動したため、probe timeoutを5秒へ変更した。healthの成功条件自体は変更していない。

## 検証上の残件

Ready後の正常取引・cover失敗時補償・frontend proxyを確認した。一方、起動・再起動中の最初の合成試行1件はaccountingがPENDINGのまま残った。その後、Sagaのイベント順序の競合を修正し、accountingのrolling restart中の10取引がすべて完了することを確認した。Pod停止による処理中断、無停止切替、負荷試験の合格とは扱わない。offset resetやDB状態の手動書換えで隠していない。

結果は [配備レポート](../../../CAMEL-4.22-OPENSHIFT-REPORT.md) を参照する。
