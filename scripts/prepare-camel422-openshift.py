#!/usr/bin/env python3
"""Create deployment-only Secrets without printing or writing their values."""
import json
import secrets
import subprocess
import sys

NAMESPACE = "fx-trading-camel422"

def apply(obj):
    subprocess.run(["oc", "apply", "-f", "-"], input=json.dumps(obj), text=True, check=True)

def ensure_secret(name, values):
    result = subprocess.run(["oc", "get", "secret", name, "-n", NAMESPACE], capture_output=True, text=True)
    if result.returncode == 0:
        print("Preserved Secret:", name)
        return
    if "NotFound" not in result.stderr:
        raise RuntimeError("Cannot inspect deployment Secret " + name)
    apply({"apiVersion": "v1", "kind": "Secret", "metadata": {"name": name, "namespace": NAMESPACE}, "type": "Opaque", "stringData": values})

ensure_secret("fx-database", {"username": "fx", "password": secrets.token_urlsafe(32)})
ensure_secret("fx-observability", {"admin-password": secrets.token_urlsafe(24)})
