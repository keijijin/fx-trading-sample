#!/usr/bin/env python3
"""
OpenShift 上のアプリ Deployment を指定レプリカにスケールし、k6-spike-scale-test.js で
warm → spike → cool を実行。複数レプリカ数を順に試してスパイク時のスループット確保を比較する。

例:
  python3 loadtest/run_spike_scale_test.py \\
    --namespace fx-trading-sample \\
    --compare-replicas 1 3 \\
    --output loadtest/spike-scale-report.json

前提: oc, podman, クラスタに fx-core-service / fx-prometheus Route。

相別メトリクス: k6 実行ブロックの開始時刻から warm/spike/cool 秒で切った窓に対し
`query_range`（`--prometheus-range-step`）を実行する。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path


DEPLOYMENTS = [
    "fx-core-service",
    "trade-saga-service",
    "cover-service",
    "risk-service",
    "accounting-service",
    "settlement-service",
    "notification-service",
    "compliance-service",
]


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True)


def rollout_wait(namespace: str, name: str) -> None:
    subprocess.check_call(
        ["oc", "-n", namespace, "rollout", "status", f"deployment/{name}", "--timeout=300s"]
    )


def scale_all(namespace: str, replicas: int) -> None:
    subprocess.check_call(
        ["oc", "-n", namespace, "scale", "deployment", *DEPLOYMENTS, f"--replicas={replicas}"]
    )
    for deployment in DEPLOYMENTS:
        rollout_wait(namespace, deployment)


def prom_query(prometheus_host: str, expr: str) -> float | None:
    encoded = urllib.parse.quote(expr, safe="")
    url = f"http://{prometheus_host}/api/v1/query?query={encoded}"
    with urllib.request.urlopen(url, timeout=60) as response:
        payload = json.loads(response.read().decode())
    result = payload["data"]["result"]
    if not result:
        return None
    raw = result[0]["value"][1]
    return None if raw == "NaN" else float(raw)


def prom_query_range(
    prometheus_host: str,
    expr: str,
    start: float,
    end: float,
    step: str,
    merge_same_timestamp: str = "sum",
) -> list[tuple[float, float]]:
    """
    /api/v1/query_range の matrix を取得。同一タイムスタンプに複数系列がある場合は
    merge_same_timestamp で集約（sum: カウンタ系の重複、max: lag 等の重複）。
    """
    params = urllib.parse.urlencode({"query": expr, "start": str(start), "end": str(end), "step": step})
    url = f"http://{prometheus_host}/api/v1/query_range?{params}"
    with urllib.request.urlopen(url, timeout=120) as response:
        payload = json.loads(response.read().decode())
    result = payload["data"]["result"]
    if not result:
        return []
    by_ts: dict[float, list[float]] = {}
    for series in result:
        for ts_raw, val_raw in series.get("values") or []:
            ts = float(ts_raw)
            if val_raw == "NaN":
                continue
            by_ts.setdefault(ts, []).append(float(val_raw))
    merged: list[tuple[float, float]] = []
    for ts in sorted(by_ts.keys()):
        vals = by_ts[ts]
        if merge_same_timestamp == "max":
            merged.append((ts, max(vals)))
        else:
            merged.append((ts, sum(vals)))
    return merged


def series_stats(points: list[tuple[float, float]]) -> dict:
    vals = [v for _, v in points]
    if not vals:
        return {"min": None, "max": None, "avg": None, "samples": 0}
    return {
        "min": min(vals),
        "max": max(vals),
        "avg": sum(vals) / len(vals),
        "samples": len(vals),
    }


def collect_phase_range_metrics(
    prometheus_host: str,
    t_start: float,
    warm_sec: int,
    spike_sec: int,
    cool_sec: int,
    step: str,
) -> dict:
    """
    k6 の warm / spike / cool と同じ秒数の時間窓で Outbox・Kafka lag・Hikari 活性を query_range する。
    """
    total_sec = warm_sec + spike_sec + cool_sec
    boundaries = [
        ("warm", t_start, t_start + warm_sec),
        ("spike", t_start + warm_sec, t_start + warm_sec + spike_sec),
        ("cool", t_start + warm_sec + spike_sec, t_start + total_sec),
    ]
    queries: dict[str, tuple[str, str]] = {
        "outbox_backlog_sum": ("sum(fx_outbox_backlog_total)", "sum"),
        "kafka_lag_max": ("max(fx_kafka_consumer_group_lag)", "max"),
        "hikaricp_active_sum": ("sum(hikaricp_connections_active)", "sum"),
    }
    phases: dict[str, dict] = {}
    for name, p_start, p_end in boundaries:
        if p_end <= p_start:
            phases[name] = {"error": "invalid_phase_window"}
            continue
        phase_block: dict[str, dict | str] = {"window_unix": {"start": p_start, "end": p_end}}
        for qname, (expr, merge) in queries.items():
            try:
                pts = prom_query_range(
                    prometheus_host, expr, p_start, p_end, step, merge_same_timestamp=merge
                )
                phase_block[qname] = series_stats(pts)
            except Exception as exc:  # noqa: BLE001 — 試験レポート用に理由を残す
                phase_block[qname] = {"error": str(exc)}
        phases[name] = phase_block
    return {
        "aligned_with_k6_phases_sec": {"warm": warm_sec, "spike": spike_sec, "cool": cool_sec},
        "step": step,
        "test_start_unix": t_start,
        "phases": phases,
    }


def collect_snapshot(prometheus_host: str, window: str) -> dict:
    return {
        "http_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{application="fx-core-service",uri="/api/trades",method="POST"}}[{window}])))',
        ),
        "http_req_rate": prom_query(
            prometheus_host,
            f'sum(rate(http_server_requests_seconds_count{{application="fx-core-service",uri="/api/trades",method="POST",status="201"}}[{window}]))',
        ),
        "kafka_lag_max": prom_query(
            prometheus_host,
            f"max(max_over_time(fx_kafka_consumer_group_lag[{window}]))",
        ),
        "outbox_backlog_max": prom_query(
            prometheus_host,
            f"max_over_time(sum(fx_outbox_backlog_total)[{window}:15s])",
        ),
        "fx_core_tx_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_core_transaction_duration_seconds_bucket[{window}])))',
        ),
    }


def run_k6(script: str, workdir: Path, env: dict[str, str]) -> tuple[int, dict]:
    summary_dir = workdir / ".tmp"
    summary_dir.mkdir(parents=True, exist_ok=True)
    summary_path = summary_dir / f"k6-spike-summary-{int(time.time() * 1000)}.json"

    cmd = [
        "podman",
        "run",
        "--rm",
        "-v",
        f"{workdir.resolve()}:/scripts:Z",
    ]
    for key, value in env.items():
        cmd.extend(["-e", f"{key}={value}"])
    cmd.extend(
        [
            "docker.io/grafana/k6:latest",
            "run",
            f"--summary-export=/scripts/.tmp/{summary_path.name}",
            f"/scripts/{script}",
        ]
    )

    try:
        result = subprocess.run(cmd, text=True)
        if result.returncode not in (0, 99):
            raise subprocess.CalledProcessError(result.returncode, cmd)
        data = json.loads(summary_path.read_text())
        return result.returncode, data
    finally:
        summary_path.unlink(missing_ok=True)


def summarise_k6_metrics(data: dict) -> dict:
    metrics = data.get("metrics", {})
    out: dict = {"raw_metrics_keys": list(metrics.keys())}
    for key in ("http_reqs", "http_req_failed", "iteration_duration", "iterations"):
        if key in metrics:
            out[key] = metrics[key].get("values") or metrics[key]
    if "http_req_duration" in metrics:
        out["http_req_duration"] = metrics["http_req_duration"].get("values", {})
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="Spike load test with optional replica comparison.")
    parser.add_argument("--namespace", default="fx-trading-sample")
    parser.add_argument(
        "--compare-replicas",
        type=int,
        nargs="+",
        default=[1, 3, 5],
        help="Run the same k6 spike test for each replica count (default: 1 3 5)",
    )
    parser.add_argument("--script", default="k6-spike-scale-test.js")
    parser.add_argument("--output", default="loadtest/spike-scale-report.json")
    parser.add_argument("--sleep-after-scale", type=int, default=15, help="Base seconds to wait after oc scale")
    parser.add_argument(
        "--sleep-extra-per-replica",
        type=int,
        default=5,
        help="Add (replicas-1)*N seconds so Kafka/consumers stabilize after scale-out",
    )
    parser.add_argument("--prometheus-window", default="3m", help="Prometheus range for post-run snapshot")
    parser.add_argument(
        "--prometheus-range-step",
        default="15s",
        help="query_range の step（相別 Outbox / Kafka / Hikari に使用）",
    )
    parser.add_argument(
        "--disable-phase-prometheus",
        action="store_true",
        help="warm/spike/cool ごとの query_range をスキップ（Prometheus 不通時など）",
    )
    parser.add_argument("--warm-sec", type=int, default=120)
    parser.add_argument("--spike-sec", type=int, default=60)
    parser.add_argument("--cool-sec", type=int, default=120)
    parser.add_argument("--base-rate", type=int, default=20, help="SPIKE_BASE_RATE (req/s)")
    parser.add_argument("--peak-rate", type=int, default=100, help="SPIKE_PEAK_RATE (req/s)")
    parser.add_argument("--trace-sample-rate", type=float, default=0.0, help="0 = POST only")
    parser.add_argument(
        "--spike-p95-ms",
        type=int,
        default=6000,
        help="k6 SPIKE_P95_MS (多レプリカ時の尾部対策。厳しめにする場合は 3000 など)",
    )
    parser.add_argument("--spike-p99-ms", type=int, default=12000)
    parser.add_argument("--spike-max-vus", type=int, default=3500)
    parser.add_argument("--spike-preallocated-vus", type=int, default=800)
    args = parser.parse_args()

    workdir = Path(__file__).resolve().parent
    core_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-core-service", "-o", "jsonpath={.spec.host}"]
    ).strip()
    prometheus_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-prometheus", "-o", "jsonpath={.spec.host}"]
    ).strip()

    warm_sec = args.warm_sec
    spike_sec = args.spike_sec
    cool_sec = args.cool_sec
    total_sec = warm_sec + spike_sec + cool_sec

    report: dict = {
        "namespace": args.namespace,
        "fx_core_route_host": core_host,
        "prometheus_host": prometheus_host,
        "phases_sec": {"warm": warm_sec, "spike": spike_sec, "cool": cool_sec, "total": total_sec},
        "k6_rates": {"base_rps": args.base_rate, "peak_rps": args.peak_rate},
        "k6_thresholds_ms": {"spike_p95": args.spike_p95_ms, "spike_p99": args.spike_p99_ms},
        "runs": {},
    }

    for replicas in args.compare_replicas:
        scale_all(args.namespace, replicas)
        settle_s = args.sleep_after_scale + max(0, replicas - 1) * args.sleep_extra_per_replica
        time.sleep(settle_s)

        env = {
            "FX_CORE_BASE_URL": f"http://{core_host}",
            "WARM_SEC": str(warm_sec),
            "SPIKE_SEC": str(spike_sec),
            "COOL_SEC": str(cool_sec),
            "SPIKE_BASE_RATE": str(args.base_rate),
            "SPIKE_PEAK_RATE": str(args.peak_rate),
            "TRACE_SAMPLE_RATE": str(args.trace_sample_rate),
            "ACCOUNT_PREFIX": f"ACC-SPK-r{replicas}",
            "SPIKE_P95_MS": str(args.spike_p95_ms),
            "SPIKE_P99_MS": str(args.spike_p99_ms),
            "SPIKE_MAX_VUS": str(args.spike_max_vus),
            "SPIKE_PREALLOCATED_VUS": str(args.spike_preallocated_vus),
        }

        t_start = time.time()
        exit_code, k6_json = run_k6(args.script, workdir, env)
        t_end = time.time()
        prom_snap = collect_snapshot(prometheus_host, args.prometheus_window)
        phase_prom: dict | None = None
        if not args.disable_phase_prometheus:
            phase_prom = collect_phase_range_metrics(
                prometheus_host,
                t_start,
                warm_sec,
                spike_sec,
                cool_sec,
                args.prometheus_range_step,
            )
            phase_prom["test_end_unix"] = t_end

        report["runs"][f"replicas_{replicas}"] = {
            "replicas": replicas,
            "settle_sleep_sec": settle_s,
            "k6_exit_code": exit_code,
            "k6_thresholds_passed": exit_code == 0,
            "k6_summary_metrics": summarise_k6_metrics(k6_json),
            "prometheus_after_test": prom_snap,
            "prometheus_by_phase": phase_prom,
            "timestamps_unix": {"k6_block_start": t_start, "k6_block_end": t_end},
        }

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(out_path)

    md_path = out_path.with_suffix(".md")
    md_lines = [
        "# スパイク × スケールアウト試験レポート",
        "",
        f"- namespace: `{args.namespace}`",
        f"- 相: warm {warm_sec}s → spike {spike_sec}s → cool {cool_sec}s（`k6-spike-scale-test.js`）",
        f"- 到着率: 平常 {args.base_rate}/s → スパイク {args.peak_rate}/s",
        "",
        "## 結果サマリー",
        "",
        "| replicas | k6 exit | 閾値 | HTTP p95(s)* | POST 201 rate/s* | Kafka lag max* | Outbox backlog max* |",
        "|---|---:|:---:|---:|---:|---:|---:|",
    ]
    for key, block in report["runs"].items():
        pr = block.get("prometheus_after_test") or {}
        md_lines.append(
            f"| {block['replicas']} | {block['k6_exit_code']} | "
            f"{'PASS' if block['k6_thresholds_passed'] else 'FAIL'} | "
            f"{pr.get('http_p95_s')!s} | {pr.get('http_req_rate')!s} | "
            f"{pr.get('kafka_lag_max')!s} | {pr.get('outbox_backlog_max')!s} |"
        )
    md_lines.extend(
        [
            "",
            f"* Prometheus は試験直後の `{args.prometheus_window}` ウィンドウのスナップショットです。",
            "",
        ]
    )
    if not args.disable_phase_prometheus:
        md_lines.extend(
            [
                "## 相別 Prometheus（query_range）",
                "",
                "試験ブロック開始時刻を `t0` とし、k6 の warm / spike / cool 秒数と**同じ幅**の Unix 窓で "
                "`sum(fx_outbox_backlog_total)` / `max(fx_kafka_consumer_group_lag)` / `sum(hikaricp_connections_active)` を集計（min/max/avg）。",
                "",
                "| replicas | 相 | Outbox avg | Outbox max | Kafka lag avg | Kafka lag max | Hikari active avg | Hikari max |",
                "|---|:---:|---:|---:|---:|---:|---:|---:|",
            ]
        )
        for key, block in report["runs"].items():
            pp = block.get("prometheus_by_phase") or {}
            phases_data = pp.get("phases") or {}
            rep = block["replicas"]
            for pname in ("warm", "spike", "cool"):
                ph = phases_data.get(pname) or {}
                o = ph.get("outbox_backlog_sum") or {}
                k = ph.get("kafka_lag_max") or {}
                h = ph.get("hikaricp_active_sum") or {}
                md_lines.append(
                    f"| {rep} | {pname} | {o.get('avg')!s} | {o.get('max')!s} | "
                    f"{k.get('avg')!s} | {k.get('max')!s} | {h.get('avg')!s} | {h.get('max')!s} |"
                )
        md_lines.append("")
    md_lines.extend(
        [
            "## 解釈メモ",
            "",
            "- k6 exit **99** は閾値違反（サマリーの thresholds）を示すことがあります。",
            "- replicas を増やしても HTTP p95 や lag が改善しない場合、**単一約定コアや DB** が限界の可能性があります。",
            "- **spike 相**で Outbox max や Kafka lag max が突出する場合は、Saga 以外のボトルネック（配信遅延・コンシューマ）を疑う。",
            "",
        ]
    )
    md_path.write_text("\n".join(md_lines) + "\n", encoding="utf-8")
    print(md_path)

    try:
        scale_all(args.namespace, 1)
    except Exception:
        print("WARNING: scale back to 1 replica failed (report already saved)")


if __name__ == "__main__":
    main()
