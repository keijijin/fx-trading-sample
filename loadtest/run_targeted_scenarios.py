#!/usr/bin/env python3

import argparse
import json
import subprocess
import time
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
        ["oc", "-n", namespace, "rollout", "status", f"deployment/{name}", "--timeout=180s"]
    )


def scale_all(namespace: str, replicas: int) -> None:
    subprocess.check_call(
        ["oc", "-n", namespace, "scale", "deployment", *DEPLOYMENTS, f"--replicas={replicas}"]
    )
    for deployment in DEPLOYMENTS:
        rollout_wait(namespace, deployment)


def run_k6(script: str, workdir: Path, envs: dict[str, str]) -> dict:
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
        data = json.loads(summary_path.read_text())
        data["_exit_code"] = result.returncode
        return data
    finally:
        summary_path.unlink(missing_ok=True)


def metric_value(metrics: dict, metric: str, field: str):
    payload = metrics.get(metric, {})
    if field in payload:
        return payload[field]
    return payload.get("values", {}).get(field)


def summarise(data: dict) -> dict:
    metrics = data.get("metrics", {})
    return {
        "passed": data.get("_exit_code") == 0,
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
        "requests_per_sec": metric_value(metrics, "trade_accepted", "rate"),
        "trade_accepted": metric_value(metrics, "trade_accepted", "count"),
        "trace_sampled": metric_value(metrics, "trace_sampled", "count"),
        "trace_timed_out": metric_value(metrics, "trace_timed_out", "count"),
        "saga_completed": metric_value(metrics, "saga_completed", "count"),
        "saga_failed": metric_value(metrics, "saga_failed", "count"),
        "trade_e2e_p95_s": (
            metric_value(metrics, "trade_e2e_latency", "p(95)") / 1000
            if metric_value(metrics, "trade_e2e_latency", "p(95)") is not None
            else None
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run targeted baseline/notification_fail scenarios for selected replicas.")
    parser.add_argument("--namespace", default="fx-trading-sample")
    parser.add_argument("--replicas", default="3,5")
    parser.add_argument("--output", default="loadtest/targeted-baseline-notification-comparison.json")
    args = parser.parse_args()

    workdir = Path(__file__).resolve().parent
    host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-core-service", "-o", "jsonpath={.spec.host}"]
    ).strip()

    scenarios = {
        "baseline": {
            "SCENARIO_MODE": "baseline",
            "DURATION": "60s",
            "RATE": "30",
            "PREALLOCATED_VUS": "60",
            "MAX_VUS": "180",
            "TRACE_TIMEOUT_MS": "45000",
            "TRACE_SAMPLE_RATE": "0.05",
            "BASELINE_FAILURE_PERCENT": "5",
        },
        "notification_fail": {
            "SCENARIO_MODE": "notification_fail",
            "DURATION": "60s",
            "RATE": "10",
            "PREALLOCATED_VUS": "40",
            "MAX_VUS": "120",
            "TRACE_TIMEOUT_MS": "45000",
            "TRACE_SAMPLE_RATE": "0.2",
        },
    }

    results = {}
    for replica in [int(item) for item in args.replicas.split(",") if item.strip()]:
        scale_all(args.namespace, replica)
        time.sleep(10)
        replica_result = {}
        for scenario_name, env in scenarios.items():
            envs = {
                "FX_CORE_BASE_URL": f"http://{host}",
                "ACCOUNT_COUNT": "1000",
                "ACCOUNT_PREFIX": f"ACC-TARGET-{scenario_name.upper()}-{replica}",
                "CURRENCY_PAIR": "USD/JPY",
                "SIDE": "BUY",
                "ORDER_AMOUNT": "1.0",
                "REQUESTED_PRICE": "151.25",
            }
            envs.update(env)
            replica_result[scenario_name] = summarise(run_k6("k6-test-plan-suite.js", workdir, envs))
            time.sleep(10)
        results[f"replica_{replica}"] = replica_result

    scale_all(args.namespace, 1)
    output_path = Path(args.output)
    output_path.write_text(json.dumps(results, indent=2))
    print(output_path)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
