#!/usr/bin/env bash
set -euo pipefail

# 对已经启动的统一商城镜像执行最小但覆盖核心链路的运行验收。
base_url="${1:-http://127.0.0.1:8080}"
headers_file="$(mktemp)"
body_file="$(mktemp)"

cleanup() {
  rm -f "${headers_file}" "${body_file}"
}
trap cleanup EXIT

assert_get() {
  local path="$1"
  local expected="$2"
  if ! curl --fail --silent --show-error \
      --connect-timeout 5 \
      --max-time 20 \
      --dump-header "${headers_file}" \
      --output "${body_file}" \
      "${base_url}${path}"; then
    echo "运行验收失败：${path} 未返回成功状态" >&2
    sed -n '1,40p' "${body_file}" >&2
    exit 1
  fi
  if ! grep -Fq "${expected}" "${body_file}"; then
    echo "运行验收失败：${path} 响应中缺少 ${expected}" >&2
    sed -n '1,40p' "${body_file}" >&2
    exit 1
  fi
}

assert_get "/admin/" '<div id="app"></div>'
assert_get "/healthz" '"status":"UP"'
if ! grep -Eiq '^x-request-id: [A-Za-z0-9._:-]+' "${headers_file}"; then
  echo "运行验收失败：API 响应未返回有效 X-Request-Id" >&2
  exit 1
fi
assert_get "/api/v1/catalog/products" '"success":true'
assert_get "/api/v1/catalog/products/1" '"skus":['
assert_get "/api/v1/catalog/categories" '"success":true'
assert_get "/api/v1/content" '"success":true'

wechat_status="$(
  # Probe miniprogram login: disabled stacks fail closed; mock stacks may issue a token.
  curl --silent --show-error \
    --connect-timeout 5 \
    --max-time 20 \
    --header 'Content-Type: application/json' \
    --data '{"code":"smoke-probe","inviteCode":null,"sponsorClaimSecret":null}' \
    --output "${body_file}" \
    --write-out '%{http_code}' \
    "${base_url}/api/v1/auth/wechat/miniprogram/login"
)"
if [[ "${wechat_status}" == "409" ]] && grep -Fq '"code":"WECHAT_DISABLED"' "${body_file}"; then
  :
elif [[ "${wechat_status}" == "200" ]] && grep -Fq '"token"' "${body_file}"; then
  :
else
  echo "运行验收失败：小程序登录探测异常，实际 HTTP ${wechat_status}" >&2
  sed -n '1,40p' "${body_file}" >&2
  exit 1
fi

echo "商城运行验收通过：admin SPA、空库迁移、多规格商品、内容接口、请求关联与小程序登录开关均正常。"
