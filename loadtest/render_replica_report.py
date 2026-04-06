#!/usr/bin/env python3

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def fmt(value, digits: int = 4) -> str:
    if value is None:
      return "N/A"
    if isinstance(value, float):
      return f"{value:.{digits}f}"
    return str(value)


def fmt_bytes(value) -> str:
    if value is None:
        return "N/A"
    units = ["B", "KiB", "MiB", "GiB", "TiB"]
    size = float(value)
    unit = 0
    while size >= 1024 and unit < len(units) - 1:
        size /= 1024
        unit += 1
    return f"{size:.2f} {units[unit]}"


def status_summary(status_counts: dict) -> str:
    if not status_counts:
        return "-"
    ordered = sorted(status_counts.items(), key=lambda item: item[0])
    return ", ".join(f"{code}={count}" for code, count in ordered)


def diff_percent(base, compare) -> str:
    if base in (None, 0) or compare is None:
        return "N/A"
    return f"{((compare - base) / base) * 100:+.2f}%"


def build_markdown(report: dict, title: str) -> str:
    scenarios = sorted(report.values(), key=lambda item: item.get("replicas", 0))
    if not scenarios:
        return f"# {title}\n\nレポートにシナリオがありません。"

    baseline = scenarios[0]
    load_key = "hey" if "hey" in baseline else "k6"

    headers = ["指標"] + [f"{item.get('replicas', 'N/A')} replicas" for item in scenarios]

    def row(label: str, getter, formatter=fmt):
        values = [formatter(getter(item)) for item in scenarios]
        return [label] + values

    def build_table(rows):
        header = "| " + " | ".join(headers) + " |"
        separator = "|" + "|".join(["---"] + ["---:" for _ in scenarios]) + "|"
        lines = [header, separator]
        for cells in rows:
            lines.append("| " + " | ".join(cells) + " |")
        return lines

    hey_rows = [
        row("Average latency (s)", lambda item: item.get(load_key, {}).get("average_s")),
        row("Fastest (s)", lambda item: item.get(load_key, {}).get("fastest_s")),
        row("Slowest (s)", lambda item: item.get(load_key, {}).get("slowest_s")),
        row("Requests/sec", lambda item: item.get(load_key, {}).get("requests_per_sec")),
    ]

    prom_rows = [
        row("HTTP p95 (s)", lambda item: item.get("prometheus", {}).get("http_p95_s")),
        row("HTTP p99 (s)", lambda item: item.get("prometheus", {}).get("http_p99_s")),
        row("HTTP error rate (/s)", lambda item: item.get("prometheus", {}).get("http_error_rate")),
        row("trade_saga p95 (s)", lambda item: item.get("prometheus", {}).get("saga_p95_s")),
        row("trade_saga p99 (s)", lambda item: item.get("prometheus", {}).get("saga_p99_s")),
        row("Outbox backlog max", lambda item: item.get("prometheus", {}).get("outbox_backlog_max")),
        row("Hikari active max", lambda item: item.get("prometheus", {}).get("hikari_active_max")),
        row("Hikari pending max", lambda item: item.get("prometheus", {}).get("hikari_pending_max")),
        row("Hikari max", lambda item: item.get("prometheus", {}).get("hikari_max")),
        row("Kafka lag max", lambda item: item.get("prometheus", {}).get("kafka_lag_max")),
        row("Pod CPU sum (cores)", lambda item: item.get("prometheus", {}).get("pod_cpu_sum_cores")),
        row("Pod Memory sum", lambda item: item.get("prometheus", {}).get("pod_memory_sum_bytes"), fmt_bytes),
        row("Pod Restarts", lambda item: item.get("prometheus", {}).get("pod_restarts")),
    ]

    compared = (
        ", ".join(f"`{item.get('replicas', 'N/A')}` replicas" for item in scenarios[1:])
        if len(scenarios) > 1
        else "なし"
    )
    def account_label(item: dict) -> str:
        account_id = item.get("accountId")
        if account_id:
            return f"`{account_id}`"
        return f"`pool={item.get('accountPoolSize', 'N/A')}`"

    account_ids = ", ".join(
        account_label(item) for item in scenarios
    )

    lines = [
        f"# {title}",
        "",
        "## サマリー",
        "",
        f"- ベースラインは `{baseline.get('replicas', 'N/A')}` replica です。",
        f"- 比較対象: {compared}",
        f"- accountId: {account_ids}",
        "",
        f"## {load_key} 結果",
        "",
        *build_table(hey_rows),
        "",
        (
            "| Status counts | "
            + " | ".join(
                status_summary(item.get("hey", {}).get("status_counts", {}))
                for item in scenarios
            )
            + " |"
            if load_key == "hey"
            else (
                "| HTTP failed rate | "
                + " | ".join(fmt(item.get("k6", {}).get("http_req_failed_rate")) for item in scenarios)
                + " |"
            )
        ),
        "",
        "## Prometheus 指標",
        "",
        *build_table(prom_rows),
        "",
        "## 読み方",
        "",
        "- `Requests/sec` が上がり、`Average latency` や `p95/p99` が下がれば、レプリカ追加の効果が出ています。",
        "- `HTTP error rate` が増える場合は、スケールしても上流または下流の制約で捌き切れていません。",
        "- `trade_saga p95/p99` が悪化する場合は、非同期処理側や Kafka / DB 側の詰まりを疑います。",
        "- `Outbox backlog max` や `Kafka lag max` が増える場合は、送信側または consumer 側が追いついていません。",
        "- `Hikari active max` が `Hikari max` に近づく場合は、DB 接続プールがボトルネック候補です。",
        "- `Pod CPU sum` と `Pod Memory sum` は、性能改善の代償として使っているリソース量の比較に使えます。",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Render replica comparison JSON into a Markdown report."
    )
    parser.add_argument(
        "input",
        help="Input JSON report path",
    )
    parser.add_argument(
        "-o",
        "--output",
        help="Output Markdown path. Defaults to input filename with .md extension.",
    )
    parser.add_argument(
        "--title",
        default="Replica Comparison Report",
        help="Report title",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output) if args.output else input_path.with_suffix(".md")

    report = load(input_path)
    markdown = build_markdown(report, args.title)
    output_path.write_text(markdown)
    print(output_path)


if __name__ == "__main__":
    main()
