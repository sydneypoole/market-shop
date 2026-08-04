#!/usr/bin/env bash
set -Eeuo pipefail

# Real HTTP + MySQL acceptance for a clean Compose stack. The script never
# reads session values or passwords back to stdout and uses cookie jars for all
# authenticated calls.
base_url="${1:-http://127.0.0.1:8080}"
scope="${2:-${MARKET_SHOP_E2E_SCOPE:-full}}"
base_url="${base_url%/}"
origin="$(printf '%s' "${base_url}" | sed -E 's#^(https?://[^/]+).*$#\1#')"

case "${scope}" in
  full|storage) ;;
  *)
    echo "business-e2e: scope must be 'full' or 'storage'" >&2
    exit 2
    ;;
esac

for required in curl jq docker python3 sed awk od; do
  if ! command -v "${required}" >/dev/null 2>&1; then
    echo "business-e2e: required command is missing: ${required}" >&2
    exit 2
  fi
done

e2e_project="${MARKET_SHOP_E2E_COMPOSE_PROJECT:-market-shop-e2e}"
e2e_env_file="${MARKET_SHOP_E2E_ENV_FILE:-.env.local.example}"
e2e_compose_files="${MARKET_SHOP_E2E_COMPOSE_FILES:-docker-compose.yml:docker-compose.local.yml:docker-compose.e2e.yml}"
admin_username="${MARKET_SHOP_E2E_ADMIN_USERNAME:-admin}"
admin_temp_password="${MARKET_SHOP_E2E_ADMIN_TEMP_PASSWORD:-E2eBootstrapTemp2026Strong}"
admin_password="${MARKET_SHOP_E2E_ADMIN_PASSWORD:-E2eBootstrapLive2026Strong}"
invite_code="${MARKET_SHOP_E2E_INVITE_CODE:-E2EBOOTSTRAP2026}"
outbox_max_attempts="${MARKET_SHOP_E2E_OUTBOX_MAX_ATTEMPTS:-2}"

compose=(docker compose --project-name "${e2e_project}")
if [[ -n "${e2e_env_file}" ]]; then
  compose+=(--env-file "${e2e_env_file}")
fi
IFS=':' read -r -a compose_files <<<"${e2e_compose_files}"
for compose_file in "${compose_files[@]}"; do
  compose+=(-f "${compose_file}")
done

tmp_dir="$(mktemp -d)"
chmod 700 "${tmp_dir}"
body_file="${tmp_dir}/response.json"
headers_file="${tmp_dir}/response.headers"
download_file="${tmp_dir}/proof-download.bin"
png_file="${tmp_dir}/proof.png"
admin_jar="${tmp_dir}/admin.cookies"
operator_jar="${tmp_dir}/operator.cookies"
operator_reset_jar="${tmp_dir}/operator-reset.cookies"
sponsor_jar="${tmp_dir}/sponsor.cookies"
child_jar="${tmp_dir}/child.cookies"
outsider_jar="${tmp_dir}/outsider.cookies"
anonymous_jar="${tmp_dir}/anonymous.cookies"
touch "${admin_jar}" "${operator_jar}" "${operator_reset_jar}" \
  "${sponsor_jar}" "${child_jar}" "${outsider_jar}" "${anonymous_jar}"
