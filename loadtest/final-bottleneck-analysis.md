# Final Bottleneck Analysis

## 対象

本レポートは、以下の最新ロードテスト結果を統合し、現時点の最終ボトルネック仮説を整理したものです。

- `loadtest/k6-1000-accounts-brokers-3-db-split-comparison.json`
- `loadtest/k6-unique-accounts-brokers-3-db-split-comparison.json`
- `loadtest/k6-1000-accounts-brokers-3-db-split-activity-async-comparison.json`

さらに、`trade_activity` 非同期化後の `5 replicas` 再試験:

- `loadtest/k6-1000-accounts-brokers-3-db-split-activity-async-5replicas.json`

と、`fx-core-service` の HikariCP / PostgreSQL 調整後の `5 replicas` 再試験:

- `loadtest/k6-1000-accounts-brokers-3-db-split-activity-async-pool30-5replicas.json`
- `loadtest/k6-permanent-fix-comparison.json`
- `loadtest/k6-balance-bucket-comparison.json`

も考察に反映します。

## 1. 最新結果の要約

### 1000アカウント集中 `trade_activity` 同期版

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 371.31 | 0.1745s | 0.4025s | 0.0275s | 687 | 506 |
| 3 | 372.02 | 0.1754s | 0.7036s | 0.0313s | 3774 | 719 |
| 5 | 18.10 | 3.2405s | 30.2603s | 0.0101s | 7748 | 583 |

### 1000アカウント集中 `trade_activity` 非同期版

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 375.97 | 0.1738s | 0.3416s | 0.0109s | 752 | 87 |
| 3 | 373.63 | 0.1748s | 0.7574s | 0.0299s | 2018 | 1551 |
| 5 | 336.84 | 0.1931s | 1.8160s | 0.1437s | 707 | 3599 |

### 1000アカウント集中 `trade_activity` 非同期 + Pool 30 / PG tuning

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max | Hikari active max | Hikari pending max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 169.97 | 0.3755s | 2.6574s | 0.7282s | 703 | 730 | 6 | 0 |

### 毎回ユニークアカウント `trade_activity` 同期版

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 377.23 | 0.1733s | 0.2606s | 0.0266s | 17606 | 359 |
| 3 | 7.05 | 8.1031s | 30.2460s | 0.0098s | 18458 | 10 |
| 5 | 5.81 | 9.6407s | 30.2364s | 0.0092s | 75 | 14 |

### 1000アカウント集中 + `trade_activity` 非同期化後の 5 replicas

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max | Hikari active max | Hikari pending max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 155.03 | 0.4096s | 4.5852s | 1.2031s | 511 | 1132 | 10 | 14 |

### 1000アカウント集中 + 恒久対策版

恒久対策として、`fx-core-service` の接続予算を `DB_POOL_MAX_SIZE=8`, `DB_POOL_MIN_IDLE=2` に抑え、`idle-timeout=30000ms` を追加しました。さらに `fx-core-db` 側で `max_connections=160` を明示し、`log_lock_waits=on`, `deadlock_timeout=100ms` を有効化しました。

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max | Hikari active max | Hikari pending max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 369.76 | 0.1749s | 0.3287s | 0.0150s | 3509 | 220 | 2 | 0 |
| 3 | 354.89 | 0.1837s | 1.2031s | 0.0475s | 1646 | 3659 | 1 | 0 |
| 5 | 49.48 | 1.0848s | 25.6881s | 5.7266s | 740 | 406 | 8 | 5 |

### 1000アカウント集中 + `account_balance` bucket 化版

`account_balance_bucket` を導入し、`tradeId` のハッシュを起点に bucket へ hold を分散する方式へ変更しました。`balance_hold.bucket_no` も追加し、残高参照は bucket 合計へ切り替えています。

| replicas | Requests/sec | Avg latency | Slowest | HTTP p95 | Kafka lag max | Outbox backlog max | Hikari active max | Hikari pending max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 375.91 | 0.1738s | 0.2917s | 0.0166s | 3602 | 395 | 3 | 0 |
| 3 | 361.08 | 0.1804s | 0.7963s | 0.0434s | 2478 | 4459 | 5 | 0 |
| 5 | 28.18 | 1.8266s | 30.1461s | 3.1317s | 303 | 83 | 8 | 0 |

## 2. まず何が確実に言えるか

### 2.1 `trade_activity` 同期書き込みはボトルネックだった

これはほぼ確定です。

`1000アカウント集中, 5 replicas` で

- 非同期化前: `18.10 req/s`, `3.2405s avg`, `30.2603s slowest`
- 非同期化後の単発再試験: `155.03 req/s`, `0.4096s avg`, `4.5852s slowest`
- 非同期化後のフル比較: `336.84 req/s`, `0.1931s avg`, `1.8160s slowest`

