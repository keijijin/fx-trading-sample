#!/usr/bin/env python3
"""
段階 RPS スイープ（baseline）: 各レートで短時間 k6 を実行し、Prometheus 指標を取得して JSON に保存する。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True)


def prom_query(host: str, expr: str, timeout: int = 60) -> float | None:
    encoded = urllib.parse.quote(expr, safe="")
    url = f"http://{host}/api/v1/query?query={encoded}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            payload = json.loads(response.read().decode())
    except OSError:
        return None
    result = payload.get("data", {}).get("result", [])
    if not result:
        return None
    raw = result[0]["value"][1]
    return None if raw == "NaN" else float(raw)


def run_k6(workdir: Path, envs: dict[str, str]) -> tuple[int, dict]:
    summary_dir = workdir / ".tmp"
    summary_dir.mkdir(parents=True, exist_ok=True)
    summary_path = summary_dir / f"k6-sweep-{int(time.time() * 1000)}.json"
    cmd = [
        "podman",
        "run",
        "--rm",
        "-v",
        f"{workdir.resolve()}:/scripts:Z",
    ]
    for key, value in envs.items():
        cmd.extend(["-e", f"{key}={value}"])
    cmd.extend(
        [
            "docker.io/grafana/k6:latest",
            "run",
            f"--summary-export=/scripts/.tmp/{summary_path.name}",
            "/scripts/k6-test-plan-suite.js",
        ]
    )
    try:
        result = subprocess.run(cmd, text=True)
        data = json.loads(summary_path.read_text())
        data["_exit_code"] = result.returncode
        return result.returncode, data
    finally:
        summary_path.unlink(missing_ok=True)


def metric_value(metrics: dict, name: str, field: str):
    payload = metrics.get(name, {})
    if field in payload:
        return payload[field]
    return payload.get("values", {}).get(field)


def summarise_k6(data: dict) -> dict:
    metrics = data.get("metrics", {})
    return {
        "passed": data.get("_exit_code") == 0,
        "exit_code": data.get("_exit_code"),
        "avg_api_s": (metric_value(metrics, "trade_api_latency", "avg") or 0) / 1000,
        "p95_api_s": (metric_value(metrics, "trade_api_latency", "p(95)") or 0) / 1000,
        "trade_accepted": metric_value(metrics, "trade_accepted", "count"),
        "trace_timed_out": metric_value(metrics, "trace_timed_out", "count"),
        "saga_completed": metric_value(metrics, "saga_completed", "count"),
        "http_req_failed_rate": metric_value(metrics, "http_req_failed", "rate"),
    }


def collect_prom(host: str, window: str = "90s") -> dict:
    return {
        "fx_core_http_post_p95_s": prom_query(
            host,
            f'histogram_quantile(0.95, sum by (le) (rate('
            f'http_server_requests_seconds_bucket{{application="fx-core-service",uri="/api/trades",method="POST"}}[{window}])))',
        ),
        "fx_core_tx_p95_s": prom_query(
            host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_core_transaction_duration_seconds_bucket[{window}])))',
        ),
        "kafka_lag_max": prom_query(host, f"max(max_over_time(fx_kafka_consumer_group_lag[{window}]))"),
        "hikari_pending_max": prom_query(
            host,
            f'max(max_over_time(hikaricp_connections_pending{{application="fx-core-service"}}[{window}]))',
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Baseline RPS sweep with Prometheus snapshots.")
    parser.add_argument("--namespace", default="fx-trading-sample")
    parser.add_argument("--replicas", type=int, default=3)
    parser.add_argument("--rates", default="15,30,50", help="Comma-separated RATE values for baseline")
    parser.add_argument("--duration", default="60s")
    parser.add_argument(
        "--output",
        default=str(Path(__file__).resolve().parent / "load-sweep-report.json"),
    )
    args = parser.parse_args()

    workdir = Path(__file__).resolve().parent
    core_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-core-service", "-o", "jsonpath={.spec.host}"]
    ).strip()
    try:
        prom_host = run(
            ["oc", "-n", args.namespace, "get", "route", "fx-prometheus", "-o", "jsonpath={.spec.host}"]
        ).strip()
    except subprocess.CalledProcessError:
        prom_host = ""

    deps = [
        "fx-core-service",
        "trade-saga-service",
        "cover-service",
        "risk-service",
        "accounting-service",
        "settlement-service",
        "notification-service",
        "compliance-service",
    ]
    subprocess.check_call(["oc", "-n", args.namespace, "scale", "deployment", *deps, f"--replicas={args.replicas}"])
    for d in deps:
        subprocess.check_call(
            ["oc", "-n", args.namespace, "rollout", "status", f"deployment/{d}", "--timeout=180s"]
        )
    time.sleep(10)

    rates = [int(x.strip()) for x in args.rates.split(",") if x.strip()]
    results = {
        "namespace": args.namespace,
        "replicas": args.replicas,
        "duration": args.duration,
        "rates": {},
    }

    base_env = {
        "FX_CORE_BASE_URL": f"http://{core_host}",
        "SCENARIO_MODE": "baseline",
        "ACCOUNT_COUNT": "1000",
        "ACCOUNT_PREFIX": "ACC-SWEEP",
        "CURRENCY_PAIR": "USD/JPY",
        "SIDE": "BUY",
        "ORDER_AMOUNT": "1.0",
        "REQUESTED_PRICE": "151.25",
        "DURATION": args.duration,
        "PREALLOCATED_VUS": "120",
        "MAX_VUS": "300",
        "BASELINE_FAILURE_PERCENT": "5",
        "TRACE_TIMEOUT_MS": "60000",
        "TRACE_SAMPLE_RATE": "0.05",
    }

    for rate in rates:
        env = {**base_env, "RATE": str(rate)}
        code, raw = run_k6(workdir, env)
        time.sleep(15)
        prom = collect_prom(prom_host) if prom_host else {}
        results["rates"][str(rate)] = {
            "k6": summarise_k6(raw),
            "prometheus_after_window": prom,
        }

    subprocess.check_call(["oc", "-n", args.namespace, "scale", "deployment", *deps, "--replicas=1"])
    for d in deps:
        subprocess.check_call(
            ["oc", "-n", args.namespace, "rollout", "status", f"deployment/{d}", "--timeout=180s"]
        )

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(results, indent=2))
    print(out)


if __name__ == "__main__":
    main()
