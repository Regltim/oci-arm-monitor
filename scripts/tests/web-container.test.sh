#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_NAME="oci-arm-monitor-web:test"
CONTAINER_NAME="oci-arm-monitor-web-test-$$"
OUTPUT_FILE="$(mktemp "${TMPDIR:-/tmp}/oci-arm-monitor-index.XXXXXX")"

cleanup() {
  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  rm -f "${OUTPUT_FILE}"
}
trap cleanup EXIT

docker build -t "${IMAGE_NAME}" "${ROOT_DIR}/web"
docker run --rm --entrypoint caddy "${IMAGE_NAME}" \
  validate --config /etc/caddy/Caddyfile --adapter caddyfile

docker run -d \
  --name "${CONTAINER_NAME}" \
  -e MONITOR_SITE_ADDRESS=:8080 \
  -p 127.0.0.1::8080 \
  "${IMAGE_NAME}" >/dev/null

HOST_PORT="$(docker port "${CONTAINER_NAME}" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${HOST_PORT}/" >"${OUTPUT_FILE}"; then
    break
  fi
  sleep 1
done

grep -q '<div id="root"></div>' "${OUTPUT_FILE}"
