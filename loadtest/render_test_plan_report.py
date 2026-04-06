#!/usr/bin/env python3

import argparse
import json
from pathlib import Path


def fmt(value, digits=4):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}"


def status_label(passed: bool) -> str:
    return "PASS" if passed else "FAIL"


def main() -> None:
    parser = argparse.ArgumentParser(description="Render the test-plan suite JSON into Markdown.")
    parser.add_argument("input_json")
    parser.add_argument(
        "-o",
        "--output",
        default="loadtest/test-plan-suite-report.md",
    )
    parser.add_argument(
        "--title",
        default="Test Plan Validation Report",
    )
    args = parser.parse_args()

    data = json.loads(Path(args.input_json).read_text())
    scenarios = data["scenarios"]

    lines = [
        f"# {args.title}",
        "",
        "## 実行条件",
        "",
        f"- namespace: `{data['namespace']}`",
        f"- replicas: `{data['replicas']}`",
        f"- script: `{data['script']}`",
        "- 備考: 耐久は計画書の2時間版ではなく、短縮バリデーション版を実行",
        "",
        "## シナリオ結果",
        "",
        "| シナリオ | 判定 | 平均応答(s) | p95(s) | p99(s) | req/s | 受理数 | Trace検証数 | Trace timeout | Saga完了 | Saga補償 | Saga失敗 | 業務失敗率 | E2E p95(s) |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]

    for name, result in scenarios.items():
        k6 = result["k6"]
        lines.append(
            f"| {result['description']} (`{name}`) | {status_label(result['passed'])} | "
            f"{fmt(k6['average_s'])} | {fmt(k6['p95_s'])} | {fmt(k6['p99_s'])} | "
            f"{fmt(k6['requests_per_sec'])} | {fmt(k6['trade_accepted'], 0)} | "
            f"{fmt(k6['trace_sampled'], 0)} | {fmt(k6['trace_timed_out'], 0)} | "
            f"{fmt(k6['saga_completed'], 0)} | {fmt(k6['saga_compensated'], 0)} | "
            f"{fmt(k6['saga_failed'], 0)} | {fmt(k6['business_failure_rate'])} | "
            f"{fmt(k6['trade_e2e_p95_s'])} |"
        )

    lines.extend(
        [
            "",
            "## 観測メモ",
            "",
            "| シナリオ | HTTP p95(s) | Saga p95(s) | Outbox backlog max | Hikari active max | Hikari pending max | Kafka lag max | FX Core Tx p95(s) | Lock wait p95(s) |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )

    for name, result in scenarios.items():
        prom = result["prometheus"]
        lines.append(
            f"| {result['description']} (`{name}`) | {fmt(prom['http_p95_s'])} | {fmt(prom['saga_p95_s'])} | "
            f"{fmt(prom['outbox_backlog_max'])} | {fmt(prom['hikari_active_max'])} | "
            f"{fmt(prom['hikari_pending_max'])} | {fmt(prom['kafka_lag_max'])} | "
            f"{fmt(prom['fx_core_tx_p95_s'])} | {fmt(prom['fx_core_lock_wait_p95_s'])} |"
        )

    lines.extend(
        [
            "",
            "## 所見",
            "",
            "- `PASS/FAIL` は k6 threshold 判定です。",
            "- `業務失敗率` は sampled trace 検証対象だけを母数にした最終 Saga 状態の期待差分です。",
            "- `E2E p95` は sampled trace だけで計測した、`POST /api/trades` 受理から `trace` が終端状態になるまでの時間です。",
        ]
    )

    Path(args.output).write_text("\n".join(lines) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
