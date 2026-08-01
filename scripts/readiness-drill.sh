#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${1:-http://127.0.0.1:8080}"
shift || true
base_url="${base_url%/}"
if [[ $# -eq 0 ]]; then
  set -- redis mysql
fi

for required in curl docker jq; do
  command -v "${required}" >/dev/null 2>&1 \
    || { echo "readiness-drill: required command is missing: ${required}" >&2; exit 2; }
done

project="${MARKET_SHOP_E2E_COMPOSE_PROJECT:-market-shop-e2e}"
env_file="${MARKET_SHOP_E2E_ENV_FILE:-.env.local.example}"
files_spec="${MARKET_SHOP_E2E_COMPOSE_FILES:-docker-compose.yml:docker-compose.local.yml:docker-compose.e2e.yml}"
profiles_spec="${MARKET_SHOP_E2E_COMPOSE_PROFILES:-}"
compose=(docker compose --project-name "${project}")
if [[ -n "${env_file}" ]]; then
  compose+=(--env-file "${env_file}")
fi
IFS=':' read -r -a files <<<"${files_spec}"
for file in "${files[@]}"; do
  compose+=(-f "${file}")
done
if [[ -n "${profiles_spec}" ]]; then
  IFS=':' read -r -a profiles <<<"${profiles_spec}"
  for profile in "${profiles[@]}"; do
    compose+=(--profile "${profile}")
  done
fi

body_file="$(mktemp)"
stopped_service=''

log() {
  printf '[readiness-drill] %s\n' "$*"
}

restore_stopped_service() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ -n "${stopped_service}" ]]; then
    log "trap is restoring ${stopped_service}"
    "${compose[@]}" up -d --wait --wait-timeout 180 "${stopped_service}" >/dev/null 2>&1 || true
  fi
  rm -f "${body_file}"
  exit "${exit_code}"
}
trap restore_stopped_service EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

public_status() {
  curl --silent --show-error --connect-timeout 3 --max-time 12 \
    --output "${body_file}" --write-out '%{http_code}' "${base_url}/healthz" || true
}

wait_public_up() {
  local deadline=$((SECONDS + 120))
  local status=''
  while (( SECONDS < deadline )); do
    status="$(public_status)"
    if [[ "${status}" == '200' ]] \
        && jq -e '.status == "UP"' "${body_file}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "readiness-drill: readiness did not recover; last HTTP ${status}" >&2
  "${compose[@]}" ps >&2 || true
  return 1
}

wait_public_down() {
  local service="$1"
  local deadline=$((SECONDS + 120))
  local status=''
  while (( SECONDS < deadline )); do
    status="$(public_status)"
    if [[ "${status}" == '503' ]] \
        && jq -e '.status == "DOWN"' "${body_file}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "readiness-drill: ${service} outage did not produce HTTP 503/DOWN; last HTTP ${status}" >&2
  "${compose[@]}" ps >&2 || true
  return 1
}

assert_internal_liveness() {
  local response
  response="$("${compose[@]}" exec -T app \
    curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
      http://127.0.0.1:8081/actuator/health/liveness)"
  jq -e '.status == "UP"' <<<"${response}" >/dev/null \
    || { echo 'readiness-drill: internal liveness was not UP' >&2; return 1; }
}

log 'asserting healthy readiness before dependency faults'
wait_public_up

for service in "$@"; do
  case "${service}" in
    redis|mysql|rustfs) ;;
    *)
      echo "readiness-drill: unsupported dependency: ${service}" >&2
      exit 2
      ;;
  esac
  log "stopping ${service} and waiting for readiness DOWN"
  "${compose[@]}" stop "${service}" >/dev/null
  stopped_service="${service}"
  wait_public_down "${service}"
  assert_internal_liveness
  log "restoring ${service} and waiting for readiness UP"
  "${compose[@]}" up -d --wait --wait-timeout 180 "${service}" >/dev/null
  stopped_service=''
  wait_public_up
done

log 'dependency fault drill passed: readiness failed closed while liveness stayed UP.'
