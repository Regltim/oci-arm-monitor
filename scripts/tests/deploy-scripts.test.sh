#!/usr/bin/env bash

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_DIR="${ROOT_DIR}/scripts/tests/fixtures"
FAILURES=0

fail() {
  printf "[FAIL] %s\n" "$1" >&2
  return 1
}

assert_contains() {
  local actual="$1"
  local expected="$2"

  if [[ "${actual}" != *"${expected}"* ]]; then
    fail "输出中缺少：${expected}"
  fi
}

assert_not_contains() {
  local actual="$1"
  local unexpected="$2"

  if [[ "${actual}" == *"${unexpected}"* ]]; then
    fail "输出中不应包含：${unexpected}"
  fi
}

assert_equals() {
  local actual="$1"
  local expected="$2"

  if [ "${actual}" != "${expected}" ]; then
    fail "期望 ${expected}，实际 ${actual}"
  fi
}

run_test() {
  local name="$1"
  shift

  if "$@"; then
    printf "[PASS] %s\n" "${name}"
  else
    FAILURES=$((FAILURES + 1))
    printf "[FAIL] %s\n" "${name}" >&2
  fi
}

test_cloud_shell_script_supports_dry_run() {
  local output

  output="$(bash "${ROOT_DIR}/scripts/oci-cloud-shell-setup.sh" \
    --tenancy-id "ocid1.tenancy.oc1..exampletenancy" \
    --instance-id "ocid1.instance.oc1.test.exampleinstance" \
    --resource-compartment-id "ocid1.compartment.oc1..examplecompartment" \
    --dry-run 2>&1)" || return 1

  assert_contains "${output}" "instance.id = 'ocid1.instance.oc1.test.exampleinstance'"
  assert_contains "${output}" "read instance-family in compartment id ocid1.compartment.oc1..examplecompartment"
  assert_contains "${output}" "read usage-report in tenancy"
}

test_cloud_shell_script_supports_root_compartment() {
  local output
  local tenancy_id="ocid1.tenancy.oc1..exampletenancy"

  output="$(bash "${ROOT_DIR}/scripts/oci-cloud-shell-setup.sh" \
    --tenancy-id "${tenancy_id}" \
    --instance-id "ocid1.instance.oc1.test.exampleinstance" \
    --resource-compartment-id "${tenancy_id}" \
    --dry-run 2>&1)" || return 1

  assert_contains "${output}" "read instance-family in tenancy" || return 1
  assert_contains "${output}" "read virtual-network-family in tenancy" || return 1
  assert_contains "${output}" "read metrics in tenancy" || return 1
  assert_not_contains "${output}" "in compartment id ${tenancy_id}"
}

test_instance_metadata_is_auto_detected() {
  local output
  local expected
  local tmp_metadata_file
  local exit_code

  tmp_metadata_file="$(mktemp "${TMPDIR:-/tmp}/oci-instance-metadata.XXXXXX")"
  cp "${FIXTURE_DIR}/instance-metadata.json" "${tmp_metadata_file}"

  output="$(
    INIT_DEPLOY_LIB_ONLY=true \
    OCI_INSTANCE_METADATA_URL="file://${tmp_metadata_file}" \
      bash -c '
        source "$1"
        detect_instance_metadata
        printf "%s|%s|%s|%s" \
          "${DETECTED_OCI_INSTANCE_OCID}" \
          "${DETECTED_OCI_COMPARTMENT_OCID}" \
          "${DETECTED_OCI_TENANCY_OCID}" \
          "${DETECTED_OCI_REGION}"
      ' _ "${ROOT_DIR}/scripts/init-deploy.sh"
  )"
  exit_code=$?
  rm -f "${tmp_metadata_file}"

  if [ "${exit_code}" -ne 0 ]; then
    return 1
  fi

  expected="ocid1.instance.oc1.test.exampleinstance|ocid1.compartment.oc1..examplecompartment|ocid1.tenancy.oc1..exampletenancy|us-example-1"
  assert_equals "${output}" "${expected}"
}

test_http_access_mode_generates_same_origin_settings() {
  local output

  output="$(INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    configure_http_access "203.0.113.10" "8080"
    printf "%s|%s|%s|%s|%s|%s" \
      "${COMPOSE_FILE}" "${MONITOR_ACCESS_MODE}" "${MONITOR_SITE_ADDRESS}" \
      "${MONITOR_CORS_ALLOWED_ORIGINS}" "${MONITOR_COOKIE_SECURE}" "${MONITOR_ACCESS_URL}"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh")" || return 1

  assert_equals "${output}" \
    "docker-compose.yml:docker-compose.http.yml|http|:8080|http://203.0.113.10:8080|false|http://203.0.113.10:8080"
}

