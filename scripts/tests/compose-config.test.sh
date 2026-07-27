#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

render_compose() {
  MONITOR_ADMIN_USERNAME=admin \
  MONITOR_ADMIN_PASSWORD=replace-with-a-strong-password \
  MONITOR_WEB_BIND_ADDRESS=127.0.0.1 \
  MONITOR_WEB_PORT=28461 \
  OCI_REGION=us-example-1 \
  OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..replace-with-your-compartment-ocid \
    docker compose \
      -f "${ROOT_DIR}/docker-compose.yml" \
      config --format json
}

COMPOSE_CONFIG="$(render_compose)"

jq -e '.services["oci-arm-monitor-server"].ports == null' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].expose | map(tostring) | index("9090")' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].ports[] | select(
  .host_ip == "127.0.0.1" and
  (.published | tostring) == "28461" and
  .target == 8080
)' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '[.services["oci-arm-monitor-web"].ports[].published | tostring] | index("80") | not' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '[.services["oci-arm-monitor-web"].ports[].published | tostring] | index("443") | not' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].depends_on["oci-arm-monitor-server"].condition == "service_healthy"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].volumes == null' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '(.volumes | keys) == ["oci-arm-monitor-data"]' <<<"${COMPOSE_CONFIG}" >/dev/null
