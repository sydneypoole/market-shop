#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  --help|-h)
    echo "usage: scripts/production-verify.sh [base-url]"
    exit 0
    ;;
  --dry-run)
    echo "dry-run: /healthz + admin SPA/public API + capability + blocked actuator/OpenAPI checks"
    exit 0
    ;;
esac

base_url="${1:-http://127.0.0.1:8080}"
expected_dev_login="${MARKET_SHOP_EXPECT_DEV_LOGIN_ENABLED:-false}"
[[ "${expected_dev_login}" == "true" || "${expected_dev_login}" == "false" ]] \
  || { echo "MARKET_SHOP_EXPECT_DEV_LOGIN_ENABLED must be true or false" >&2; exit 1; }
body_file="$(mktemp)"
cleanup() {
  rm -f "${body_file}"
}
trap cleanup EXIT

get_expect() {
  local path="$1"
  local expected="$2"
  curl --fail --silent --show-error --connect-timeout 5 --max-time 20 \
    --output "${body_file}" "${base_url}${path}"
  grep -Fq "${expected}" "${body_file}" \
    || { echo "production verification failed: ${path} missing ${expected}" >&2; return 1; }
}

expect_blocked() {
  local path="$1"
  local status
  status="$(curl --silent --show-error --connect-timeout 5 --max-time 20 \
    --output "${body_file}" --write-out '%{http_code}' "${base_url}${path}")"
  [[ "${status}" == "404" ]] \
    || { echo "production verification failed: ${path} returned ${status}, expected 404" >&2; return 1; }
}

get_expect /healthz '"status":"UP"'
if grep -Eq '"(components|details)"[[:space:]]*:' "${body_file}"; then
  echo "production verification failed: public health leaks component details" >&2
  exit 1
fi
get_expect /admin/ '<div id="app"></div>'
get_expect /api/v1/system/capabilities "\"devLoginEnabled\":${expected_dev_login}"
get_expect /api/v1/catalog/products '"success":true'

expect_blocked /actuator/health
expect_blocked /actuator/health/readiness
expect_blocked /actuator
expect_blocked /docs
expect_blocked /docs/
expect_blocked /api-docs
expect_blocked /swagger-ui/index.html
expect_blocked /swagger-ui.html
expect_blocked /v3/api-docs

printf 'production verification passed: readiness, admin SPA, public API and diagnostic isolation\n'
