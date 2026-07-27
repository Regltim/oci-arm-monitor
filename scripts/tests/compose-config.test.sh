#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

render_compose() {
  local override_file="$1"

  MONITOR_ADMIN_USERNAME=admin \
  MONITOR_ADMIN_PASSWORD=replace-with-a-strong-password \
  MONITOR_SITE_ADDRESS="${MONITOR_SITE_ADDRESS:-:8080}" \
  MONITOR_HTTP_PORT=8080 \
  OCI_REGION=us-example-1 \
  OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..replace-with-your-compartment-ocid \
    docker compose \
      -f "${ROOT_DIR}/docker-compose.yml" \
      -f "${ROOT_DIR}/${override_file}" \
      config --format json
}

HTTP_CONFIG="$(render_compose docker-compose.http.yml)"
MONITOR_SITE_ADDRESS=monitor.example.com
HTTPS_CONFIG="$(render_compose docker-compose.https.yml)"

jq -e '.services["oci-arm-monitor-server"].ports == null' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].expose | map(tostring) | index("9090")' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].ports[] | select((.published | tostring) == "8080" and .target == 8080)' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '[.services["oci-arm-monitor-web"].ports[].published | tostring] | index("80") | not' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '[.services["oci-arm-monitor-web"].ports[].published | tostring] | index("443") | not' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].ports[] | select((.published | tostring) == "80" and .target == 80)' <<<"${HTTPS_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].ports[] | select((.published | tostring) == "443" and .target == 443)' <<<"${HTTPS_CONFIG}" >/dev/null
jq -e '[.services["oci-arm-monitor-web"].ports[].published | tostring] | index("8080") | not' <<<"${HTTPS_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].depends_on["oci-arm-monitor-server"].condition == "service_healthy"' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].environment.MONITOR_SITE_ADDRESS == ":8080"' <<<"${HTTP_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-web"].environment.MONITOR_SITE_ADDRESS == "monitor.example.com"' <<<"${HTTPS_CONFIG}" >/dev/null
jq -e '.volumes["oci-arm-monitor-caddy-data"] and .volumes["oci-arm-monitor-caddy-config"]' <<<"${HTTP_CONFIG}" >/dev/null
