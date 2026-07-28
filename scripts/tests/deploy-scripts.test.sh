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

test_https_public_origin_generates_secure_proxy_settings() {
  local output

  output="$(INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    configure_public_access "https://monitor.example.com" "28461"
    printf "%s|%s|%s|%s|%s|%s|%s" \
      "${COMPOSE_FILE}" "${MONITOR_PUBLIC_URL}" "${MONITOR_WEB_BIND_ADDRESS}" \
      "${MONITOR_WEB_PORT}" "${MONITOR_CORS_ALLOWED_ORIGINS}" \
      "${MONITOR_COOKIE_SECURE}" "${MONITOR_ACCESS_URL}"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh")" || return 1

  assert_equals "${output}" \
    "docker-compose.yml|https://monitor.example.com|127.0.0.1|28461|https://monitor.example.com|true|https://monitor.example.com"
}

test_http_public_origin_generates_non_secure_proxy_settings() {
  local output

  output="$(INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    configure_public_access "http://monitor.example.com:8088" "28461"
    printf "%s|%s|%s|%s" \
      "${MONITOR_PUBLIC_URL}" "${MONITOR_WEB_PORT}" \
      "${MONITOR_CORS_ALLOWED_ORIGINS}" "${MONITOR_COOKIE_SECURE}"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh")" || return 1

  assert_equals "${output}" \
    "http://monitor.example.com:8088|28461|http://monitor.example.com:8088|false"
}

test_public_origin_and_web_port_validation_rejects_invalid_values() {
  INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    validate_public_origin "https://monitor.example.com"
    validate_public_origin "http://203.0.113.10:8088"
    validate_web_port "1024"
    validate_web_port "65535"
    ! validate_public_origin "monitor.example.com"
    ! validate_public_origin "https://monitor.example.com/"
    ! validate_public_origin "https://monitor.example.com/path"
    ! validate_public_origin "https://monitor.example.com?debug=true"
    ! validate_public_origin "https://user@localhost"
    ! validate_public_origin "https://monitor.example.com:0"
    ! validate_public_origin "https://monitor.example.com:65536"
    ! validate_public_origin "https://monitor.example.com:not-a-port"
    ! validate_public_origin "https://999.1.1.1"
    ! validate_web_port "80"
    ! validate_web_port "65536"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh"
}

