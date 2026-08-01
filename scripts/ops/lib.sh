#!/usr/bin/env bash

OPS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OPS_TOOL_IMAGE="${MARKET_SHOP_BACKUP_TOOL_IMAGE:-alpine:3.22}"
OPS_ENV_FILE="${MARKET_SHOP_ENV_FILE:-${OPS_ROOT}/.env}"
[[ "${OPS_ENV_FILE}" == /* ]] || OPS_ENV_FILE="${OPS_ROOT}/${OPS_ENV_FILE#./}"
OPS_COMPOSE_FILE="${MARKET_SHOP_COMPOSE_FILE:-${OPS_ROOT}/docker-compose.yml}"
OPS_COMPOSE_FILES_SPEC="${MARKET_SHOP_COMPOSE_FILES:-${COMPOSE_FILE:-${OPS_COMPOSE_FILE}}}"
OPS_RELEASE_FILE="${MARKET_SHOP_RELEASE_COMPOSE_FILE:-${OPS_ROOT}/docker-compose.release.yml}"
OPS_STATE_DIR="${MARKET_SHOP_RELEASE_STATE_DIR:-${OPS_ROOT}/.market-shop-release}"
OPS_ACTIVE_ENV="${OPS_STATE_DIR}/active.env"
OPS_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-market-shop}"
OPS_MAINTENANCE_LOCK_DIR="${OPS_STATE_DIR}/maintenance.lock"
OPS_MAINTENANCE_HELD=false
OPS_MAINTENANCE_NESTED=false
OPS_MAINTENANCE_OPERATION=''
OPS_MAINTENANCE_LOCAL_TOKEN=''
OPS_MAINTENANCE_LOCAL_OWNER_PID=''

ops_log() {
  printf '[market-shop-ops] %s\n' "$*" >&2
}

ops_die() {
  ops_log "ERROR: $*"
  exit 1
}

ops_require_command() {
  command -v "$1" >/dev/null 2>&1 || ops_die "required command not found: $1"
}

ops_require_file() {
  [[ -f "$1" ]] || ops_die "required file not found: $1"
}

ops_init() {
  ops_require_command docker
  ops_require_command curl
  ops_require_file "${OPS_ENV_FILE}"
  mkdir -p "${OPS_STATE_DIR}"
}

ops_maintenance_pair_allowed() {
  case "$1:$2" in
    deploy:backup|deploy:preflight|rollback:backup) return 0 ;;
    *) return 1 ;;
  esac
}

ops_acquire_maintenance_lock() {
  local operation="$1"
  local inherited_token="${MARKET_SHOP_MAINTENANCE_INHERIT_TOKEN:-}"
  local inherited_owner_pid="${MARKET_SHOP_MAINTENANCE_OWNER_PID:-}"
  local inherited_parent_operation="${MARKET_SHOP_MAINTENANCE_PARENT_OPERATION:-}"
  local inherited_child_operation="${MARKET_SHOP_MAINTENANCE_CHILD_OPERATION:-}"
  local inherited_delegate_pid="${MARKET_SHOP_MAINTENANCE_DELEGATE_PID:-}"
  local recorded_token recorded_owner_pid recorded_operation token

  [[ "${operation}" =~ ^(backup|restore|deploy|rollback|preflight)$ ]] \
    || ops_die "invalid maintenance operation: ${operation}"
  [[ "${OPS_MAINTENANCE_HELD}" != "true" ]] \
    || ops_die "maintenance lock was already acquired by this process"

  if [[ -n "${inherited_token}" ]]; then
    [[ -d "${OPS_MAINTENANCE_LOCK_DIR}" ]] \
      || ops_die "inherited maintenance capability has no live lock"
    recorded_token="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/token" 2>/dev/null || true)"
    recorded_owner_pid="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/owner.pid" 2>/dev/null || true)"
    recorded_operation="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/operation" 2>/dev/null || true)"
    [[ "${inherited_token}" =~ ^[a-f0-9]{64}$ && "${inherited_token}" == "${recorded_token}" ]] \
      || ops_die "invalid inherited maintenance capability"
    [[ "${inherited_owner_pid}" =~ ^[0-9]+$ \
      && "${inherited_owner_pid}" == "${recorded_owner_pid}" ]] \
      || ops_die "inherited maintenance owner does not match the lock"
    kill -0 "${recorded_owner_pid}" 2>/dev/null \
      || ops_die "inherited maintenance owner is no longer running"
    [[ "${inherited_delegate_pid}" =~ ^[0-9]+$ \
      && "${inherited_delegate_pid}" == "${PPID}" ]] \
      || ops_die "inherited maintenance capability was not delegated to this process"
    [[ "${inherited_parent_operation}" == "${recorded_operation}" \
      && "${inherited_child_operation}" == "${operation}" ]] \
      || ops_die "inherited maintenance operation does not match the lock"
    ops_maintenance_pair_allowed "${recorded_operation}" "${operation}" \
      || ops_die "maintenance nesting ${recorded_operation} -> ${operation} is not allowed"

    OPS_MAINTENANCE_HELD=true
    OPS_MAINTENANCE_NESTED=true
    OPS_MAINTENANCE_OPERATION="${operation}"
    OPS_MAINTENANCE_LOCAL_TOKEN="${inherited_token}"
    OPS_MAINTENANCE_LOCAL_OWNER_PID="${recorded_owner_pid}"
    return 0
  fi

  if ! mkdir -m 700 "${OPS_MAINTENANCE_LOCK_DIR}" 2>/dev/null; then
    recorded_operation="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/operation" 2>/dev/null || printf 'unknown')"
    recorded_owner_pid="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/owner.pid" 2>/dev/null || printf 'unknown')"
    ops_die "maintenance lock is held by ${recorded_operation} (pid ${recorded_owner_pid})"
  fi

  token="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  if [[ ! "${token}" =~ ^[a-f0-9]{64}$ ]] || ! (
    umask 077
    printf '%s\n' "${token}" > "${OPS_MAINTENANCE_LOCK_DIR}/token"
    printf '%s\n' "$$" > "${OPS_MAINTENANCE_LOCK_DIR}/owner.pid"
    printf '%s\n' "${operation}" > "${OPS_MAINTENANCE_LOCK_DIR}/operation"
  ); then
    rm -f "${OPS_MAINTENANCE_LOCK_DIR}/token" \
      "${OPS_MAINTENANCE_LOCK_DIR}/owner.pid" \
      "${OPS_MAINTENANCE_LOCK_DIR}/operation"
    rmdir "${OPS_MAINTENANCE_LOCK_DIR}" 2>/dev/null || true
    ops_die "could not initialize the maintenance lock capability"
  fi

  OPS_MAINTENANCE_HELD=true
  OPS_MAINTENANCE_NESTED=false
  OPS_MAINTENANCE_OPERATION="${operation}"
  OPS_MAINTENANCE_LOCAL_TOKEN="${token}"
  OPS_MAINTENANCE_LOCAL_OWNER_PID="$$"
}

ops_run_maintenance_child() {
  local child_operation="$1"
  shift
  [[ "${OPS_MAINTENANCE_HELD}" == "true" && "${OPS_MAINTENANCE_NESTED}" == "false" ]] \
    || ops_die "only the outer maintenance owner may delegate a child operation"
  ops_maintenance_pair_allowed "${OPS_MAINTENANCE_OPERATION}" "${child_operation}" \
    || ops_die "maintenance nesting ${OPS_MAINTENANCE_OPERATION} -> ${child_operation} is not allowed"
  [[ $# -gt 0 ]] || ops_die "maintenance child command is required"

  MARKET_SHOP_MAINTENANCE_INHERIT_TOKEN="${OPS_MAINTENANCE_LOCAL_TOKEN}" \
  MARKET_SHOP_MAINTENANCE_OWNER_PID="${OPS_MAINTENANCE_LOCAL_OWNER_PID}" \
  MARKET_SHOP_MAINTENANCE_PARENT_OPERATION="${OPS_MAINTENANCE_OPERATION}" \
  MARKET_SHOP_MAINTENANCE_CHILD_OPERATION="${child_operation}" \
  MARKET_SHOP_MAINTENANCE_DELEGATE_PID="$$" \
    "$@"
}

ops_release_maintenance_lock() {
  local recorded_token recorded_owner_pid
  if [[ "${OPS_MAINTENANCE_HELD}" != "true" ]]; then
    return 0
  fi
  if [[ "${OPS_MAINTENANCE_NESTED}" == "true" ]]; then
    OPS_MAINTENANCE_HELD=false
    return 0
  fi

  recorded_token="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/token" 2>/dev/null || true)"
  recorded_owner_pid="$(cat "${OPS_MAINTENANCE_LOCK_DIR}/owner.pid" 2>/dev/null || true)"
  if [[ "${recorded_token}" != "${OPS_MAINTENANCE_LOCAL_TOKEN}" \
    || "${recorded_owner_pid}" != "${OPS_MAINTENANCE_LOCAL_OWNER_PID}" \
    || "${recorded_owner_pid}" != "$$" ]]; then
    ops_log "ERROR: maintenance lock ownership changed; leaving it in place"
    return 1
  fi
  rm -f "${OPS_MAINTENANCE_LOCK_DIR}/token" \
    "${OPS_MAINTENANCE_LOCK_DIR}/owner.pid" \
    "${OPS_MAINTENANCE_LOCK_DIR}/operation"
  rmdir "${OPS_MAINTENANCE_LOCK_DIR}" 2>/dev/null || {
    ops_log "ERROR: maintenance lock directory could not be removed"
    return 1
  }
  OPS_MAINTENANCE_HELD=false
  OPS_MAINTENANCE_LOCAL_TOKEN=''
  OPS_MAINTENANCE_LOCAL_OWNER_PID=''
}

ops_compose() {
  local args=()
  local compose_file
  local compose_files=()
  IFS=':' read -r -a compose_files <<< "${OPS_COMPOSE_FILES_SPEC}"
  for compose_file in "${compose_files[@]}"; do
    [[ "${compose_file}" == /* ]] || compose_file="${OPS_ROOT}/${compose_file}"
    args+=(-f "${compose_file}")
  done
  args+=(--env-file "${OPS_ENV_FILE}")
  if [[ -f "${OPS_ACTIVE_ENV}" ]]; then
    args+=(--env-file "${OPS_ACTIVE_ENV}")
  fi
  docker compose "${args[@]}" "$@"
}

ops_compose_release() {
  local args=()
  local compose_file
  local compose_files=()
  IFS=':' read -r -a compose_files <<< "${OPS_COMPOSE_FILES_SPEC}"
  for compose_file in "${compose_files[@]}"; do
    [[ "${compose_file}" == /* ]] || compose_file="${OPS_ROOT}/${compose_file}"
    args+=(-f "${compose_file}")
  done
  args+=(-f "${OPS_RELEASE_FILE}" --env-file "${OPS_ENV_FILE}")
  if [[ -f "${OPS_ACTIVE_ENV}" ]]; then
    args+=(--env-file "${OPS_ACTIVE_ENV}")
  fi
  docker compose "${args[@]}" "$@"
}

ops_validate_digest() {
  [[ "$1" =~ ^[^[:space:]@]+@sha256:[a-f0-9]{64}$ ]] \
    || ops_die "image must use repository@sha256:<64 lowercase hex>"
}

ops_validate_mysql_identifier() {
  local value="$1"
  local label="${2:-MySQL identifier}"
  local normalized
  [[ "${value}" =~ ^[A-Za-z0-9_]+$ ]] || ops_die "${label} contains unsafe characters"
  normalized="$(printf '%s' "${value}" | tr '[:upper:]' '[:lower:]')"
  case "${normalized}" in
    mysql|information_schema|performance_schema|sys)
      ops_die "${label} must not target a MySQL system schema"
      ;;
  esac
}

ops_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

ops_verify_manifest() {
  local directory="$1"
  ops_require_file "${directory}/SHA256SUMS"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${directory}" && sha256sum --check SHA256SUMS)
  else
    (cd "${directory}" && shasum -a 256 --check SHA256SUMS)
  fi
}

ops_write_manifest() {
  local directory="$1"
  (
    cd "${directory}"
    find . -maxdepth 1 -type f ! -name SHA256SUMS -print \
      | LC_ALL=C sort \
      | while IFS= read -r file; do
          ops_sha256 "${file#./}"
        done > SHA256SUMS
  )
}

ops_find_volume() {
  local logical_name="$1"
  docker volume ls --quiet \
    --filter "label=com.docker.compose.project=${OPS_PROJECT_NAME}" \
    --filter "label=com.docker.compose.volume=${logical_name}" \
    | head -n 1
}

ops_ensure_volume() {
  local logical_name="$1"
  local volume
  volume="$(ops_find_volume "${logical_name}")"
  if [[ -z "${volume}" ]]; then
    volume="${OPS_PROJECT_NAME}_${logical_name}"
    docker volume create \
      --label "com.docker.compose.project=${OPS_PROJECT_NAME}" \
      --label "com.docker.compose.volume=${logical_name}" \
      "${volume}" >/dev/null
  fi
  printf '%s\n' "${volume}"
}

ops_volume_tree_digest() {
  local volume="$1"
  docker run --rm --read-only \
    --volume "${volume}:/source:ro" \
    "${OPS_TOOL_IMAGE}" sh -euc '
      cd /source
      find . -type f -exec sha256sum "{}" ";" | LC_ALL=C sort | sha256sum | awk "{print \$1}"
    '
}

ops_archive_volume() {
  local volume="$1"
  local destination_directory="$2"
  local archive_name="$3"
  docker run --rm --read-only \
    --volume "${volume}:/source:ro" \
    --volume "${destination_directory}:/backup" \
    "${OPS_TOOL_IMAGE}" \
    tar -czf "/backup/${archive_name}" -C /source .
}

ops_volume_is_empty() {
  local volume="$1"
  docker run --rm --read-only --volume "${volume}:/source:ro" "${OPS_TOOL_IMAGE}" \
    sh -euc '! find /source -mindepth 1 -print -quit | grep -q .'
}

ops_restore_volume() {
  local volume="$1"
  local source_directory="$2"
  local archive_name="$3"
  docker run --rm \
    --volume "${volume}:/target" \
    --volume "${source_directory}:/backup:ro" \
    "${OPS_TOOL_IMAGE}" \
    sh -euc 'find /target -mindepth 1 -delete; tar -xzf "/backup/$1" -C /target' sh "${archive_name}"
}

ops_wait_container_health() {
  local service="$1"
  local timeout_seconds="${2:-180}"
  local container_id status started now
  container_id="$(ops_compose ps --quiet "${service}")"
  [[ -n "${container_id}" ]] || return 1
  started="$(date +%s)"
  while true; do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
    if [[ "${status}" == "healthy" || "${status}" == "running" ]]; then
      return 0
    fi
    if [[ "${status}" == "unhealthy" || "${status}" == "exited" || "${status}" == "dead" ]]; then
      return 1
    fi
    now="$(date +%s)"
    (( now - started < timeout_seconds )) || return 1
    sleep 2
  done
}

ops_current_image() {
  local container_id image reference
  container_id="$(ops_compose ps --quiet app)"
  [[ -n "${container_id}" ]] || return 1
  reference="$(docker inspect --format '{{.Config.Image}}' "${container_id}")"
  if [[ "${reference}" =~ @sha256:[a-f0-9]{64}$ ]]; then
    printf '%s\n' "${reference}"
    return 0
  fi
  image="$(docker inspect --format '{{.Image}}' "${container_id}")"
  docker image inspect --format '{{index .RepoDigests 0}}' "${image}" 2>/dev/null
}

ops_container_env() {
  local service="$1"
  local key="$2"
  local container_id
  container_id="$(ops_compose ps --all --quiet "${service}")"
  [[ -n "${container_id}" ]] || return 1
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${container_id}" \
    | awk -F= -v wanted="${key}" '$1 == wanted {sub(/^[^=]*=/, ""); print; exit}'
}

ops_set_active_image() {
  local digest="$1"
  ops_validate_digest "${digest}"
  mkdir -p "${OPS_STATE_DIR}"
  local temporary="${OPS_ACTIVE_ENV}.tmp.$$"
  printf 'MARKET_SHOP_IMAGE=%s\n' "${digest}" > "${temporary}"
  chmod 600 "${temporary}"
  mv "${temporary}" "${OPS_ACTIVE_ENV}"
}

ops_materialize_payload() {
  local backup_directory="$1"
  local payload="$2"
  local destination="$3"
  if [[ -f "${backup_directory}/${payload}" ]]; then
    cp "${backup_directory}/${payload}" "${destination}"
    return
  fi
  if [[ -f "${backup_directory}/${payload}.age" ]]; then
    ops_require_command age
    [[ -n "${RESTORE_AGE_IDENTITY:-}" ]] \
      || ops_die "RESTORE_AGE_IDENTITY is required for encrypted backup"
    age --decrypt --identity "${RESTORE_AGE_IDENTITY}" \
      --output "${destination}" "${backup_directory}/${payload}.age"
    return
  fi
  if [[ -n "${RESTORE_DECRYPT_HOOK:-}" && -x "${RESTORE_DECRYPT_HOOK}" ]]; then
    "${RESTORE_DECRYPT_HOOK}" "${backup_directory}" "${payload}" "${destination}"
    [[ -f "${destination}" ]] || ops_die "decrypt hook did not produce ${payload}"
    return
  fi
  ops_die "backup payload not found: ${payload}"
}

ops_public_verify() {
  local base_url="$1"
  "${OPS_ROOT}/scripts/production-verify.sh" "${base_url}"
}

ops_default_public_url() {
  local published
  published="$(ops_compose port app 8080 2>/dev/null | head -n 1)"
  [[ -n "${published}" ]] || return 1
  if [[ "${published}" == 0.0.0.0:* ]]; then
    published="127.0.0.1:${published##*:}"
  elif [[ "${published}" == \[::\]:* ]]; then
    published="127.0.0.1:${published##*:}"
  fi
  printf 'http://%s\n' "${published}"
}
