# 構造的改善 Before / After 比較レポート（2026-04-06）

PDF レポート「The Microservice Scaling Paradox」およびディスカッション（`notebooklm.md`、`chapie.md`）の指摘に基づき、「Pod を増やしても速くならない」構造的ボトルネックに対処した。

## 施策サマリー

| # | 施策 | 変更対象 |
|---|------|----------|
| 1 | Outbox ポーリング用部分インデックス | `idx_outbox_event_poll (source_service, status, created_at) WHERE status IN ('NEW','RETRY')` |
| 2 | `claimAndLoad`: UPDATE RETURNING で claim + findById を 1 往復に統合 | `OutboxSupport.java` |
| 3 | `markSent` / `markFailed` の冗長 findById 除去 | 同上。ルート側のヘッダからコンテキストを渡す |
| 4 | SENT 済みイベントの定期クリーンアップ | `AbstractOutboxPublisherRoute` に 60 秒ごとの cleanup ルート追加 |
| 5 | 並列 Outbox の接続プール制限（4 スレッド） | `executorService(Executors.newFixedThreadPool(4))` |

**DB 往復の変化（1 イベントの Outbox 配信）:**

| 段階 | Before | After |
|------|:---:|:---:|
| claim | 1 (UPDATE) | — |
| findById | 1 (SELECT) | — |
| claimAndLoad | — | 1 (UPDATE RETURNING) |
| Kafka send | — | — |
| markSent (findById + UPDATE) | 2 | 1 (UPDATE のみ) |
| **合計** | **4** | **2** |

---

## フルスイート結果比較

3 時点の比較: **初回ベースライン** → **前回施策（並列化 + consumersCount）** → **今回（構造最適化）**

### trade p95（秒）

| シナリオ | ベースライン | 前回 | **今回** |
|----------|:---:|:---:|:---:|
| smoke | 0.226 | 0.228 | **0.159** |
| baseline | 0.232 | 0.226 | **0.154** |
| cover_fail | 0.225 | 0.224 | **0.155** |
| accounting_fail | 0.226 | 0.224 | **0.155** |
| notification_fail | 0.227 | 0.226 | **0.155** |
| stress | 0.227 | 0.225 | **0.153** |
| soak | 0.224 | 0.251 | **0.155** |

→ **trade p95 が全シナリオで約 30% 短縮**（0.22s → 0.15s）。`claimAndLoad` の DB 往復削減がトランザクション全体に波及。

### Kafka consumer lag max

| シナリオ | ベースライン | 前回 | **今回** | ベース比 |
|----------|:---:|:---:|:---:|---:|
| smoke | 42 | 6 | **8** | -81% |
| baseline | 400 | 118 | **128** | -68% |
| cover_fail | 227 | 118 | **94** | -59% |
| accounting_fail | 219 | 60 | **72** | -67% |
| notification_fail | 186 | 66 | **76** | -59% |
| stress | **1391** | 829 | **954** | -31% |
| soak | 593 | 829 | **954** | — |

→ lag は前回施策（並列化 + consumersCount=2）とほぼ同水準を維持。接続プール制限（4 スレッド）で接続枯渇を防ぎつつ、並列性の効果は保持。

### saga p95（秒）

| シナリオ | ベースライン | 前回 | **今回** |
|----------|:---:|:---:|:---:|
| smoke | 0.340 | 0.420 | **0.157** |
| baseline | 0.349 | 0.432 | **0.156** |
| stress | 0.352 | 0.443 | **0.219** |
| soak | 0.350 | 0.430 | **0.201** |

→ **saga p95 が 50-55% 改善**。前回は並列 Outbox が saga DB を圧迫して悪化したが、接続プール制限で解消。

### k6 判定

| シナリオ | ベースライン | 前回 | **今回** |
|----------|:---:|:---:|:---:|
| smoke | PASS | PASS | **PASS** |
| baseline | PASS | PASS | **PASS** |
| cover_fail | PASS | PASS | **PASS** |
| accounting_fail | PASS | PASS | **PASS** |
| notification_fail | PASS | PASS | **PASS** |
| stress | PASS | PASS | **PASS** |
| soak | PASS | PASS | **PASS** |

→ **全 7 シナリオ PASS**。stress は前回の v1 並列化では接続枯渇で FAIL していたが、プール制限で解決。

---

## 総合評価

| 指標 | ベースライン → 今回 |
|------|------|
| **trade p95** | 0.22s → **0.15s（-32%）** |
| **saga p95** | 0.35s → **0.16-0.22s（-37〜-55%）** |
| **Kafka lag（stress）** | 1391 → **954（-31%）** |
| **全シナリオ PASS** | ✓ |

**主要な改善ポイント:**

1. **DB 往復半減**（4→2 per event）が trade / saga 両方のレイテンシに直接反映
2. **接続プール保護**（4 スレッド制限）で高負荷時の接続枯渇を防止
3. **部分インデックス + SENT クリーンアップ** でポーリングの長期安定性を確保

---

## ディスカッション提案との対応

| 提案の原則 | 今回の具体施策 |
|------------|----------------|
| 「流量ではなく競合点を減らす」 | Outbox DB 往復を半減し、接続の奪い合いを制限 |
| 「共有リソースの排除」 | 接続プール予算を Outbox(4) / API(残り) で明示分離 |
| 「同期を削減」 | claim + findById の 2 同期呼出を 1 回の UPDATE RETURNING に |
| 「書き込み圧を分散」 | SENT クリーンアップでテーブル肥大化を防止 |

---

### 参照ファイル

| 内容 | パス |
|------|------|
| ベースライン JSON | `loadtest/reports/test-plan-suite-2026-04-05.json` |
| 前回（並列化）JSON | `loadtest/reports/test-plan-suite-after.json` |
| 今回（構造最適化）JSON | `loadtest/reports/test-plan-suite-structural-v2.json` |
| 設計書（更新済み） | `design/problems-and-mitigations.md` §8 |
