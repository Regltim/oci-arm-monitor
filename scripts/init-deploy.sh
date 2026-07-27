#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
OCI_CONFIG_CONTAINER_PATH="/home/monitor/.oci/config"
OCI_KEY_CONTAINER_PATH="/home/monitor/.oci/oci_api_key.pem"
OCI_INSTANCE_METADATA_URL="${OCI_INSTANCE_METADATA_URL:-http://169.254.169.254/opc/v2/instance/}"
DETECTED_OCI_INSTANCE_OCID=""
DETECTED_OCI_COMPARTMENT_OCID=""
DETECTED_OCI_TENANCY_OCID=""
DETECTED_OCI_REGION=""

info() {
  printf "\033[1;34m[INFO]\033[0m %s\n" "$1"
}

warn() {
  printf "\033[1;33m[WARN]\033[0m %s\n" "$1"
}

ok() {
  printf "\033[1;32m[OK]\033[0m %s\n" "$1"
}

read_env_value() {
  local key="$1"
  local value=""
  if [ ! -f "${ENV_FILE}" ]; then
    return 0
  fi

  value="$(awk -F '=' -v key="${key}" '
    $1 == key {
      sub(/^[^=]*=/, "")
      print
      exit
    }
  ' "${ENV_FILE}")"
  value="${value%$'\r'}"

  if [ "${#value}" -ge 2 ]; then
    local first_char="${value:0:1}"
    local last_char="${value: -1}"

    if [ "${first_char}" = "'" ] && [ "${last_char}" = "'" ]; then
      local escaped_quote="\\'"
      local plain_quote="'"
      value="${value:1:${#value}-2}"
      value="${value//${escaped_quote}/${plain_quote}}"
    elif [ "${first_char}" = "\"" ] && [ "${last_char}" = "\"" ]; then
      local escaped_double="\\\""
      local plain_double="\""
      value="${value:1:${#value}-2}"
      value="${value//${escaped_double}/${plain_double}}"
    fi
  fi

  printf "%s" "${value}"
}

ask() {
  local var_name="$1"
  local label="$2"
  local default_value="${3:-}"
  local required="${4:-false}"
  local value=""

  while true; do
    if [ -n "${default_value}" ]; then
      read -r -p "${label} [${default_value}]: " value
      value="${value:-${default_value}}"
    else
      read -r -p "${label}: " value
    fi

    if [ "${required}" != "true" ] || [ -n "${value}" ]; then
      printf -v "${var_name}" "%s" "${value}"
      return 0
    fi

    warn "该字段不能为空。"
  done
}

ask_with_hidden_default() {
  local var_name="$1"
  local label="$2"
  local default_value="${3:-}"
  local required="${4:-false}"
  local value=""

  while true; do
    if [ -n "${default_value}" ]; then
      printf "%s [已设置，回车保留]: " "${label}" >&2
      read -r value
      value="${value:-${default_value}}"
    else
      printf "%s: " "${label}" >&2
      read -r value
    fi

    if [ "${required}" != "true" ] || [ -n "${value}" ]; then
      printf -v "${var_name}" "%s" "${value}"
      return 0
    fi

    warn "该字段不能为空。"
  done
}

ask_secret() {
  local var_name="$1"
  local label="$2"
  local default_value="${3:-}"
  local required="${4:-false}"
  local value=""

  while true; do
    if [ -n "${default_value}" ]; then
      read -r -s -p "${label} [已设置，回车保留]: " value
      printf "\n"
      value="${value:-${default_value}}"
    else
      read -r -s -p "${label}: " value
      printf "\n"
    fi

    if [ "${required}" != "true" ] || [ -n "${value}" ]; then
      printf -v "${var_name}" "%s" "${value}"
      return 0
    fi

    warn "该字段不能为空。"
  done
}

normalize_auth_mode() {
  local auth_mode="$1"
  case "${auth_mode}" in
    instance_principal|instance-principal|instance_principals|instance-principals)
      printf "instance_principal"
      ;;
    config_file|config-file|config|api_key|api-key|apikey)
      printf "config_file"
      ;;
    *)
      return 1
      ;;
  esac
}

