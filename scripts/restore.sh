#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/lib.sh
source "${SCRIPT_DIR}/ops/lib.sh"

case "${1:-}" in
  --help|-h)
    echo "usage: RESTORE_CONFIRM=YES_RESTORE scripts/restore.sh <backup-directory>"
    exit 0
    ;;
  --dry-run)
    echo "dry-run: verify manifest/provider/object_snapshot_mode -> require empty target -> restore DB/objects (raw RustFS only for explicit bundled mode; otherwise OBJECT_RESTORE_HOOK) -> FLUSHDB -> Flyway/digest/smoke"
    exit 0
    ;;
esac

ops_init
ops_require_command gzip

# A raw rustfs-data archive is safe to apply only to the explicitly selected
# bundled RustFS belonging to this Compose project.  In particular, a running
# service named `rustfs` is not evidence that the backup's S3 endpoint uses
# that volume; external S3 restores must go through OBJECT_RESTORE_HOOK.
ops_endpoint_host() {
  local endpoint="$1"
  local authority host
  [[ "${endpoint}" =~ ^[A-Za-z][A-Za-z0-9+.-]*:// ]] || return 1
  authority="${endpoint#*://}"
  authority="${authority%%/*}"
  authority="${authority%%\?*}"
  authority="${authority%%#*}"
  authority="${authority##*@}"
  if [[ "${authority}" == \[*\]* ]]; then
    host="${authority#\[}"
    host="${host%%\]*}"
  else
    host="${authority%%:*}"
  fi
  [[ -n "${host}" ]] || return 1
  printf '%s\n' "${host}" | tr '[:upper:]' '[:lower:]'
}

bundled_rustfs_container=''
bundled_rustfs_volume=''
ops_validate_bundled_rustfs() {
  local endpoint="$1"
  local endpoint_host project service aliases volume_project volume_label mount_name

  endpoint_host="$(ops_endpoint_host "${endpoint}" 2>/dev/null || true)"
  [[ "${endpoint_host}" == "rustfs.localhost" ]] \
    || ops_die "bundled s3 mode requires MARKET_SHOP_RUSTFS_ENDPOINT host rustfs.localhost"

  bundled_rustfs_container="$(ops_compose --profile rustfs ps --status running --quiet rustfs 2>/dev/null || true)"
  [[ -n "${bundled_rustfs_container}" ]] \
    || ops_die "bundled s3 mode requires a running rustfs service in compose project ${OPS_PROJECT_NAME}"
  [[ "$(printf '%s\n' "${bundled_rustfs_container}" | awk 'NF {count++} END {print count + 0}')" == "1" ]] \
    || ops_die "bundled s3 mode requires exactly one running rustfs service"

  project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' \
    "${bundled_rustfs_container}" 2>/dev/null || true)"
  service="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' \
    "${bundled_rustfs_container}" 2>/dev/null || true)"
  [[ "${project}" == "${OPS_PROJECT_NAME}" && "${service}" == "rustfs" ]] \
    || ops_die "bundled rustfs container is not owned by compose project ${OPS_PROJECT_NAME}"

  aliases="$(docker inspect --format '{{range .NetworkSettings.Networks}}{{range .Aliases}}{{println .}}{{end}}{{end}}' \
    "${bundled_rustfs_container}" 2>/dev/null || true)"
  printf '%s\n' "${aliases}" | grep -Fqx 'rustfs.localhost' \
    || ops_die "bundled rustfs container has no rustfs.localhost network alias"

  bundled_rustfs_volume="$(ops_find_volume rustfs-data | head -n 1 || true)"
  [[ -n "${bundled_rustfs_volume}" ]] \
    || ops_die "bundled s3 mode requires the ${OPS_PROJECT_NAME}_rustfs-data compose volume"
  volume_project="$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' \
    "${bundled_rustfs_volume}" 2>/dev/null || true)"
  volume_label="$(docker volume inspect --format '{{index .Labels "com.docker.compose.volume"}}' \
    "${bundled_rustfs_volume}" 2>/dev/null || true)"
  [[ "${volume_project}" == "${OPS_PROJECT_NAME}" && "${volume_label}" == "rustfs-data" ]] \
    || ops_die "rustfs-data volume is not owned by compose project ${OPS_PROJECT_NAME}"

  mount_name="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' \
    "${bundled_rustfs_container}" 2>/dev/null || true)"
  [[ "${mount_name}" == "${bundled_rustfs_volume}" ]] \
    || ops_die "bundled rustfs is not mounted from the project rustfs-data volume"

  ops_wait_container_health rustfs 120 \
    || ops_die "bundled rustfs service is not healthy"
}

backup_directory="${1:-}"
[[ -n "${backup_directory}" ]] || ops_die "usage: RESTORE_CONFIRM=YES_RESTORE scripts/restore.sh <backup-directory>"
backup_directory="$(cd "${backup_directory}" 2>/dev/null && pwd)" \
  || ops_die "backup directory does not exist"
[[ "${RESTORE_CONFIRM:-}" == "YES_RESTORE" ]] \
  || ops_die "set RESTORE_CONFIRM=YES_RESTORE after reviewing the target environment"

ops_log "verifying backup manifest before changing the target"
ops_verify_manifest "${backup_directory}"
ops_require_file "${backup_directory}/backup.meta"
backup_provider="$(awk -F= '$1 == "storage_provider" {print $2; exit}' "${backup_directory}/backup.meta")"
[[ "${backup_provider}" == "local" || "${backup_provider}" == "s3" ]] \
  || ops_die "backup.meta has an invalid storage_provider"

# New backups record the object snapshot provenance.  A missing mode is
# tolerated only for legacy local-volume backups; an S3 backup without this
# declaration is intentionally treated as unknown and can only be handled by
# an explicit object restore hook (never by a guessed raw-volume restore).
backup_snapshot_mode="$(awk -F= '$1 == "object_snapshot_mode" {print $2; exit}' "${backup_directory}/backup.meta")"
if [[ -z "${backup_snapshot_mode}" && "${backup_provider}" == "local" ]]; then
  backup_snapshot_mode='local-volume'
fi
case "${backup_snapshot_mode}" in
  local-volume|bundled-rustfs|external-hook) ;;
  '') backup_snapshot_mode='unknown' ;;
  *) ops_die "backup.meta has an invalid object_snapshot_mode" ;;
