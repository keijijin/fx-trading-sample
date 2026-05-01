#!/usr/bin/env bash
# Push one application image to the OpenShift internal registry.
# Prerequisite: image exists locally as backend-<name>:latest (see README OpenShift section).
# クラスタが amd64 のとき、arm64 ホストでビルドしたイメージは Pod で Exec format error になる。
# 一括ビルドは ./scripts/build-openshift-images.sh（既定 linux/amd64）を使うこと。
# Registry URL MUST use --public (plain "oc registry info" can be empty and breaks the tag).
set -euo pipefail

IMAGE_NAME="${1:?usage: $0 <image-name>   e.g. trade-saga-service}"
REGISTRY="$(oc registry info --public)"
NAMESPACE="${NAMESPACE:-fx-trading-sample}"
LOCAL_IMAGE="${LOCAL_IMAGE:-backend-${IMAGE_NAME}:latest}"

USER_NAME="$(oc whoami)"
TOKEN="$(oc whoami -t)"
podman login "$REGISTRY" -u "$USER_NAME" -p "$TOKEN" --tls-verify=false
podman tag "$LOCAL_IMAGE" "$REGISTRY/$NAMESPACE/${IMAGE_NAME}:latest"
podman push --tls-verify=false "$REGISTRY/$NAMESPACE/${IMAGE_NAME}:latest"
echo "Pushed $REGISTRY/$NAMESPACE/${IMAGE_NAME}:latest"