validate_http_port() {
  [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

validate_web_port() {
  [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

is_true() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|y|Y) return 0 ;;
    *) return 1 ;;
  esac
}

validate_daily_time() {
  local value="$1"
  local hour
  local minute

  [[ "${value}" =~ ^([0-9]{2}):([0-9]{2})$ ]] || return 1
  hour="${BASH_REMATCH[1]}"
  minute="${BASH_REMATCH[2]}"
  [ "$((10#${hour}))" -le 23 ] && [ "$((10#${minute}))" -le 59 ]
}

validate_hostname() {
  local value="$1"
  local label
  local -a labels

  [ -n "${value}" ] && [ "${#value}" -le 253 ] || return 1
  [[ "${value}" != .* ]] && [[ "${value}" != *. ]] && [[ "${value}" != *..* ]] || return 1
  IFS='.' read -r -a labels <<<"${value}"
  for label in "${labels[@]}"; do
    [[ "${label}" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ ]] || return 1
  done
}

validate_http_host() {
  local value="$1"
  local octet
  local -a octets

  if [[ "${value}" =~ ^[0-9.]+$ ]]; then
    [[ "${value}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
    IFS='.' read -r -a octets <<<"${value}"
    for octet in "${octets[@]}"; do
      [ "${octet}" -le 255 ] || return 1
    done
    return 0
  fi

  validate_hostname "${value}"
}

validate_public_origin() {
  local value="$1"
  local origin_pattern='^https?://([^/?#]+)$'
  local authority
  local host
  local port=""

  [[ "${value}" =~ ${origin_pattern} ]] || return 1
  authority="${BASH_REMATCH[1]}"
  [[ "${authority}" != *"@"* ]] || return 1

  if [[ "${authority}" =~ ^([^:]+):([0-9]+)$ ]]; then
    host="${BASH_REMATCH[1]}"
    port="${BASH_REMATCH[2]}"
  elif [[ "${authority}" == *":"* ]]; then
    return 1
  else
    host="${authority}"
  fi

  validate_http_host "${host}" || return 1
  [ -z "${port}" ] || validate_http_port "${port}"
}

configure_public_access() {
  local public_url="$1"
  local web_port="$2"

  validate_public_origin "${public_url}" || return 1
  validate_web_port "${web_port}" || return 1

  COMPOSE_FILE="docker-compose.yml"
  MONITOR_PUBLIC_URL="${public_url}"
  MONITOR_WEB_BIND_ADDRESS="127.0.0.1"
  MONITOR_WEB_PORT="${web_port}"
  MONITOR_CORS_ALLOWED_ORIGINS="${public_url}"
  MONITOR_ACCESS_URL="${public_url}"
  if [[ "${public_url}" == https://* ]]; then
    MONITOR_COOKIE_SECURE="true"
  else
    MONITOR_COOKIE_SECURE="false"
  fi
}

collect_access_settings() {
  local public_url_default
  local web_port_default
  local public_url
  local web_port

  public_url_default="$(read_env_value MONITOR_PUBLIC_URL || true)"
  web_port_default="$(read_env_value MONITOR_WEB_PORT || true)"
  web_port_default="${web_port_default:-28461}"
  while true; do
    ask_with_hidden_default public_url "用户访问地址（完整地址，例如 https://monitor.example.com）" "${public_url_default}" true
    ask web_port "容器 Web 服务宿主机端口" "${web_port_default}" true
    configure_public_access "${public_url}" "${web_port}" && return 0
    warn "公开访问 Origin 或 Web 端口格式不正确。Origin 不能包含路径、查询参数或末尾斜杠。"
  done
}

collect_wechat_settings() {
  local enabled_default
  local immediate_default
  local daily_default
  local daily_time_default
  local zone_id_default

  MONITOR_WECHAT_APP_ID="$(read_env_value MONITOR_WECHAT_APP_ID || true)"
  MONITOR_WECHAT_APP_SECRET="$(read_env_value MONITOR_WECHAT_APP_SECRET || true)"
  MONITOR_WECHAT_TEMPLATE_ID="$(read_env_value MONITOR_WECHAT_TEMPLATE_ID || true)"
  MONITOR_WECHAT_OPEN_IDS="$(read_env_value MONITOR_WECHAT_OPEN_IDS || true)"
  enabled_default="$(read_env_value MONITOR_WECHAT_ENABLED || true)"
  immediate_default="$(read_env_value MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED || true)"
  daily_default="$(read_env_value MONITOR_WECHAT_DAILY_SUMMARY_ENABLED || true)"
  daily_time_default="$(read_env_value MONITOR_WECHAT_DAILY_SUMMARY_TIME || true)"
  zone_id_default="$(read_env_value MONITOR_WECHAT_ZONE_ID || true)"

  immediate_default="${immediate_default:-true}"
  daily_default="${daily_default:-false}"
  daily_time_default="${daily_time_default:-09:00}"
  zone_id_default="${zone_id_default:-Asia/Shanghai}"

  if is_true "${enabled_default}"; then
    if confirm "是否启用微信公众号通知" "y"; then
      MONITOR_WECHAT_ENABLED="true"
    else
      MONITOR_WECHAT_ENABLED="false"
    fi
  elif confirm "是否启用微信公众号通知" "n"; then
    MONITOR_WECHAT_ENABLED="true"
  else
    MONITOR_WECHAT_ENABLED="false"
  fi

  if [ "${MONITOR_WECHAT_ENABLED}" != "true" ]; then
    MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED="${immediate_default}"
    MONITOR_WECHAT_DAILY_SUMMARY_ENABLED="${daily_default}"
    MONITOR_WECHAT_DAILY_SUMMARY_TIME="${daily_time_default}"
    MONITOR_WECHAT_ZONE_ID="${zone_id_default}"
    return 0
  fi

  ask_with_hidden_default MONITOR_WECHAT_APP_ID "微信公众号 AppID" "${MONITOR_WECHAT_APP_ID}" true
  ask_secret MONITOR_WECHAT_APP_SECRET "微信公众号 AppSecret" "${MONITOR_WECHAT_APP_SECRET}" true
  ask_with_hidden_default MONITOR_WECHAT_TEMPLATE_ID "微信公众号 Template ID" "${MONITOR_WECHAT_TEMPLATE_ID}" true
  ask_with_hidden_default MONITOR_WECHAT_OPEN_IDS "接收人 OpenID（多个用逗号分隔）" "${MONITOR_WECHAT_OPEN_IDS}" true

  if is_true "${immediate_default}"; then
    if confirm "告警状态变化时是否立即推送" "y"; then
      MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED="true"
    else
      MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED="false"
    fi
  elif confirm "告警状态变化时是否立即推送" "n"; then
    MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED="true"
  else
    MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED="false"
  fi

  if is_true "${daily_default}"; then
    if confirm "是否启用每日状态摘要" "y"; then
      MONITOR_WECHAT_DAILY_SUMMARY_ENABLED="true"
    else
      MONITOR_WECHAT_DAILY_SUMMARY_ENABLED="false"
    fi
  elif confirm "是否启用每日状态摘要" "n"; then
    MONITOR_WECHAT_DAILY_SUMMARY_ENABLED="true"
  else
    MONITOR_WECHAT_DAILY_SUMMARY_ENABLED="false"
  fi

  MONITOR_WECHAT_DAILY_SUMMARY_TIME="${daily_time_default}"
  MONITOR_WECHAT_ZONE_ID="${zone_id_default}"
  if [ "${MONITOR_WECHAT_DAILY_SUMMARY_ENABLED}" = "true" ]; then
    while true; do
      ask MONITOR_WECHAT_DAILY_SUMMARY_TIME "每日状态摘要推送时间（HH:mm）" "${daily_time_default}" true
      validate_daily_time "${MONITOR_WECHAT_DAILY_SUMMARY_TIME}" && break
      warn "每日推送时间格式必须为 HH:mm，例如 09:00。"
    done
    ask MONITOR_WECHAT_ZONE_ID "每日状态摘要时区" "${zone_id_default}" true
  fi
}

ensure_settings_encryption_key() {
  if [ -z "${MONITOR_SETTINGS_ENCRYPTION_KEY:-}" ]; then
    MONITOR_SETTINGS_ENCRYPTION_KEY="$(read_env_value MONITOR_SETTINGS_ENCRYPTION_KEY || true)"
  fi
  if [ -n "${MONITOR_SETTINGS_ENCRYPTION_KEY}" ]; then
    return 0
  fi

  if command -v openssl >/dev/null 2>&1; then
    MONITOR_SETTINGS_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\r\n')"
  elif command -v base64 >/dev/null 2>&1; then
    MONITOR_SETTINGS_ENCRYPTION_KEY="$(dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 | tr -d '\r\n')"
  else
    warn "缺少 openssl 或 base64，无法生成通知配置加密密钥。"
    return 1
  fi

  [ -n "${MONITOR_SETTINGS_ENCRYPTION_KEY}" ] || return 1
  ok "已生成通知配置加密密钥"
}

detect_instance_metadata() {
  local metadata

  command -v curl >/dev/null 2>&1 || return 1
  command -v jq >/dev/null 2>&1 || return 1

  metadata="$(curl \
    --fail \
    --silent \
    --show-error \
    --location \
    --connect-timeout 2 \
    --max-time 4 \
    --header "Authorization: Bearer Oracle" \
    "${OCI_INSTANCE_METADATA_URL}" 2>/dev/null)" || return 1

  DETECTED_OCI_INSTANCE_OCID="$(jq -r '.id // empty' <<<"${metadata}")"
  DETECTED_OCI_COMPARTMENT_OCID="$(jq -r '.compartmentId // empty' <<<"${metadata}")"
  DETECTED_OCI_TENANCY_OCID="$(jq -r '.tenantId // empty' <<<"${metadata}")"
  DETECTED_OCI_REGION="$(jq -r '.canonicalRegionName // .region // empty' <<<"${metadata}")"

  [ -n "${DETECTED_OCI_INSTANCE_OCID}" ] && \
    [ -n "${DETECTED_OCI_COMPARTMENT_OCID}" ] && \
    [ -n "${DETECTED_OCI_TENANCY_OCID}" ] && \
    [ -n "${DETECTED_OCI_REGION}" ]
}

confirm() {
  local label="$1"
  local default_answer="${2:-y}"
  local suffix="[Y/n]"
  local answer=""

  if [ "${default_answer}" = "n" ]; then
    suffix="[y/N]"
  fi

  read -r -p "${label} ${suffix}: " answer
  answer="${answer:-${default_answer}}"

  case "${answer}" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

absolute_path() {
  local input_path="$1"

  if [[ "${input_path}" = /* ]]; then
    printf "%s" "${input_path}"
  else
    printf "%s/%s" "${ROOT_DIR}" "${input_path}"
  fi
}

dotenv_value() {
  local value="$1"
  value="${value//\'/\\\'}"
  printf "'%s'" "${value}"
}

write_env_entry() {
  local key="$1"
  local value="$2"
  printf "%s=%s\n" "${key}" "$(dotenv_value "${value}")"
}

write_env_file() {
  local tmp_file="${ENV_FILE}.tmp"

  {
    write_env_entry "COMPOSE_FILE" "${COMPOSE_FILE}"
    write_env_entry "MONITOR_PUBLIC_URL" "${MONITOR_PUBLIC_URL}"
    write_env_entry "MONITOR_WEB_BIND_ADDRESS" "${MONITOR_WEB_BIND_ADDRESS}"
    write_env_entry "MONITOR_WEB_PORT" "${MONITOR_WEB_PORT}"
    write_env_entry "MONITOR_ADMIN_USERNAME" "${MONITOR_ADMIN_USERNAME}"
    write_env_entry "MONITOR_ADMIN_PASSWORD" "${MONITOR_ADMIN_PASSWORD}"
    write_env_entry "MONITOR_COOKIE_SECURE" "${MONITOR_COOKIE_SECURE}"
    write_env_entry "MONITOR_CORS_ALLOWED_ORIGINS" "${MONITOR_CORS_ALLOWED_ORIGINS}"
    write_env_entry "OCI_AUTH_MODE" "${OCI_AUTH_MODE}"
    write_env_entry "OCI_CONFIG_PROFILE" "${OCI_CONFIG_PROFILE}"
    write_env_entry "OCI_REGION" "${OCI_REGION}"
    write_env_entry "OCI_COMPARTMENT_OCID" "${OCI_COMPARTMENT_OCID}"
    write_env_entry "OCI_TENANCY_OCID" "${OCI_TENANCY_OCID}"
    write_env_entry "OCI_CONFIG_DIR" "${OCI_CONFIG_DIR}"
    write_env_entry "MONITOR_OCI_CONNECT_TIMEOUT_MILLIS" "${MONITOR_OCI_CONNECT_TIMEOUT_MILLIS}"
    write_env_entry "MONITOR_OCI_READ_TIMEOUT_MILLIS" "${MONITOR_OCI_READ_TIMEOUT_MILLIS}"
    write_env_entry "MONITOR_LOG_LEVEL" "${MONITOR_LOG_LEVEL}"
    write_env_entry "MONITOR_OCI_SDK_LOG_LEVEL" "${MONITOR_OCI_SDK_LOG_LEVEL}"
    write_env_entry "MONITOR_SERVER_METRICS_ENABLED" "${MONITOR_SERVER_METRICS_ENABLED}"
    write_env_entry "MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS" "${MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS}"
    write_env_entry "MONITOR_SERVER_HISTORY_RETENTION_HOURS" "${MONITOR_SERVER_HISTORY_RETENTION_HOURS}"
    write_env_entry "MONITOR_WECHAT_ENABLED" "${MONITOR_WECHAT_ENABLED}"
    write_env_entry "MONITOR_WECHAT_APP_ID" "${MONITOR_WECHAT_APP_ID}"
    write_env_entry "MONITOR_WECHAT_APP_SECRET" "${MONITOR_WECHAT_APP_SECRET}"
    write_env_entry "MONITOR_WECHAT_TEMPLATE_ID" "${MONITOR_WECHAT_TEMPLATE_ID}"
    write_env_entry "MONITOR_WECHAT_OPEN_IDS" "${MONITOR_WECHAT_OPEN_IDS}"
    write_env_entry "MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED" "${MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED}"
    write_env_entry "MONITOR_WECHAT_DAILY_SUMMARY_ENABLED" "${MONITOR_WECHAT_DAILY_SUMMARY_ENABLED}"
    write_env_entry "MONITOR_WECHAT_DAILY_SUMMARY_TIME" "${MONITOR_WECHAT_DAILY_SUMMARY_TIME}"
    write_env_entry "MONITOR_WECHAT_ZONE_ID" "${MONITOR_WECHAT_ZONE_ID}"
    write_env_entry "MONITOR_SETTINGS_ENCRYPTION_KEY" "${MONITOR_SETTINGS_ENCRYPTION_KEY}"
  } > "${tmp_file}"

  if [ -f "${ENV_FILE}" ]; then
    local backup_file="${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    cp "${ENV_FILE}" "${backup_file}"
    info "已备份旧 .env 到 ${backup_file}"
  fi

  mv "${tmp_file}" "${ENV_FILE}"
  ok "已写入 ${ENV_FILE}"
}

write_oci_config() {
  local oci_dir_abs="$1"
  local config_file="${oci_dir_abs}/config"
  local existing_user=""
  local existing_fingerprint=""
  local existing_tenancy=""
  local existing_region=""

  if [ -f "${config_file}" ] && ! confirm "检测到 deploy/oci/config 已存在，是否重新生成" "n"; then
    return 0
  fi

  if [ -f "${config_file}" ]; then
    existing_user="$(awk -F '=' '$1 == "user" {print $2; exit}' "${config_file}")"
    existing_fingerprint="$(awk -F '=' '$1 == "fingerprint" {print $2; exit}' "${config_file}")"
    existing_tenancy="$(awk -F '=' '$1 == "tenancy" {print $2; exit}' "${config_file}")"
    existing_region="$(awk -F '=' '$1 == "region" {print $2; exit}' "${config_file}")"
  fi

  ask_with_hidden_default OCI_USER_OCID "OCI user OCID" "${existing_user}" true
  ask_with_hidden_default OCI_FINGERPRINT "OCI API key fingerprint" "${existing_fingerprint}" true
  ask_with_hidden_default OCI_CONFIG_TENANCY "OCI tenancy OCID" "${existing_tenancy:-${OCI_TENANCY_OCID}}" true
  ask OCI_CONFIG_REGION "OCI config region" "${existing_region:-${OCI_REGION}}" true

  cat > "${config_file}" <<EOF
[${OCI_CONFIG_PROFILE}]
user=${OCI_USER_OCID}
fingerprint=${OCI_FINGERPRINT}
tenancy=${OCI_CONFIG_TENANCY}
region=${OCI_CONFIG_REGION}
key_file=${OCI_KEY_CONTAINER_PATH}
EOF

  ok "已写入 ${config_file}"
}

copy_private_key_if_needed() {
  local oci_dir_abs="$1"
  local key_file="${oci_dir_abs}/oci_api_key.pem"
  local source_key=""

  if [ -f "${key_file}" ]; then
    ok "已找到私钥文件 ${key_file}"
    return 0
  fi

  warn "未找到 ${key_file}"
  read -r -p "输入已有 OCI 私钥路径用于复制，直接回车跳过: " source_key
  if [ -z "${source_key}" ]; then
    warn "已跳过私钥复制，启动前请手动放入 ${key_file}"
    return 0
  fi

  if [ ! -f "${source_key}" ]; then
    warn "私钥源文件不存在：${source_key}"
    return 0
  fi

  cp "${source_key}" "${key_file}"
  ok "已复制私钥到 ${key_file}"
}

fix_permissions() {
  local oci_dir_abs="$1"
  local chown_cmd=(chown -R 10001:10001 "${oci_dir_abs}")
  local chmod_dir_cmd=(chmod 700 "${oci_dir_abs}")
  local chmod_files_cmd=(chmod 600 "${oci_dir_abs}/config" "${oci_dir_abs}/oci_api_key.pem")

  if ! confirm "是否调整 deploy/oci 权限为容器用户可读" "y"; then
    warn "已跳过权限调整。建议稍后执行：sudo chown -R 10001:10001 deploy/oci && sudo chmod 700 deploy/oci && sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem"
    return 0
  fi

  if [ "$(id -u)" -eq 0 ]; then
    "${chown_cmd[@]}"
    "${chmod_dir_cmd[@]}"
    [ -f "${oci_dir_abs}/config" ] && [ -f "${oci_dir_abs}/oci_api_key.pem" ] && "${chmod_files_cmd[@]}"
  else
    sudo "${chown_cmd[@]}"
    sudo "${chmod_dir_cmd[@]}"
    if [ -f "${oci_dir_abs}/config" ] && [ -f "${oci_dir_abs}/oci_api_key.pem" ]; then
      sudo "${chmod_files_cmd[@]}"
    fi
  fi

  ok "deploy/oci 权限已调整"
}

validate_config() {
  local oci_dir_abs="$1"
  local config_file="${oci_dir_abs}/config"
  local key_file="${oci_dir_abs}/oci_api_key.pem"
  local configured_key_file=""

  if [ ! -f "${config_file}" ]; then
    warn "缺少 OCI config：${config_file}"
  else
    configured_key_file="$(awk -F '=' '$1 == "key_file" {print $2; exit}' "${config_file}")"
    if [ "${configured_key_file}" != "${OCI_KEY_CONTAINER_PATH}" ]; then
      warn "config 中 key_file 当前是 ${configured_key_file:-空}，容器部署必须改成 ${OCI_KEY_CONTAINER_PATH}"
    else
      ok "OCI config 的 key_file 路径正确"
    fi
  fi

  if [ ! -f "${key_file}" ]; then
    warn "缺少 OCI 私钥：${key_file}"
  else
    ok "OCI 私钥文件存在"
  fi
}

print_instance_principal_policy_template() {
  local monitor_instance_ocid="${DETECTED_OCI_INSTANCE_OCID}"
  local resource_scope="in compartment id ${OCI_COMPARTMENT_OCID}"

  if [ "${OCI_COMPARTMENT_OCID}" = "${OCI_TENANCY_OCID}" ]; then
    resource_scope="in tenancy"
  fi

  info "Instance Principal 还需要创建 Dynamic Group 和 Policy。"
  if [ -z "${monitor_instance_ocid}" ]; then
    read -r -p "输入当前监控服务器的 Instance OCID，用于生成可复制规则，直接回车跳过: " monitor_instance_ocid
  fi

  printf "\n=== Dynamic Group matching rule ===\n"
  if [ -n "${monitor_instance_ocid}" ]; then
    printf "instance.id = '%s'\n" "${monitor_instance_ocid}"
  else
    printf "instance.id = '替换为当前监控服务器InstanceOCID'\n"
  fi

  printf "\n=== IAM Policy statements ===\n"
  printf "Allow dynamic-group oci-arm-monitor-instances to read instance-family %s\n" "${resource_scope}"
  printf "Allow dynamic-group oci-arm-monitor-instances to read virtual-network-family %s\n" "${resource_scope}"
  printf "Allow dynamic-group oci-arm-monitor-instances to read metrics %s\n" "${resource_scope}"
  printf "Allow dynamic-group oci-arm-monitor-instances to read usage-report in tenancy\n\n"

  if [ -n "${monitor_instance_ocid}" ]; then
    printf "=== OCI Cloud Shell command ===\n"
    printf "bash scripts/oci-cloud-shell-setup.sh \\\n"
    printf "  --tenancy-id '%s' \\\n" "${OCI_TENANCY_OCID}"
    printf "  --instance-id '%s' \\\n" "${monitor_instance_ocid}"
    printf "  --resource-compartment-id '%s'\n\n" "${OCI_COMPARTMENT_OCID}"
  fi
}

main() {
  info "OCI ARM Monitor 快捷部署配置"
  info "项目目录：${ROOT_DIR}"

  collect_access_settings
  local admin_username_default
  admin_username_default="$(read_env_value MONITOR_ADMIN_USERNAME || true)"
  admin_username_default="${admin_username_default:-admin}"
  ask MONITOR_ADMIN_USERNAME "管理员用户名" "${admin_username_default}" true
  ask_secret MONITOR_ADMIN_PASSWORD "管理员密码" "$(read_env_value MONITOR_ADMIN_PASSWORD || true)" true

  local auth_mode_default
  auth_mode_default="$(read_env_value OCI_AUTH_MODE || true)"
  auth_mode_default="${auth_mode_default:-instance_principal}"
  while true; do
    ask OCI_AUTH_MODE "OCI 认证模式，Oracle 机器推荐 instance_principal，非 OCI 服务器填 config_file" "${auth_mode_default}" true
    if OCI_AUTH_MODE="$(normalize_auth_mode "${OCI_AUTH_MODE}")"; then
      break
    fi
    warn "OCI_AUTH_MODE 只支持 instance_principal 或 config_file。"
  done

  if [ "${OCI_AUTH_MODE}" = "instance_principal" ] && detect_instance_metadata; then
    ok "已从 OCI Instance Metadata 自动读取 Instance、Tenancy、Compartment 和 Region。"
  elif [ "${OCI_AUTH_MODE}" = "instance_principal" ]; then
    warn "未能读取 OCI Instance Metadata，将继续手工输入 OCI 配置。"
  fi

  local config_profile_default
  config_profile_default="$(read_env_value OCI_CONFIG_PROFILE || true)"
  config_profile_default="${config_profile_default:-DEFAULT}"
  if [ "${OCI_AUTH_MODE}" = "config_file" ]; then
    ask OCI_CONFIG_PROFILE "OCI config profile" "${config_profile_default}" true
  else
    OCI_CONFIG_PROFILE="${config_profile_default}"
  fi
  local region_default
  region_default="$(read_env_value OCI_REGION || true)"
  region_default="${region_default:-${DETECTED_OCI_REGION}}"
  ask OCI_REGION "OCI region，例如 us-ashburn-1" "${region_default}" true

  local compartment_default
  compartment_default="$(read_env_value OCI_COMPARTMENT_OCID || true)"
  compartment_default="${compartment_default:-${DETECTED_OCI_COMPARTMENT_OCID}}"
  ask_with_hidden_default OCI_COMPARTMENT_OCID "被监控资源所在 compartment OCID" "${compartment_default}" true

  if [ "${OCI_AUTH_MODE}" = "instance_principal" ]; then
    local tenancy_default
    tenancy_default="$(read_env_value OCI_TENANCY_OCID || true)"
    tenancy_default="${tenancy_default:-${DETECTED_OCI_TENANCY_OCID}}"
    ask_with_hidden_default OCI_TENANCY_OCID "tenancy OCID，Instance Principal 费用同步必填" "${tenancy_default}" true
  else
    ask_with_hidden_default OCI_TENANCY_OCID "tenancy OCID，可留空" "$(read_env_value OCI_TENANCY_OCID || true)" false
  fi

  local config_dir_default
  config_dir_default="$(read_env_value OCI_CONFIG_DIR || true)"
  config_dir_default="${config_dir_default:-./deploy/oci}"
  if [ "${OCI_AUTH_MODE}" = "config_file" ]; then
    ask OCI_CONFIG_DIR "OCI config 宿主机目录" "${config_dir_default}" true
  else
    OCI_CONFIG_DIR="${config_dir_default}"
  fi

  MONITOR_OCI_CONNECT_TIMEOUT_MILLIS="$(read_env_value MONITOR_OCI_CONNECT_TIMEOUT_MILLIS || true)"
  MONITOR_OCI_CONNECT_TIMEOUT_MILLIS="${MONITOR_OCI_CONNECT_TIMEOUT_MILLIS:-10000}"
  MONITOR_OCI_READ_TIMEOUT_MILLIS="$(read_env_value MONITOR_OCI_READ_TIMEOUT_MILLIS || true)"
  MONITOR_OCI_READ_TIMEOUT_MILLIS="${MONITOR_OCI_READ_TIMEOUT_MILLIS:-60000}"
  MONITOR_LOG_LEVEL="$(read_env_value MONITOR_LOG_LEVEL || true)"
  MONITOR_LOG_LEVEL="${MONITOR_LOG_LEVEL:-INFO}"
  MONITOR_OCI_SDK_LOG_LEVEL="$(read_env_value MONITOR_OCI_SDK_LOG_LEVEL || true)"
  MONITOR_OCI_SDK_LOG_LEVEL="${MONITOR_OCI_SDK_LOG_LEVEL:-WARN}"
  MONITOR_SERVER_METRICS_ENABLED="$(read_env_value MONITOR_SERVER_METRICS_ENABLED || true)"
  MONITOR_SERVER_METRICS_ENABLED="${MONITOR_SERVER_METRICS_ENABLED:-true}"
  MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS="$(read_env_value MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS || true)"
  MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS="${MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS:-15000}"
  MONITOR_SERVER_HISTORY_RETENTION_HOURS="$(read_env_value MONITOR_SERVER_HISTORY_RETENTION_HOURS || true)"
  MONITOR_SERVER_HISTORY_RETENTION_HOURS="${MONITOR_SERVER_HISTORY_RETENTION_HOURS:-72}"

  collect_wechat_settings
  MONITOR_SETTINGS_ENCRYPTION_KEY="$(read_env_value MONITOR_SETTINGS_ENCRYPTION_KEY || true)"
  ensure_settings_encryption_key

  write_env_file

  local oci_dir_abs
  oci_dir_abs="$(absolute_path "${OCI_CONFIG_DIR}")"
  mkdir -p "${oci_dir_abs}"

  if [ "${OCI_AUTH_MODE}" = "instance_principal" ]; then
    print_instance_principal_policy_template
  elif confirm "是否现在生成/检查 OCI config 和私钥" "y"; then
    write_oci_config "${oci_dir_abs}"
    copy_private_key_if_needed "${oci_dir_abs}"
    fix_permissions "${oci_dir_abs}"
  fi

  if [ "${OCI_AUTH_MODE}" = "config_file" ]; then
    validate_config "${oci_dir_abs}"
  fi

  info "下一步可以执行："
  printf "  docker compose config --quiet\n"
  printf "  docker compose up -d --build\n"
  printf "  docker compose ps\n"
  printf "  docker compose logs -f\n"
  printf "\n公开访问地址：%s\n" "${MONITOR_ACCESS_URL}"
  printf "Nginx/OpenResty 反向代理目标：http://%s:%s\n" "${MONITOR_WEB_BIND_ADDRESS}" "${MONITOR_WEB_PORT}"
}

if [ "${INIT_DEPLOY_LIB_ONLY:-false}" != "true" ]; then
  main "$@"
fi
