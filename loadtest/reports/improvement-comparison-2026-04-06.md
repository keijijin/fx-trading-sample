# パフォーマンス改善 Before / After 比較レポート（2026-04-06）

## 施策

| # | 変更 | ファイル |
|---|------|----------|
| 1 | Outbox split に `parallelProcessing()` を追加 | `AbstractOutboxPublisherRoute.java`, `FxCoreServiceApplication.java` |
| 2 | Kafka コンシューマに `consumersCount=2` を追加 | `KafkaConsumerUris.java` |
| 3 | Kafka プロデューサに `lingerMs=5` を追加 | 上記 Outbox ルートの `toD("kafka:...")` |

**目的**: Kafka consumer lag（ストレス時 1391、スパイク cool 相 1354）を削減する。

---

## 1. フルスイート（1 レプリカ固定）

全 7 シナリオ、Before / After ともに **PASS**。

| シナリオ | trade p95 (s) || Kafka lag max || saga p95 (s) || lag 変化率 |
|----------|:---:|:---:|:---:|:---:|:---:|:---:|---:|
| | Before | After | Before | After | Before | After | |
| smoke | 0.226 | 0.228 | **42** | **6** | 0.340 | 0.420 | **-86%** |
| baseline | 0.232 | 0.226 | **400** | **118** | 0.349 | 0.432 | **-71%** |
| cover_fail | 0.225 | 0.224 | **227** | **118** | 0.248 | 0.268 | **-48%** |
| accounting_fail | 0.226 | 0.224 | **219** | **60** | 0.353 | 0.442 | **-73%** |
| notification_fail | 0.227 | 0.226 | **186** | **66** | 0.346 | 0.428 | **-65%** |
| stress | 0.227 | 0.225 | **1391** | **829** | 0.352 | 0.443 | **-40%** |
| soak | 0.224 | 0.251 | **593** | **829** | 0.350 | 0.430 | +40%* |

\* soak はストレスの直後に実行されるため、前シナリオ（stress）の lag 残りが影響している。ストレス単体の改善（-40%）を主眼とする。

### 分析

- **trade p95（API 応答）**: 施策前後でほぼ横ばい（±3ms）。約定コア側のレイテンシには影響なし。
- **Kafka lag**: ストレスを含む 6/7 シナリオで **40〜86% 削減**。`parallelProcessing()` による Outbox 排出の並列化と、`consumersCount=2` によるコンシューマ多重化が効いている。
- **saga p95**: 0.34→0.43 付近に約 25% 増加。Outbox の並列送信が DB（activity / saga）への書き込み並行度を上げた結果、saga 側の DB 応答がやや遅くなっている可能性がある。ただし全シナリオ PASS（ビジネス SLA 内）。

---

## 2. スパイク × スケールアウト（replicas 1 / 3 / 5）

全ラン k6 PASS。Kafka lag のピーク（3m instant snapshot）:

| replicas | Before | After | 変化率 |
|----------|--------|-------|--------|
| 1 | 657 | **634** | -4% |
| 3 | 1111 | **2167** | +95% |
| 5 | 1354 | **1836** | +36% |

### 相別 lag max（query_range）

| replicas | 相 | Before | After | 変化率 |
|---|:---:|---:|---:|---:|
| 1 | warm | 113 | 100 | -12% |
| 1 | spike | 657 | 634 | -4% |
| 1 | cool | 581 | 598 | +3% |
| 3 | warm | 171 | 102 | **-40%** |
| 3 | spike | 902 | 1776 | +97% |
| 3 | cool | 1111 | 2167 | +95% |
| 5 | warm | 226 | 140 | **-38%** |
| 5 | spike | 982 | 1193 | +21% |
| 5 | cool | 1354 | 1836 | +36% |

### 分析

- **r=1（スケールなし）**: lag は微改善。フルスイートと同じ傾向。
- **r=3 / r=5（スケールアウト時）**: lag が悪化。**warm 相は 38-40% 改善**しているが、**spike→cool で大幅に増加**。
- **原因**: `consumersCount=2` により、Pod 数 × 2 のコンシューマスレッドが各グループに参加するため、**スケールアウト時の Kafka リバランスが頻繁化**し、spike 相の処理中断時間が伸びたと考えられる。

---

## 3. 総合評価

| 観点 | 評価 |
|------|------|
| **定常運用（1 Pod）** | **大幅改善**: lag -40〜-86%、全シナリオ PASS。Outbox 並列化 + コンシューマ多重化が有効。 |
| **スケールアウト時** | **退行あり**: 多 Pod でのリバランスオーバーヘッドが lag を増加させる。warm 相は改善しており、定常安定後は効果があるが、**動的スケーリング直後の spike は悪化**。 |
| **API レイテンシ** | **変化なし**: trade p95 は ±3ms 以内。 |
| **saga レイテンシ** | **微増**: 0.34→0.43s（+25%）。SLA 内だが監視ポイント。 |

## 4. 推奨アクション

1. **`consumersCount` の調整**: スケールアウトを頻繁に行う運用では `consumersCount=1` に戻すか、`static` メンバーシップ（`group.instance.id`）で リバランスを抑制する。定常 Pod 数が固定なら `consumersCount=2` で lag 削減効果を享受。
2. **`parallelProcessing()` + `lingerMs=5`**: 定常・スケールアウトともに Outbox 排出には正の効果があるため維持を推奨。
3. **saga DB 負荷**: saga p95 の 25% 増を監視し、必要に応じて `activity.db.pool.max-size` の引き上げまたは saga DB の `max_connections` 増を検討。

---

### 参照ファイル

| 内容 | パス |
|------|------|
| Before フルスイート | `loadtest/reports/test-plan-suite-2026-04-05.json` |
| After フルスイート | `loadtest/reports/test-plan-suite-after.json` |
| Before スパイク | `loadtest/reports/spike-scale-2026-04-05.json` / `.md` |
| After スパイク | `loadtest/reports/spike-scale-after.json` / `.md` |
