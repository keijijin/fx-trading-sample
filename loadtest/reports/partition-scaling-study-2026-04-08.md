# Kafka Partition 増設の再試験（2026-04-08）

`The Microservice Scaling Paradox` の続きとして、スケールアウト時の Kafka lag を下げるために **高頻度 topic の partition 数を 6 -> 12** へ増やし、`1 / 3 / 5 replicas` のスパイク試験を再実施した。

## 変更内容

- `fx-trade-events`
- `fx-cover-events`
- `fx-risk-events`
- `fx-accounting-events`
- `fx-settlement-events`
- `fx-notification-events`
- `fx-compliance-events`

上記 7 topic を **12 partitions** に増設。`fx-compensation-events` は正常系スパイクでは主役ではないため据え置き。

前提として、consumer 側は直前の調整で **`consumersCount=1`** にしている。

## 比較対象

| ラベル | 内容 |
|--------|------|
| **base** | `partitions=6`, `consumersCount=2` |
| **cons1** | `partitions=6`, `consumersCount=1` |
| **part12** | `partitions=12`, `consumersCount=1` |

## 結果サマリー

### Kafka lag max（試験直後 3m スナップショット）

| replicas | base | cons1 | part12 |
|:---:|---:|---:|---:|
| 1 | 554 | **302** | 583 |
| 3 | 910 | **697** | 825 |
| 5 | 1357 | 1157 | **918** |

### spike 相の Kafka lag max

| replicas | base | cons1 | part12 |
|:---:|---:|---:|---:|
| 1 | 554 | **302** | 583 |
| 3 | 910 | **697** | 825 |
| 5 | 1357 | 1157 | **918** |

### cool 相の Kafka lag max

| replicas | base | cons1 | part12 |
|:---:|---:|---:|---:|
| 1 | 495 | **268** | 442 |
| 3 | 670 | **612** | 613 |
| 5 | 940 | 912 | **810** |

### POST 201 rate（Prometheus 3m スナップショット）

| replicas | base | cons1 | part12 |
|:---:|---:|---:|---:|
| 1 | 42.25/s | 46.90/s | **47.65/s** |
| 3 | 15.80/s | **17.12/s** | 15.40/s |
| 5 | 8.72/s | **10.93/s** | 9.47/s |

## 解釈

### 1. `consumersCount=1` は明確に効いた

`partitions=6` のままでも、`consumersCount=2 -> 1` に下げることで:

- `r=1`: lag `554 -> 302`（**-45%**）
- `r=3`: lag `910 -> 697`（**-23%**）
- `r=5`: lag `1357 -> 1157`（**-15%**）

つまり、まず効いていたのは **consumer の過剰並列を止めたこと**。

### 2. `partitions=12` は **5 replicas** にだけ効いた

`cons1 -> part12` で見ると:

- `r=1`: lag `302 -> 583`（悪化）
- `r=3`: lag `697 -> 825`（悪化）
- `r=5`: lag `1157 -> 918`（**改善**）

つまり、partition を増やすだけでは常に良くなるわけではない。  
**consumer 数が十分にあるときだけ** partition 増設のメリットが出る。

### 3. 現時点の最適値は単一ではない

| replicas | 暫定ベスト |
|:---:|------------|
| 1 | `partitions=6`, `consumersCount=1` |
| 3 | `partitions=6`, `consumersCount=1` |
| 5 | `partitions=12`, `consumersCount=1` |

## 問題点

1. **スケールアウト時の最適設定が固定ではない**  
   Pod 数に応じて最適 partition 数が変わる。
2. **5 replicas でもまだ lag は 918**  
   改善したが、まだ「十分小さい」とは言い切れない。
3. **Outbox backlog は 5 replicas で極端に悪化していない**  
   現在の主ボトルネックは Outbox より Kafka / consumer 側。

## 解決策の方向性

1. **運用目線では 3 replicas + `consumersCount=1` を基本線にする**  
   バランスが良い。
2. **5 replicas を使うなら 12 partitions へ増設する**  
   6 partitions のままよりは良い。
3. **次段の最適化は `trade-saga-service` 側の責務分割**  
   topic ごとの consumer group / route 構成を見直し、重い topic の処理をさらに分散する。
4. **partition 数は 12 を既定に更新**  
   `openshift/fx-trading-stack.yaml` の `KAFKA_NUM_PARTITIONS` を 12 に更新済み。

## 結論

**Pod を増やせば自動で速くなるわけではない。**  
今回の再試験では、

- **まず `consumersCount=1` が効いた**
- **そのうえで `partitions=12` は 5 replicas にだけ効いた**

という結果だった。

従って、現時点の実運用指針は次のとおり:

- **標準構成**: `3 replicas`, `consumersCount=1`, `partitions=6`
- **より高い並列を狙う構成**: `5 replicas`, `consumersCount=1`, `partitions=12`

ただし、5 replicas でも lag は残るため、根本対策としては **Saga / consumer 責務分割** や **シャーディング** が次の論点になる。
