#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/lib.sh
source "${SCRIPT_DIR}/ops/lib.sh"

case "${1:-}" in
  --help|-h)
    echo "usage: [MARKET_SHOP_ENV_FILE=.env] scripts/backup.sh [--dry-run]"
    exit 0
    ;;
  --dry-run)
    echo "dry-run: graceful stop app -> mysqldump -> object snapshot (S3 external requires OBJECT_BACKUP_HOOK; bundled requires explicit mode/endpoint/project) -> SHA256SUMS -> restart/readiness"
    exit 0
    ;;
esac

ops_init
ops_require_command gzip

# Never infer that an S3 provider is backed by the local RustFS volume merely
# because a container named `rustfs` happens to be running.  Raw-volume
# snapshots are opt-in and require the explicit bundled mode plus matching
# endpoint, compose project/service labels, network alias, and volume mount.
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

backup_root="${MARKET_SHOP_BACKUP_ROOT:-${OPS_ROOT}/backups}"
if [[ "${backup_root}" != /* ]]; then
  backup_root="${OPS_ROOT}/${backup_root#./}"
fi
retention_days="${MARKET_SHOP_BACKUP_RETENTION_DAYS:-14}"
[[ "${retention_days}" =~ ^[0-9]+$ ]] || ops_die "backup retention days must be an integer"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_directory=''
staging_directory=''
app_stopped=false
rustfs_stopped=false
backup_succeeded=false
cleanup() {
  local exit_code=$?
  if [[ "${rustfs_stopped}" == "true" ]]; then
    ops_log "restarting bundled RustFS after snapshot window"
    ops_compose --profile rustfs up -d --wait --wait-timeout 180 rustfs >/dev/null 2>&1 || true
  fi
  if [[ "${app_stopped}" == "true" ]]; then
    ops_log "restarting application after snapshot window"
    ops_compose up -d --no-deps --wait --wait-timeout 300 app >/dev/null 2>&1 || true
  fi
  if [[ "${backup_succeeded}" != "true" && -n "${staging_directory}" ]]; then
    rm -rf "${staging_directory}"
  fi
  ops_release_maintenance_lock || true
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ops_acquire_maintenance_lock backup
mkdir -p "${backup_root}"
backup_root="$(cd "${backup_root}" && pwd)"
final_directory="${backup_root}/backup-${timestamp}"
staging_directory="${backup_root}/.backup-${timestamp}.partial.$$"
mkdir -m 700 "${staging_directory}"

[[ -n "$(ops_compose ps --quiet app)" ]] || ops_die "application service is not running"
backup_database="$(ops_container_env mysql MYSQL_DATABASE)"
ops_validate_mysql_identifier "${backup_database}" "MYSQL_DATABASE"
provider_from_env="${MARKET_SHOP_STORAGE_PROVIDER:-}"
provider_from_app="$(ops_container_env app MARKET_SHOP_STORAGE_PROVIDER 2>/dev/null || true)"
if [[ -n "${provider_from_env}" && -n "${provider_from_app}" \
  && "${provider_from_env}" != "${provider_from_app}" ]]; then
  ops_die "MARKET_SHOP_STORAGE_PROVIDER differs from the running app configuration"
fi
provider="${provider_from_env:-${provider_from_app:-local}}"
[[ "${provider}" == "local" || "${provider}" == "s3" ]] || ops_die "unsupported storage provider: ${provider}"

s3_mode_from_env="${MARKET_SHOP_S3_BACKEND_MODE:-}"
s3_mode_from_app="$(ops_container_env app MARKET_SHOP_S3_BACKEND_MODE 2>/dev/null || true)"
if [[ -n "${s3_mode_from_env}" && -n "${s3_mode_from_app}" \
  && "${s3_mode_from_env}" != "${s3_mode_from_app}" ]]; then
  ops_die "MARKET_SHOP_S3_BACKEND_MODE differs from the running app configuration"
fi
s3_backend_mode="${s3_mode_from_env:-${s3_mode_from_app:-external}}"
[[ "${s3_backend_mode}" == "bundled" || "${s3_backend_mode}" == "external" ]] \
  || ops_die "MARKET_SHOP_S3_BACKEND_MODE must be bundled or external"

profile_from_env="${SPRING_PROFILES_ACTIVE:-}"
profile_from_app="$(ops_container_env app SPRING_PROFILES_ACTIVE 2>/dev/null || true)"
if [[ -n "${profile_from_env}" && -n "${profile_from_app}" \
  && "${profile_from_env}" != "${profile_from_app}" ]]; then
  ops_die "SPRING_PROFILES_ACTIVE differs from the running app configuration"
fi
active_profile="${profile_from_env:-${profile_from_app:-}}"
if [[ -z "${active_profile}" ]]; then
  case "${MARKET_SHOP_COMPOSE_FILES:-}" in
    *docker-compose.local.yml*|*docker-compose.e2e.yml*) active_profile='local' ;;
    *) active_profile='prod' ;;
  esac
fi
if [[ "${provider}" == "s3" && "${s3_backend_mode}" == "bundled" \
  && "${active_profile}" =~ (^|[,[:space:]])prod([,[:space:]]|$) ]]; then
  ops_die "MARKET_SHOP_S3_BACKEND_MODE=bundled is reserved for local/e2e; production backups require external object hooks"
fi

rustfs_container=""
object_snapshot_mode='local-volume'
if [[ "${provider}" == "s3" ]]; then
  rustfs_endpoint_from_env="${MARKET_SHOP_RUSTFS_ENDPOINT:-}"
  rustfs_endpoint_from_app="$(ops_container_env app MARKET_SHOP_RUSTFS_ENDPOINT 2>/dev/null || true)"
  if [[ -n "${rustfs_endpoint_from_env}" && -n "${rustfs_endpoint_from_app}" \
    && "${rustfs_endpoint_from_env}" != "${rustfs_endpoint_from_app}" ]]; then
    ops_die "MARKET_SHOP_RUSTFS_ENDPOINT differs from the running app configuration"
  fi
  rustfs_endpoint="${rustfs_endpoint_from_env:-${rustfs_endpoint_from_app:-}}"
  object_snapshot_mode='external-hook'
  if [[ "${s3_backend_mode}" == "bundled" ]]; then
    [[ -n "${rustfs_endpoint}" ]] \
      || ops_die "bundled s3 mode requires MARKET_SHOP_RUSTFS_ENDPOINT"
    ops_validate_bundled_rustfs "${rustfs_endpoint}"
    rustfs_container="${bundled_rustfs_container}"
    rustfs_volume="${bundled_rustfs_volume}"
    object_snapshot_mode='bundled-rustfs'
  else
    [[ -n "${OBJECT_BACKUP_HOOK:-}" && -x "${OBJECT_BACKUP_HOOK}" ]] \
      || ops_die "external s3 mode requires executable OBJECT_BACKUP_HOOK"
  fi
fi

ops_log "gracefully stopping app so in-flight transactions finish before the snapshot"
ops_compose stop app >/dev/null
app_stopped=true
if [[ -n "${rustfs_container}" ]]; then
  ops_log "stopping bundled RustFS before its raw-volume snapshot"
  ops_compose --profile rustfs stop rustfs >/dev/null
  rustfs_stopped=true
fi
snapshot_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

ops_log "dumping MySQL with a single transaction"
ops_compose exec -T mysql sh -euc '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump \
    --user=root --single-transaction --quick --routines --triggers --events \
    --hex-blob --set-gtid-purged=OFF "$MYSQL_DATABASE"
' > "${staging_directory}/mysql.sql"
gzip -9 "${staging_directory}/mysql.sql"

if [[ "${provider}" == "local" ]]; then
  uploads_volume="$(ops_find_volume market-shop-uploads)"
  [[ -n "${uploads_volume}" ]] || ops_die "local upload volume not found"
  ops_log "archiving local object volume ${uploads_volume}"
  ops_archive_volume "${uploads_volume}" "${staging_directory}" uploads.tar.gz
  ops_volume_tree_digest "${uploads_volume}" > "${staging_directory}/uploads.tree.sha256"
else
  if [[ "${object_snapshot_mode}" == "bundled-rustfs" ]]; then
    ops_log "archiving bundled RustFS volume ${rustfs_volume}"
    ops_archive_volume "${rustfs_volume}" "${staging_directory}" rustfs-data.tar.gz
    ops_volume_tree_digest "${rustfs_volume}" > "${staging_directory}/rustfs-data.tree.sha256"
  else
    ops_log "running external object-storage snapshot hook"
    "${OBJECT_BACKUP_HOOK}" "${staging_directory}" "${snapshot_at}"
    # Keep the marker value backward-compatible; backup.meta carries the
    # unambiguous `external-hook` provenance.
    printf '%s\n' "external" > "${staging_directory}/external-object.marker"
  fi
fi

cat > "${staging_directory}/backup.meta" <<EOF
format_version=1
created_at=${timestamp}
consistent_snapshot_at=${snapshot_at}
storage_provider=${provider}
object_snapshot_mode=${object_snapshot_mode}
s3_backend_mode=${s3_backend_mode}
reason=${BACKUP_REASON:-scheduled}
compose_project=${OPS_PROJECT_NAME}
encryption=none
EOF

if [[ "${rustfs_stopped}" == "true" ]]; then
  ops_compose --profile rustfs up -d --wait --wait-timeout 180 rustfs >/dev/null
  rustfs_stopped=false
fi
ops_compose up -d --no-deps --wait --wait-timeout 300 app >/dev/null
app_stopped=false
ops_wait_container_health app 180 || ops_die "application did not recover readiness after backup"

if [[ -n "${BACKUP_AGE_RECIPIENT:-}" ]]; then
  ops_require_command age
  ops_log "encrypting backup payloads with age"
  for payload in mysql.sql.gz uploads.tar.gz rustfs-data.tar.gz; do
    if [[ -f "${staging_directory}/${payload}" ]]; then
      age --recipient "${BACKUP_AGE_RECIPIENT}" \
        --output "${staging_directory}/${payload}.age" "${staging_directory}/${payload}"
      rm -f "${staging_directory:?}/${payload}"
    fi
  done
  sed -i.bak 's/^encryption=none$/encryption=age/' "${staging_directory}/backup.meta"
  rm -f "${staging_directory}/backup.meta.bak"
fi

if [[ -n "${BACKUP_ENCRYPT_HOOK:-}" ]]; then
  [[ -x "${BACKUP_ENCRYPT_HOOK}" ]] || ops_die "BACKUP_ENCRYPT_HOOK is not executable"
  "${BACKUP_ENCRYPT_HOOK}" "${staging_directory}"
fi

ops_write_manifest "${staging_directory}"
ops_verify_manifest "${staging_directory}" >/dev/null
mv "${staging_directory}" "${final_directory}"
chmod -R go-rwx "${final_directory}"
backup_succeeded=true

if [[ -n "${BACKUP_OFFSITE_HOOK:-}" ]]; then
  [[ -x "${BACKUP_OFFSITE_HOOK}" ]] || ops_die "BACKUP_OFFSITE_HOOK is not executable"
  "${BACKUP_OFFSITE_HOOK}" "${final_directory}" "${final_directory}/SHA256SUMS"
fi

find "${backup_root}" -mindepth 1 -maxdepth 1 -type d -name 'backup-*' \
  -mtime "+${retention_days}" -exec rm -rf -- {} +

ops_log "backup completed: ${final_directory}"
printf '%s\n' "${final_directory}"
