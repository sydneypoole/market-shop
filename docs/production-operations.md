# 商城生产发布、备份与恢复手册

## 1. 服务目标与边界

- **RPO ≤ 15 分钟**：至少每 15 分钟执行一次完整一致性备份；外部对象存储的版本清单必须与同一次停写窗口关联。
- **RTO ≤ 2 小时**：值班人员应能在空环境完成清单校验、MySQL/对象恢复、Flyway 验证和业务 smoke。
- MySQL、Redis 不在生产 Compose 发布宿主机端口。需要本机调试时，必须叠加 `docker-compose.local.yml`，它只绑定 `127.0.0.1`。
- 公网仅开放 `/healthz` 的无详情 readiness。`/actuator/**`、`/docs`、`/api-docs/**`、`/swagger-ui/**` 由 Nginx 返回 404。
- Flyway 只允许前向迁移。镜像回滚不会回滚数据库，所有迁移必须对上一版应用保持向后兼容。

## 2. 首次生产部署

1. 从 `.env.example` 复制 `.env`，权限设为 `0600`。
2. 替换全部 `CHANGE_ME`/`OWNER`，将 `MARKET_SHOP_IMAGE` 设置为 `repository@sha256:<64 hex>`，不得使用 tag。
3. 保持 `SPRING_PROFILES_ACTIVE=prod`、`MARKET_SHOP_COOKIE_SECURE=true`、`MARKET_SHOP_WECHAT_MOCK_ENABLED=false`。
4. 使用 local 存储时生成不少于 32 字符的签名密钥。使用 S3/RustFS 时提供 HTTPS endpoint、强凭据并预建私有 bucket；生产默认不授予应用建桶权限。
5. 仅空库第一次启动可临时设置 `MARKET_SHOP_BOOTSTRAP_ADMIN_ENABLED=true`、强临时密码、邀请码和独立的 `MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET`（至少 32 位，不能复用邀请码）。创建完成后立刻清除这三项敏感值、关闭 Bootstrap 并重启。
6. 校验并启动：

   ```bash
   docker compose --env-file .env config --quiet
   docker compose --env-file .env up -d --wait --wait-timeout 300
   scripts/production-verify.sh http://127.0.0.1:8080
   ```

本机开发必须显式执行：

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml \
  --env-file .env.local.example up -d --build --wait
```

## 3. 一致性备份

```bash
MARKET_SHOP_ENV_FILE=.env scripts/backup.sh
```

脚本遵循 `COMPOSE_PROJECT_NAME`，并可通过冒号分隔的 `MARKET_SHOP_COMPOSE_FILES` 使用与已启动环境完全相同的 overlay。例如 CI 恢复演练：

```bash
export COMPOSE_PROJECT_NAME=market-shop-e2e-local
export MARKET_SHOP_ENV_FILE=.env.local.example
export MARKET_SHOP_COMPOSE_FILES='docker-compose.yml:docker-compose.local.yml:docker-compose.e2e.yml'
backup_dir="$(scripts/backup.sh)"
docker compose --project-name "$COMPOSE_PROJECT_NAME" \
  -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.e2e.yml \
  --env-file .env.local.example down --volumes --remove-orphans

# recovery-prod.env 必须使用 prod、Secure Cookie、关闭 mock/bootstrap、强密钥和待验证镜像；
# CI 会在 runner 临时目录按这些约束生成该文件。恢复阶段只使用生产基础编排。
export MARKET_SHOP_ENV_FILE=/secure/recovery-prod.env
export MARKET_SHOP_COMPOSE_FILES=docker-compose.yml
RESTORE_CONFIRM=YES_RESTORE RESTORE_TARGET_STORAGE_PROVIDER=local \
  scripts/restore.sh "$backup_dir"