chmod 600 "${tmp_dir}"/*.cookies
projection_lock_pid=''
projection_lock_connection_id=''

run_suffix="$(date +%s)-$$"
run_key="$(printf '%s' "${run_suffix}" | tr -cd '0-9' | tail -c 13)"
if [[ -z "${run_key}" ]]; then
  run_key="$$"
fi

log() {
  printf '[business-e2e] %s\n' "$*"
}

fail() {
  printf '[business-e2e] FAILED: %s\n' "$*" >&2
  return 1
}

print_api_summary() {
  if jq -e . "${body_file}" >/dev/null 2>&1; then
    jq -c '{success, code, message}' "${body_file}" >&2 || true
  else
    echo '{"message":"response body was not API JSON"}' >&2
  fi
}

db_query() {
  local sql="$1"
  "${compose[@]}" exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names --raw -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "$1"' \
    sh "${sql}"
}

db_scalar() {
  db_query "$1" | tr -d '\r' | tail -n 1
}

db_root_query() {
  local sql="$1"
  "${compose[@]}" exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names --raw -uroot "$MYSQL_DATABASE" -e "$1"' \
    sh "${sql}"
}

db_root_scalar() {
  db_root_query "$1" | tr -d '\r' | tail -n 1
}

diagnose() {
  set +e
  echo '[business-e2e] Compose service state:' >&2
  "${compose[@]}" ps >&2
  echo '[business-e2e] Recent sanitized order/outbox state:' >&2
  db_query "
    SELECT CONCAT('order=', id, ',status=', status)
    FROM trade_order ORDER BY id DESC LIMIT 6;
    SELECT CONCAT(
      'outbox=', id, ',aggregate=', aggregate_type, ':', aggregate_id,
      ',event=', event_type, ',status=', status, ',attempt=', attempt_count,
      ',replay=', replay_count, ',error=', COALESCE(LEFT(last_error, 120), ''))
    FROM sys_outbox_event ORDER BY id DESC LIMIT 12;
  " >&2
  set -e
}

finish() {
  local status=$?
  trap - EXIT ERR
  if [[ -n "${projection_lock_pid}" ]]; then
    release_projection_gate >/dev/null 2>&1 || true
  fi
  if [[ ${status} -ne 0 ]]; then
    diagnose
  fi
  rm -rf "${tmp_dir}"
  exit "${status}"
}
trap finish EXIT

api_json() {
  local method="$1"
  local path="$2"
  local jar="$3"
  local payload="${4:-}"
  local -a args=(
    --fail-with-body --silent --show-error
    --connect-timeout 5 --max-time 30
    --request "${method}"
    --header 'Accept: application/json'
    --cookie "${jar}" --cookie-jar "${jar}"
    --dump-header "${headers_file}" --output "${body_file}"
  )
  if [[ "${method}" != "GET" && "${method}" != "HEAD" ]]; then
    args+=(--header "Origin: ${origin}")
  fi
  if [[ -n "${payload}" ]]; then
    args+=(--header 'Content-Type: application/json' --data "${payload}")
  fi
  if ! curl "${args[@]}" "${base_url}${path}"; then
    echo "business-e2e: ${method} ${path} failed" >&2
    print_api_summary
    return 1
  fi
  if ! jq -e '.success == true and .code == "OK"' "${body_file}" >/dev/null; then
    echo "business-e2e: ${method} ${path} returned a non-success API envelope" >&2
    print_api_summary
    return 1
  fi
}

api_expect_failure() {
  local method="$1"
  local path="$2"
  local jar="$3"
  local payload="$4"
  local expected_statuses="$5"
  local expected_code="$6"
  local -a args=(
    --silent --show-error
    --connect-timeout 5 --max-time 30
    --request "${method}"
    --header 'Accept: application/json'
    --cookie "${jar}" --cookie-jar "${jar}"
    --dump-header "${headers_file}" --output "${body_file}"
    --write-out '%{http_code}'
  )
  if [[ "${method}" != "GET" && "${method}" != "HEAD" ]]; then
    args+=(--header "Origin: ${origin}")
  fi
  if [[ -n "${payload}" ]]; then
    args+=(--header 'Content-Type: application/json' --data "${payload}")
  fi
  local status
  status="$(curl "${args[@]}" "${base_url}${path}")"
  if [[ ",${expected_statuses}," != *",${status},"* ]]; then
    echo "business-e2e: ${method} ${path} returned HTTP ${status}; expected ${expected_statuses}" >&2
    print_api_summary
    return 1
  fi
  if ! jq -e --arg code "${expected_code}" \
      '.success == false and .code == $code' "${body_file}" >/dev/null; then
    echo "business-e2e: ${method} ${path} did not return ${expected_code}" >&2
    print_api_summary
    return 1
  fi
}

api_upload_png() {
  local path="$1"
  local jar="$2"
  if ! curl --fail-with-body --silent --show-error \
      --connect-timeout 5 --max-time 45 \
      --request POST \
      --header 'Accept: application/json' \
      --header "Origin: ${origin}" \
      --cookie "${jar}" --cookie-jar "${jar}" \
      --form "file=@${png_file};type=image/png;filename=e2e-proof.png" \
      --dump-header "${headers_file}" --output "${body_file}" \
      "${base_url}${path}"; then
    echo "business-e2e: proof upload failed for ${path}" >&2
    print_api_summary
    return 1
  fi
  jq -e '.success == true and .code == "OK" and .data.mediaType == "image/png"' \
    "${body_file}" >/dev/null || {
      print_api_summary
      return 1
    }
}

jq_assert() {
  local description="$1"
  local filter="$2"
  shift 2
  if ! jq -e "$@" "${filter}" "${body_file}" >/dev/null; then
    echo "business-e2e: assertion failed: ${description}" >&2
    print_api_summary
    return 1
  fi
}

assert_equal() {
  local actual="$1"
  local expected="$2"
  local description="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    fail "${description}: expected '${expected}', got '${actual}'"
  fi
}

assert_positive() {
  local actual="$1"
  local description="$2"
  if [[ ! "${actual}" =~ ^[0-9]+$ ]] || (( actual <= 0 )); then
    fail "${description}: expected a positive integer, got '${actual}'"
  fi
}

wait_db_equal() {
  local description="$1"
  local sql="$2"
  local expected="$3"
  local timeout_seconds="${4:-45}"
  local deadline=$((SECONDS + timeout_seconds))
  local actual=''
  while (( SECONDS < deadline )); do
    actual="$(db_scalar "${sql}")"
    if [[ "${actual}" == "${expected}" ]]; then
      return 0
    fi
    sleep 1
  done
  fail "${description}: expected '${expected}', last value '${actual}'"
}

assert_cookie_contract() {
  local jar="$1"
  local token_name="$2"
  if ! awk -F '\t' -v token="${token_name}" \
      '$6 == token && $3 == "/" { found = 1 } END { exit(found ? 0 : 1) }' "${jar}"; then
    fail "cookie jar is missing ${token_name}"
  fi
  if ! awk -v token="${token_name}" '
      BEGIN { needle = tolower(token) "=" }
      {
        line = tolower($0)
        if (index(line, "set-cookie:") == 1 && index(line, needle) > 0 && index(line, "httponly") > 0 && index(line, "samesite=lax") > 0) found = 1
      }
      END { exit(found ? 0 : 1) }
    ' "${headers_file}"; then
    fail "${token_name} Set-Cookie is missing HttpOnly or SameSite=Lax"
  fi
}

login_admin() {
  local jar="$1"
  local username="$2"
  local password="$3"
  local payload
  payload="$(jq -nc --arg username "${username}" --arg password "${password}" \
    '{username:$username,password:$password}')"
  api_json POST '/api/v1/admin/auth/login' "${jar}" "${payload}"
  jq_assert "admin login must not expose bearer-token fields" \
    '.data | (has("token") or has("tokenName") or has("tokenValue")) | not'
}

login_user() {
  local jar="$1"
  local open_id="$2"
  local nickname="$3"
  local invitation="${4:-}"
  local payload
  payload="$(jq -nc \
    --arg openId "${open_id}" --arg nickname "${nickname}" --arg inviteCode "${invitation}" \
    '{openId:$openId,nickname:$nickname,inviteCode:(if $inviteCode == "" then null else $inviteCode end)}')"
  api_json POST '/api/v1/auth/dev-login' "${jar}" "${payload}"
  jq_assert "member login must not expose bearer-token fields" \
    '.data | (has("token") or has("tokenName") or has("tokenValue")) | not'
}

assert_order_status() {
  local order_id="$1"
  local expected="$2"
  api_json GET "/api/v1/orders/${order_id}" "${child_jar}"
  jq_assert "order ${order_id} status ${expected}" \
    '.data.order.status == $expected' --arg expected "${expected}"
}

assert_after_sale_status() {
  local after_sale_id="$1"
  local expected="$2"
  api_json GET "/api/v1/after-sales/${after_sale_id}" "${child_jar}"
  jq_assert "after-sale ${after_sale_id} status ${expected}" \
    '.data.status == $expected' --arg expected "${expected}"
}

signed_url_args=(--location)
prepare_signed_url() {
  local url="$1"
  signed_url_args=(--location)
  # Local Compose drills may expose RustFS through either the service alias or
  # Docker Desktop's host gateway.  Keep the signed authority unchanged (it is
  # part of the SigV4 signature) and only pin the local name to loopback.
  if [[ "${url}" =~ ^https?://(rustfs\.localhost|host\.docker\.internal):([0-9]+)/ ]]; then
    signed_url_args+=(--resolve "${BASH_REMATCH[1]}:${BASH_REMATCH[2]}:127.0.0.1")
  fi
}

download_signed_png() {
  local url="$1"
  if [[ "${url}" == /* ]]; then
    url="${base_url}${url}"
  fi
  prepare_signed_url "${url}"
  curl --fail-with-body --silent --show-error \
    --connect-timeout 5 --max-time 30 \
    "${signed_url_args[@]}" --output "${download_file}" "${url}"
  local signature
  signature="$(od -An -tx1 -N8 "${download_file}" | tr -d ' \n')"
  assert_equal "${signature}" '89504e470d0a1a0a' 'downloaded proof PNG signature'
}

wait_signed_url_expired() {
  local url="$1"
  if [[ "${url}" == /* ]]; then
    url="${base_url}${url}"
  fi
  prepare_signed_url "${url}"
  local deadline=$((SECONDS + 15))
  local status='200'
  while (( SECONDS < deadline )); do
    status="$(curl --silent --show-error \
      --connect-timeout 5 --max-time 20 \
      "${signed_url_args[@]}" --output /dev/null --write-out '%{http_code}' "${url}" || true)"
    if [[ "${status}" == '403' ]]; then
      return 0
    fi
    sleep 1
  done
  fail "short-lived signed proof URL did not expire with HTTP 403 (last HTTP ${status})"
}

wait_signed_url_deleted() {
  local url="$1"
  if [[ "${url}" == /* ]]; then
    url="${base_url}${url}"
  fi
  prepare_signed_url "${url}"
  local deadline=$((SECONDS + 20))
  local status='200'
  while (( SECONDS < deadline )); do
    status="$(curl --silent --show-error \
      --connect-timeout 5 --max-time 20 \
      "${signed_url_args[@]}" --output /dev/null --write-out '%{http_code}' "${url}" || true)"
    if [[ "${status}" != 2* ]]; then
      return 0
    fi
    sleep 1
  done
  fail "deleted proof URL remained readable (HTTP ${status})"
}

signed_url_remaining_seconds() {
  python3 - "$1" <<'PY'
import datetime
import sys

value = sys.argv[1].replace("Z", "+00:00")
expires_at = datetime.datetime.fromisoformat(value)
if expires_at.tzinfo is None:
    expires_at = expires_at.replace(tzinfo=datetime.timezone.utc)
remaining = (expires_at - datetime.datetime.now(datetime.timezone.utc)).total_seconds()
print(int(remaining))
PY
}

assert_signed_url_targets_object() {
  python3 - "$1" "$2" <<'PY'
import sys
import urllib.parse

path = urllib.parse.unquote(urllib.parse.urlsplit(sys.argv[1]).path)
object_key = sys.argv[2]
if not path.endswith("/" + object_key):
    raise SystemExit(f"signed URL path does not target object key: {object_key}")
PY
}

assert_signed_url_deleted_before_expiry() {
  local url="$1"
  local expires_at="$2"
  local remaining status
  remaining="$(signed_url_remaining_seconds "${expires_at}")"
  if [[ ! "${remaining}" =~ ^[0-9]+$ ]] || (( remaining < 2 )); then
    fail "fresh deletion URL has insufficient TTL before verification (${remaining}s)"
  fi
  if [[ "${url}" == /* ]]; then
    url="${base_url}${url}"
  fi
  prepare_signed_url "${url}"
  status="$(curl --silent --show-error \
    --connect-timeout 5 --max-time 20 \
    "${signed_url_args[@]}" --output "${download_file}" --write-out '%{http_code}' "${url}" || true)"
  if [[ "${status}" == 2* ]]; then
    fail "deleted proof object remained readable through a fresh signed URL (HTTP ${status})"
  fi
  case "${status}" in
    403|404|410) ;;
    *) fail "deleted proof object returned an unexpected transport/server status (HTTP ${status})" ;;
  esac
  remaining="$(signed_url_remaining_seconds "${expires_at}")"
  if [[ ! "${remaining}" =~ ^[0-9]+$ ]] || (( remaining <= 0 )); then
    fail "deletion URL expired before object-absence verification (HTTP ${status})"
  fi
}

python3 - "${png_file}" <<'PY'
import binascii
import pathlib
import struct
import sys
import zlib

def chunk(kind: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF))

width = height = 2
rows = b"".join(b"\x00" + bytes((32, 96, 160)) * width for _ in range(height))
png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(rows))
       + chunk(b"IEND", b""))
pathlib.Path(sys.argv[1]).write_bytes(png)
PY

log 'waiting for the public readiness contract'
if ! curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    --output "${body_file}" "${base_url}/healthz"; then
  fail 'readiness endpoint did not return success'
fi
jq -e '.status == "UP"' "${body_file}" >/dev/null || fail 'readiness status is not UP'

log 'bootstrapping the forced-password admin session'
login_admin "${admin_jar}" "${admin_username}" "${admin_temp_password}"
jq_assert 'bootstrap admin must require a password change' '.data.mustChangePassword == true'
assert_cookie_contract "${admin_jar}" 'market-shop-admin-token'
api_expect_failure GET '/api/v1/admin/accounts' "${admin_jar}" '' '400,409' \
  'ADMIN_PASSWORD_CHANGE_REQUIRED'
change_payload="$(jq -nc --arg currentPassword "${admin_temp_password}" \
  --arg newPassword "${admin_password}" \
  '{currentPassword:$currentPassword,newPassword:$newPassword}')"
api_json POST '/api/v1/admin/auth/change-password' "${admin_jar}" "${change_payload}"
api_json GET '/api/v1/admin/auth/me' "${admin_jar}"
jq_assert 'bootstrap admin password-change flag must clear' \
  '.data.mustChangePassword == false and (.data.roles | index("SUPER_ADMIN") != null)'

log 'establishing sponsor, buyer and unrelated-member cookie sessions'
login_user "${sponsor_jar}" 'bootstrap-sponsor' '商城发起人'
jq_assert 'bootstrap sponsor must be an existing identity' '.data.newlyRegistered == false'
assert_cookie_contract "${sponsor_jar}" 'market-shop-user-token'
login_user "${child_jar}" "e2e-child-${run_key}" 'E2E 买家' "${invite_code}"
jq_assert 'buyer must register through the bootstrap invitation' '.data.newlyRegistered == true'
assert_cookie_contract "${child_jar}" 'market-shop-user-token'
login_user "${outsider_jar}" "e2e-outsider-${run_key}" 'E2E 旁观者' "${invite_code}"
jq_assert 'unrelated member must register through the bootstrap invitation' '.data.newlyRegistered == true'

api_json GET '/api/v1/auth/me' "${sponsor_jar}"
sponsor_user_id="$(jq -er '.data.userId' "${body_file}")"
api_json GET '/api/v1/auth/me' "${child_jar}"
child_user_id="$(jq -er '.data.userId' "${body_file}")"
api_json GET '/api/v1/auth/me' "${outsider_jar}"
outsider_user_id="$(jq -er '.data.userId' "${body_file}")"
assert_positive "${sponsor_user_id}" 'sponsor user id'
assert_positive "${child_user_id}" 'buyer user id'
assert_positive "${outsider_user_id}" 'unrelated user id'
assert_equal "$(db_scalar "SELECT superior_user_id FROM customer_relation WHERE member_user_id = ${child_user_id}")" \
  "${sponsor_user_id}" 'buyer sponsor relationship'

if [[ "${scope}" == 'full' ]]; then
  log 'verifying member disable, session revocation, rejected login and recovery'
  member_request_id="member-disable-${run_key}"
  member_payload="$(jq -nc --arg status DISABLED --arg reason 'E2E session revocation' \
    --arg requestId "${member_request_id}" \
    '{status:$status,reason:$reason,requestId:$requestId}')"
  api_json PUT "/api/v1/admin/members/${child_user_id}/status" "${admin_jar}" "${member_payload}"
  api_expect_failure GET '/api/v1/orders' "${child_jar}" '' '401,403' 'NOT_LOGGED_IN'
  disabled_payload="$(jq -nc --arg openId "e2e-child-${run_key}" \
    '{openId:$openId,nickname:"E2E 买家",inviteCode:null}')"
  api_expect_failure POST '/api/v1/auth/dev-login' "${child_jar}" "${disabled_payload}" \
    '403' 'MEMBER_DISABLED'
  member_payload="$(jq -nc --arg status ACTIVE --arg reason 'E2E restore member' \
    --arg requestId "member-restore-${run_key}" \
    '{status:$status,reason:$reason,requestId:$requestId}')"
  api_json PUT "/api/v1/admin/members/${child_user_id}/status" "${admin_jar}" "${member_payload}"
  login_user "${child_jar}" "e2e-child-${run_key}" 'E2E 买家'
  jq_assert 'restored buyer must re-login as an existing identity' '.data.newlyRegistered == false'
  api_json GET '/api/v1/auth/me' "${child_jar}"
  assert_equal "$(jq -er '.data.userId' "${body_file}")" "${child_user_id}" \
    'restored buyer identity'

  log 'verifying temporary operator status, lock, password and role-session invalidation'
  operator_username="e2e-operator-${run_key}"
  operator_temp_password='E2eOperatorTemp2026Strong'
  operator_password='E2eOperatorLive2026Strong'
  operator_reset_password='E2eOperatorReset2026Strong'
  operator_final_password='E2eOperatorFinal2026Strong'
  operator_create="$(jq -nc \
    --arg username "${operator_username}" \
    --arg temporaryPassword "${operator_temp_password}" \
    --arg currentPassword "${admin_password}" \
    '{username:$username,displayName:"E2E Operator",temporaryPassword:$temporaryPassword,
      linkedUserId:null,roles:["ORDER_REVIEWER"],currentPassword:$currentPassword,
      reason:"E2E operator lifecycle"}')"
  api_json POST '/api/v1/admin/accounts' "${admin_jar}" "${operator_create}"
  operator_id="$(jq -er '.data.id' "${body_file}")"
  assert_positive "${operator_id}" 'temporary operator id'
  login_admin "${operator_jar}" "${operator_username}" "${operator_temp_password}"
  jq_assert 'temporary operator must change password' '.data.mustChangePassword == true'
  api_expect_failure GET '/api/v1/admin/orders' "${operator_jar}" '' '400,409' \
    'ADMIN_PASSWORD_CHANGE_REQUIRED'
  operator_change="$(jq -nc --arg currentPassword "${operator_temp_password}" \
    --arg newPassword "${operator_password}" \
    '{currentPassword:$currentPassword,newPassword:$newPassword}')"
  api_json POST '/api/v1/admin/auth/change-password' "${operator_jar}" "${operator_change}"
  api_json GET '/api/v1/admin/auth/me' "${operator_jar}"
  jq_assert 'operator password change must clear the flag' '.data.mustChangePassword == false'

  operator_status_payload="$(jq -nc --arg currentPassword "${admin_password}" \
    '{currentPassword:$currentPassword,status:"DISABLED",reason:"E2E disable invalidates sessions"}')"
  api_json PUT "/api/v1/admin/accounts/${operator_id}/status" "${admin_jar}" \
    "${operator_status_payload}"
  api_expect_failure GET '/api/v1/admin/auth/me' "${operator_jar}" '' '401,403' 'NOT_LOGGED_IN'
  operator_login_payload="$(jq -nc --arg username "${operator_username}" \
    --arg password "${operator_password}" '{username:$username,password:$password}')"
  api_expect_failure POST '/api/v1/admin/auth/login' "${operator_jar}" \
    "${operator_login_payload}" '403' 'ADMIN_DISABLED'

  operator_status_payload="$(jq -nc --arg currentPassword "${admin_password}" \
    '{currentPassword:$currentPassword,status:"ACTIVE",reason:"E2E restore requires a new session"}')"
  api_json PUT "/api/v1/admin/accounts/${operator_id}/status" "${admin_jar}" \
    "${operator_status_payload}"
  login_admin "${operator_jar}" "${operator_username}" "${operator_password}"
  jq_assert 'restored operator must establish a new active session' \
    '.data.mustChangePassword == false and (.data.roles | index("ORDER_REVIEWER") != null)'

  wrong_login_payload="$(jq -nc --arg username "${operator_username}" \
    '{username:$username,password:"E2eDeliberatelyWrongPassword"}')"
  for _ in 1 2 3 4 5; do
    api_expect_failure POST '/api/v1/admin/auth/login' "${operator_jar}" \
      "${wrong_login_payload}" '400' 'ADMIN_CREDENTIALS_INVALID'
  done
  api_expect_failure GET '/api/v1/admin/auth/me' "${operator_jar}" '' '401,403' 'NOT_LOGGED_IN'
  api_expect_failure POST '/api/v1/admin/auth/login' "${operator_jar}" \
    "${operator_login_payload}" '400' 'ADMIN_CREDENTIALS_INVALID'
  unlock_payload="$(jq -nc --arg currentPassword "${admin_password}" \
    '{currentPassword:$currentPassword,reason:"E2E unlock after verified lockout"}')"
  api_json POST "/api/v1/admin/accounts/${operator_id}/unlock" "${admin_jar}" "${unlock_payload}"
  login_admin "${operator_jar}" "${operator_username}" "${operator_password}"
  jq_assert 'unlocked operator must establish a new active session' \
    '.data.mustChangePassword == false and (.data.roles | index("ORDER_REVIEWER") != null)'

  reset_payload="$(jq -nc --arg currentPassword "${admin_password}" \
    --arg temporaryPassword "${operator_reset_password}" \
    '{currentPassword:$currentPassword,temporaryPassword:$temporaryPassword,
      reason:"E2E reset invalidates sessions"}')"
  api_json POST "/api/v1/admin/accounts/${operator_id}/reset-password" "${admin_jar}" "${reset_payload}"
  api_expect_failure GET '/api/v1/admin/auth/me' "${operator_jar}" '' '401,403' 'NOT_LOGGED_IN'
  login_admin "${operator_reset_jar}" "${operator_username}" "${operator_reset_password}"
  jq_assert 'reset password must restore the forced-change flag' '.data.mustChangePassword == true'
  operator_change="$(jq -nc --arg currentPassword "${operator_reset_password}" \
    --arg newPassword "${operator_final_password}" \
    '{currentPassword:$currentPassword,newPassword:$newPassword}')"
  api_json POST '/api/v1/admin/auth/change-password' "${operator_reset_jar}" "${operator_change}"
  roles_payload="$(jq -nc --arg currentPassword "${admin_password}" \
    '{currentPassword:$currentPassword,roles:["AUDIT_VIEWER"],
      reason:"E2E role replacement invalidates sessions"}')"
  api_json PUT "/api/v1/admin/accounts/${operator_id}/roles" "${admin_jar}" "${roles_payload}"
  api_expect_failure GET '/api/v1/admin/auth/me' "${operator_reset_jar}" '' '401,403' 'NOT_LOGGED_IN'
  login_admin "${operator_reset_jar}" "${operator_username}" "${operator_final_password}"
  jq_assert 'operator re-login must observe replaced roles' \
    '(.data.roles | index("AUDIT_VIEWER") != null) and (.data.roles | index("ORDER_REVIEWER") == null)'

  log 'inserting an older poison ORDER_COMPLETED event before valid business events'
  poison_aggregate_id='8999999999999999999'
  poison_id="$(db_scalar "
    INSERT INTO sys_outbox_event
      (event_id, aggregate_type, aggregate_id, event_type, payload_json,
       occurred_at, status, next_attempt_at)
    VALUES
      (UUID(), 'ORDER', '${poison_aggregate_id}', 'ORDER_COMPLETED',
       JSON_OBJECT('orderId', ${poison_aggregate_id}, 'status', 'COMPLETED',
                   'source', 'E2E_POISON', 'ruleVersionIds', JSON_OBJECT()),
       CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3));
    SELECT LAST_INSERT_ID();
  ")"
  assert_positive "${poison_id}" 'poison outbox id'
fi

submit_order() {
  local sku_id="$1"
  local label="$2"
  local inventory
  inventory="$(db_scalar "SELECT CONCAT(available_quantity, '|', reserved_quantity)
    FROM catalog_inventory WHERE sku_id = ${sku_id}")"
  IFS='|' read -r order_available_before order_reserved_before <<<"${inventory}"
  order_sku_id="${sku_id}"
  order_label="${label}"
  order_payload="$(jq -nc \
    --arg clientRequestId "order-${label}-${run_key}" \
    --argjson skuId "${sku_id}" \
    '{clientRequestId:$clientRequestId,source:"H5",
      address:{recipientName:"E2E Buyer",phone:"13800000000",province:"广东省",
        city:"深圳市",district:"南山区",detailAddress:"科技园 E2E 1 号",postalCode:"518000"},
      items:[{skuId:$skuId,quantity:1}]}')"
  api_json POST '/api/v1/orders' "${child_jar}" "${order_payload}"
  order_id="$(jq -er '.data.id' "${body_file}")"
  assert_positive "${order_id}" "${label} order id"
  jq_assert "${label} order starts at the canonical pending-superior status" \
    '.data.status == "PENDING_SUPERIOR"'
  api_json GET "/api/v1/orders/${order_id}" "${child_jar}"
  jq_assert "${label} buyer receives only pending buyer actions" \
    '.data.actorCapabilities == {
      canReceive:false,canUploadProof:true,canCancel:true,canSuperiorDecide:false
    }'
  api_json GET "/api/v1/orders/${order_id}" "${sponsor_jar}"
  jq_assert "${label} superior receives no buyer actions" \
    '.data.actorCapabilities == {
      canReceive:false,canUploadProof:false,canCancel:false,canSuperiorDecide:true
    }'
  assert_equal "$(db_scalar "SELECT status FROM trade_order WHERE id = ${order_id}")" \
    'PENDING_SUPERIOR' "${label} persisted pending-superior status"
  assert_equal "$(db_scalar "SELECT CONCAT(available_quantity, '|', reserved_quantity)
    FROM catalog_inventory WHERE sku_id = ${sku_id}")" \
    "$((order_available_before - 1))|$((order_reserved_before + 1))" \
    "${label} inventory reservation"
}

seed_direct_qualification_history() {
  historical_direct_v1_id="$(db_scalar "SELECT id FROM operation_rule_version
    WHERE rule_code = 'DIVIDEND_MEMBER_QUALIFICATION' AND version_no = 1")"
  historical_points_v1_id="$(db_scalar "SELECT id FROM operation_rule_version
    WHERE rule_code = 'DIRECT_REFERRAL_POINTS' AND version_no = 1")"
  historical_experience_v1_id="$(db_scalar "SELECT id FROM operation_rule_version
    WHERE rule_code = 'EXPERIENCE_OFFICER_UPGRADE' AND version_no = 1")"
  historical_super_v1_id="$(db_scalar "SELECT id FROM operation_rule_version
    WHERE rule_code = 'SUPER_MEMBER_UPGRADE' AND version_no = 1")"
  assert_positive "${historical_direct_v1_id}" 'historical direct-referral v1 id'
  assert_positive "${historical_points_v1_id}" 'historical points v1 id'
  assert_positive "${historical_experience_v1_id}" 'historical experience-upgrade v1 id'
  assert_positive "${historical_super_v1_id}" 'historical super-upgrade v1 id'

  local ordinal fixture_order_id
  for ordinal in 1 2 3 4 5; do
    fixture_order_id="$(db_scalar "
      INSERT INTO trade_order
        (order_no, buyer_user_id, superior_user_id, address_snapshot_json,
         total_amount_fen, status, source, client_request_id,
         superior_confirmed_at, admin_reviewed_at, shipped_at, completed_at, version)
      VALUES
        ('E2EH${run_key}${ordinal}', ${outsider_user_id}, ${sponsor_user_id}, JSON_OBJECT(),
         199800, 'COMPLETED', 'H5', 'direct-history-${run_key}-${ordinal}',
         CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 4);
      SELECT LAST_INSERT_ID();
    ")"
    assert_positive "${fixture_order_id}" "qualified-direct history order ${ordinal}"
    db_query "
      INSERT INTO distribution_direct_performance
        (beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
         completed_ordinal, performance_fen, status)
      VALUES
        (${sponsor_user_id}, ${outsider_user_id}, ${fixture_order_id},
         ${historical_direct_v1_id}, ${ordinal}, 199800, 'ACTIVE');
    " >/dev/null
  done
  assert_equal "$(db_scalar "SELECT COUNT(*) FROM distribution_direct_performance
    WHERE beneficiary_user_id = ${sponsor_user_id} AND status = 'ACTIVE'")" \
    '5' 'five pre-existing qualified direct referrals'
}

align_with_fresh_outbox_cycle() {
  local marker_id
  marker_id="$(db_scalar "
    INSERT INTO sys_outbox_event
      (event_id, aggregate_type, aggregate_id, event_type, payload_json,
       occurred_at, status, next_attempt_at)
    VALUES
      (UUID(), 'E2E', '${run_key}', 'E2E_RULE_SWITCH_GATE', JSON_OBJECT(),
       CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3));
    SELECT LAST_INSERT_ID();
  ")"
  assert_positive "${marker_id}" 'rule-switch outbox cycle marker'
  wait_db_equal 'rule-switch cycle marker publication' \
    "SELECT status FROM sys_outbox_event WHERE id = ${marker_id}" 'PUBLISHED' 30
}

acquire_projection_gate() {
  local account_count lock_log deadline
  account_count="$(db_root_scalar "SELECT COUNT(*) FROM membership_account
    WHERE user_id = ${child_user_id}")"
  assert_equal "${account_count}" '1' 'projection-gate membership account'

  lock_log="${tmp_dir}/projection-gate.log"
  : >"${lock_log}"
  "${compose[@]}" exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names --raw -uroot "$MYSQL_DATABASE" -e "$1"' \
    sh "START TRANSACTION;
        SELECT id FROM membership_account WHERE user_id = ${child_user_id} FOR UPDATE;
        SELECT /* E2E_PROJECTION_GATE_${run_key} */ SLEEP(120);
        ROLLBACK;" >"${lock_log}" 2>&1 &
  projection_lock_pid=$!

  deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    if ! kill -0 "${projection_lock_pid}" >/dev/null 2>&1; then
      cat "${lock_log}" >&2 || true
      fail 'projection-gate transaction exited before obtaining the membership lock'
      return 1
    fi
    if ! db_root_query "SELECT id FROM membership_account
      WHERE user_id = ${child_user_id} FOR UPDATE NOWAIT" >/dev/null 2>&1; then
      projection_lock_connection_id="$(db_root_scalar "
        SELECT ID
        FROM information_schema.PROCESSLIST
        WHERE ID <> CONNECTION_ID()
          AND INFO LIKE 'SELECT /* E2E_PROJECTION_GATE_${run_key} */ SLEEP(120)%'
        ORDER BY ID DESC
        LIMIT 1
      ")"
      assert_positive "${projection_lock_connection_id}" 'projection-gate MySQL connection id'
      return 0
    fi
    sleep 0.2
  done
  cat "${lock_log}" >&2 || true
  fail 'projection-gate transaction did not acquire the membership row lock'
}