test_existing_public_origin_is_not_echoed_by_access_prompt() {
  local output
  local private_origin="https://private-monitor.example.net"
  local tmp_dir

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-access-settings.XXXXXX")"
  mkdir -p "${tmp_dir}/scripts"
  cp "${ROOT_DIR}/scripts/init-deploy.sh" "${tmp_dir}/scripts/init-deploy.sh"
  {
    printf "MONITOR_PUBLIC_URL='%s'\n" "${private_origin}"
    printf "MONITOR_WEB_PORT='28461'\n"
  } >"${tmp_dir}/.env"

  output="$(
    printf '\n\n' | INIT_DEPLOY_LIB_ONLY=true bash -c '
      source "$1"
      collect_access_settings
      printf "selected=%s|%s" "${MONITOR_PUBLIC_URL}" "${MONITOR_WEB_PORT}"
    ' _ "${tmp_dir}/scripts/init-deploy.sh" 2>&1
  )" || {
    rm -rf "${tmp_dir}"
    return 1
  }

  rm -rf "${tmp_dir}"
  assert_contains "${output}" "已设置，回车保留" || return 1
  assert_contains "${output}" "selected=${private_origin}|28461" || return 1
  assert_not_contains "${output%selected=*}" "${private_origin}"
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
    printf 'https://monitor.example.com\n\n\nexample-strong-password\n\n\n\n\nn\n' | \
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
  assert_contains "$(<"${tmp_dir}/.env")" "COMPOSE_FILE='docker-compose.yml'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_PUBLIC_URL='https://monitor.example.com'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_WEB_BIND_ADDRESS='127.0.0.1'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_WEB_PORT='28461'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_CORS_ALLOWED_ORIGINS='https://monitor.example.com'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "$(<"${tmp_dir}/.env")" "MONITOR_COOKIE_SECURE='true'" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_not_contains "$(<"${tmp_dir}/.env")" "MONITOR_ACCESS_MODE=" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_not_contains "$(<"${tmp_dir}/.env")" "MONITOR_SITE_ADDRESS=" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "${output}" "公开访问地址：https://monitor.example.com" || {
    rm -rf "${tmp_dir}"
    return 1
  }
  assert_contains "${output}" "Nginx/OpenResty 反向代理目标：http://127.0.0.1:28461" || {
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

test_wechat_notifications_can_be_disabled_without_echoing_existing_credentials() {
  local output
  local tmp_dir
  local private_secret="existing-private-secret"
  local private_open_id="existing-private-openid"
  local private_cost_template="existing-private-cost-template"

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-wechat-settings.XXXXXX")"
  mkdir -p "${tmp_dir}/scripts"
  cp "${ROOT_DIR}/scripts/init-deploy.sh" "${tmp_dir}/scripts/init-deploy.sh"
  {
    printf "MONITOR_WECHAT_ENABLED='true'\n"
    printf "MONITOR_WECHAT_APP_ID='wx_existing_app'\n"
    printf "MONITOR_WECHAT_APP_SECRET='%s'\n" "${private_secret}"
    printf "MONITOR_WECHAT_TEMPLATE_ID='existing-template'\n"
    printf "MONITOR_WECHAT_COST_TEMPLATE_ID='%s'\n" "${private_cost_template}"
    printf "MONITOR_WECHAT_OPEN_IDS='%s'\n" "${private_open_id}"
  } >"${tmp_dir}/.env"

  output="$(
    printf 'n\n' | INIT_DEPLOY_LIB_ONLY=true bash -c '
      source "$1"
      collect_wechat_settings
      printf "selected=%s|%s|%s" \
        "${MONITOR_WECHAT_ENABLED}" \
        "${MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED}" \
        "${MONITOR_WECHAT_DAILY_SUMMARY_ENABLED}"
    ' _ "${tmp_dir}/scripts/init-deploy.sh" 2>&1
  )" || {
    rm -rf "${tmp_dir}"
    return 1
  }

  rm -rf "${tmp_dir}"
  assert_contains "${output}" "selected=false|true|false" || return 1
  assert_not_contains "${output}" "AppSecret:" || return 1
  assert_not_contains "${output}" "接收人 OpenID:" || return 1
  assert_not_contains "${output}" "${private_secret}" || return 1
  assert_not_contains "${output}" "${private_cost_template}" || return 1
  assert_not_contains "${output}" "${private_open_id}"
}

test_wechat_notifications_collects_immediate_and_daily_policy() {
  local output
  local tmp_dir

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-wechat-settings.XXXXXX")"
  mkdir -p "${tmp_dir}/scripts"
  cp "${ROOT_DIR}/scripts/init-deploy.sh" "${tmp_dir}/scripts/init-deploy.sh"

  output="$(
    printf 'y\nwx_example_app\nexample-secret\ntemplate_example_status\ntemplate_example_cost\nopenid_example_1,openid_example_2\n\ny\n21:30\n\n\n\n' | \
      MONITOR_PUBLIC_URL=https://monitor.example.com INIT_DEPLOY_LIB_ONLY=true bash -c '
        source "$1"
        collect_wechat_settings
        printf "selected=%s|%s|%s|%s|%s|%s|%s|%s|%s|%s" \
          "${MONITOR_WECHAT_ENABLED}" \
          "${MONITOR_WECHAT_APP_ID}" \
          "${MONITOR_WECHAT_TEMPLATE_ID}" \
          "${MONITOR_WECHAT_COST_TEMPLATE_ID}" \
          "${MONITOR_WECHAT_OPEN_IDS}" \
          "${MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED}" \
          "${MONITOR_WECHAT_DAILY_SUMMARY_ENABLED}" \
          "${MONITOR_WECHAT_DAILY_SUMMARY_TIME}@${MONITOR_WECHAT_ZONE_ID}" \
          "${MONITOR_WECHAT_DETAIL_PAGE_ENABLED}" \
          "${MONITOR_WECHAT_DETAIL_PAGE_TOKEN_TTL_DAYS}"
      ' _ "${tmp_dir}/scripts/init-deploy.sh" 2>&1
  )" || {
    rm -rf "${tmp_dir}"
    return 1
  }

  rm -rf "${tmp_dir}"
  assert_contains "${output}" "selected=true|wx_example_app|template_example_status|template_example_cost|openid_example_1,openid_example_2|true|true|21:30@Asia/Shanghai|true|1"
}