まで改善しています。

つまり、**Saga DB への同期 activity 書き込みが、リクエスト経路と後続処理に強い悪影響を与えていた**と判断できます。

### 2.2 接続枯渇は恒久対策で抑制できた

これは今回の追加観測で確認できました。

- 恒久対策前の観測では、`fx-core-db` に `FATAL: sorry, too many clients already` が連続発生した
- 恒久対策後のフル試験では、同エラーは直近ログに出ていない
- `5 replicas` でも `Hikari active max = 8`, `Hikari pending max = 5` で、上限に張り付くが DB 接続破綻までは進んでいない

つまり、**接続プールの過剰拡大が引き起こしていた DB 接続枯渇は抑え込めた**と言えます。

### 2.3 bucket 化だけでは `5 replicas` の決定打にならなかった

bucket 化後の `5 replicas` は

- `49.48 req/s -> 28.18 req/s`
- `1.0848s avg -> 1.8266s avg`
- `HTTP p95 5.7266s -> 3.1317s`
- `fx_core_lock_wait_p95 0.0065s -> 4.0981s`

でした。

つまり、

- HTTP p95 は改善した
- しかし throughput と平均遅延は悪化した
- `fx_core_lock_wait` はむしろ大きく悪化した

ため、**現在の bucket 化実装では `5 replicas` 時の競合を十分に解消できていない**と判断できます。

## 3. その次に強いボトルネック候補

### 3.1 `fx-core-db` の同期 ACID 更新

`trade_activity` を外しても、

- `5 replicas` は `1 replica / 3 replicas` より遅い
- `Hikari active max = 10`
- `Hikari pending max = 14`

となっています。

これは、`fx-core-service` が使う DB 接続プールと DB 応答待ちが詰まり始めていることを示しています。恒久対策後は「接続枯渇」自体は抑えられたため、今は **接続数そのものより、1 Tx あたりの DB 処理量と待ち時間** が中心課題です。

`fx-core-service` は 1 リクエストで

- `trade_order`
- `trade_execution`
- `account_balance`
- `balance_hold`
- `fx_position`
- `outbox_event`

を書きます。  
この ACID トランザクションが、**次の主ボトルネック**と見てよいです。

## 4. 条件別の違いから分かること

### 4.1 1000アカウント集中

`1 -> 3 replicas` はほぼ横ばいでした。

- `371.31 -> 372.02 req/s`

つまり、**3 replicas に増やしても throughput はほとんど伸びていない**です。  
この時点で、単純な stateless horizontal scaling が効いていないことが分かります。

### 4.2 毎回ユニークアカウント

こちらはさらに厳しく、

- `1 replica`: `377.23 req/s`
- `3 replicas`: `7.05 req/s`
- `5 replicas`: `5.81 req/s`

でした。

この挙動は、単なるアカウントホットスポットだけでは説明できません。  
むしろ、**レプリカ増加に伴うシステム全体の同期点や待ち行列の増幅**が発生している可能性が高いです。

## 5. Kafka は主犯か？

### 結論

**主犯ではないが、無視はできない**です。

理由:

- Kafka `3 broker` 化で、固定 1000 アカウント側の `3 replicas` は改善した
- しかし、DB 分離後・activity 非同期化後でも `5 replicas` は完全には伸びない
- さらにユニークアカウントでは `3 / 5 replicas` が強く悪化した

このため、Kafka は「一因」ではあっても、「最後に残る主ボトルネック」ではなさそうです。

## 6. 現時点の最終ボトルネック仮説

優先度順に整理すると、現時点では次の順です。

### 1. `fx-core-db` の同期 ACID トランザクション

特に

- `account_balance`
- `fx_position`
- `outbox_event`

の更新を含む 1 Tx が重い可能性が高いです。  
bucket 化後も `fx_core_tx_p95 = 3.0602s`, `fx_core_lock_wait_p95 = 4.0981s` が出ているため、単一行競合だけでなく **複数 bucket を順番に触る実装コスト** も新たな負荷になっています。

### 2. `5 replicas` 時の残留 DB wait / pool 飽和

恒久対策後の `5 replicas` でも

- `Hikari active max = 8`
- `Hikari pending max = 5`
- `fx_core_tx_p95 = 5.2494s`
- `fx_core_lock_wait_p95 = 0.0065s`

でした。  
接続枯渇ほどではないものの、`fx-core-service` が `5 replicas` で DB 同時実行の上限に達し、待ち行列を作っていると考えられます。

### 3. Kafka consumer と Saga 依存チェーン

Kafka lag 自体は以前より改善しているものの、

- Cover
- Risk
- Accounting
- Settlement

の依存チェーンが残るため、後続が完全並列には伸びません。

### 4. ユニークアカウント試験で見えている別要因