wait_for_projection_gate_contention() {
  local current_order_id="$1"
  local deadline=$((SECONDS + 30))
  local waiters='0'
  while (( SECONDS < deadline )); do
    waiters="$(db_root_scalar "
      SELECT COUNT(*)
      FROM performance_schema.data_lock_waits waits
      JOIN performance_schema.data_locks requested
        ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
      WHERE requested.OBJECT_SCHEMA = DATABASE()
        AND requested.OBJECT_NAME = 'membership_account'
    ")"
    if [[ "${waiters}" =~ ^[0-9]+$ ]] && (( waiters > 0 )); then
      assert_equal "$(db_scalar "SELECT status FROM sys_outbox_event
        WHERE aggregate_id = '${current_order_id}' AND event_type = 'ORDER_COMPLETED'
        ORDER BY id DESC LIMIT 1")" 'PENDING' \
        'blocked historical event remains unpublished'
      assert_equal "$(db_scalar "SELECT COUNT(*) FROM membership_evidence
        WHERE source_order_id = ${current_order_id}")" '0' \
        'blocked historical projection has no visible membership evidence'
      assert_equal "$(db_scalar "SELECT COUNT(*) FROM distribution_direct_performance
        WHERE source_order_id = ${current_order_id}")" '0' \
        'blocked historical projection has no visible direct performance'
      assert_equal "$(db_scalar "SELECT COUNT(*) FROM ledger_entry
        WHERE source_order_id = ${current_order_id}")" '0' \
        'blocked historical projection has no visible ledger award'
      return 0
    fi
    sleep 0.2
  done
  fail "historical projector never contended on the deterministic membership gate (waiters=${waiters})"
}

