#!/usr/bin/env bash
# OpenShift ノードは多くが linux/amd64。ローカルが arm64（Apple Silicon 等）のまま
# podman build すると、クラスタ上でコンテナ起動時に「Exec format error」になる。
# 既定で --platform linux/amd64 を付与してビルドする。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# クラスタのノードアーキテクチャに合わせる（amd64 クラスタが一般的）
CONTAINER_PLATFORM="${CONTAINER_PLATFORM:-linux/amd64}"

echo "Building container images for platform: ${CONTAINER_PLATFORM}"

(
  cd "${REPO_ROOT}/backend"
  mvn -DskipTests package -q
)

podman_build() {
  local tag=$1
  shift
  podman build --platform "${CONTAINER_PLATFORM}" -t "${tag}" "$@"
}

podman_build fx-postgres:latest -f openshift-images/postgres/Dockerfile openshift-images/postgres
podman_build fx-kafka:latest -f openshift-images/kafka/Dockerfile openshift-images/kafka

for svc in fx-core-service trade-saga-service cover-service risk-service accounting-service settlement-service notification-service compliance-service; do
  echo "=== build ${svc} ==="
  podman_build "backend-${svc}:latest" -f "backend/${svc}/Dockerfile" "backend/${svc}"
done

echo "=== build frontend ==="
podman_build backend-frontend:latest -f frontend/Dockerfile frontend

echo "Done. Local tags: fx-postgres:latest fx-kafka:latest backend-<service>:latest backend-frontend:latest"
echo "Push with: ./scripts/push-openshift-images.sh"
