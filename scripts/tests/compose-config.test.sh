#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

render_compose() {
  MONITOR_ADMIN_USERNAME=admin \
  MONITOR_ADMIN_PASSWORD=replace-with-a-strong-password \
  MONITOR_WEB_BIND_ADDRESS=127.0.0.1 \
  MONITOR_WEB_PORT=28461 \
  MONITOR_PUBLIC_URL=https://monitor.example.com \
  MONITOR_WECHAT_ENABLED=true \
  MONITOR_WECHAT_APP_ID=wx_example_app \
  MONITOR_WECHAT_APP_SECRET=example-secret \
  MONITOR_WECHAT_TEMPLATE_ID=template_example_01 \
  MONITOR_WECHAT_OPEN_IDS=openid_example_1,openid_example_2 \
  MONITOR_SETTINGS_ENCRYPTION_KEY=replace-with-base64-32-byte-key \
  OCI_REGION=us-example-1 \
  OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..replace-with-your-compartment-ocid \
    docker compose \
      -f "${ROOT_DIR}/docker-compose.yml" \
      config --format json
}

COMPOSE_CONFIG="$(render_compose)"

jq -e '.services["oci-arm-monitor-server"].ports == null' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].expose | map(tostring) | index("9090")' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_PUBLIC_URL == "https://monitor.example.com"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_WECHAT_ENABLED == "true"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_WECHAT_APP_ID == "wx_example_app"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_WECHAT_APP_SECRET == "example-secret"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_WECHAT_TEMPLATE_ID == "template_example_01"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_WECHAT_OPEN_IDS == "openid_example_1,openid_example_2"' <<<"${COMPOSE_CONFIG}" >/dev/null
jq -e '.services["oci-arm-monitor-server"].environment.MONITOR_SETTINGS_ENCRYPTION_KEY == "replace-with-base64-32-byte-key"' <<<"${COMPOSE_CONFIG}" >/dev/null
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