release_projection_gate() {
  local deadline
  if [[ -n "${projection_lock_connection_id}" ]]; then
    db_root_query "KILL ${projection_lock_connection_id}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${projection_lock_pid}" ]]; then
    kill "${projection_lock_pid}" >/dev/null 2>&1 || true
    wait "${projection_lock_pid}" >/dev/null 2>&1 || true
  fi
  projection_lock_pid=''
  projection_lock_connection_id=''

  deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    if db_root_query "SELECT id FROM membership_account
      WHERE user_id = ${child_user_id} FOR UPDATE NOWAIT" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.2
  done
  fail 'projection-gate membership row lock was not released'
}

publish_historical_rule_v2() {
  local payload
  payload="$(jq -nc \
    --arg ruleCode 'SUPER_MEMBER_UPGRADE' \
    --arg ruleType 'SELF_ORDER_TASK' \
    --arg parametersJson '{"minimumCompletedOrderAmountFen":999999999,"targetLevel":"SUPER_MEMBER"}' \
    '{ruleCode:$ruleCode,ruleType:$ruleType,parametersJson:$parametersJson,effectiveFrom:null}')"
  api_json POST '/api/v1/admin/rules' "${admin_jar}" "${payload}"
  historical_super_v2_id="$(jq -er '.data.id' "${body_file}")"
  jq_assert 'super-member v2 publishes after the historical snapshot' '.data.version == 2'

  payload="$(jq -nc \
    --arg ruleCode 'DIVIDEND_MEMBER_QUALIFICATION' \
    --arg ruleType 'DIRECT_REFERRAL_TASK' \
    --arg parametersJson '{"requiredCompletedDirectReferrals":999,"minimumReferralOrderAmountFen":999999999,"requiredReferralLevel":"SUPER_MEMBER","targetLevel":"DIVIDEND_MEMBER"}' \
    '{ruleCode:$ruleCode,ruleType:$ruleType,parametersJson:$parametersJson,effectiveFrom:null}')"
  api_json POST '/api/v1/admin/rules' "${admin_jar}" "${payload}"
  historical_direct_v2_id="$(jq -er '.data.id' "${body_file}")"
  jq_assert 'direct-referral v2 publishes after the historical snapshot' '.data.version == 2'

  payload="$(jq -nc \
    --arg ruleCode 'DIRECT_REFERRAL_POINTS' \
    --arg ruleType 'DIRECT_REFERRAL_POINTS' \
    --arg parametersJson '{"pointsStartOrdinal":999,"availableAPoints":7,"frozenBPoints":11}' \
    '{ruleCode:$ruleCode,ruleType:$ruleType,parametersJson:$parametersJson,effectiveFrom:null}')"
  api_json POST '/api/v1/admin/rules' "${admin_jar}" "${payload}"
  historical_points_v2_id="$(jq -er '.data.id' "${body_file}")"
  jq_assert 'direct-points v2 publishes after the historical snapshot' '.data.version == 2'

  assert_positive "${historical_super_v2_id}" 'historical super-upgrade v2 id'
  assert_positive "${historical_direct_v2_id}" 'historical direct-referral v2 id'
  assert_positive "${historical_points_v2_id}" 'historical points v2 id'
}