```

脚本先向 app 发送 graceful stop，并等待最长 45 秒让在途事务完成；随后在**无应用写入**的短暂停写窗口中执行单事务 `mysqldump`，再归档 local upload volume。

S3 对象的来源**不会由“是否有一个名为 `rustfs` 的运行容器”推断**。`MARKET_SHOP_S3_BACKEND_MODE` 默认是 `external`，此模式始终要求可执行的 `OBJECT_BACKUP_HOOK`，脚本不会触碰任何 `rustfs-data` 卷。生产 `prod` profile（包括 digest 发布的 release candidate）明确拒绝 `MARKET_SHOP_S3_BACKEND_MODE=bundled`，避免候选或正式实例把本地 RustFS 卷误当成外部对象源；`bundled` 只在显式的 local/e2e fixture 中启用。只有在这些 fixture 中显式设置 `MARKET_SHOP_S3_BACKEND_MODE=bundled` 时，脚本才会继续检查 `MARKET_SHOP_RUSTFS_ENDPOINT` 的主机必须是 `rustfs.localhost`，并核对 RustFS 容器的 Compose project/service 标签、网络别名、`rustfs-data` 卷标签和 `/data` 挂载均属于当前 project；检查通过后才在原始 volume 快照前停止 RustFS，避免后台合并写入破坏 tar 一致性。无论中途成功或失败，trap 都按 RustFS → app 顺序重启并等待 readiness。大型对象卷应使用存储快照/外部 hook 缩短窗口。

每份 `backup-<UTC>` 包含：

- `mysql.sql.gz`；
- `uploads.tar.gz` 或 `rustfs-data.tar.gz`，以及恢复后重新计算的对象树 SHA-256；
- `backup.meta`（一致性时间点、provider、用途）；
- `SHA256SUMS`（所有文件的最终校验清单）。

可选接口：

- `BACKUP_AGE_RECIPIENT`：使用 `age` 加密数据库和 volume payload，恢复时设置 `RESTORE_AGE_IDENTITY`；
- `BACKUP_ENCRYPT_HOOK=<executable>`：对 staging 目录执行企业 KMS/加密流程；相应恢复需设置 `RESTORE_DECRYPT_HOOK`；
- `BACKUP_OFFSITE_HOOK=<executable>`：参数为最终备份目录和 `SHA256SUMS`，用于异地不可变复制；
- `MARKET_SHOP_S3_BACKEND_MODE=external|bundled`：S3 后端模式，默认 `external`；只有明确使用同一 Compose project 的 bundled RustFS，且 endpoint 主机为 `rustfs.localhost` 时才能设置 `bundled`；
- `OBJECT_BACKUP_HOOK=<executable>`：external S3 必填（即使本机恰好运行 RustFS 容器），参数为目标目录和一致性 UTC 时间。hook 必须输出对象版本/清单及完成时间；
- `MARKET_SHOP_BACKUP_RETENTION_DAYS`：本地保留天数，默认 14。异地保留策略由 offsite hook 管理。

`backup.meta` 的 `object_snapshot_mode` 是对象来源的权威记录：`local-volume`、`bundled-rustfs` 或 `external-hook`。每次备份都应把该字段与 `SHA256SUMS` 一起送入异地存储，禁止人工把外部备份改标为 bundled。

建议 systemd timer/调度器每 15 分钟调用一次并对非零退出、备份年龄超过 15 分钟、offsite hook 失败告警。不得只备份 MySQL 或只复制对象目录。

## 4. 空环境恢复

1. 准备与原 provider 相同的空 MySQL、空 local/RustFS volume，并把目标镜像 digest 写入 `.env` 或 `.market-shop-release/active.env`。
2. 下载备份和清单，人工核对来源、时间点、事故编号。
3. 执行：

   ```bash
   RESTORE_CONFIRM=YES_RESTORE \
   RESTORE_TARGET_STORAGE_PROVIDER=local \
   MARKET_SHOP_ENV_FILE=.env \
     scripts/restore.sh /secure/backup-YYYYmmddTHHMMSSZ
   ```

脚本在任何写入前验证 `SHA256SUMS`，默认拒绝非空数据库/volume；恢复 MySQL 和对象后会清空配置的 Redis DB，避免备份时间点之后的 token/缓存继续有效。随后启动 app，让 Flyway validate/前向迁移，比较恢复后的对象树摘要，并运行 `production-verify.sh`。失败时 app 保持停止，值班人员先保留现场和日志。

### V17 legacy after-sale preflight

应用启动时会在正常 Flyway 迁移前自动获取 MySQL advisory lock `market-shop:legacy-aftersale-v17`。它只处理历史上同一订单存在多个 `COMPLETED` 售后且 V17 尚未成功的情况：保留确定性的 canonical 行，把其余行改为 `CANCELLED`，保留行及其外键，并写入可追溯的 SYSTEM 审计记录。没有 `operation_audit_log` 的旧 schema 不会因缺少审计表而阻断修复。

不要在生产环境手工执行 `flyway repair` 或修改 `flyway_schema_history`。只有 V17 失败记录、脚本 checksum 与当前资源完全一致、且 V17 的生成列和唯一索引均不存在时，应用才会执行受保护的 repair 后重跑；任何其他失败迁移、checksum 不一致或部分/不明 V17 对象都会 fail closed。预检失败时保留现场，先核对备份、迁移历史和数据库对象，再按变更审批处理。

- 非空灾备环境只有在明确审查后才可设置 `RESTORE_ALLOW_NONEMPTY=true`；脚本会重建目标数据库并覆盖对象 volume。
- provider 与 `backup.meta` 不一致时默认拒绝。跨 provider 必须同时设置 `RESTORE_ALLOW_PROVIDER_CHANGE=true` 和 `OBJECT_RESTORE_HOOK`，由 hook 通过 S3 API 完成格式迁移，禁止直接复用另一产品的原始磁盘布局。
- 恢复只会在备份 `object_snapshot_mode=bundled-rustfs`、目标 provider 为 `s3` 且目标 `MARKET_SHOP_S3_BACKEND_MODE=bundled` 时复用 `rustfs-data`；目标 endpoint、Compose project/service、网络别名和卷挂载仍会重新核对。目标模式为 `external`、备份模式为 `external-hook` 或模式缺失/未知时一律要求可执行的 `OBJECT_RESTORE_HOOK`，不会因存在同名 RustFS 容器而恢复原始卷。
- 外部对象备份必须提供 `OBJECT_RESTORE_HOOK`，恢复版本清单并自行校验对象版本/摘要。

## 5. Digest 晋级与健康切流

CI 发布完成后，在 `Build image` Job Summary 或 `Record immutable image digest` 步骤日志中复制完整 `repository@sha256:...`。该值不使用 Actions Artifact 存储，同时已关闭 `build-push-action` 默认的 `.dockerbuild` 记录上传，避免 Artifact 配额耗尽导致已发布的镜像工作流失败：

```bash
MARKET_SHOP_ENV_FILE=.env \
  scripts/deploy-digest.sh ghcr.io/ORG/market-shop@sha256:<64-hex>
