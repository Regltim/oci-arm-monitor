#!/usr/bin/env bash

set -Eeuo pipefail

DYNAMIC_GROUP_NAME="oci-arm-monitor-instances"
POLICY_NAME="oci-arm-monitor-readonly"
TENANCY_ID="${OCI_TENANCY_OCID:-${OCI_TENANCY_ID:-}}"
INSTANCE_ID=""
RESOURCE_COMPARTMENT_ID=""
DRY_RUN=false

info() {
  printf "\033[1;34m[INFO]\033[0m %s\n" "$1"
}

ok() {
  printf "\033[1;32m[OK]\033[0m %s\n" "$1"
}

fail() {
  printf "\033[1;31m[ERROR]\033[0m %s\n" "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
用法：
  bash scripts/oci-cloud-shell-setup.sh [选项]

选项：
  --tenancy-id OCID                 Tenancy OCID；省略时尝试读取 OCI CLI 配置
  --instance-id OCID                运行监控服务的 OCI Instance OCID
  --resource-compartment-id OCID    被监控资源所在的 Compartment OCID；根 Compartment 传 Tenancy OCID
  --dynamic-group-name NAME         Dynamic Group 名称
  --policy-name NAME                IAM Policy 名称
  --dry-run                         只显示规则，不调用 OCI API
  -h, --help                        显示帮助

建议在 OCI Console 的 Cloud Shell 中执行。当前登录用户需要具备管理
Dynamic Group 和 Policy 的权限。
EOF
}

read_config_tenancy() {
  local config_file="${OCI_CONFIG_FILE:-${HOME}/.oci/config}"

  if [ ! -f "${config_file}" ]; then
    return 0
  fi

  awk -F '=' '
    /^[[:space:]]*\[DEFAULT\][[:space:]]*$/ { in_default = 1; next }
    /^[[:space:]]*\[/ { in_default = 0 }
    in_default && $1 ~ /^[[:space:]]*tenancy[[:space:]]*$/ {
      value = $2
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "${config_file}"
}

ask_required() {
  local var_name="$1"
  local label="$2"
  local value=""

  while [ -z "${value}" ]; do
    read -r -p "${label}: " value
  done

  printf -v "${var_name}" "%s" "${value}"
}

validate_resource_name() {
  local label="$1"
  local value="$2"

  if [[ ! "${value}" =~ ^[A-Za-z0-9._-]+$ ]]; then
    fail "${label} 只能包含字母、数字、点、下划线和连字符。"
  fi
}

validate_ocid() {
  local label="$1"
  local value="$2"
  local prefix="$3"

  if [[ "${value}" != ocid1."${prefix}".* ]]; then
    fail "${label} 格式不正确，应以 ocid1.${prefix}. 开头。"
  fi
}

validate_resource_compartment_id() {
  if [ "${RESOURCE_COMPARTMENT_ID}" = "${TENANCY_ID}" ]; then
    return 0
  fi

  validate_ocid "Compartment OCID" "${RESOURCE_COMPARTMENT_ID}" "compartment"
}

build_policy_statements() {
  local resource_scope="in compartment id ${RESOURCE_COMPARTMENT_ID}"

  if [ "${RESOURCE_COMPARTMENT_ID}" = "${TENANCY_ID}" ]; then
    resource_scope="in tenancy"
  fi

  printf "Allow dynamic-group %s to read instance-family %s\n" "${DYNAMIC_GROUP_NAME}" "${resource_scope}"
  printf "Allow dynamic-group %s to read virtual-network-family %s\n" "${DYNAMIC_GROUP_NAME}" "${resource_scope}"
  printf "Allow dynamic-group %s to read metrics %s\n" "${DYNAMIC_GROUP_NAME}" "${resource_scope}"
  printf "Allow dynamic-group %s to read usage-report in tenancy\n" "${DYNAMIC_GROUP_NAME}"
}

print_preview() {
  printf "\n=== Dynamic Group ===\n"
  printf "Name: %s\n" "${DYNAMIC_GROUP_NAME}"
  printf "Matching rule: instance.id = '%s'\n" "${INSTANCE_ID}"
  printf "\n=== IAM Policy ===\n"
  printf "Name: %s\n" "${POLICY_NAME}"
  build_policy_statements
  printf "\n"
}

find_resource_id() {
  local resource_type="$1"
  local resource_name="$2"
  local query

  query="data[?name=='${resource_name}'].id | [0]"
  oci iam "${resource_type}" list \
    --compartment-id "${TENANCY_ID}" \
    --all \
    --query "${query}" \
    --raw-output
}

upsert_dynamic_group() {
  local matching_rule="instance.id = '${INSTANCE_ID}'"
  local dynamic_group_id

  dynamic_group_id="$(find_resource_id dynamic-group "${DYNAMIC_GROUP_NAME}")"
  if [ -z "${dynamic_group_id}" ] || [ "${dynamic_group_id}" = "null" ]; then
    info "正在创建 Dynamic Group：${DYNAMIC_GROUP_NAME}"
    oci iam dynamic-group create \
      --compartment-id "${TENANCY_ID}" \
      --name "${DYNAMIC_GROUP_NAME}" \
      --description "OCI ARM monitor instance principals" \
      --matching-rule "${matching_rule}" \
      --wait-for-state ACTIVE \
      --query 'data.id' \
      --raw-output >/dev/null
  else
    info "Dynamic Group 已存在，正在同步 matching rule。"
    oci iam dynamic-group update \
      --dynamic-group-id "${dynamic_group_id}" \
      --description "OCI ARM monitor instance principals" \
      --matching-rule "${matching_rule}" \
      --force \
      --wait-for-state ACTIVE >/dev/null
  fi

  ok "Dynamic Group 已就绪。"
}

upsert_policy() {
  local policy_id
  local statements_json

  statements_json="$(build_policy_statements | jq -R . | jq -s .)"
  policy_id="$(find_resource_id policy "${POLICY_NAME}")"

  if [ -z "${policy_id}" ] || [ "${policy_id}" = "null" ]; then
    info "正在创建 IAM Policy：${POLICY_NAME}"
    oci iam policy create \
      --compartment-id "${TENANCY_ID}" \
      --name "${POLICY_NAME}" \
      --description "Read-only access for OCI ARM monitor" \
      --statements "${statements_json}" \
      --wait-for-state ACTIVE \
      --query 'data.id' \
      --raw-output >/dev/null
  else
    info "IAM Policy 已存在，正在同步只读策略。"
    oci iam policy update \
      --policy-id "${policy_id}" \
      --description "Read-only access for OCI ARM monitor" \
      --statements "${statements_json}" \
      --force \
      --wait-for-state ACTIVE >/dev/null
  fi

  ok "IAM Policy 已就绪。"
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --tenancy-id)
        [ "$#" -ge 2 ] || fail "--tenancy-id 缺少参数。"
        TENANCY_ID="$2"
        shift 2
        ;;
      --instance-id)
        [ "$#" -ge 2 ] || fail "--instance-id 缺少参数。"
        INSTANCE_ID="$2"
        shift 2
        ;;
      --resource-compartment-id)
        [ "$#" -ge 2 ] || fail "--resource-compartment-id 缺少参数。"
        RESOURCE_COMPARTMENT_ID="$2"
        shift 2
        ;;
      --dynamic-group-name)
        [ "$#" -ge 2 ] || fail "--dynamic-group-name 缺少参数。"
        DYNAMIC_GROUP_NAME="$2"
        shift 2
        ;;
      --policy-name)
        [ "$#" -ge 2 ] || fail "--policy-name 缺少参数。"
        POLICY_NAME="$2"
        shift 2
        ;;
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        fail "未知参数：$1"
        ;;
    esac
  done
}

