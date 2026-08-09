import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFile, readdir, stat } from 'node:fs/promises'
import test from 'node:test'

const root = new URL('../', import.meta.url)
const source = (path) => readFile(new URL(path, root), 'utf8')

function composeConfig(args) {
  return JSON.parse(execFileSync('docker', ['compose', ...args, 'config', '--format', 'json'], {
    cwd: root,
    encoding: 'utf8',
    env: {
      ...process.env,
      MARKET_SHOP_CANDIDATE_IMAGE:
        'ghcr.io/example/market-shop@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      MARKET_SHOP_RELEASE_DB_NAME: 'market_shop_preflight'
    }
  }))
}

test('production compose is the default and does not publish MySQL or Redis', () => {
  const config = composeConfig(['--env-file', '.env.example'])

  assert.equal(config.services.app.environment.SPRING_PROFILES_ACTIVE, 'prod')
  assert.equal(config.services.app.environment.MARKET_SHOP_WECHAT_MOCK_ENABLED, 'false')
  assert.equal(config.services.app.environment.MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID, '')
  assert.equal(config.services.app.environment.MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET, '')
  assert.equal(config.services.app.environment.MARKET_SHOP_ADDITIONAL_WRITE_ORIGINS, '')
  assert.equal(config.services.app.environment.MARKET_SHOP_S3_BACKEND_MODE, 'external')
  assert.equal(config.services.app.environment.MARKET_SHOP_COOKIE_SECURE, 'true')
  assert.equal(config.services.app.build, undefined)
  assert.equal(config.services.mysql.ports, undefined)
  assert.equal(config.services.redis.ports, undefined)
})

test('local override is explicit and binds dependency ports to loopback only', () => {
  const config = composeConfig([
    '-f', 'docker-compose.yml',
    '-f', 'docker-compose.local.yml',
    '--env-file', '.env.local.example'
  ])

  assert.equal(config.services.app.environment.SPRING_PROFILES_ACTIVE, 'local')
  assert.equal(config.services.app.environment.MARKET_SHOP_WECHAT_MOCK_ENABLED, 'true')
  assert.ok(config.services.app.build)
  for (const service of ['mysql', 'redis']) {
    assert.ok(config.services[service].ports.length > 0)
    assert.ok(config.services[service].ports.every((port) => port.host_ip === '127.0.0.1'))
  }
})

test('release overlay defines an immutable, isolated candidate', () => {
  const config = composeConfig([
    '-f', 'docker-compose.yml',
    '-f', 'docker-compose.release.yml',
    '--env-file', '.env.example',
    '--profile', 'release'
  ])
  const candidate = config.services.candidate

  assert.match(candidate.image, /@sha256:[a-f0-9]{64}$/)
  assert.equal(candidate.environment.SPRING_PROFILES_ACTIVE, 'prod')
  assert.equal(candidate.environment.MARKET_SHOP_SCHEDULING_ENABLED, 'false')
  assert.equal(candidate.environment.MARKET_SHOP_BOOTSTRAP_ADMIN_ENABLED, 'false')
  assert.equal(candidate.environment.MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID, '')
  assert.equal(candidate.environment.MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET, '')
  assert.equal(candidate.environment.MARKET_SHOP_ADDITIONAL_WRITE_ORIGINS, '')
  assert.equal(candidate.environment.MARKET_SHOP_S3_BACKEND_MODE, 'external')
  assert.equal(candidate.ports[0].host_ip, '127.0.0.1')
})