esac
if [[ -f "${backup_directory}/external-object.marker" ]]; then
  backup_marker="$(tr -d '[:space:]' < "${backup_directory}/external-object.marker")"
  case "${backup_marker}" in
    external|external-hook)
      [[ "${backup_snapshot_mode}" == "external-hook" || "${backup_snapshot_mode}" == "unknown" ]] \
        || ops_die "backup metadata conflicts with external object marker"
      ;;
    *) ops_die "external object marker is invalid" ;;
  esac
fi

# Hold the project-wide maintenance capability before inspecting or changing
# the target Compose services.  This closes the race where a deploy could
# replace the RustFS container between validation and the raw-volume restore.
temporary_directory=''
restore_succeeded=false
rustfs_stopped=false
cleanup() {
  local exit_code=$?
  [[ -z "${temporary_directory}" ]] || rm -rf "${temporary_directory}"
  if [[ "${rustfs_stopped}" == "true" ]]; then
    ops_log "restarting bundled RustFS after restore interruption"
    ops_compose --profile rustfs up -d --wait --wait-timeout 180 rustfs >/dev/null 2>&1 || true
  fi
  if [[ "${restore_succeeded}" != "true" ]]; then
    ops_log "restore stopped before verification completed; app remains stopped for inspection"
  fi
  ops_release_maintenance_lock || true
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ops_acquire_maintenance_lock restore

target_provider_from_app="$(ops_container_env app MARKET_SHOP_STORAGE_PROVIDER 2>/dev/null || true)"
target_provider_from_env="${MARKET_SHOP_STORAGE_PROVIDER:-}"
if [[ -n "${RESTORE_TARGET_STORAGE_PROVIDER:-}" ]]; then
  target_provider="${RESTORE_TARGET_STORAGE_PROVIDER}"
else
  if [[ -n "${target_provider_from_env}" && -n "${target_provider_from_app}" \
    && "${target_provider_from_env}" != "${target_provider_from_app}" ]]; then
    ops_die "MARKET_SHOP_STORAGE_PROVIDER differs from the target app configuration"
  fi
  target_provider="${target_provider_from_env:-${target_provider_from_app:-}}"
fi
[[ "${target_provider}" == "local" || "${target_provider}" == "s3" ]] \
  || ops_die "set RESTORE_TARGET_STORAGE_PROVIDER to local or s3"

target_mode_from_env="${MARKET_SHOP_S3_BACKEND_MODE:-}"
target_mode_from_app="$(ops_container_env app MARKET_SHOP_S3_BACKEND_MODE 2>/dev/null || true)"
if [[ -n "${target_mode_from_env}" && -n "${target_mode_from_app}" \
  && "${target_mode_from_env}" != "${target_mode_from_app}" ]]; then
  ops_die "MARKET_SHOP_S3_BACKEND_MODE differs from the target app configuration"
fi
target_s3_backend_mode="${target_mode_from_env:-${target_mode_from_app:-external}}"
[[ "${target_s3_backend_mode}" == "bundled" || "${target_s3_backend_mode}" == "external" ]] \
  || ops_die "MARKET_SHOP_S3_BACKEND_MODE must be bundled or external"

target_profile_from_env="${SPRING_PROFILES_ACTIVE:-}"
target_profile_from_app="$(ops_container_env app SPRING_PROFILES_ACTIVE 2>/dev/null || true)"
if [[ -n "${target_profile_from_env}" && -n "${target_profile_from_app}" \
  && "${target_profile_from_env}" != "${target_profile_from_app}" ]]; then
  ops_die "SPRING_PROFILES_ACTIVE differs from the target app configuration"
fi
target_active_profile="${target_profile_from_env:-${target_profile_from_app:-}}"
if [[ -z "${target_active_profile}" ]]; then
  case "${MARKET_SHOP_COMPOSE_FILES:-}" in
    *docker-compose.local.yml*|*docker-compose.e2e.yml*) target_active_profile='local' ;;
    *) target_active_profile='prod' ;;
  esac
