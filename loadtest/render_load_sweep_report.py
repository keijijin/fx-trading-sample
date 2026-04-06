#!/usr/bin/env python3
"""load-sweep-report.json を Markdown に整形する。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="loadtest/load-sweep-report.json")
    parser.add_argument("--output", default="loadtest/load-sweep-report.md")
    args = parser.parse_args()

    data = json.loads(Path(args.input).read_text())
    lines = [
        "# Baseline RPS スイープ",
        "",
        f"- namespace: `{data.get('namespace')}`",
        f"- replicas: **{data.get('replicas')}**",
        f"- duration / step: `{data.get('duration')}`",
        "",
        "| RATE (req/s) | k6 PASS | p95 API (s) | http_fail (rate) | saga_completed | trace_timeout | fx_core_tx_p95 (Prom) | kafka_lag_max |",
        "|---|---:|---:|---|---|---|---|---|",
    ]

    for rate in sorted(data.get("rates", {}).keys(), key=lambda x: int(x)):
        block = data["rates"][rate]
        k6 = block.get("k6", {})
        pr = block.get("prometheus_after_window", {})
        lines.append(
            f"| {rate} | {k6.get('passed')} | {k6.get('p95_api_s', 0):.4f} | {k6.get('http_req_failed_rate')} | "
            f"{k6.get('saga_completed')} | {k6.get('trace_timed_out')} | {pr.get('fx_core_tx_p95_s')} | {pr.get('kafka_lag_max')} |"
        )

    lines.extend(["", "Prometheus は各ステップ実行後の瞬間クエリのため、ピークと一致しない場合があります。"])
    Path(args.output).write_text("\n".join(lines))
    print(args.output)


if __name__ == "__main__":
    main()
