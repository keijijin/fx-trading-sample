#!/usr/bin/env python3

import argparse
import json
import subprocess
import tempfile
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


def run_k6(script: str, workdir: Path, envs: dict[str, str]) -> dict:
    summary_dir = workdir / ".tmp"
    summary_dir.mkdir(parents=True, exist_ok=True)
    summary_path = summary_dir / f"k6-summary-{int(time.time() * 1000)}.json"

    try:
        cmd = [
            "podman",
            "run",
            "--rm",
            "-v",
            f"{workdir.resolve()}:/scripts:Z",
            "-e",
            f"FX_CORE_BASE_URL={envs['FX_CORE_BASE_URL']}",
            "-e",
            f"VUS={envs['VUS']}",
            "-e",
            f"DURATION={envs['DURATION']}",
            "-e",
            f"ACCOUNT_COUNT={envs['ACCOUNT_COUNT']}",
            "-e",
            f"ACCOUNT_SELECTION_MODE={envs['ACCOUNT_SELECTION_MODE']}",
            "-e",
            f"ACCOUNT_PREFIX={envs['ACCOUNT_PREFIX']}",
            "-e",
            f"ORDER_AMOUNT={envs['ORDER_AMOUNT']}",
            "-e",
            f"REQUESTED_PRICE={envs['REQUESTED_PRICE']}",
            "docker.io/grafana/k6:latest",
            "run",
            f"--summary-export=/scripts/.tmp/{summary_path.name}",
            f"/scripts/{script}",
        ]
        result = subprocess.run(cmd, text=True)
        if result.returncode not in (0, 99):
            raise subprocess.CalledProcessError(result.returncode, cmd)
        return json.loads(summary_path.read_text())
    finally:
        summary_path.unlink(missing_ok=True)