fi
if [[ "${target_provider}" == "s3" && "${target_s3_backend_mode}" == "bundled" \
  && "${target_active_profile}" =~ (^|[,[:space:]])prod([,[:space:]]|$) ]]; then
  ops_die "MARKET_SHOP_S3_BACKEND_MODE=bundled is reserved for local/e2e; production restores require external object hooks"
fi

target_endpoint_from_env="${MARKET_SHOP_RUSTFS_ENDPOINT:-}"
target_endpoint_from_app="$(ops_container_env app MARKET_SHOP_RUSTFS_ENDPOINT 2>/dev/null || true)"
if [[ -n "${target_endpoint_from_env}" && -n "${target_endpoint_from_app}" \
  && "${target_endpoint_from_env}" != "${target_endpoint_from_app}" ]]; then
  ops_die "MARKET_SHOP_RUSTFS_ENDPOINT differs from the target app configuration"
fi
target_rustfs_endpoint="${target_endpoint_from_env:-${target_endpoint_from_app:-}}"

provider_changed=false
if [[ "${backup_provider}" != "${target_provider}" ]]; then
  [[ "${RESTORE_ALLOW_PROVIDER_CHANGE:-false}" == "true" ]] \
    || ops_die "backup provider ${backup_provider} differs from target ${target_provider}"
  [[ -n "${OBJECT_RESTORE_HOOK:-}" && -x "${OBJECT_RESTORE_HOOK}" ]] \
    || ops_die "cross-provider restore requires executable OBJECT_RESTORE_HOOK"
  provider_changed=true
fi