test_https_access_mode_generates_secure_settings() {
  local output

  output="$(INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    configure_https_access "monitor.example.com"
    printf "%s|%s|%s|%s|%s|%s" \
      "${COMPOSE_FILE}" "${MONITOR_ACCESS_MODE}" "${MONITOR_SITE_ADDRESS}" \
      "${MONITOR_CORS_ALLOWED_ORIGINS}" "${MONITOR_COOKIE_SECURE}" "${MONITOR_ACCESS_URL}"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh")" || return 1

  assert_equals "${output}" \
    "docker-compose.yml:docker-compose.https.yml|https|monitor.example.com|https://monitor.example.com|true|https://monitor.example.com"
}

test_access_mode_validation_rejects_invalid_values() {
  INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    validate_http_host "203.0.113.10"
    validate_http_host "monitor.example.com"
    validate_http_port "1"
    validate_http_port "65535"
    validate_https_domain "monitor.example.com"
    ! validate_http_host "999.1.1.1"
    ! validate_http_host "https://monitor.example.com"
    ! validate_http_host "bad-.example.com"
    ! validate_http_port "0"
    ! validate_http_port "65536"
    ! validate_https_domain "https://monitor.example.com"
    ! validate_https_domain "monitor.example.com:443"
    ! validate_https_domain "monitor.example.com/path"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh"
}

test_existing_http_host_is_not_echoed_by_access_prompt() {
  local output
  local private_host="private-monitor.example.net"
  local tmp_dir

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-access-settings.XXXXXX")"
  mkdir -p "${tmp_dir}/scripts"
  cp "${ROOT_DIR}/scripts/init-deploy.sh" "${tmp_dir}/scripts/init-deploy.sh"
  {
    printf "MONITOR_ACCESS_MODE='http'\n"
    printf "MONITOR_HTTP_HOST='%s'\n" "${private_host}"
    printf "MONITOR_HTTP_PORT='8080'\n"
  } >"${tmp_dir}/.env"

  output="$(
    printf '\n\n\n' | INIT_DEPLOY_LIB_ONLY=true bash -c '
      source "$1"
      collect_access_settings
      printf "selected=%s" "${MONITOR_HTTP_HOST}"
    ' _ "${tmp_dir}/scripts/init-deploy.sh" 2>&1
  )" || {
    rm -rf "${tmp_dir}"
    return 1
  }

  rm -rf "${tmp_dir}"
  assert_contains "${output}" "已设置，回车保留" || return 1
  assert_contains "${output}" "selected=${private_host}" || return 1
  assert_not_contains "${output%selected=*}" "${private_host}"
}

test_instance_principal_init_uses_safe_defaults() {
  local tmp_dir
  local output
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-init-deploy.XXXXXX")"
  mkdir -p "${tmp_dir}/scripts"
  cp "${ROOT_DIR}/scripts/init-deploy.sh" "${tmp_dir}/scripts/init-deploy.sh"
  cp "${FIXTURE_DIR}/instance-metadata.json" "${tmp_dir}/instance-metadata.json"

  output="$(
    printf '\n203.0.113.10\n\n\nexample-strong-password\n\n\n\n\n' | \
      OCI_INSTANCE_METADATA_URL="file://${tmp_dir}/instance-metadata.json" \
      bash "${tmp_dir}/scripts/init-deploy.sh" 2>&1
  )"
  exit_code=$?

  if [ "${exit_code}" -ne 0 ]; then
    rm -rf "${tmp_dir}"
    return 1
  fi

  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_ADMIN_USERNAME='admin'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "COMPOSE_FILE='docker-compose.yml:docker-compose.http.yml'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_ACCESS_MODE='http'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_CORS_ALLOWED_ORIGINS='http://203.0.113.10:8080'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_COOKIE_SECURE='false'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "${output}" "启动后访问地址：http://203.0.113.10:8080" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "OCI_REGION='us-example-1'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "${output}" "--instance-id 'ocid1.instance.oc1.test.exampleinstance'" || {
    rm -rf "${tmp_dir}"
    return 1
  }

  rm -rf "${tmp_dir}"
}