test('health groups and proxy expose readiness without diagnostics', async () => {
  const [application, production, nginx, dockerfile] = await Promise.all([
    source('backend/shop-bootstrap/src/main/resources/application.yml'),
    source('backend/shop-bootstrap/src/main/resources/application-prod.yml'),
    source('deploy/nginx.conf'),
    source('Dockerfile')
  ])

  assert.match(application, /liveness:\s+include: livenessState/)
  assert.match(application, /readiness:\s+include: readinessState,db,redis,objectStorage/)
  assert.match(production, /validation-enabled: true/)
  assert.match(production, /secure-cookie: \$\{MARKET_SHOP_COOKIE_SECURE:true\}/)
  assert.match(production, /miniprogram-app-id: \$\{MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID:\}/)
  assert.match(production, /miniprogram-secret: \$\{MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET:\}/)
  assert.doesNotMatch(production, /storefront-base-url/)
  assert.doesNotMatch(production, /oauth-callback-base-url/)
  assert.doesNotMatch(production, /MARKET_SHOP_WECHAT_CALLBACK_BASE_URL/)
  assert.match(nginx, /location = \/healthz/)
  assert.match(nginx, /proxy_pass http:\/\/market_shop_backend\/actuator\/health\/readiness/)
  assert.match(nginx, /location \^~ \/actuator\/\s*\{\s*return 404;/)
  assert.match(nginx, /location \^~ \/swagger-ui\/\s*\{\s*return 404;/)
  assert.match(dockerfile, /HEALTHCHECK[\s\S]*\/healthz/)
})

test('production and release reject bundled RustFS while local fixtures retain it', async () => {
  const [postProcessor, backup, restore, localEnv, releaseCompose] = await Promise.all([
    source('backend/shop-bootstrap/src/main/java/com/marketshop/bootstrap/config/ProductionEnvironmentPostProcessor.java'),
    source('scripts/backup.sh'),
    source('scripts/restore.sh'),
    source('.env.local.example'),
    source('docker-compose.release.yml')
  ])

  assert.match(postProcessor, /validateS3BackendMode\([\s\S]*,\s*true\s*\)/)
  assert.match(postProcessor, /bundled is reserved for local\/e2e/)
  assert.match(localEnv, /SPRING_PROFILES_ACTIVE=local/)
  assert.match(localEnv, /MARKET_SHOP_S3_BACKEND_MODE=bundled/)
  assert.match(releaseCompose, /MARKET_SHOP_S3_BACKEND_MODE: \$\{MARKET_SHOP_S3_BACKEND_MODE:-external\}/)
  assert.match(backup, /bundled is reserved for local\/e2e/)
  assert.match(restore, /bundled is reserved for local\/e2e/)
})

test('backup and restore implement consistency, integrity and cache invalidation', async () => {
  const [backup, restore, library] = await Promise.all([
    source('scripts/backup.sh'),
    source('scripts/restore.sh'),
    source('scripts/ops/lib.sh')
  ])

  assert.match(backup, /ops_compose stop app/)
  assert.match(backup, /--single-transaction/)
  assert.match(backup, /uploads\.tree\.sha256/)
  assert.match(backup, /rustfs-data\.tree\.sha256/)
  assert.match(backup, /BACKUP_AGE_RECIPIENT/)
  assert.match(backup, /BACKUP_OFFSITE_HOOK/)
  assert.match(backup, /OBJECT_BACKUP_HOOK/)
  assert.match(backup, /ops_write_manifest/)
  assert.match(backup, /MARKET_SHOP_S3_BACKEND_MODE/)
  assert.match(backup, /object_snapshot_mode='bundled-rustfs'/)
  assert.match(backup, /object_snapshot_mode='external-hook'/)
  assert.match(backup, /rustfs\.localhost/)
  assert.match(backup, /com\.docker\.compose\.project/)
  assert.match(restore, /ops_verify_manifest/)
  assert.match(restore, /RESTORE_CONFIRM/)
  assert.match(restore, /RESTORE_ALLOW_PROVIDER_CHANGE/)
  assert.match(restore, /FLUSHDB/)
  assert.match(restore, /flyway_schema_history/)
  assert.match(restore, /ops_volume_tree_digest/)
  assert.match(restore, /backup_snapshot_mode/)
  assert.match(restore, /target_s3_backend_mode/)
  assert.match(restore, /restore_bundled_rustfs/)
  assert.match(restore, /external object restore requires executable OBJECT_RESTORE_HOOK/)
  assert.match(restore, /bundled RustFS backup payload is missing/)
  assert.match(library, /sha256sum --check SHA256SUMS/)
})

test('checksum manifests are complete, verifiable and atomically replaced', () => {
  execFileSync('bash', ['-c', String.raw`
    set -euo pipefail
    workspace="$(mktemp -d)"
    trap 'rm -rf "$workspace"' EXIT
    source scripts/ops/lib.sh

    mkdir "$workspace/data" "$workspace/empty" "$workspace/tmp"
    export TMPDIR="$workspace/tmp"
    printf 'alpha\n' > "$workspace/data/with space.txt"
    printf 'beta\n' > "$workspace/data/second.txt"
    printf 'stale\n' > "$workspace/data/SHA256SUMS"
    printf 'abandoned\n' > "$workspace/data/.SHA256SUMS.abandoned"

    ops_write_manifest "$workspace/data"
    ops_verify_manifest "$workspace/data"
    [[ "$(wc -l < "$workspace/data/SHA256SUMS" | tr -d ' ')" == 2 ]]
    grep -Eq '  with space\.txt$' "$workspace/data/SHA256SUMS"
    ! grep -q 'SHA256SUMS' "$workspace/data/SHA256SUMS"

    ops_write_manifest "$workspace/empty"
    [[ ! -s "$workspace/empty/SHA256SUMS" ]]
    ops_verify_manifest "$workspace/empty"
    printf 'unlisted\n' > "$workspace/empty/unlisted.txt"
    if (ops_verify_manifest "$workspace/empty"); then
      exit 1
    fi
    rm -f "$workspace/empty/unlisted.txt"

    rm -f "$workspace/data/.SHA256SUMS.abandoned"
    printf 'preserved\n' > "$workspace/data/SHA256SUMS"
    if (
      ops_sha256() { return 17; }
      ops_write_manifest "$workspace/data"
    ); then
      exit 1
    fi
    grep -qx 'preserved' "$workspace/data/SHA256SUMS"
    ! find "$workspace/data" -maxdepth 1 -name '.SHA256SUMS.*' -print -quit | grep -q .
    [[ -z "$(find "$workspace/tmp" -mindepth 1 -print -quit)" ]]
  `], { cwd: root, stdio: 'pipe' })
})

test('intentional SC2016 exceptions are explained and command-scoped', async () => {
  const scriptDirectories = ['scripts/', 'scripts/ops/']
  const scriptPaths = []
  for (const directory of scriptDirectories) {
    const entries = await readdir(new URL(directory, root), { withFileTypes: true })
    for (const entry of entries) {
      if (entry.isFile() && entry.name.endsWith('.sh')) {
        scriptPaths.push(`${directory}${entry.name}`)
      }
    }
  }

  for (const scriptPath of scriptPaths) {
    const lines = (await source(scriptPath)).split('\n')
    for (const [index, line] of lines.entries()) {
      if (!/^\s*#\s*shellcheck\s+disable=.*\bSC2016\b/.test(line)) continue

      const location = `${scriptPath}:${index + 1}`
      const precedingCode = lines.slice(0, index)
        .some((candidate) => candidate.trim() !== '' && !candidate.trim().startsWith('#'))
      assert.ok(precedingCode, `${location}: SC2016 must not be disabled file-wide`)
      assert.match(
        lines[index - 1] ?? '',
        /^\s*#\s+(?!shellcheck\b).{12,}$/,
        `${location}: SC2016 needs an immediately preceding reason`
      )
      assert.match(lines[index + 1] ?? '', /\S/, `${location}: SC2016 needs a following command`)
      assert.doesNotMatch(
        lines[index + 1],
        /^\s*#/,
        `${location}: SC2016 must directly precede its command`
      )
      assert.doesNotMatch(
        lines[index + 1],
        /^\s*(?:function\s+)?[A-Za-z_][A-Za-z0-9_]*\s*\(\)\s*\{/,
        `${location}: SC2016 must not suppress a whole function`
      )
    }
  }
})

test('S3 object snapshots are provenance-driven and fail closed', async () => {
  const [backup, restore] = await Promise.all([
    source('scripts/backup.sh'),
    source('scripts/restore.sh')
  ])

  // A running service name alone must not select the raw volume.  The backup
  // path is gated by explicit mode and project/endpoint/mount validation.
  assert.match(backup, /s3_backend_mode=.*external/)
  assert.match(backup, /if \[\[ "\$\{s3_backend_mode\}" == "bundled" \]\]/)
  assert.match(backup, /ops_validate_bundled_rustfs "\$\{rustfs_endpoint\}"/)
  assert.match(backup, /external s3 mode requires executable OBJECT_BACKUP_HOOK/)
  assert.match(backup, /if \[\[ "\$\{object_snapshot_mode\}" == "bundled-rustfs" \]\]/)

  // Restore must use backup.meta's object_snapshot_mode and target mode; an
  // unclassified/external S3 backup can only be handed to the hook.
  assert.match(restore, /s3:external-hook\|s3:unknown/)
  assert.match(restore, /if \[\[ "\$\{restore_external_objects\}" == "true" \]\]/)
  assert.match(restore, /if \[\[ "\$\{restore_bundled_rustfs\}" == "true" \]\]/)
  assert.doesNotMatch(
    restore,
    /if \[\[ "\$\{provider_changed\}" != "true" && \( -f "\$\{backup_directory\}\/rustfs-data\.tar\.gz"/
  )
})

test('digest deployment gates traffic on backup, migration and candidate health', async () => {
  const [deploy, rollback, preflight, verify] = await Promise.all([
    source('scripts/deploy-digest.sh'),
    source('scripts/rollback-digest.sh'),
    source('scripts/migration-preflight.sh'),
    source('scripts/production-verify.sh')
  ])

  const backupIndex = deploy.indexOf('backup.sh')
  const preflightIndex = deploy.indexOf('migration-preflight.sh')
  const cutoverIndex = deploy.indexOf('ops_set_active_image "${candidate_image}"')
  assert.ok(backupIndex >= 0 && backupIndex < preflightIndex && preflightIndex < cutoverIndex)
  assert.match(deploy, /--wait --wait-timeout 300 candidate/)
  assert.match(deploy, /traffic was not switched/)
  assert.match(rollback, /previous\.digest/)
  assert.match(rollback, /Flyway remains forward-only/)
  assert.match(preflight, /market_shop_preflight_/)
  assert.match(preflight, /DROP DATABASE IF EXISTS/)
  assert.match(verify, /MARKET_SHOP_EXPECT_DEV_LOGIN_ENABLED:-false/)
  assert.match(verify, /devLoginEnabled/)
  assert.match(verify, /public health leaks component details/)
})

test('all mutating operations share one fail-fast maintenance capability lock', async () => {
  const paths = [
    ['scripts/backup.sh', 'backup'],
    ['scripts/restore.sh', 'restore'],
    ['scripts/deploy-digest.sh', 'deploy'],
    ['scripts/rollback-digest.sh', 'rollback'],
    ['scripts/migration-preflight.sh', 'preflight']
  ]
  const library = await source('scripts/ops/lib.sh')
  for (const [path, operation] of paths) {
    assert.match(await source(path), new RegExp(`ops_acquire_maintenance_lock ${operation}`))
  }
  assert.match(library, /mkdir -m 700 "\$\{OPS_MAINTENANCE_LOCK_DIR\}"/)
  assert.match(library, /\^\[a-f0-9\]\{64\}\$/)
  assert.match(library, /deploy:backup\|deploy:preflight\|rollback:backup/)
  assert.match(library, /inherited_delegate_pid.*PPID/s)

  execFileSync('bash', ['-c', String.raw`
    set -euo pipefail
    state="$(mktemp -d)"
    holder_pid=''
    export MARKET_SHOP_RELEASE_STATE_DIR="$state/release"
    mkdir -p "$MARKET_SHOP_RELEASE_STATE_DIR"
    cleanup_test() {
      [[ -z "$holder_pid" ]] || kill -TERM "$holder_pid" >/dev/null 2>&1 || true
      [[ -z "$holder_pid" ]] || wait "$holder_pid" >/dev/null 2>&1 || true
      rm -rf "$state"
    }
    trap cleanup_test EXIT

    bash -c '
      set -euo pipefail
      source scripts/ops/lib.sh
      trap "exit 143" TERM
      trap "ops_release_maintenance_lock" EXIT
      ops_acquire_maintenance_lock backup
      touch "$OPS_STATE_DIR/holder.ready"
      while :; do sleep 0.1; done
    ' &
    holder_pid=$!
    deadline=$((SECONDS + 5))
    while [[ ! -f "$MARKET_SHOP_RELEASE_STATE_DIR/holder.ready" ]]; do
      (( SECONDS < deadline )) || exit 1
      sleep 0.05
    done

    started=$SECONDS
    if bash -c '
      set -euo pipefail
      source scripts/ops/lib.sh
      ops_acquire_maintenance_lock restore
      touch "$OPS_STATE_DIR/forbidden-mutation"
    '; then
      exit 1
    fi
    (( SECONDS - started <= 1 ))
    [[ ! -e "$MARKET_SHOP_RELEASE_STATE_DIR/forbidden-mutation" ]]

    kill -TERM "$holder_pid"
    wait "$holder_pid" >/dev/null 2>&1 || true
    holder_pid=''
    [[ ! -d "$MARKET_SHOP_RELEASE_STATE_DIR/maintenance.lock" ]]

    bash -c '
      set -euo pipefail
      source scripts/ops/lib.sh
      trap "ops_release_maintenance_lock" EXIT
      ops_acquire_maintenance_lock deploy
      ops_run_maintenance_child backup bash -c '\''
        set -euo pipefail
        source scripts/ops/lib.sh
        ops_acquire_maintenance_lock backup
        [[ "$OPS_MAINTENANCE_NESTED" == true ]]
        touch "$OPS_STATE_DIR/nested-backup-ran"
        ops_release_maintenance_lock
      '\''
      [[ -d "$OPS_MAINTENANCE_LOCK_DIR" ]]
    '
    [[ -f "$MARKET_SHOP_RELEASE_STATE_DIR/nested-backup-ran" ]]
    [[ ! -d "$MARKET_SHOP_RELEASE_STATE_DIR/maintenance.lock" ]]
  `], { cwd: root, stdio: 'pipe' })
})

test('all operational scripts are executable and parse as Bash', async () => {
  const scripts = [
    'scripts/backup.sh',
    'scripts/restore.sh',
    'scripts/migration-preflight.sh',
    'scripts/deploy-digest.sh',
    'scripts/rollback-digest.sh',
    'scripts/production-verify.sh',
    'scripts/ops/lib.sh'
  ]
  for (const script of scripts) {
    const metadata = await stat(new URL(script, root))
    assert.notEqual(metadata.mode & 0o111, 0, `${script} must be executable`)
    if (script !== 'scripts/ops/lib.sh') {
      execFileSync(script, ['--help'], { cwd: root })
      execFileSync(script, ['--dry-run'], { cwd: root })
    }
  }
  execFileSync('bash', ['-n', ...scripts], { cwd: root })
})

test('runbook defines RPO, RTO, restore drill and digest rollback', async () => {
  const [runbook, workflow] = await Promise.all([
    source('docs/production-operations.md'),
    source('.github/workflows/docker-image.yml')
  ])

  assert.match(runbook, /RPO ≤ 15 分钟/)
  assert.match(runbook, /RTO ≤ 2 小时/)
  assert.match(runbook, /MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET/)
  assert.match(runbook, /每季度恢复演练/)
  assert.match(runbook, /repository@sha256/)
  assert.match(runbook, /scripts\/rollback-digest\.sh/)
  assert.match(runbook, /MARKET_SHOP_S3_BACKEND_MODE=external\|bundled/)
  assert.match(runbook, /object_snapshot_mode=bundled-rustfs/)
  assert.match(runbook, /MARKET_SHOP_COMPOSE_FILES=docker-compose\.yml/)
  assert.match(workflow, /Generate a production-safe recovery environment/)
  assert.match(workflow, /SPRING_PROFILES_ACTIVE=prod/)
  assert.match(workflow, /MARKET_SHOP_WECHAT_MOCK_ENABLED=false/)
  assert.match(workflow, /MARKET_SHOP_COMPOSE_FILES: docker-compose\.yml/)
})
