#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/lib.sh
source "${SCRIPT_DIR}/ops/lib.sh"

case "${1:-}" in
  --help|-h)
    echo "usage: scripts/rollback-digest.sh [repository@sha256:digest]"
    exit 0
    ;;
  --dry-run)
    echo "dry-run: pre-rollback backup -> previous digest -> readiness/smoke (Flyway remains forward-only)"
    exit 0
    ;;
esac

ops_init

target_image="${1:-}"
if [[ -z "${target_image}" ]]; then
  ops_require_file "${OPS_STATE_DIR}/previous.digest"
  target_image="$(tr -d '[:space:]' < "${OPS_STATE_DIR}/previous.digest")"
fi
ops_validate_digest "${target_image}"

cleanup() {
  local exit_code=$?
  ops_release_maintenance_lock || true
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ops_acquire_maintenance_lock rollback

current_image="$(ops_current_image)" || ops_die "cannot determine current image"
ops_validate_digest "${current_image}"
[[ "${current_image}" != "${target_image}" ]] || ops_die "target digest is already active"

if [[ "${ROLLBACK_SKIP_BACKUP:-false}" != "true" ]]; then
  ops_log "creating pre-rollback backup"
  BACKUP_REASON=pre-rollback \
    ops_run_maintenance_child backup "${SCRIPT_DIR}/backup.sh" \
    > "${OPS_STATE_DIR}/last-pre-rollback-backup"
fi

docker pull "${target_image}" >/dev/null
ops_set_active_image "${target_image}"
if ! ops_compose up -d --no-deps --wait --wait-timeout 300 app; then
  ops_set_active_image "${current_image}"
  ops_compose up -d --no-deps --wait --wait-timeout 300 app || true
  ops_die "rollback target failed readiness; current digest was requested again"
fi

public_url="${MARKET_SHOP_PUBLIC_URL:-$(ops_default_public_url)}"
if ! ops_public_verify "${public_url}"; then
  ops_set_active_image "${current_image}"
  ops_compose up -d --no-deps --wait --wait-timeout 300 app || true
  ops_die "rollback target failed smoke verification; current digest was requested again"
fi

printf '%s\n' "${current_image}" > "${OPS_STATE_DIR}/previous.digest"
printf '%s\n' "${target_image}" > "${OPS_STATE_DIR}/current.digest"
ops_log "rollback completed: ${target_image}"
ops_log "database migrations were not reversed; Flyway remains forward-only"
