#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/lib.sh
source "${SCRIPT_DIR}/ops/lib.sh"

case "${1:-}" in
  --help|-h)
    echo "usage: scripts/migration-preflight.sh <repository@sha256:digest> <backup-directory>"
    exit 0
    ;;
  --dry-run)
    echo "dry-run: restore backup to isolated database -> start candidate -> Flyway/readiness/smoke -> drop database"
    exit 0
    ;;
esac

ops_init
ops_require_command gzip

candidate_image="${1:-}"
backup_directory="${2:-}"
[[ -n "${candidate_image}" && -n "${backup_directory}" ]] \
  || ops_die "usage: scripts/migration-preflight.sh <repository@sha256:digest> <backup-directory>"
ops_validate_digest "${candidate_image}"
backup_directory="$(cd "${backup_directory}" && pwd)"
ops_verify_manifest "${backup_directory}" >/dev/null

preflight_database="market_shop_preflight_$(date -u +%Y%m%d%H%M%S)_$$"
ops_validate_mysql_identifier "${preflight_database}" "preflight database"
preflight_database_user="$(ops_container_env mysql MYSQL_USER)"
ops_validate_mysql_identifier "${preflight_database_user}" "MYSQL_USER"
temporary_directory=''
preflight_started=false
cleanup() {
  local exit_code=$?
  if [[ "${preflight_started}" == "true" ]]; then
    MARKET_SHOP_CANDIDATE_IMAGE="${candidate_image}" \
    MARKET_SHOP_RELEASE_DB_NAME="${preflight_database}" \
      ops_compose_release --profile release rm --stop --force candidate >/dev/null 2>&1 || true
    ops_compose exec -T mysql sh -euc '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root \
        --execute="DROP DATABASE IF EXISTS \`$1\`"
    ' sh "${preflight_database}" >/dev/null 2>&1 || true
    preflight_volume="$(ops_find_volume release-candidate-uploads)"
    [[ -z "${preflight_volume}" ]] \
      || docker volume rm "${preflight_volume}" >/dev/null 2>&1 \
      || true
  fi
  [[ -z "${temporary_directory}" ]] || rm -rf "${temporary_directory}"
  ops_release_maintenance_lock || true
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ops_acquire_maintenance_lock preflight
preflight_started=true
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/market-shop-preflight.XXXXXX")"

ops_materialize_payload "${backup_directory}" mysql.sql.gz "${temporary_directory}/mysql.sql.gz"
ops_log "creating isolated migration-preflight database ${preflight_database}"
ops_compose exec -T mysql sh -euc '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --execute="
    CREATE DATABASE \`$1\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
    GRANT ALL PRIVILEGES ON \`$1\`.* TO \"$2\"@\"%\";
  "
' sh "${preflight_database}" "${preflight_database_user}"
gzip -dc "${temporary_directory}/mysql.sql.gz" \
  | ops_compose exec -T mysql sh -euc '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root "$1"
    ' sh "${preflight_database}"

export MARKET_SHOP_CANDIDATE_IMAGE="${candidate_image}"
export MARKET_SHOP_RELEASE_DB_NAME="${preflight_database}"
export MARKET_SHOP_RELEASE_STORAGE_VOLUME=release-candidate-uploads
export MARKET_SHOP_RELEASE_REDIS_DATABASE=15

ops_log "starting candidate against the isolated database; live traffic is unchanged"
if ! ops_compose_release --profile release up -d --no-deps --wait --wait-timeout 300 candidate; then
  ops_compose_release --profile release logs --no-color candidate >&2 || true
  ops_die "candidate migration preflight failed"
fi

failed_migrations="$(ops_compose exec -T mysql sh -euc '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM \`$1\`.flyway_schema_history WHERE success = 0"
' sh "${preflight_database}")"
[[ "${failed_migrations}" == "0" ]] || ops_die "preflight Flyway history contains a failed migration"

"${SCRIPT_DIR}/production-verify.sh" "http://127.0.0.1:${MARKET_SHOP_CANDIDATE_PORT:-18080}"
ops_log "migration preflight passed; no production traffic was switched"
