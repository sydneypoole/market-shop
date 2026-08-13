#!/usr/bin/env bash
set -euo pipefail

# 对已经启动的统一商城镜像执行最小但覆盖核心链路的运行验收。
base_url="${1:-http://127.0.0.1:8080}"
expect_miniprogram_mock_login="${MARKET_SHOP_EXPECT_MINIPROGRAM_MOCK_LOGIN:-false}"
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
    --data '{"code":"bootstrap-sponsor"}' \
    --output "${body_file}" \
    --write-out '%{http_code}' \
    "${base_url}/api/v1/auth/wechat/miniprogram/login"
)"
if [[ "${wechat_status}" == "409" ]] && grep -Fq '"code":"WECHAT_DISABLED"' "${body_file}"; then
  if [[ "${expect_miniprogram_mock_login}" == "true" ]]; then
    echo "运行验收失败：当前环境要求 mock 小程序登录，但服务报告微信登录未启用" >&2
    sed -n '1,40p' "${body_file}" >&2
    exit 1
  fi
elif [[ "${wechat_status}" == "200" ]] && grep -Fq '"token"' "${body_file}"; then
  miniprogram_token="$(python3 -c '
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    payload = json.load(response)
token = (payload.get("data") or {}).get("token") or ""
print(token)
' "${body_file}")"
  if [[ -z "${miniprogram_token}" ]]; then
    echo "运行验收失败：小程序登录成功响应缺少 data.token" >&2
    sed -n '1,40p' "${body_file}" >&2
    exit 1
  fi

  member_status="$(
    # Do not use a cookie jar here: this request must prove Header session transport.
    curl --silent --show-error \
      --connect-timeout 5 \
      --max-time 20 \
      --header "market-shop-user-token: ${miniprogram_token}" \
      --output "${body_file}" \
      --write-out '%{http_code}' \
      "${base_url}/api/v1/auth/me"
  )"
  if [[ "${member_status}" != "200" ]] || ! grep -Fq '"success":true' "${body_file}"; then
    echo "运行验收失败：小程序 Token Header 未能访问受保护会员接口，实际 HTTP ${member_status}" >&2
    sed -n '1,40p' "${body_file}" >&2
    exit 1
  fi
else
  echo "运行验收失败：小程序登录探测异常，实际 HTTP ${wechat_status}" >&2
  sed -n '1,40p' "${body_file}" >&2
  exit 1
fi

echo "商城运行验收通过：admin SPA、空库迁移、多规格商品、内容接口、请求关联与小程序 Header 会话均正常。"