test_wechat_detail_page_rejects_invalid_ttl_and_accepts_range() {
  local output

  output="$(
    printf 'y\nwx_example_app\nexample-secret\ntemplate_example_status\ntemplate_example_cost\nopenid_example_1\n\ny\n09:00\n\n\n0\n91\n7\n' | \
      MONITOR_PUBLIC_URL=https://monitor.example.com INIT_DEPLOY_LIB_ONLY=true bash -c '
        source "$1"
        collect_wechat_settings
        printf "selected=%s|%s" \
          "${MONITOR_WECHAT_DETAIL_PAGE_ENABLED}" \
          "${MONITOR_WECHAT_DETAIL_PAGE_TOKEN_TTL_DAYS}"
      ' _ "${ROOT_DIR}/scripts/init-deploy.sh" 2>&1
  )" || return 1

  assert_contains "${output}" "免登录明细令牌有效期必须为 1 至 90 天" || return 1
  assert_contains "${output}" "selected=true|7"
}

test_wechat_detail_page_requires_https_origin() {
  local output

  output="$(
    printf 'y\nwx_example_app\nexample-secret\ntemplate_example_status\ntemplate_example_cost\nopenid_example_1\n\ny\n09:00\n\n\n' | \
      MONITOR_PUBLIC_URL=http://monitor.example.com INIT_DEPLOY_LIB_ONLY=true bash -c '
        source "$1"
        collect_wechat_settings
        printf "selected=%s" "${MONITOR_WECHAT_DETAIL_PAGE_ENABLED}"
      ' _ "${ROOT_DIR}/scripts/init-deploy.sh" 2>&1
  )" || return 1

  assert_contains "${output}" "免登录明细仅支持 HTTPS 用户访问地址" || return 1
  assert_contains "${output}" "selected=false"
}

test_caddy_disables_browser_cache_for_public_report() {
  grep -Fq 'Cache-Control "no-store"' "${ROOT_DIR}/web/Caddyfile"
}

test_wechat_cost_template_is_optional_when_daily_summary_is_disabled() {
  local output

  output="$(
    printf 'y\nwx_example_app\nexample-secret\ntemplate_example_status\n\nopenid_example_1\n\nn\n' | \
      INIT_DEPLOY_LIB_ONLY=true bash -c '
        source "$1"
        collect_wechat_settings
        printf "selected=%s|%s" \
          "${MONITOR_WECHAT_COST_TEMPLATE_ID}" \
          "${MONITOR_WECHAT_DAILY_SUMMARY_ENABLED}"
      ' _ "${ROOT_DIR}/scripts/init-deploy.sh" 2>&1
  )" || return 1

  assert_contains "${output}" "selected=|false"
}

test_wechat_daily_summary_requires_cost_template() {
  local output

  output="$(
    printf 'y\nwx_example_app\nexample-secret\ntemplate_example_status\n\nopenid_example_1\n\ny\n\ntemplate_example_cost\n21:30\n\nn\n' | \
      INIT_DEPLOY_LIB_ONLY=true bash -c '
        source "$1"
        collect_wechat_settings
        printf "selected=%s|%s" \
          "${MONITOR_WECHAT_COST_TEMPLATE_ID}" \
          "${MONITOR_WECHAT_DAILY_SUMMARY_ENABLED}"
      ' _ "${ROOT_DIR}/scripts/init-deploy.sh" 2>&1
  )" || return 1

  assert_contains "${output}" "该字段不能为空" || return 1
  assert_contains "${output}" "selected=template_example_cost|true"
}