# Decide the object operation from signed backup metadata and the explicit
# target mode.  The only path that may touch rustfs-data is a same-provider
# bundled-to-bundled restore whose target endpoint/project passes validation.
restore_local_objects=false
restore_bundled_rustfs=false
restore_external_objects=false
case "${backup_provider}:${backup_snapshot_mode}" in
  local:local-volume)
    if [[ "${provider_changed}" == "true" ]]; then
      restore_external_objects=true
    else
      [[ -f "${backup_directory}/uploads.tar.gz" || -f "${backup_directory}/uploads.tar.gz.age" ]] \
        || ops_die "local object backup payload is missing"
      [[ -f "${backup_directory}/uploads.tree.sha256" ]] \
        || ops_die "local object backup digest is missing"
      restore_local_objects=true
    fi
    ;;
  s3:bundled-rustfs)
    if [[ "${provider_changed}" != "true" && "${target_s3_backend_mode}" == "bundled" ]]; then
      [[ -f "${backup_directory}/rustfs-data.tar.gz" || -f "${backup_directory}/rustfs-data.tar.gz.age" ]] \
        || ops_die "bundled RustFS backup payload is missing"
      [[ -f "${backup_directory}/rustfs-data.tree.sha256" ]] \
        || ops_die "bundled RustFS backup digest is missing"
      [[ -n "${target_rustfs_endpoint}" ]] \
        || ops_die "bundled s3 restore requires MARKET_SHOP_RUSTFS_ENDPOINT"
      ops_validate_bundled_rustfs "${target_rustfs_endpoint}"
      restore_bundled_rustfs=true
    else
      restore_external_objects=true
    fi
    ;;
  s3:external-hook|s3:unknown)
    restore_external_objects=true
    ;;
  *)
    ops_die "backup object snapshot mode does not match storage_provider"
    ;;
esac

if [[ "${restore_external_objects}" == "true" ]]; then
  [[ -n "${OBJECT_RESTORE_HOOK:-}" && -x "${OBJECT_RESTORE_HOOK}" ]] \
    || ops_die "external object restore requires executable OBJECT_RESTORE_HOOK"
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/market-shop-restore.XXXXXX")"

ops_compose up -d --wait --wait-timeout 180 mysql redis
target_database="$(ops_container_env mysql MYSQL_DATABASE)"
target_database_user="$(ops_container_env mysql MYSQL_USER)"
ops_validate_mysql_identifier "${target_database}" "MYSQL_DATABASE"
ops_validate_mysql_identifier "${target_database_user}" "MYSQL_USER"
# MySQL credentials and database name are resolved by sh inside the container.
# shellcheck disable=SC2016
database_tables="$(ops_compose exec -T mysql sh -euc '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()" "$MYSQL_DATABASE"
')"
if [[ "${database_tables}" != "0" && "${RESTORE_ALLOW_NONEMPTY:-false}" != "true" ]]; then
  ops_die "target database is not empty; use a new environment or explicitly set RESTORE_ALLOW_NONEMPTY=true"
fi

if [[ -n "$(ops_compose ps --quiet app)" ]]; then
  ops_log "stopping app for restore"
  ops_compose stop app
fi

if [[ "${restore_bundled_rustfs}" == "true" ]]; then
  ops_log "stopping bundled RustFS before raw-volume restore"
  ops_compose --profile rustfs stop rustfs
  rustfs_stopped=true
fi