test_init_deploy_root_compartment_uses_tenancy_scope() {
  local output
  local tenancy_id="ocid1.tenancy.oc1..exampletenancy"

  output="$(
    INIT_DEPLOY_LIB_ONLY=true bash -c '
      source "$1"
      DETECTED_OCI_INSTANCE_OCID="ocid1.instance.oc1.test.exampleinstance"
      OCI_TENANCY_OCID="$2"
      OCI_COMPARTMENT_OCID="$2"
      print_instance_principal_policy_template
    ' _ "${ROOT_DIR}/scripts/init-deploy.sh" "${tenancy_id}" 2>&1
  )" || return 1

  assert_contains "${output}" "read instance-family in tenancy" || return 1
  assert_not_contains "${output}" "in compartment id ${tenancy_id}"
}

test_existing_private_value_is_not_echoed() {
  local output
  local private_origin="https://private-monitor.example.net"

  output="$(
    printf '\n' | INIT_DEPLOY_LIB_ONLY=true bash -c '
      source "$1"
      ask_with_hidden_default SELECTED_ORIGIN "面板访问域名" "$2" true
      printf "selected=%s" "${SELECTED_ORIGIN}"
    ' _ "${ROOT_DIR}/scripts/init-deploy.sh" "${private_origin}" 2>&1
  )" || return 1

  assert_contains "${output}" "已设置，回车保留" || return 1
  assert_contains "${output}" "selected=${private_origin}" || return 1
  assert_not_contains "${output%selected=*}" "${private_origin}"
}

test_public_release_check_rejects_sensitive_content() {
  local tmp_dir
  local output
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-public-check.XXXXXX")"

  git -C "${tmp_dir}" init -q
  printf "%s%s\n" "-----BEGIN PRIVATE" " KEY-----" > "${tmp_dir}/secret.txt"
  git -C "${tmp_dir}" add secret.txt

  output="$(bash "${ROOT_DIR}/scripts/check-public-release.sh" --root "${tmp_dir}" 2>&1)"
  exit_code=$?
  rm -rf "${tmp_dir}"

  if [ "${exit_code}" -eq 0 ]; then
    return 1
  fi

  assert_contains "${output}" "私钥内容"
}

test_public_release_check_accepts_placeholder_data() {
  local tmp_dir
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-public-check.XXXXXX")"

  git -C "${tmp_dir}" init -q
  printf "%s\n" "OCI_TENANCY_OCID=ocid1.tenancy.oc1..replace-with-your-tenancy-ocid" > "${tmp_dir}/.env.example"
  git -C "${tmp_dir}" add .env.example

  bash "${ROOT_DIR}/scripts/check-public-release.sh" --root "${tmp_dir}"
  exit_code=$?
  rm -rf "${tmp_dir}"

  return "${exit_code}"
}

test_public_release_check_rejects_untracked_sensitive_file() {
  local tmp_dir
  local output
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-public-check.XXXXXX")"
  git -C "${tmp_dir}" init -q
  printf "%s\n" "sensitive placeholder" > "${tmp_dir}/credentials.pem"

  output="$(bash "${ROOT_DIR}/scripts/check-public-release.sh" --root "${tmp_dir}" 2>&1)"
  exit_code=$?
  rm -rf "${tmp_dir}"

  if [ "${exit_code}" -eq 0 ]; then
    return 1
  fi

  assert_contains "${output}" "敏感文件或生成产物"
}

run_test "Cloud Shell IAM dry-run" test_cloud_shell_script_supports_dry_run
run_test "Cloud Shell 支持根 Compartment" test_cloud_shell_script_supports_root_compartment
run_test "实例 Metadata 自动识别" test_instance_metadata_is_auto_detected
run_test "HTTP 访问模式生成同源配置" test_http_access_mode_generates_same_origin_settings
run_test "HTTPS 访问模式生成安全配置" test_https_access_mode_generates_secure_settings
run_test "访问模式参数校验" test_access_mode_validation_rejects_invalid_values
run_test "已有 HTTP 主机不在提示中回显" test_existing_http_host_is_not_echoed_by_access_prompt
run_test "Instance Principal 初始化默认值" test_instance_principal_init_uses_safe_defaults
run_test "初始化脚本支持根 Compartment" test_init_deploy_root_compartment_uses_tenancy_scope
run_test "已有私有配置不回显" test_existing_private_value_is_not_echoed
run_test "开源检查拦截私钥" test_public_release_check_rejects_sensitive_content
run_test "开源检查允许占位数据" test_public_release_check_accepts_placeholder_data
run_test "开源检查拦截未跟踪敏感文件" test_public_release_check_rejects_untracked_sensitive_file

if [ "${FAILURES}" -gt 0 ]; then
  printf "共 %s 项测试失败。\n" "${FAILURES}" >&2
  exit 1
fi

printf "全部部署脚本测试通过。\n"