def summarise_k6(data: dict) -> dict:
    metrics = data.get("metrics", {})

    def value(metric: str, field: str):
        return metrics.get(metric, {}).get(field)

    return {
        "average_s": value("http_req_duration", "avg") / 1000 if value("http_req_duration", "avg") is not None else None,
        "fastest_s": value("http_req_duration", "min") / 1000 if value("http_req_duration", "min") is not None else None,
        "slowest_s": value("http_req_duration", "max") / 1000 if value("http_req_duration", "max") is not None else None,
        "requests_per_sec": value("http_reqs", "rate"),
        "http_req_failed_rate": value("http_req_failed", "rate"),
        "checks_rate": value("checks", "rate"),
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
        "http_error_rate": prom_query(
            prometheus_host,
            f'sum(rate(http_server_requests_seconds_count{{application="fx-core-service",uri="/api/trades",status=~"4..|5.."}}[{window}]))',
        ),
        "saga_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_trade_saga_duration_seconds_bucket{{outcome="completed"}}[{window}])))',
        ),
        "saga_p99_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.99, sum by (le) (rate(fx_trade_saga_duration_seconds_bucket{{outcome="completed"}}[{window}])))',
        ),
        "outbox_backlog_max": prom_query(
            prometheus_host,
            f"max_over_time(sum(fx_outbox_backlog_total)[{window}:15s])",
        ),
        "hikari_active_max": prom_query(
            prometheus_host,
            f'max(max_over_time(hikaricp_connections_active{{application="fx-core-service"}}[{window}]))',
        ),
        "hikari_pending_max": prom_query(
            prometheus_host,
            f'max(max_over_time(hikaricp_connections_pending{{application="fx-core-service"}}[{window}]))',
        ),
        "hikari_max": prom_query(
            prometheus_host,
            'max(hikaricp_connections_max{application="fx-core-service"})',
        ),
        "kafka_lag_max": prom_query(
            prometheus_host,
            f"max(max_over_time(fx_kafka_consumer_group_lag[{window}]))",
        ),
        "pod_cpu_sum_cores": prom_query(
            prometheus_host,
            f'sum(rate(container_cpu_usage_seconds_total{{namespace="{namespace}",pod=~"(fx-core-service|trade-saga-service|cover-service|risk-service|accounting-service|settlement-service|notification-service|compliance-service).*",container!="",container!="POD"}}[{window}]))',
        ),
        "pod_memory_sum_bytes": prom_query(
            prometheus_host,
            f'sum(max_over_time(container_memory_working_set_bytes{{namespace="{namespace}",pod=~"(fx-core-service|trade-saga-service|cover-service|risk-service|accounting-service|settlement-service|notification-service|compliance-service).*",container!="",container!="POD"}}[{window}]))',
        ),
        "pod_restarts": prom_query(
            prometheus_host,
            f'sum(kube_pod_container_status_restarts_total{{namespace="{namespace}",pod=~"(fx-core-service|trade-saga-service|cover-service|risk-service|accounting-service|settlement-service|notification-service|compliance-service).*"}})',
        ),
        "fx_core_tx_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le, outcome) (rate(fx_core_transaction_duration_seconds_bucket[{window}])))',
        ),
        "fx_core_tx_p99_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.99, sum by (le, outcome) (rate(fx_core_transaction_duration_seconds_bucket[{window}])))',
        ),
        "fx_core_lock_wait_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le, operation) (rate(fx_core_lock_wait_seconds_bucket[{window}])))',
        ),
        "fx_core_lock_wait_p99_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.99, sum by (le, operation) (rate(fx_core_lock_wait_seconds_bucket[{window}])))',
        ),
        "fx_core_slow_operation_rate": prom_query(
            prometheus_host,
            f'sum(rate(fx_core_slow_operation_total[{window}]))',
        ),
        "trade_saga_stage_cover_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_trade_saga_stage_duration_seconds_bucket{{stage="cover",outcome="completed"}}[{window}])))',
        ),
        "trade_saga_stage_risk_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_trade_saga_stage_duration_seconds_bucket{{stage="risk",outcome="completed"}}[{window}])))',
        ),
        "trade_saga_stage_accounting_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_trade_saga_stage_duration_seconds_bucket{{stage="accounting",outcome="completed"}}[{window}])))',
        ),
        "trade_saga_stage_settlement_p95_s": prom_query(
            prometheus_host,
            f'histogram_quantile(0.95, sum by (le) (rate(fx_trade_saga_stage_duration_seconds_bucket{{stage="settlement",outcome="completed"}}[{window}])))',
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare load test results across replica counts using k6 and Prometheus."
    )
    parser.add_argument("--namespace", default="fx-trading-sample")
    parser.add_argument("--duration", default="60s")
    parser.add_argument("--window", default="75s")
    parser.add_argument("--vus", default="100")
    parser.add_argument("--account-count", default="1000")
    parser.add_argument("--selection-mode", default="round_robin", choices=["round_robin", "random"])
    parser.add_argument("--script", default="k6-1000-accounts-stress.js")
    parser.add_argument("--account-prefix-template", default="ACC-K6-R{replicas}")
    parser.add_argument("--mode-label", default="1000-account-pool")
    parser.add_argument("--order-amount", default="1.0")
    parser.add_argument("--requested-price", default="151.25")
    parser.add_argument("--currency-pair", default="USD/JPY")
    parser.add_argument("--side", default="BUY")
    parser.add_argument(
        "--replicas",
        default="1,3,5",
        help="Comma-separated replica counts to compare, e.g. 1,3,5",
    )
    parser.add_argument(
        "--output",
        default="loadtest/k6-1000-accounts-replica-comparison.json",
    )
    args = parser.parse_args()

    workdir = Path(__file__).resolve().parent
    core_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-core-service", "-o", "jsonpath={.spec.host}"]
    ).strip()
    prometheus_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-prometheus", "-o", "jsonpath={.spec.host}"]
    ).strip()

    replica_counts = [int(item.strip()) for item in args.replicas.split(",") if item.strip()]
    results: dict[str, dict] = {}

    for replica_count in replica_counts:
        scale_all(args.namespace, replica_count)
        time.sleep(10)

        k6_result = run_k6(
            args.script,
            workdir,
            {
                "FX_CORE_BASE_URL": f"http://{core_host}",
                "VUS": str(args.vus),
                "DURATION": args.duration,
                "ACCOUNT_COUNT": str(args.account_count),
                "ACCOUNT_SELECTION_MODE": args.selection_mode,
                "ACCOUNT_PREFIX": args.account_prefix_template.format(replicas=replica_count),
                "ORDER_AMOUNT": str(args.order_amount),
                "REQUESTED_PRICE": str(args.requested_price),
            },
        )

        results[f"replica_{replica_count}"] = {
            "replicas": replica_count,
            "accountPoolSize": int(args.account_count),
            "selectionMode": args.selection_mode,
            "script": args.script,
            "modeLabel": args.mode_label,
            "k6": summarise_k6(k6_result),
            "prometheus": collect_metrics(args.namespace, prometheus_host, args.window),
        }
        time.sleep(10)

    scale_all(args.namespace, 1)

    output_path = Path(args.output)
    output_path.write_text(json.dumps(results, indent=2))
    print(output_path)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