if [[ "${database_tables}" != "0" ]]; then
  ops_log "explicit non-empty override selected; recreating the target database"
  # MySQL credentials and positional parameters are resolved inside the container.
  # shellcheck disable=SC2016
  ops_compose exec -T mysql sh -euc '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --execute="
      DROP DATABASE \`$1\`;
      CREATE DATABASE \`$1\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
      GRANT ALL PRIVILEGES ON \`$1\`.* TO \"$2\"@\"%\";
    "
  ' sh "${target_database}" "${target_database_user}"
fi

ops_materialize_payload "${backup_directory}" mysql.sql.gz "${temporary_directory}/mysql.sql.gz"
ops_log "restoring MySQL"
# MySQL credentials and database name are resolved by sh inside the container.
# shellcheck disable=SC2016
gzip -dc "${temporary_directory}/mysql.sql.gz" \
  | ops_compose exec -T mysql sh -euc '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root "$MYSQL_DATABASE"
    '

restore_volume_payload() {
  local logical_name="$1"
  local payload="$2"
  local digest_file="$3"
  local volume actual expected
  if [[ "${logical_name}" == "rustfs-data" && -n "${bundled_rustfs_volume}" ]]; then
    volume="${bundled_rustfs_volume}"
  else
    volume="$(ops_ensure_volume "${logical_name}")"
  fi
  if ! ops_volume_is_empty "${volume}" && [[ "${RESTORE_ALLOW_NONEMPTY:-false}" != "true" ]]; then
    ops_die "target volume ${logical_name} is not empty"
  fi
  [[ -f "${backup_directory}/${digest_file}" ]] \
    || ops_die "backup is missing the ${logical_name} object digest"
  ops_materialize_payload "${backup_directory}" "${payload}" "${temporary_directory}/${payload}"
  ops_restore_volume "${volume}" "${temporary_directory}" "${payload}"
  expected="$(tr -d '[:space:]' < "${backup_directory}/${digest_file}")"
  actual="$(ops_volume_tree_digest "${volume}")"
  [[ "${actual}" == "${expected}" ]] || ops_die "restored object digest mismatch for ${logical_name}"
}

if [[ "${restore_local_objects}" == "true" ]]; then
  ops_log "restoring local object volume"
  restore_volume_payload market-shop-uploads uploads.tar.gz uploads.tree.sha256
fi
if [[ "${restore_bundled_rustfs}" == "true" ]]; then
  ops_log "restoring bundled RustFS volume"
  restore_volume_payload rustfs-data rustfs-data.tar.gz rustfs-data.tree.sha256
fi
if [[ "${restore_external_objects}" == "true" ]]; then
  # The marker is informational; the signed backup.meta mode is authoritative.
  # Accept both the legacy marker and the explicit marker used by external
  # snapshot implementations; backup.meta remains authoritative.
  if [[ -f "${backup_directory}/external-object.marker" ]]; then
    marker="$(tr -d '[:space:]' < "${backup_directory}/external-object.marker")"
    [[ "${marker}" == "external" || "${marker}" == "external-hook" ]] \
      || ops_die "external object marker is invalid"
  fi
  "${OBJECT_RESTORE_HOOK}" "${backup_directory}"
fi

ops_log "clearing the configured Redis database so post-backup sessions/caches cannot survive"
# Redis credentials and database index are resolved by sh inside the container.
# shellcheck disable=SC2016
ops_compose exec -T redis sh -euc '
  REDISCLI_AUTH="$MARKET_SHOP_REDIS_PASSWORD" redis-cli \
    --no-auth-warning --raw -n "${MARKET_SHOP_REDIS_DATABASE:-0}" FLUSHDB | grep -qx OK
'

ops_log "starting app so Flyway validates/applies forward migrations"
if [[ "${restore_bundled_rustfs}" == "true" ]]; then
  ops_compose --profile rustfs up -d --wait --wait-timeout 180 rustfs
  rustfs_stopped=false
fi
ops_compose up -d --no-deps --wait --wait-timeout 300 app

# MySQL credentials and database name are resolved by sh inside the container.
# shellcheck disable=SC2016
failed_migrations="$(ops_compose exec -T mysql sh -euc '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0" "$MYSQL_DATABASE"
')"
[[ "${failed_migrations}" == "0" ]] || ops_die "Flyway reports failed migrations after restore"

public_url="${MARKET_SHOP_PUBLIC_URL:-$(ops_default_public_url)}"
ops_public_verify "${public_url}"

restore_succeeded=true
ops_log "restore, Flyway validation, object digest verification and smoke checks passed"