test_existing_wechat_cost_template_is_preserved_without_echoing_it() {
  local output
  local tmp_dir
  local private_cost_template="existing-private-cost-template"

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-wechat-cost-template.XXXXXX")"
  mkdir -p "${tmp_dir}/scripts"
  cp "${ROOT_DIR}/scripts/init-deploy.sh" "${tmp_dir}/scripts/init-deploy.sh"
  {
    printf "MONITOR_WECHAT_ENABLED='true'\n"
    printf "MONITOR_WECHAT_APP_ID='wx_existing_app'\n"
    printf "MONITOR_WECHAT_APP_SECRET='existing-private-secret'\n"
    printf "MONITOR_WECHAT_TEMPLATE_ID='existing-private-status-template'\n"
    printf "MONITOR_WECHAT_COST_TEMPLATE_ID='%s'\n" "${private_cost_template}"
    printf "MONITOR_WECHAT_OPEN_IDS='existing-private-openid'\n"
    printf "MONITOR_WECHAT_DAILY_SUMMARY_ENABLED='false'\n"
  } >"${tmp_dir}/.env"

  output="$(
    printf 'y\n\n\n\n\n\n\nn\n' | INIT_DEPLOY_LIB_ONLY=true bash -c '
      source "$1"
      collect_wechat_settings
      if [ "${MONITOR_WECHAT_COST_TEMPLATE_ID}" = "$2" ]; then
        printf "selected=preserved"
      fi
    ' _ "${tmp_dir}/scripts/init-deploy.sh" "${private_cost_template}" 2>&1
  )" || {
    rm -rf "${tmp_dir}"
    return 1
  }

  rm -rf "${tmp_dir}"
  assert_contains "${output}" "费用与流量 Template ID [已设置，回车保留]" || return 1
  assert_contains "${output}" "selected=preserved" || return 1
  assert_not_contains "${output}" "${private_cost_template}"
}