main() {
  parse_args "$@"

  if [ -z "${TENANCY_ID}" ]; then
    TENANCY_ID="$(read_config_tenancy)"
  fi

  [ -n "${TENANCY_ID}" ] || ask_required TENANCY_ID "Tenancy OCID"
  [ -n "${INSTANCE_ID}" ] || ask_required INSTANCE_ID "监控服务器 Instance OCID"
  [ -n "${RESOURCE_COMPARTMENT_ID}" ] || ask_required RESOURCE_COMPARTMENT_ID "被监控资源 Compartment OCID；根 Compartment 可填 Tenancy OCID"

  validate_resource_name "Dynamic Group 名称" "${DYNAMIC_GROUP_NAME}"
  validate_resource_name "Policy 名称" "${POLICY_NAME}"
  validate_ocid "Tenancy OCID" "${TENANCY_ID}" "tenancy"
  validate_ocid "Instance OCID" "${INSTANCE_ID}" "instance"
  validate_resource_compartment_id

  print_preview
  if [ "${DRY_RUN}" = "true" ]; then
    ok "dry-run 完成，未调用 OCI API。"
    return 0
  fi

  command -v oci >/dev/null 2>&1 || fail "未找到 OCI CLI。请在 OCI Cloud Shell 中运行本脚本。"
  command -v jq >/dev/null 2>&1 || fail "未找到 jq，无法生成 Policy statements JSON。"

  upsert_dynamic_group
  upsert_policy

  ok "Instance Principal IAM 配置完成。权限生效通常需要几分钟。"
}

main "$@"