```

发布顺序固定：

1. 拉取不可变 digest，记录当前 digest；
2. graceful stop 创建部署前 DB+对象备份，并恢复旧 app；
3. 从备份恢复临时数据库，使用 `docker-compose.release.yml` 启动候选镜像完成 Flyway 预检；预检失败立即清理，**不切流**；
4. 候选镜像连接生产依赖但关闭定时任务，执行真实迁移并通过 readiness、SPA/API、诊断隔离检查；迁移或健康失败时旧 app 继续接流；
5. 仅候选全部健康后更新 `.market-shop-release/active.env`，重建 `app` 并执行公网 smoke；
6. 保存上一 digest、当前 digest和部署前备份路径。切流失败自动请求上一 digest。

候选端口只绑定 `127.0.0.1:${MARKET_SHOP_CANDIDATE_PORT:-18080}`。生产边缘代理仍应为 app 的短暂重建配置重试/连接排空；本脚本不宣称跨主机零停机。

## 6. 回滚

```bash
# 默认读取 .market-shop-release/previous.digest
MARKET_SHOP_ENV_FILE=.env scripts/rollback-digest.sh

# 或显式指定已验证的上一 digest
MARKET_SHOP_ENV_FILE=.env scripts/rollback-digest.sh \
  ghcr.io/ORG/market-shop@sha256:<64-hex>
```

回滚前默认再次备份；仅事故指挥明确记录后可设置 `ROLLBACK_SKIP_BACKUP=true`。回滚会等待 readiness 和公网 smoke，不执行任何 down migration。若上一镜像与现有前向 schema 不兼容，应保持当前镜像、从部署前备份恢复到隔离环境，再按事故方案切换完整环境。

## 7. 每季度恢复演练

1. 选择最近备份并验证它不超过 15 分钟 RPO；记录备份、异地副本和 digest。
2. 创建全新 Compose project/空 volume，禁止复用生产数据库和 Redis。
3. 执行 restore，记录清单验证、MySQL 导入、Redis 清理、Flyway、对象摘要和 smoke 各阶段耗时。
4. 分别抽查一份订单凭证和一份 catalog 对象；外部 S3 还要核对版本清单时间点。
5. 模拟候选 migration 失败，确认旧流量未切换；模拟健康失败，确认上一 digest 可恢复。
6. 总耗时必须小于 2 小时。超时、摘要不一致、hook 缺失或 RPO 超标都要建立 P0 整改项，并在下一次发布前复演。