complete_order() {
  local current_order_id="$1"
  local label="$2"
  local expected_level="${3:-EXPERIENCE_OFFICER}"
  local switch_rules="${4:-false}"
  local decision_payload
  decision_payload='{"approve":true,"reason":"E2E superior confirmation"}'
  api_json POST "/api/v1/superior/orders/${current_order_id}/decision" \
    "${sponsor_jar}" "${decision_payload}"
  assert_order_status "${current_order_id}" 'PENDING_ADMIN_REVIEW'
  api_json POST "/api/v1/admin/orders/${current_order_id}/review" "${admin_jar}" \
    '{"approve":true,"reason":"E2E admin approval"}'
  assert_order_status "${current_order_id}" 'PENDING_SHIPMENT'
  ship_payload="$(jq -nc --arg trackingNo "E2E-${label}-${run_key}" \
    '{carrierCode:"SF",carrierName:"顺丰速运",trackingNo:$trackingNo}')"
  api_json POST "/api/v1/admin/orders/${current_order_id}/ship" "${admin_jar}" "${ship_payload}"
  assert_order_status "${current_order_id}" 'SHIPPED'
  jq_assert "${label} shipped buyer receives only the receive action" \
    '.data.actorCapabilities == {
      canReceive:true,canUploadProof:false,canCancel:false,canSuperiorDecide:false
    }'
  api_json GET "/api/v1/orders/${current_order_id}" "${sponsor_jar}"
  jq_assert "${label} shipped superior receives no buyer actions" \
    '.data.actorCapabilities == {
      canReceive:false,canUploadProof:false,canCancel:false,canSuperiorDecide:false
    }'
  assert_equal "$(db_scalar "SELECT CONCAT(available_quantity, '|', reserved_quantity)
    FROM catalog_inventory WHERE sku_id = ${order_sku_id}")" \
    "$((order_available_before - 1))|${order_reserved_before}" \
    "${label} reserved inventory consumption"
  if [[ "${switch_rules}" == 'true' ]]; then
    align_with_fresh_outbox_cycle
    acquire_projection_gate
  fi
  api_json POST "/api/v1/orders/${current_order_id}/receive" "${child_jar}"
  assert_order_status "${current_order_id}" 'COMPLETED'
  jq_assert "${label} completed buyer receives no write actions" \
    '.data.actorCapabilities == {
      canReceive:false,canUploadProof:false,canCancel:false,canSuperiorDecide:false
    }'
  assert_equal "$(db_scalar "SELECT status FROM trade_order WHERE id = ${current_order_id}")" \
    'COMPLETED' "${label} persisted completed status"
  if [[ "${switch_rules}" == 'true' ]]; then
    assert_equal "$(db_scalar "SELECT status FROM sys_outbox_event
      WHERE aggregate_id = '${current_order_id}' AND event_type = 'ORDER_COMPLETED'
      ORDER BY id DESC LIMIT 1")" 'PENDING' \
      'historical completion remains delayed before v2 publication'
    assert_equal "$(db_scalar "SELECT COUNT(*) FROM trade_order_rule_snapshot
      WHERE order_id = ${current_order_id}
        AND rule_version_id IN (
          ${historical_experience_v1_id}, ${historical_super_v1_id},
          ${historical_direct_v1_id}, ${historical_points_v1_id}
        )")" '4' 'historical order freezes all upgrade v1 rules'
    publish_historical_rule_v2
    wait_for_projection_gate_contention "${current_order_id}"
    release_projection_gate
  fi
  snapshot_count="$(db_scalar "SELECT COUNT(*) FROM trade_order_rule_snapshot
    WHERE order_id = ${current_order_id}")"
  assert_positive "${snapshot_count}" "${label} completion-time rule snapshots"
  payload_rule_count="$(db_scalar "SELECT JSON_LENGTH(JSON_EXTRACT(payload_json, '$.ruleVersionIds'))
    FROM sys_outbox_event
    WHERE aggregate_id = '${current_order_id}' AND event_type = 'ORDER_COMPLETED'
    ORDER BY id DESC LIMIT 1")"
  assert_positive "${payload_rule_count}" "${label} outbox rule-version payload"
  wait_db_equal "${label} valid outbox publication" \
    "SELECT status FROM sys_outbox_event
     WHERE aggregate_id = '${current_order_id}' AND event_type = 'ORDER_COMPLETED'
     ORDER BY id DESC LIMIT 1" 'PUBLISHED' 60
  wait_db_equal "${label} membership promotion" \
    "SELECT level.code FROM membership_account account
     JOIN membership_level level ON level.id = account.current_level_id
     WHERE account.user_id = ${child_user_id}" "${expected_level}" 30
  evidence_count="$(db_scalar "SELECT COUNT(*) FROM membership_evidence
    WHERE user_id = ${child_user_id} AND source_order_id = ${current_order_id} AND status = 'ACTIVE'")"
  assert_positive "${evidence_count}" "${label} active membership evidence"
  if [[ "${switch_rules}" == 'true' ]]; then
    assert_equal "$(db_scalar "SELECT COUNT(*) FROM membership_evidence
      WHERE user_id = ${child_user_id} AND source_order_id = ${current_order_id}
        AND rule_version_id IN (${historical_experience_v1_id}, ${historical_super_v1_id})")" \
      '2' 'historical evidence uses both snapshotted self-task v1 rules'
    assert_equal "$(db_scalar "SELECT COUNT(*) FROM membership_level_change
      WHERE user_id = ${child_user_id} AND trigger_type = 'ORDER_COMPLETED'
        AND trigger_id = '${current_order_id}'
        AND rule_version_id IN (${historical_experience_v1_id}, ${historical_super_v1_id})")" \
      '2' 'historical upgrades use self-task v1 rules'
    assert_equal "$(db_scalar "SELECT rule_version_id FROM distribution_direct_performance
      WHERE beneficiary_user_id = ${sponsor_user_id} AND source_order_id = ${current_order_id}")" \
      "${historical_direct_v1_id}" 'historical direct performance uses v1'
    assert_equal "$(db_scalar "SELECT CONCAT(rule_version_id, '|', available_delta, '|', frozen_delta)
      FROM ledger_entry WHERE source_order_id = ${current_order_id}
        AND entry_type = 'DIRECT_REFERRAL_AWARD'")" \
      "${historical_points_v1_id}|160|160" 'historical reward uses v1 values and rule id'
    assert_equal "$(db_scalar "SELECT CONCAT(rule_version_id, '|', original_points, '|', remaining_points, '|', status)
      FROM ledger_frozen_batch WHERE source_order_id = ${current_order_id}")" \
      "${historical_points_v1_id}|160|160|ACTIVE" 'historical frozen reward batch uses v1'
    assert_equal "$(db_scalar "SELECT
        (SELECT COUNT(*) FROM membership_evidence
         WHERE source_order_id = ${current_order_id} AND rule_version_id = ${historical_super_v2_id})
        + (SELECT COUNT(*) FROM distribution_direct_performance
           WHERE source_order_id = ${current_order_id} AND rule_version_id = ${historical_direct_v2_id})
        + (SELECT COUNT(*) FROM ledger_entry
           WHERE source_order_id = ${current_order_id} AND rule_version_id = ${historical_points_v2_id})")" \
      '0' 'post-completion v2 rules never rewrite historical projection outputs'
  fi
}

