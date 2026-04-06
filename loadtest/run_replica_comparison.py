#!/usr/bin/env python3

import argparse
import json
import re
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


def parse_hey(output: str) -> dict:
    def grab(label: str):
        match = re.search(rf"{label}:\s+([0-9.]+)", output)
        return float(match.group(1)) if match else None

    status_counts = {
        status: int(count)
        for status, count in re.findall(r"\[(\d+)\]\s+(\d+) responses", output)
    }
    return {
        "average_s": grab("Average"),
        "fastest_s": grab("Fastest"),
        "slowest_s": grab("Slowest"),
        "requests_per_sec": grab("Requests/sec"),
        "status_counts": status_counts,
        "raw": output,
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
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare application performance across replica counts with hey and Prometheus."
    )
    parser.add_argument("--namespace", default="fx-trading-sample")
    parser.add_argument("--duration", default="30s")
    parser.add_argument("--window", default="45s")
    parser.add_argument("--concurrency", default="30")
    parser.add_argument("--currency-pair", default="USD/JPY")
    parser.add_argument("--side", default="BUY")
    parser.add_argument("--order-amount", type=float, default=1.0)
    parser.add_argument("--requested-price", type=float, default=151.25)
    parser.add_argument(
        "--output",
        default="loadtest/replica-comparison-report-generated.json",
    )
    args = parser.parse_args()

    core_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-core-service", "-o", "jsonpath={.spec.host}"]
    ).strip()
    prometheus_host = run(
        ["oc", "-n", args.namespace, "get", "route", "fx-prometheus", "-o", "jsonpath={.spec.host}"]
    ).strip()
    url = f"http://{core_host}/api/trades"

    base_body = {
        "currencyPair": args.currency_pair,
        "side": args.side,
        "orderAmount": args.order_amount,
        "requestedPrice": args.requested_price,
        "simulateCoverFailure": False,
        "simulateRiskFailure": False,
        "simulateAccountingFailure": False,
        "simulateSettlementFailure": False,
        "simulateNotificationFailure": False,
        "simulateComplianceFailure": False,
        "preTradeComplianceFailure": False,
    }

    results = {}

    for label, replicas, account_id in [
        ("replica_1", 1, "ACC-LOAD-R1-001"),
        ("replica_3", 3, "ACC-LOAD-R3-001"),
    ]:
        scale_all(args.namespace, replicas)
        time.sleep(10)

        body = dict(base_body)
        body["accountId"] = account_id

        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json") as handle:
            json.dump(body, handle)
            temp_file = handle.name

        try:
            output = run(
                [
                    "hey",
                    "-z",
                    args.duration,
                    "-c",
                    args.concurrency,
                    "-m",
                    "POST",
                    "-T",
                    "application/json",
                    "-D",
                    temp_file,
                    url,
                ]
            )
        finally:
            Path(temp_file).unlink(missing_ok=True)

        results[label] = {
            "replicas": replicas,
            "accountId": account_id,
            "hey": parse_hey(output),
            "prometheus": collect_metrics(args.namespace, prometheus_host, args.window),
        }
        time.sleep(5)

    scale_all(args.namespace, 1)

    output_path = Path(args.output)
    output_path.write_text(json.dumps(results, indent=2))
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
