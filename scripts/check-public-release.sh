#!/usr/bin/env bash

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DENYLIST_FILE="${PUBLIC_RELEASE_DENYLIST_FILE:-}"
FAILURES=0

usage() {
  cat <<'EOF'
用法：
  bash scripts/check-public-release.sh [--root PATH] [--denylist FILE]

检查 Git 已跟踪文件，以及未跟踪但未被忽略的发布候选文件中，是否包含
不适合公开发布的凭据、真实 OCI OCID、邮箱、个人用户目录或敏感文件类型。

选项：
  --root PATH       要检查的 Git 工作区，默认是项目根目录
  --denylist FILE   本机私有关键词列表，每行一个固定字符串
  -h, --help        显示帮助
EOF
}

fail() {
  printf "[ERROR] %s\n" "$1" >&2
  exit 1
}

report_matches() {
  local label="$1"
  local matches="$2"

  FAILURES=$((FAILURES + 1))
  printf "[FAIL] %s\n" "${label}" >&2
  while IFS= read -r file_path; do
    [ -n "${file_path}" ] && printf "  - %s\n" "${file_path}" >&2
  done <<<"${matches}"
}

append_match() {
  local current="$1"
  local file_path="$2"

  if [ -n "${current}" ]; then
    printf "%s\n%s" "${current}" "${file_path}"
  else
    printf "%s" "${file_path}"
  fi
}

list_release_files() {
  git -C "${ROOT_DIR}" ls-files --cached --others --exclude-standard -z
}

check_content_pattern() {
  local label="$1"
  local pattern="$2"
  local matches=""
  local file_path

  while IFS= read -r -d '' file_path; do
    [ -f "${ROOT_DIR}/${file_path}" ] || continue
    if LC_ALL=C grep -I -q -E -e "${pattern}" "${ROOT_DIR}/${file_path}"; then
      matches="$(append_match "${matches}" "${file_path}")"
    fi
  done < <(list_release_files)

  if [ -n "${matches}" ]; then
    report_matches "${label}" "${matches}"
  fi
}

check_fixed_marker() {
  local marker="$1"
  local matches=""
  local file_path

  while IFS= read -r -d '' file_path; do
    [ -f "${ROOT_DIR}/${file_path}" ] || continue
    if LC_ALL=C grep -I -q -F -e "${marker}" "${ROOT_DIR}/${file_path}"; then
      matches="$(append_match "${matches}" "${file_path}")"
    fi
  done < <(list_release_files)

  if [ -n "${matches}" ]; then
    report_matches "本机 denylist 命中" "${matches}"
  fi
}

check_sensitive_paths() {
  local matches=""
  local file_path

  while IFS= read -r -d '' file_path; do
    [ -f "${ROOT_DIR}/${file_path}" ] || continue
    case "${file_path}" in
      .env|*/.env|.DS_Store|*/.DS_Store|.playwright-cli/*|*/.playwright-cli/*|dist/*|*/dist/*|target/*|*/target/*|*.pem|*.key|*.p12|*.pfx|*.jks|*.db|*.sqlite|*.sqlite3|output-*.png|*/output-*.png|output-*.jpg|*/output-*.jpg|output-*.jpeg|*/output-*.jpeg)
        matches="$(append_match "${matches}" "${file_path}")"
        ;;
    esac
  done < <(list_release_files)

  if [ -n "${matches}" ]; then
    report_matches "敏感文件或生成产物被 Git 跟踪" "${matches}"
  fi
}

check_denylist() {
  local line

  [ -n "${DENYLIST_FILE}" ] || DENYLIST_FILE="${ROOT_DIR}/.public-release-denylist"
  [ -f "${DENYLIST_FILE}" ] || return 0

  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ""|\#*) continue ;;
    esac
    check_fixed_marker "${line}"
  done < "${DENYLIST_FILE}"
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --root)
        [ "$#" -ge 2 ] || fail "--root 缺少参数。"
        ROOT_DIR="$(cd "$2" && pwd)"
        shift 2
        ;;
      --denylist)
        [ "$#" -ge 2 ] || fail "--denylist 缺少参数。"
        DENYLIST_FILE="$2"
        shift 2
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
  git -C "${ROOT_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "${ROOT_DIR} 不是 Git 工作区。"

  check_sensitive_paths
  check_content_pattern "私钥内容" '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----'
  check_content_pattern "真实 OCI OCID" 'ocid1\.(tenancy|user|instance|compartment|key)\.[[:alnum:].-]*\.[[:alnum:]]{30,}'
  check_content_pattern "邮箱地址" '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}'
  check_content_pattern "macOS 个人用户目录" '/Users/[[:alnum:]_.-]+/'
  check_denylist

  if [ "${FAILURES}" -gt 0 ]; then
    printf "开源发布检查失败，共发现 %s 类问题。\n" "${FAILURES}" >&2
    exit 1
  fi

  printf "[OK] 开源发布检查通过，未在发布候选内容中发现敏感信息。\n"
}

main "$@"