test_settings_encryption_key_is_generated_and_preserved() {
  local output
  local key_length
  local key_suffix

  output="$(INIT_DEPLOY_LIB_ONLY=true bash -c '
    source "$1"
    MONITOR_SETTINGS_ENCRYPTION_KEY=""
    ensure_settings_encryption_key
    first_key="${MONITOR_SETTINGS_ENCRYPTION_KEY}"
    ensure_settings_encryption_key
    printf "%s|%s|%s" "${#first_key}" "${first_key: -1}" "${first_key}"
  ' _ "${ROOT_DIR}/scripts/init-deploy.sh")" || return 1

  output="${output##*$'\n'}"
  IFS='|' read -r key_length key_suffix _ <<<"${output}"
  assert_equals "${key_length}" "44" || return 1
  assert_equals "${key_suffix}" "="
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

test_public_release_check_rejects_wechat_credentials() {
  local tmp_dir
  local output
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-public-check.XXXXXX")"
  git -C "${tmp_dir}" init -q
  printf "%s%s\n" \
    "MONITOR_WECHAT_APP_ID=wx12345678" \
    "90abcdef" > "${tmp_dir}/.env.example"
  git -C "${tmp_dir}" add .env.example

  output="$(bash "${ROOT_DIR}/scripts/check-public-release.sh" --root "${tmp_dir}" 2>&1)"
  exit_code=$?
  rm -rf "${tmp_dir}"

  if [ "${exit_code}" -eq 0 ]; then
    return 1
  fi

  assert_contains "${output}" "疑似真实微信公众号凭据"
}

test_public_release_check_rejects_wechat_template_id() {
  local tmp_dir
  local output
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-public-check.XXXXXX")"
  git -C "${tmp_dir}" init -q
  printf "%s%s\n" \
    "MONITOR_WECHAT_TEMPLATE_ID=AbCdEfGhIjKlMnOp" \
    "QrStUvWxYz0123456789_abcDEF" > "${tmp_dir}/.env.example"
  git -C "${tmp_dir}" add .env.example

  output="$(bash "${ROOT_DIR}/scripts/check-public-release.sh" --root "${tmp_dir}" 2>&1)"
  exit_code=$?
  rm -rf "${tmp_dir}"

  if [ "${exit_code}" -eq 0 ]; then
    return 1
  fi

  assert_contains "${output}" "疑似真实微信公众号凭据"
}

test_public_release_check_rejects_wechat_cost_template_id() {
  local tmp_dir
  local output
  local exit_code

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/oci-public-check.XXXXXX")"
  git -C "${tmp_dir}" init -q
  printf "%s%s\n" \
    "MONITOR_WECHAT_COST_TEMPLATE_ID=ZyXwVuTsRqPoNmLk" \
    "JiHgFeDcBa9876543210_costABC" > "${tmp_dir}/.env.example"
  git -C "${tmp_dir}" add .env.example

  output="$(bash "${ROOT_DIR}/scripts/check-public-release.sh" --root "${tmp_dir}" 2>&1)"
  exit_code=$?
  rm -rf "${tmp_dir}"

  if [ "${exit_code}" -eq 0 ]; then
    return 1
  fi

  assert_contains "${output}" "疑似真实微信公众号凭据"
}

run_test "Cloud Shell IAM dry-run" test_cloud_shell_script_supports_dry_run
run_test "Cloud Shell 支持根 Compartment" test_cloud_shell_script_supports_root_compartment
run_test "实例 Metadata 自动识别" test_instance_metadata_is_auto_detected
run_test "HTTPS Origin 生成安全反代配置" test_https_public_origin_generates_secure_proxy_settings
run_test "HTTP Origin 生成非安全反代配置" test_http_public_origin_generates_non_secure_proxy_settings
run_test "公开 Origin 和 Web 端口参数校验" test_public_origin_and_web_port_validation_rejects_invalid_values
run_test "已有公开 Origin 不在提示中回显" test_existing_public_origin_is_not_echoed_by_access_prompt
run_test "Instance Principal 初始化默认值" test_instance_principal_init_uses_safe_defaults
run_test "初始化脚本支持根 Compartment" test_init_deploy_root_compartment_uses_tenancy_scope
run_test "已有私有配置不回显" test_existing_private_value_is_not_echoed
run_test "关闭公众号通知时不追问或回显凭据" test_wechat_notifications_can_be_disabled_without_echoing_existing_credentials
run_test "公众号通知收集即时和每日策略" test_wechat_notifications_collects_immediate_and_daily_policy
run_test "公众号免登录明细令牌有效期限制" test_wechat_detail_page_rejects_invalid_ttl_and_accepts_range
run_test "公众号免登录明细要求 HTTPS Origin" test_wechat_detail_page_requires_https_origin
run_test "Web 容器禁用公开明细缓存" test_caddy_disables_browser_cache_for_public_report
run_test "关闭每日摘要时费用模板可留空" test_wechat_cost_template_is_optional_when_daily_summary_is_disabled
run_test "开启每日摘要时必须配置费用模板" test_wechat_daily_summary_requires_cost_template
run_test "已有费用模板隐藏并保留" test_existing_wechat_cost_template_is_preserved_without_echoing_it
run_test "通知配置加密密钥自动生成并保留" test_settings_encryption_key_is_generated_and_preserved
run_test "开源检查拦截私钥" test_public_release_check_rejects_sensitive_content
run_test "开源检查允许占位数据" test_public_release_check_accepts_placeholder_data
run_test "开源检查拦截未跟踪敏感文件" test_public_release_check_rejects_untracked_sensitive_file
run_test "开源检查拦截微信公众号凭据" test_public_release_check_rejects_wechat_credentials
run_test "开源检查拦截微信公众号 Template ID" test_public_release_check_rejects_wechat_template_id
run_test "开源检查拦截微信公众号费用 Template ID" test_public_release_check_rejects_wechat_cost_template_id

if [ "${FAILURES}" -gt 0 ]; then
  printf "共 %s 项测试失败。\n" "${FAILURES}" >&2
  exit 1
fi

printf "全部部署脚本测试通过。\n"
