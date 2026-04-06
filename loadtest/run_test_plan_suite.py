#!/usr/bin/env python3

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

SCENARIOS = [
    {
        "name": "smoke",
        "description": "スモーク",
        "mode": "smoke",
        "duration": "90s",
        "window": "120s",
        "env": {
            "RATE": "5",
            "PREALLOCATED_VUS": "20",
            "MAX_VUS": "60",
            "TRACE_TIMEOUT_MS": "15000",
            "TRACE_SAMPLE_RATE": "1.0",
        },
    },
    {
        "name": "baseline",
        "description": "ベースライン",
        "mode": "baseline",
        "duration": "120s",
        "window": "150s",
        "env": {
            "RATE": "50",
            "PREALLOCATED_VUS": "120",
            "MAX_VUS": "300",
            "BASELINE_FAILURE_PERCENT": "0",
            "TRACE_TIMEOUT_MS": "60000",
            "TRACE_SAMPLE_RATE": "0.05",
        },
    },
    {
        "name": "cover_fail",
        "description": "Cover失敗補償",
        "mode": "cover_fail",
        "duration": "90s",
        "window": "120s",
        "env": {
            "RATE": "15",
            "PREALLOCATED_VUS": "60",
            "MAX_VUS": "150",
            "TRACE_TIMEOUT_MS": "45000",
            "TRACE_SAMPLE_RATE": "0.2",
        },
    },
    {
        "name": "accounting_fail",
        "description": "会計失敗補償",
        "mode": "accounting_fail",
        "duration": "90s",
        "window": "120s",
        "env": {
            "RATE": "15",
            "PREALLOCATED_VUS": "60",
            "MAX_VUS": "150",
            "TRACE_TIMEOUT_MS": "120000",
            "TRACE_SAMPLE_RATE": "0.2",
        },
    },
    {
        "name": "notification_fail",
        "description": "通知失敗非致命",
        "mode": "notification_fail",
        "duration": "90s",
        "window": "120s",
        "env": {
            "RATE": "15",
            "PREALLOCATED_VUS": "60",
            "MAX_VUS": "150",
            "TRACE_TIMEOUT_MS": "45000",
            "TRACE_SAMPLE_RATE": "0.2",
        },
    },
    {
        "name": "stress",
        "description": "ストレス",
        "mode": "stress",
        "duration": "180s",
        "window": "210s",
        "env": {
            "PREALLOCATED_VUS": "120",
            "MAX_VUS": "400",
            "STRESS_START_RATE": "20",
            "STRESS_TARGET_ONE": "50",
            "STRESS_TARGET_TWO": "100",
            "STRESS_TARGET_THREE": "150",
            "STRESS_STAGE_ONE_DURATION": "1m",
            "STRESS_STAGE_TWO_DURATION": "1m",
            "STRESS_STAGE_THREE_DURATION": "1m",
            "TRACE_TIMEOUT_MS": "60000",
            "TRACE_SAMPLE_RATE": "0.02",
        },
    },
    {
        "name": "soak",
        "description": "耐久(短縮版)",
        "mode": "soak",
        "duration": "180s",
        "window": "210s",
        "env": {
            "RATE": "20",
            "PREALLOCATED_VUS": "80",
            "MAX_VUS": "220",
            "TRACE_TIMEOUT_MS": "60000",
            "TRACE_SAMPLE_RATE": "0.02",
        },
    },
]


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True)


def rollout_wait(namespace: str, name: str) -> None:
    subprocess.check_call(
        ["oc", "-n", namespace, "rollout", "status", f"deployment/{name}", "--timeout=180s"]
    )


def scale_all(namespace: str, replicas: int) -> None:
    subprocess.check_call(
        ["oc", "-n", namespace, "scale", "deployment", *DEPLOYMENTS, f"--replicas={replicas}"]
    )
    for deployment in DEPLOYMENTS:
        rollout_wait(namespace, deployment)