proof_lifecycle_before_completion() {
  local current_order_id="$1"
  api_upload_png "/api/v1/orders/${current_order_id}/proofs" "${child_jar}"
  proof_id="$(jq -er '.data.proofId' "${body_file}")"
  assert_positive "${proof_id}" 'uploaded proof id'
  api_json GET "/api/v1/orders/${current_order_id}/proofs" "${child_jar}"
  jq_assert 'buyer proof list contains uploaded proof' \
    '.data | any(.proofId == $proofId)' --argjson proofId "${proof_id}"
  api_json GET "/api/v1/admin/orders/${current_order_id}/proofs" "${admin_jar}"
  jq_assert 'admin proof list contains uploaded proof' \
    '.data | any(.proofId == $proofId)' --argjson proofId "${proof_id}"
  api_expect_failure GET "/api/v1/order-proofs/${proof_id}/download" \
    "${outsider_jar}" '' '403' 'PROOF_ACCESS_DENIED'
  api_json GET "/api/v1/order-proofs/${proof_id}/download" "${sponsor_jar}"
  signed_url="$(jq -er '.data.signedUrl' "${body_file}")"
  jq_assert 'signed proof URL must have an expiry' '.data.expiresAt != null'
  download_signed_png "${signed_url}"
  api_json GET "/api/v1/admin/order-proofs/${proof_id}/download" "${admin_jar}"
  jq_assert 'admin must receive an authorized signed proof URL' \
    '.data.signedUrl | type == "string" and length > 20'
}