ユニークアカウントで `3 / 5 replicas` が大きく悪化するのは、

- consumer group 再配置
- service 間接続数増加
- 各サービスのローカル DB 書き込み
- 監視 / trace / log export のオーバーヘッド

などを含む複合要因の可能性があります。

## 7. 最終判断

現時点では、最終ボトルネック仮説はこうです。

> **主ボトルネックは PostgreSQL を中心とした同期 ACID 処理であり、そこに `5 replicas` 時の残留 DB wait、Kafka / Saga の後続追従が重なっている。**

さらに言うと、

> **`trade_activity` 同期書き込みは最初の大きなボトルネックで、接続プール過剰拡大はその次の障害増幅要因だった。両方を抑えると、`fx-core-db` の同期 ACID 処理と Saga 後続遅延が次のボトルネックとして前面に出た。**

## 7.1 Hikari / PostgreSQL 調整で何が変わったか

`5 replicas` に対して、

- `DB_POOL_MAX_SIZE=30`
- `DB_POOL_MIN_IDLE=5`
- `DB_POOL_CONNECTION_TIMEOUT_MS=10000`
- PostgreSQL: `log_lock_waits=on`
- PostgreSQL: `deadlock_timeout=100ms`
- PostgreSQL: `max_connections=300`

を入れた再試験では、

- `Requests/sec`: `336.84 -> 169.97 req/s`
- `Average latency`: `0.1931s -> 0.3755s`
- `Hikari active max`: `3 -> 6`
- `Hikari pending max`: `0 -> 0`
- `Kafka lag max`: `707 -> 703`

でした。

この結果から見ると、

- 接続プールを増やすだけで throughput が上がるわけではない
- むしろ ACID 領域がより多くの同時 DB 操作を受け、1 リクエストあたりの待ちが増えた

可能性があります。

## 7.2 恒久対策で何が変わったか

`5 replicas` に対して、

- `DB_POOL_MAX_SIZE=8`
- `DB_POOL_MIN_IDLE=2`
- `DB_POOL_CONNECTION_TIMEOUT_MS=5000`
- `DB_POOL_IDLE_TIMEOUT_MS=30000`
- PostgreSQL: `max_connections=160`

を反映した再試験では、

- `Requests/sec`: `7.83 -> 49.48 req/s` （直近の接続枯渇再試験比で改善）
- `Average latency`: `6.2710s -> 1.0848s`
- `HTTP p95`: `19.4228s -> 5.7266s`
- `Hikari active max`: `11 -> 8`
- `Hikari pending max`: `0 -> 5`
- `too many clients already`: 再発なし

でした。

この結果から見ると、

- **接続予算の縮小と DB `max_connections` 明示は、接続枯渇そのものには有効**
- ただし **`5 replicas` で線形に伸びるほど ACID 区間は軽くなっていない**
- その結果、接続数破綻は避けられても `fx-core` 内部待ちと Saga 側の滞留が残る

と整理できます。

## 7.3 bucket 化で何が変わったか

bucket 化後のフル比較では、

- `1 replicas`: `375.91 req/s` でほぼ問題なし
- `3 replicas`: `361.08 req/s` で恒久対策版と同等
- `5 replicas`: `28.18 req/s` で恒久対策版 `49.48 req/s` を下回る
- `too many clients already`: 再発なし

でした。

この結果から見ると、

- **接続枯渇抑止には効いている**
- **`1 / 3 replicas` では bucket 化の副作用は限定的**
- **`5 replicas` では bucket を跨ぐ順次更新が逆に ACID 区間を伸ばし、`fx_core_lock_wait` を悪化させている**

可能性が高いです。

## 8. 次の改善案

### 優先度高

1. `fx-core-db` の lock wait / slow query / transaction duration をさらに詳細化
2. `account_balance` / `fx_position` の競合を減らす設計見直し
3. `trade_saga` 段階別レイテンシの実値が確実に出るように観測経路を調整
4. `Hikari max pool size` は単純増加ではなく、DB 実リソースと合わせて再設計

### 優先度中

1. `fx-core-service` の ACID 領域をさらに短くする
2. Outbox を polling から push 型へ比較
3. ユニークアカウント試験時の consumer group 配置を詳細観測

## 9. まとめ

- Kafka 3 broker 化: 効果あり
- DB 分離: 効果あり
- `trade_activity` 非同期化: 効果大
- 接続予算の恒久対策: 接続枯渇の抑止には有効
- `account_balance` bucket 化: `5 replicas` の決定打にはならず、今回の実装ではむしろ悪化
- それでも `5 replicas` はまだ遅い

したがって、

**いま一番怪しいのは `fx-core-db` の同期 ACID トランザクションで、その中でも bucket を跨ぐ更新を含む `account_balance` 処理です。次点が Saga 後続滞留です。**
