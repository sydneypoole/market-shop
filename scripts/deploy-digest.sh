#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/lib.sh
source "${SCRIPT_DIR}/ops/lib.sh"

case "${1:-}" in
  --help|-h)
    echo "usage: scripts/deploy-digest.sh <repository@sha256:digest>"
    exit 0
    ;;
  --dry-run)
    echo "dry-run: pull digest -> pre-deploy backup -> isolated migration preflight -> candidate health -> cutover"
    exit 0
    ;;
esac

ops_init

candidate_image="${1:-}"
[[ -n "${candidate_image}" ]] \
  || ops_die "usage: scripts/deploy-digest.sh <repository@sha256:digest>"
ops_validate_digest "${candidate_image}"

candidate_running=false
backup_output=''
cleanup() {
  local exit_code=$?
  if [[ "${candidate_running}" == "true" ]]; then
    MARKET_SHOP_CANDIDATE_IMAGE="${candidate_image}" \
      ops_compose_release --profile release rm --stop --force candidate >/dev/null 2>&1 || true
  fi
  [[ -z "${backup_output}" ]] || rm -f "${backup_output}"
  ops_release_maintenance_lock || true
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ops_acquire_maintenance_lock deploy

previous_image="$(ops_current_image)" \
  || ops_die "cannot determine current immutable image; initialize OPS state with a digest deployment"
ops_validate_digest "${previous_image}"
if [[ "${previous_image}" == "${candidate_image}" ]]; then
  ops_die "candidate digest is already active"
fi

ops_log "pulling immutable candidate ${candidate_image}"
docker pull "${candidate_image}" >/dev/null

ops_log "creating mandatory pre-deploy backup"
backup_output="${OPS_STATE_DIR}/.pre-deploy-backup.$$"
BACKUP_REASON=pre-deploy \
  ops_run_maintenance_child backup "${SCRIPT_DIR}/backup.sh" > "${backup_output}"
backup_directory="$(tail -n 1 "${backup_output}")"
rm -f "${backup_output}"
backup_output=''
[[ -n "${backup_directory}" ]] || ops_die "pre-deploy backup did not return a directory"
printf '%s\n' "${backup_directory}" > "${OPS_STATE_DIR}/last-pre-deploy-backup"

ops_run_maintenance_child preflight \
  "${SCRIPT_DIR}/migration-preflight.sh" "${candidate_image}" "${backup_directory}"

production_database="$(ops_compose exec -T mysql printenv MYSQL_DATABASE)"
ops_validate_mysql_identifier "${production_database}" "MYSQL_DATABASE"
export MARKET_SHOP_CANDIDATE_IMAGE="${candidate_image}"
export MARKET_SHOP_RELEASE_DB_NAME="${production_database}"
export MARKET_SHOP_RELEASE_STORAGE_VOLUME=market-shop-uploads
export MARKET_SHOP_RELEASE_REDIS_DATABASE=15

ops_log "starting candidate on the production dependencies with scheduling disabled"
candidate_running=true
if ! ops_compose_release --profile release up -d --no-deps --wait --wait-timeout 300 candidate; then
  ops_compose_release --profile release logs --no-color candidate >&2 || true
  ops_die "candidate startup or live migration failed; traffic was not switched"
fi
"${SCRIPT_DIR}/production-verify.sh" "http://127.0.0.1:${MARKET_SHOP_CANDIDATE_PORT:-18080}"

printf '%s\n' "${previous_image}" > "${OPS_STATE_DIR}/previous.digest"
ops_set_active_image "${candidate_image}"

ops_log "candidate is healthy; switching app service to the verified digest"
if ! ops_compose up -d --no-deps --wait --wait-timeout 300 app; then
  ops_log "cutover failed; restoring previous digest"
  ops_set_active_image "${previous_image}"
  ops_compose up -d --no-deps --wait --wait-timeout 300 app || true
  ops_die "cutover failed and previous image was requested"
fi

public_url="${MARKET_SHOP_PUBLIC_URL:-$(ops_default_public_url)}"
if ! ops_public_verify "${public_url}"; then
  ops_log "post-cutover verification failed; restoring previous digest"
  ops_set_active_image "${previous_image}"
  ops_compose up -d --no-deps --wait --wait-timeout 300 app || true
  ops_die "post-cutover verification failed and previous image was requested"
fi

printf '%s\n' "${candidate_image}" > "${OPS_STATE_DIR}/current.digest"
candidate_running=false
ops_compose_release --profile release rm --stop --force candidate >/dev/null

ops_log "deployment completed: ${candidate_image}"
ops_log "rollback digest: ${previous_image}"
ops_log "pre-deploy backup: ${backup_directory}"