def prom_query(prometheus_host: str, expr: str):
    encoded = urllib.parse.quote(expr, safe="")
    url = f"http://{prometheus_host}/api/v1/query?query={encoded}"
    with urllib.request.urlopen(url, timeout=60) as response:
        payload = json.loads(response.read().decode())
    result = payload["data"]["result"]
    if not result:
        return None
    raw = result[0]["value"][1]
    return None if raw == "NaN" else float(raw)


def wait_for_kafka_lag_to_settle(
    prometheus_host: str, threshold: float, timeout_sec: int, poll_sec: int = 5
) -> dict:
    """
    前シナリオの lag を次シナリオへ持ち込まないよう、lag が threshold 以下になるまで待つ。
    timeout 時はその時点の lag を返して先に進む。
    """
    deadline = time.time() + timeout_sec
    last_value = None
    while time.time() < deadline:
        last_value = prom_query(prometheus_host, "max(fx_kafka_consumer_group_lag) or on() vector(0)")
        if last_value is not None and last_value <= threshold:
            return {"settled": True, "last_lag": last_value}
        time.sleep(poll_sec)
    return {"settled": False, "last_lag": last_value}


def metric_value(metrics: dict, metric: str, field: str):
    payload = metrics.get(metric, {})
    if field in payload:
        return payload[field]
    return payload.get("values", {}).get(field)


def run_k6(script: str, workdir: Path, envs: dict[str, str]) -> tuple[int, dict]:
    summary_dir = workdir / ".tmp"
    summary_dir.mkdir(parents=True, exist_ok=True)
    summary_path = summary_dir / f"k6-summary-{int(time.time() * 1000)}.json"

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
            f"/scripts/{script}",
        ]
    )

    try:
        result = subprocess.run(cmd, text=True)
        if result.returncode not in (0, 99):
            raise subprocess.CalledProcessError(result.returncode, cmd)
        return result.returncode, json.loads(summary_path.read_text())
    finally:
        summary_path.unlink(missing_ok=True)


def summarise_k6(data: dict) -> dict:
    metrics = data.get("metrics", {})
    return {
        "average_s": (
            metric_value(metrics, "trade_api_latency", "avg") / 1000
            if metric_value(metrics, "trade_api_latency", "avg") is not None
            else None
        ),
        "p95_s": (
            metric_value(metrics, "trade_api_latency", "p(95)") / 1000
            if metric_value(metrics, "trade_api_latency", "p(95)") is not None
            else None
        ),
        "p99_s": (
            metric_value(metrics, "trade_api_latency", "p(99)") / 1000
            if metric_value(metrics, "trade_api_latency", "p(99)") is not None
            else None
        ),
        "slowest_s": (
            metric_value(metrics, "trade_api_latency", "max") / 1000
            if metric_value(metrics, "trade_api_latency", "max") is not None
            else None
        ),
        "requests_per_sec": metric_value(metrics, "trade_accepted", "rate"),
        "http_req_failed_rate": metric_value(metrics, "http_req_failed", "rate"),
        "trade_accepted": metric_value(metrics, "trade_accepted", "count"),
        "trace_sampled": metric_value(metrics, "trace_sampled", "count"),
        "trace_timed_out": metric_value(metrics, "trace_timed_out", "count"),
        "saga_completed": metric_value(metrics, "saga_completed", "count"),
        "saga_compensated": metric_value(metrics, "saga_compensated", "count"),
        "saga_failed": metric_value(metrics, "saga_failed", "count"),
        "business_failure_rate": metric_value(metrics, "business_failure_rate", "rate"),
        "trade_e2e_p95_s": (
            metric_value(metrics, "trade_e2e_latency", "p(95)") / 1000
            if metric_value(metrics, "trade_e2e_latency", "p(95)") is not None
            else None
        ),
        "trade_e2e_p99_s": (
            metric_value(metrics, "trade_e2e_latency", "p(99)") / 1000
            if metric_value(metrics, "trade_e2e_latency", "p(99)") is not None
            else None
        ),
    }


