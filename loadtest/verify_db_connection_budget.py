#!/usr/bin/env python3
"""
PostgreSQL の max_connections に対し、各 Pod の Hikari プール（+ アクティビティ用 DB）を
レプリカ数で積み上げた「理論上の JDBC 使用数」との比較を出力する。

前提: `openshift/fx-trading-db-separated.yaml` 系（サービスごとに DB ホスト分離 + activity は saga DB 集約）に合わせた既定値。
実際の pool は環境変数で上書きされるため、`--pool-override service:pool=8` で調整可能。

例:
  python3 loadtest/verify_db_connection_budget.py --replicas 5
  python3 loadtest/verify_db_connection_budget.py --replicas 3 --pg-max-connections 200
  python3 loadtest/verify_db_connection_budget.py --replicas 5 --oc-namespace fx-trading-sample --oc-deployment fx-core-db
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict


# openshift/fx-trading-db-separated.yaml + application.yml 既定に基づく「1 Pod あたり」接続数（メイン DS + 付帯）
# 未設定のサービスは Spring Boot / Hikari 既定 10 を仮定
# メイン DS: manifest に DB_POOL が無いサービスは Spring/Hikari 既定 10。
# activity DS: ActivityJdbcSupport の activity.db.pool.max-size 既定は 10（fx-core のみ manifest で 20）。
DEFAULT_PER_POD: dict[str, dict[str, int]] = {
    "fx-core-service": {
        "fx-core-db": 8,
        "fx-trade-saga-db": 20,
    },
    "trade-saga-service": {"fx-trade-saga-db": 10},
    "cover-service": {"fx-cover-db": 10, "fx-trade-saga-db": 10},
    "risk-service": {"fx-risk-db": 10, "fx-trade-saga-db": 10},
    "accounting-service": {"fx-accounting-db": 10, "fx-trade-saga-db": 10},
    "settlement-service": {"fx-settlement-db": 10, "fx-trade-saga-db": 10},
    "notification-service": {"fx-notification-db": 10, "fx-trade-saga-db": 10},
    "compliance-service": {"fx-compliance-db": 10, "fx-trade-saga-db": 10},
}


def parse_overrides(items: list[str]) -> dict[tuple[str, str], int]:
    out: dict[tuple[str, str], int] = {}
    for item in items:
        # fx-core-service:fx-core-db=12
        m = re.match(r"^([^:]+):([^=]+)=(\d+)$", item.strip())
        if not m:
            raise SystemExit(f"Invalid --pool-override: {item}")
        out[(m.group(1), m.group(2))] = int(m.group(3))
    return out


def try_oc_max_connections(namespace: str, deployment: str) -> int | None:
    try:
        out = subprocess.check_output(
            [
                "oc",
                "-n",
                namespace,
                "exec",
                f"deployment/{deployment}",
                "--",
                "psql",
                "-U",
                "fx",
                "-d",
                "fx_trading",
                "-t",
                "-A",
                "-c",
                "SHOW max_connections;",
            ],
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=30,
        )
        line = out.strip().splitlines()[-1] if out.strip() else ""
        return int(line.strip()) if line.strip().isdigit() else None
    except (subprocess.CalledProcessError, FileNotFoundError, ValueError, IndexError):
        return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Estimate JDBC pool usage vs PostgreSQL max_connections.")
    parser.add_argument("--replicas", type=int, default=3, help="Deployment replicas (assumed same for all app deployments)")
    parser.add_argument("--pg-max-connections", type=int, default=None, help="PostgreSQL max_connections (if omitted, try --oc or default 100)")
    parser.add_argument("--oc-namespace", default="", help="If set with --oc-deployment, run SHOW max_connections via oc exec")
    parser.add_argument("--oc-deployment", default="", help="e.g. fx-core-db (one Postgres; repeat per DB if needed)")
    parser.add_argument(
        "--pool-override",
        action="append",
        default=[],
        help="service:dbhost=size e.g. fx-core-service:fx-core-db=8",
    )
    args = parser.parse_args()

    overrides = parse_overrides(args.pool_override)
    r = args.replicas

    pg_max = args.pg_max_connections
    if pg_max is None and args.oc_namespace and args.oc_deployment:
        pg_max = try_oc_max_connections(args.oc_namespace, args.oc_deployment)
    if pg_max is None:
        pg_max = 100

    by_db: dict[str, float] = defaultdict(float)
    detail: list[dict] = []

    for svc, pools in DEFAULT_PER_POD.items():
        for db_host, default_sz in pools.items():
            key = (svc, db_host)
            sz = overrides.get(key, default_sz)
            need = sz * r
            by_db[db_host] += need
            detail.append(
                {
                    "service": svc,
                    "db_host": db_host,
                    "per_pod_pool": sz,
                    "replicas": r,
                    "jdbc_connections": need,
                }
            )

    report = {
        "replicas": r,
        "postgresql_max_connections_assumed": pg_max,
        "jdbc_connections_by_db_host": dict(sorted(by_db.items())),
        "detail": detail,
        "notes": [
            "値は manifest / 既定 YAML に基づく近似。実環境では DB_POOL_MAX_SIZE 等の env で変わる。",
            "各 PostgreSQL インスタンスごとに max_connections を oc exec で確認し、本ツールの該当 db_host 行と比較すること。",
            "アプリ以外（バックアップ・管理接続）の余裕を見て max_connections を設定する。",
        ],
    }

    if args.oc_namespace:
        report["oc_max_connections_probe"] = {
            "namespace": args.oc_namespace,
            "deployment": args.oc_deployment or None,
            "used_for_pg_max": args.pg_max_connections is None and bool(args.oc_deployment),
        }

    print(json.dumps(report, indent=2, ensure_ascii=False))

    worst = max(by_db.values()) if by_db else 0
    if worst > pg_max * 0.8:
        print(
            "\nWARNING: いずれかの DB に対する理論 JDBC 合計が max_connections の 80% を超えています。"
            " プール縮小・レプリカ・シャード分割を検討してください。",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