delete_completed_proof_as_admin() {
  api_json DELETE "/api/v1/admin/order-proofs/${proof_id}" "${admin_jar}" \
    '{"reason":"E2E storage lifecycle cleanup"}'
  api_json GET "/api/v1/admin/orders/${order_id}/proofs" "${admin_jar}"
  jq_assert 'cleaned proof must disappear from active admin list' \
    '.data | all(.proofId != $proofId)' --argjson proofId "${proof_id}"
  cleaned_at="$(db_scalar "SELECT COALESCE(DATE_FORMAT(cleaned_at, '%Y-%m-%d'), '')
    FROM trade_order_proof WHERE id = ${proof_id}")"
  [[ -n "${cleaned_at}" ]] || fail 'proof metadata was not marked cleaned'
  wait_signed_url_deleted "${signed_url}"
}

run_refund_only() {
  local current_order_id="$1"
  refund_payload="$(jq -nc --argjson orderId "${current_order_id}" \
    --arg clientRequestId "refund-only-${run_key}" \
    '{orderId:$orderId,clientRequestId:$clientRequestId,type:"REFUND_ONLY",
      reason:"买家 E2E 退款",description:"全链路退款验收"}')"
  api_json POST '/api/v1/after-sales' "${child_jar}" "${refund_payload}"
  refund_after_sale_id="$(jq -er '.data.id' "${body_file}")"
  assert_after_sale_status "${refund_after_sale_id}" 'PENDING_ADMIN_REVIEW'
  api_json POST "/api/v1/admin/after-sales/${refund_after_sale_id}/review" "${admin_jar}" \
    '{"approve":true,"reason":"E2E refund approval","returnAddressJson":null}'
  assert_after_sale_status "${refund_after_sale_id}" 'PENDING_OFFLINE_REFUND'
  api_json POST "/api/v1/after-sales/superior/${refund_after_sale_id}/confirm-offline-refund" \
    "${sponsor_jar}" '{"reason":"E2E offline refund sent"}'
  assert_after_sale_status "${refund_after_sale_id}" 'PENDING_BUYER_REFUND_CONFIRMATION'
  api_json POST "/api/v1/after-sales/${refund_after_sale_id}/confirm-refund" "${child_jar}"
  assert_after_sale_status "${refund_after_sale_id}" 'COMPLETED'
  wait_db_equal 'refund-only outbox publication' \
    "SELECT status FROM sys_outbox_event
     WHERE aggregate_id = '${refund_after_sale_id}' AND event_type = 'AFTERSALE_COMPLETED'
     ORDER BY id DESC LIMIT 1" 'PUBLISHED' 60
  wait_db_equal 'refund-only evidence invalidation' \
    "SELECT status FROM membership_evidence
     WHERE source_order_id = ${current_order_id} ORDER BY id DESC LIMIT 1" 'INVALID' 30
  wait_db_equal 'refund-only membership reversal' \
    "SELECT level.code FROM membership_account account
     JOIN membership_level level ON level.id = account.current_level_id
     WHERE account.user_id = ${child_user_id}" 'BASIC' 30
}