def collect_metrics(namespace: str, prometheus_host: str, window: str) -> dict:
    return {
        "http_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{application="fx-core-service",uri="/api/trades",method="POST"}}[{window}])))',
        ),
        "http_p99_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{application="fx-core-service",uri="/api/trades",method="POST"}}[{window}])))',
        ),
        "saga_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_trade_saga_duration_seconds_bucket[{window}])))',
        ),
        "outbox_backlog_max": prom_query(
            prometheus_host,
            f"max_over_time(((sum(fx_outbox_backlog) or on() vector(0)) or on() vector(0))[{window}:15s])",
        ),
        "hikari_active_max": prom_query(
            prometheus_host,
            f'max(max_over_time(hikaricp_connections_active{{application="fx-core-service"}}[{window}]))',
        ),
        "hikari_pending_max": prom_query(
            prometheus_host,
            f'max(max_over_time(hikaricp_connections_pending{{application="fx-core-service"}}[{window}]))',
        ),
        "kafka_lag_max": prom_query(
            prometheus_host,
            f"max(max_over_time(fx_kafka_consumer_group_lag[{window}]))",
        ),
        "fx_core_tx_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_core_transaction_duration_seconds_bucket[{window}])))',
        ),
        "fx_core_lock_wait_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_core_lock_wait_seconds_bucket[{window}])))',
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the test-plan aligned k6 suite and emit a JSON report.")
    parser.add_argument("--namespace", default="fx-trading-sample")
    parser.add_argument("--replicas", type=int, default=1)
    parser.add_argument("--script", default="k6-test-plan-suite.js")
    parser.add_argument(
        "--kafka-lag-settle-threshold",
        type=float,
        default=100.0,
        help="次シナリオ開始前に max(fx_kafka_consumer_group_lag) がこの値以下になるまで待つ",
    )
    parser.add_argument(
        "--kafka-lag-settle-timeout-sec",
        type=int,
        default=180,
        help="シナリオ間の lag settle 待ちのタイムアウト秒",
    )
    parser.add_argument(
        "--output",
        default="loadtest/test-plan-suite-report.json",
    )
    args = parser.parse_args()

    workdir = Path(__file__).resolve().parent
    core_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-core-service", "-o", "jsonpath={.spec.host}"]
    ).strip()
    prometheus_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-prometheus", "-o", "jsonpath={.spec.host}"]
    ).strip()

    scale_all(args.namespace, args.replicas)
    time.sleep(10)

    results = {
        "namespace": args.namespace,
        "replicas": args.replicas,
        "script": args.script,
        "generated_at_epoch": time.time(),
        "scenarios": {},
    }

    for idx, scenario in enumerate(SCENARIOS):
        env = {
            "FX_CORE_BASE_URL": f"http://{core_host}",
            "SCENARIO_MODE": scenario["mode"],
            "ACCOUNT_COUNT": "1000",
            "ACCOUNT_PREFIX": f"ACC-TP-{scenario['name'].upper()}",
            "CURRENCY_PAIR": "USD/JPY",
            "SIDE": "BUY",
            "ORDER_AMOUNT": "1.0",
            "REQUESTED_PRICE": "151.25",
            "DURATION": scenario["duration"],
        }
        env.update(scenario["env"])

        exit_code, k6_result = run_k6(args.script, workdir, env)
        time.sleep(10)

        results["scenarios"][scenario["name"]] = {
            "description": scenario["description"],
            "mode": scenario["mode"],
            "duration": scenario["duration"],
            "window": scenario["window"],
            "passed": exit_code == 0,
            "k6": summarise_k6(k6_result),
            "prometheus": collect_metrics(args.namespace, prometheus_host, scenario["window"]),
        }

        if idx < len(SCENARIOS) - 1 and args.kafka_lag_settle_timeout_sec > 0:
            results["scenarios"][scenario["name"]]["post_scenario_settle"] = wait_for_kafka_lag_to_settle(
                prometheus_host,
                args.kafka_lag_settle_threshold,
                args.kafka_lag_settle_timeout_sec,
            )

    scale_all(args.namespace, 1)
    output_path = Path(args.output)
    output_path.write_text(json.dumps(results, indent=2))
    print(output_path)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
