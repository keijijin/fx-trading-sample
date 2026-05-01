#!/usr/bin/env bash
# 内蔵レジストリへ一括 push（ビルド済みタグを想定）。
# ビルドは ./scripts/build-openshift-images.sh（既定 linux/amd64）で行うこと。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REGISTRY="$(oc registry info --public)"
NAMESPACE="${NAMESPACE:-fx-trading-sample}"
USER_NAME="$(oc whoami)"
TOKEN="$(oc whoami -t)"

podman login "$REGISTRY" -u "$USER_NAME" -p "$TOKEN" --tls-verify=false

push_one() {
  local local_tag=$1
  local remote_name=$2
  podman tag "${local_tag}" "${REGISTRY}/${NAMESPACE}/${remote_name}:latest"
  podman push --tls-verify=false "${REGISTRY}/${NAMESPACE}/${remote_name}:latest"
  echo "Pushed ${REGISTRY}/${NAMESPACE}/${remote_name}:latest"
}

push_one fx-postgres:latest fx-postgres
push_one fx-kafka:latest fx-kafka

for image in fx-core-service trade-saga-service cover-service risk-service accounting-service settlement-service notification-service compliance-service frontend; do
  push_one "backend-${image}:latest" "${image}"
done

echo "All images pushed to ${REGISTRY}/${NAMESPACE}/"