run_return_refund() {
  local current_order_id="$1"
  return_payload="$(jq -nc --argjson orderId "${current_order_id}" \
    --arg clientRequestId "return-refund-${run_key}" \
    '{orderId:$orderId,clientRequestId:$clientRequestId,type:"RETURN_REFUND",
      reason:"买家 E2E 退货",description:"全链路退货退款验收"}')"
  api_json POST '/api/v1/after-sales' "${child_jar}" "${return_payload}"
  return_after_sale_id="$(jq -er '.data.id' "${body_file}")"
  assert_after_sale_status "${return_after_sale_id}" 'PENDING_ADMIN_REVIEW'
  review_payload="$(jq -nc --arg returnAddressJson \
    '{"recipientName":"E2E Returns","phone":"075500000000","address":"E2E Return Center"}' \
    '{approve:true,reason:"E2E return approval",returnAddressJson:$returnAddressJson}')"
  api_json POST "/api/v1/admin/after-sales/${return_after_sale_id}/review" \
    "${admin_jar}" "${review_payload}"
  assert_after_sale_status "${return_after_sale_id}" 'AWAITING_RETURN'
  return_shipment="$(jq -nc --arg trackingNo "RETURN-${run_key}" \
    '{carrier:"顺丰速运",trackingNo:$trackingNo}')"
  api_json POST "/api/v1/after-sales/${return_after_sale_id}/return-shipment" \
    "${child_jar}" "${return_shipment}"
  assert_after_sale_status "${return_after_sale_id}" 'RETURN_SHIPPED'
  api_json POST "/api/v1/admin/after-sales/${return_after_sale_id}/confirm-return-received" \
    "${admin_jar}" '{"reason":"E2E return received"}'
  assert_after_sale_status "${return_after_sale_id}" 'PENDING_OFFLINE_REFUND'
  api_json POST "/api/v1/after-sales/superior/${return_after_sale_id}/confirm-offline-refund" \
    "${sponsor_jar}" '{"reason":"E2E return refund sent"}'
  assert_after_sale_status "${return_after_sale_id}" 'PENDING_BUYER_REFUND_CONFIRMATION'
  api_json POST "/api/v1/after-sales/${return_after_sale_id}/confirm-refund" "${child_jar}"
  assert_after_sale_status "${return_after_sale_id}" 'COMPLETED'
  wait_db_equal 'return-refund outbox publication' \
    "SELECT status FROM sys_outbox_event
     WHERE aggregate_id = '${return_after_sale_id}' AND event_type = 'AFTERSALE_COMPLETED'
     ORDER BY id DESC LIMIT 1" 'PUBLISHED' 60
  wait_db_equal 'return-refund evidence invalidation' \
    "SELECT status FROM membership_evidence
     WHERE source_order_id = ${current_order_id} ORDER BY id DESC LIMIT 1" 'INVALID' 30
  wait_db_equal 'return-refund membership reversal' \
    "SELECT level.code FROM membership_account account
     JOIN membership_level level ON level.id = account.current_level_id
     WHERE account.user_id = ${child_user_id}" 'BASIC' 30
}

if [[ "${scope}" == 'full' ]]; then
  log 'establishing the five-qualified-direct precondition for v1 reward evidence'
  seed_direct_qualification_history
fi

log "submitting a real ${scope} order and reserving inventory"
if [[ "${scope}" == 'full' ]]; then
  submit_order 2 'primary'
else
  submit_order 1 'primary'
fi
primary_order_id="${order_id}"
proof_lifecycle_before_completion "${primary_order_id}"

if [[ "${scope}" == 'storage' ]]; then
  log 'waiting for the eight-second RustFS signed URL to reject the same object'
  wait_signed_url_expired "${signed_url}"
  log 'issuing and reading a new signed URL so deletion cannot pass via prior expiry'
  object_key="$(db_scalar "SELECT object_key FROM trade_order_proof WHERE id = ${proof_id}")"
  [[ -n "${object_key}" ]] || fail 'uploaded RustFS proof is missing its object key'
    api_json GET "/api/v1/order-proofs/${proof_id}/download" "${sponsor_jar}"
    deletion_signed_url="$(jq -er '.data.signedUrl' "${body_file}")"
    deletion_expires_at="$(jq -er '.data.expiresAt' "${body_file}")"
    [[ "${deletion_signed_url}" != "${signed_url}" ]] \
      || fail 'fresh proof URL was identical to the already-expired URL'
    assert_signed_url_targets_object "${deletion_signed_url}" "${object_key}"
    # Prove that the newly issued URL is live before deleting the object; a
    # stale/expired URL must never make the deletion assertion pass.
    download_signed_png "${deletion_signed_url}"
  log 'deleting the private proof through the buyer controller before confirmation'
  api_json DELETE "/api/v1/order-proofs/${proof_id}" "${child_jar}"
  api_json GET "/api/v1/orders/${primary_order_id}/proofs" "${child_jar}"
  jq_assert 'buyer-deleted proof must disappear from active list' \
    '.data | all(.proofId != $proofId)' --argjson proofId "${proof_id}"
  cleaned_at="$(db_scalar "SELECT COALESCE(DATE_FORMAT(cleaned_at, '%Y-%m-%d'), '')
    FROM trade_order_proof WHERE id = ${proof_id}")"
  [[ -n "${cleaned_at}" ]] || fail 'buyer-deleted proof metadata was not marked cleaned'
  assert_signed_url_deleted_before_expiry "${deletion_signed_url}" "${deletion_expires_at}"
  log 'completing the RustFS-backed order after proof object deletion'
  complete_order "${primary_order_id}" 'primary'
  log 'RustFS controller E2E passed: cookie login, order, multipart upload, authorization, signed read and delete.'
  exit 0
fi

log 'completing the primary order, freezing v1, publishing v2, then projecting from history'
complete_order "${primary_order_id}" 'primary' 'SUPER_MEMBER' 'true'

log 'asserting poison isolation, dead-letter metadata and valid projection progress'
wait_db_equal 'poison outbox reaches DEAD' \
  "SELECT status FROM sys_outbox_event WHERE id = ${poison_id}" 'DEAD' 60
assert_equal "$(db_scalar "SELECT attempt_count FROM sys_outbox_event WHERE id = ${poison_id}")" \
  "${outbox_max_attempts}" 'poison outbox attempt count'
poison_error="$(db_scalar "SELECT COALESCE(last_error, '') FROM sys_outbox_event WHERE id = ${poison_id}")"
if [[ "${poison_error}" != *'PROJECTION_ORDER_INVALID'* ]]; then
  fail 'poison outbox did not retain the sanitized projection error code'
fi
api_json GET '/api/v1/admin/outbox/dead-letters?page=1&pageSize=20' "${admin_jar}"
jq_assert 'protected dead-letter API contains the poison event' \
  '.data.items | any(.id == $id and .deadAt != null and .attemptCount == $attempts)' \
  --argjson id "${poison_id}" --argjson attempts "${outbox_max_attempts}"
api_json GET '/api/v1/admin/outbox/summary' "${admin_jar}"
jq_assert 'outbox summary reports a dead letter without blocking valid events' \
  '.data.deadCount >= 1'
replay_payload='{"reason":"E2E verifies protected dead-letter replay"}'
api_json POST "/api/v1/admin/outbox/dead-letters/${poison_id}/replay" \
  "${admin_jar}" "${replay_payload}"
jq_assert 'replay API returns a reset pending state and increments replay count' \
  '.data.outboxId == $id and .data.status == "PENDING" and .data.replayCount == 1' \
  --argjson id "${poison_id}"
wait_db_equal 'replayed poison returns to DEAD' \
  "SELECT status FROM sys_outbox_event WHERE id = ${poison_id}" 'DEAD' 60
assert_equal "$(db_scalar "SELECT replay_count FROM sys_outbox_event WHERE id = ${poison_id}")" \
  '1' 'poison replay count'

log 'deleting the completed order proof through the audited admin lifecycle'
delete_completed_proof_as_admin

log 'running the complete REFUND_ONLY chain and projection reversal'
run_refund_only "${primary_order_id}"

log 'creating a second completed order for RETURN_REFUND coverage'
submit_order 1 'return'
return_order_id="${order_id}"
complete_order "${return_order_id}" 'return'
log 'running the complete RETURN_REFUND chain and projection reversal'
run_return_refund "${return_order_id}"

log 'creating a separate pending order with a private proof for the recovery drill'
submit_order 1 'recovery'
recovery_order_id="${order_id}"
assert_equal "$(db_scalar "SELECT status FROM trade_order WHERE id = ${recovery_order_id}")" \
  'PENDING_SUPERIOR' 'recovery-drill order remains pending confirmation'
api_upload_png "/api/v1/orders/${recovery_order_id}/proofs" "${child_jar}"
recovery_proof_id="$(jq -er '.data.proofId' "${body_file}")"
assert_positive "${recovery_proof_id}" 'recovery-drill proof id'
assert_equal "$(db_scalar "SELECT COUNT(*) FROM trade_order_proof
  WHERE id = ${recovery_proof_id} AND order_id = ${recovery_order_id} AND cleaned_at IS NULL")" '1' \
  'recovery-drill active proof metadata'

log 'full business acceptance passed: sessions, order/inventory, storage, outbox, refunds.'
