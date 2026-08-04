# 特殊分销商城

一套面向微信小程序用户端 + PC 运营后台的线下收款商城 MVP。后端采用 Java 21、Spring Boot 4、MyBatis-Flex、Sa-Token、Redis、Hutool、MySQL 8.4 与 Flyway，图片可存入 RustFS/S3 或本地磁盘；Web 侧仅保留 Vue 3 运营后台，用户客户端为原生微信小程序。

系统没有在线支付。订单流程固定为：

`用户提交订单 → 直属上级确认线下收款 → 后台审核 → 发货 → 用户确认/自动收货`

只有完成订单才会生成会员升级证据、直属业绩和演示积分。积分不可提现、不可转账、不可兑换现金，奖励关系深度固定为一层。

## P0 / P1 已实现范围

- P0：微信小程序 wx.login（code2session）与本地模拟登录、邀请注册、独立后台认证、RBAC、强制改密、敏感操作二次校验和不可变审计。
- P0：地址完整 CRUD、购物车与订单提交、用户取消、直属上级线下收款确认、后台审核、发货、主动/自动收货。
- P0：仅退款和退货退款闭环，由后台审核与验货、直属上级确认线下退款、买家确认到账；不调用线上支付或退款。
- P0：私有订单/售后凭证、真实图片类型校验、元数据清理、短时签名下载、访问审计和租约清理任务。
- P0：动态规则校验与版本发布、邀请二维码撤销/重建、升级/降级、直属业绩、A/B 积分池和幂等等级变更轨迹。
- P1：分类、商品、SKU、内容运营和幂等库存调整账本；会员检索、详情、状态与人工重算。
- P1：B 池冻结积分按来源订单形成可审计批次，复购订单按 FIFO 跨批次释放；释放流水和明细映射关联规则版本、原冻结分录及实际批次，售后可幂等恢复或关闭对应批次。
- P1：订单组合查询、CSV 安全导出、订单备注、批量发货部分成功结果、运营仪表盘和站内通知。
- P1：独立运营后台覆盖上述流程；用户会话（小程序）与管理员会话使用隔离的 Sa-Token 配置。
- P1：运营后台已补齐订单/售后详情与凭证、服务端分页和时间筛选、RustFS/本地磁盘商品素材、多 SKU 与库存流水、可视化规则、账号解锁与自定义角色 CRUD、审计筛选导出及系统配置；菜单和路由按权限动态收敛。

## 目录结构

```text
backend/
  shop-domain/          领域模型和状态机
  shop-application/     用例、端口与业务编排
  shop-infrastructure/  MyBatis-Flex、Redis、微信、RustFS/S3、本地文件、调度器适配
  shop-interfaces/      REST API、Sa-Token 会话、RBAC 与审计
  shop-bootstrap/       Spring Boot 启动、配置与 Flyway
frontend/
  admin/                运营后台（唯一 Web SPA）
docs/                   架构与业务说明
```

> 用户端为微信原生小程序（不在本仓库 Web monorepo 中）；小程序通过 `POST /api/v1/auth/wechat/miniprogram/login` 换取用户会话 token。

## Docker Compose 启动

`docker-compose.yml` 是生产基线：默认 `prod`，应用镜像必须由 `.env` 提供不可变 digest，MySQL/Redis 不发布宿主机端口。

```bash
cp .env.example .env
chmod 600 .env
# 替换全部 CHANGE_ME/OWNER，特别是三类密钥和 MARKET_SHOP_IMAGE
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --wait --wait-timeout 300
scripts/production-verify.sh http://127.0.0.1:8080
```

生产公开地址只包含后台 `/admin/`、API 与无详情健康 `/healthz`；根路径 `/` 返回 404（用户端为小程序，不再托管 Web 商城 SPA）。Swagger/OpenAPI/完整 Actuator 不经 Nginx 公开。首次空库初始化需显式提供 Bootstrap 强密码和邀请码，完成后立即关闭开关并重建 app。

本机/CI 才叠加开发文件，这是启用 local/mock 与回环 DB/Redis 端口的唯一默认路径：

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml \
  --env-file .env.local.example up -d --build --wait
```

RustFS 本机样本还需设置 `MARKET_SHOP_STORAGE_PROVIDER=s3` 并增加 `--profile rustfs`。开发覆盖会显式允许首启建桶；生产默认要求运维预建私有 bucket，且 S3 endpoint 必须为浏览器与后端都可达的 HTTPS origin。

完整的备份、恢复、digest 发布和回滚流程见 [生产运维手册](docs/production-operations.md)。

## 源码开发启动

需要 Java 21+、Maven 3.9+、Node.js 20+、pnpm 10+ 和 Docker。

1. 使用本地样本只启动 MySQL 和 Redis：

   ```bash
   cp .env.local.example .env.local
   docker compose -f docker-compose.yml -f docker-compose.local.yml \
     --env-file .env.local up -d mysql redis
   ```

   如果源码开发需要 RustFS，将存储模式改为 `s3`，再执行：

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.local.yml \
     --env-file .env.local --profile rustfs up -d mysql redis rustfs
   ```

2. 首次本地演示启动后端：

   ```bash
   set -a
   source .env.local
   set +a
   export SPRING_PROFILES_ACTIVE=local
   export MARKET_SHOP_BOOTSTRAP_ADMIN_ENABLED=true
   export MARKET_SHOP_BOOTSTRAP_ADMIN_PASSWORD='请替换为至少12位的强密码'
   export MARKET_SHOP_BOOTSTRAP_INVITE_CODE='BOOTSTRAP2026'
   export MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET='请替换为至少32位、且不同于邀请码的随机密钥'
   mvn install -DskipTests
   mvn -f backend/shop-bootstrap/pom.xml spring-boot:run
   ```

   也可以构建并运行可执行 JAR：

   ```bash
   mvn -f backend/pom.xml clean package
   java -jar backend/shop-bootstrap/target/shop-bootstrap-0.1.0-SNAPSHOT.jar \
     --spring.profiles.active=local
   ```

   Bootstrap 会在数据库为空时创建：

   - 1 个超级管理员：环境变量 `MARKET_SHOP_BOOTSTRAP_ADMIN_USERNAME`，默认 `admin`
   - 首个超级会员与邀请码：必须显式设置 `MARKET_SHOP_BOOTSTRAP_INVITE_CODE`
   - 发起人认领密钥：必须显式设置 `MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET`；它只保存哈希，不能与普通邀请码混用

   Bootstrap 超级管理员使用配置的临时密码并标记为必须修改密码。创建完成后应关闭 Bootstrap 开关，并清除临时密码与认领密钥。

3. 启动运营后台：

   ```bash
   pnpm install
   pnpm dev:admin
   ```

   后台默认地址为 `http://localhost:5174`，后端 API 为 `http://localhost:8080`，Swagger UI 为 `http://localhost:8080/docs`。用户端为微信小程序，不在本 monorepo 中启动。

4. `local` profile 才开放模拟微信登录（`MARKET_SHOP_WECHAT_MOCK_ENABLED=true`）。小程序登录请求体中的 `code` 会直接作为 mock openId；可用邀请码 `BOOTSTRAP2026` 创建演示买家；`bootstrap-sponsor` 是首个直属上级的本地模拟微信标识。

## 真实微信小程序登录配置

生产环境不要启用 `local` profile 或 `MARKET_SHOP_WECHAT_MOCK_ENABLED`。需要在服务端配置：

```dotenv
MARKET_SHOP_WECHAT_ENABLED=true
MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID=
MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET=
```

- 小程序调用 `wx.login` 取得临时 `code`，再请求 `POST /api/v1/auth/wechat/miniprogram/login`，请求体为 `{ "code": "...", "inviteCode"?: "...", "sponsorClaimSecret"?: "..." }`。
- 成功响应包含 `{ token, publicId, nickname, newlyRegistered }`；后续用户请求在 header `market-shop-user-token` 携带 token（cookie 读取仍保留）。
- 真实模式走微信 `jscode2session`；`errcode` 非空时返回 `WECHAT_CODE_EXCHANGE_FAILED`。
- 首次注册强制邀请码；已有身份登录不再要求邀请码。
- 身份 provider 标识为 `WECHAT_MP`。

## 验证

```bash
mvn -f backend/pom.xml clean test package
pnpm test
pnpm typecheck:web
pnpm build:web
docker compose --env-file .env config --quiet
bash scripts/runtime-smoke.sh http://localhost:8080
```

当前 Flyway 空库基线为 V1–V13。V7 将默认后台身份收敛为唯一的 `admin` 超级管理员；升级已有数据库时会安全停用旧版自动创建的 `ops-*` 账号，并保留其历史审计身份。V8 曾创建商城模板表与权限（历史迁移保留）；V13 删除模板表与 `storefront:template:manage` 权限。V9 增加 B 池冻结批次、复购释放头和释放明细映射，并从已有未冲正积分流水重建可释放余额与历史批次关系。V10 增加订单完成规则快照、outbox 退避/死信/重放字段及运维权限；V11 增加会员与管理员会话纪元以及一次性 Bootstrap 发起人认领密钥；V12 按 `created_at, id` 确定性修复直属业绩历史序号后增加受益人/序号唯一约束。

完整 API 状态顺序、积分投影和售后冲正说明见 [docs/architecture.md](docs/architecture.md)。

## GitHub 自动构建镜像

仓库提供 `.github/workflows/docker-image.yml`。每次 Push 和 Pull Request 都会先执行后端测试、运营后台测试与类型检查、空库 Compose 运行验收和 RustFS 真实对象生命周期测试，再构建单一应用镜像：

- Spring Boot 可执行 JAR 在镜像内部监听 `8081`。
- Nginx 对外监听 `8080`，运营后台位于 `/admin/`；根路径 `/` 返回 404。
- `/api/` 由 Nginx 转发给 Spring Boot；公网只额外开放无详情 `/healthz`，Actuator/Swagger/OpenAPI 路径均返回 404。
- Push 会把 `linux/amd64`、`linux/arm64` 多架构镜像发布到 `ghcr.io/<仓库所有者>/<仓库名>`；Pull Request 只构建验证，不发布。
- 默认分支同时生成 `latest`，普通分支生成分支名和 `sha-*` 标签，`v1.2.3` 形式的 Git 标签生成语义化版本标签。

发布使用仓库自带的 `GITHUB_TOKEN`，不需要另外配置 Registry 密码。仓库 Workflow 权限必须允许 GitHub Actions 对 Packages 写入；如果组织策略覆盖了仓库设置，需要由组织管理员放行。

本地构建同一镜像：

```bash
docker build -t market-shop:local .
```

运行镜像时，MySQL、Redis 仍作为独立基础设施部署。使用 `s3` 模式时 RustFS/S3 也独立部署。下面的服务名适用于容器已经加入对应 Compose 网络的场景：

```bash
docker run --rm --name market-shop-app \
  --network market-shop_default \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE='prod' \
  -e MARKET_SHOP_COOKIE_SECURE='true' \
  -e MARKET_SHOP_DB_URL='jdbc:mysql://mysql:3306/market_shop?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Hong_Kong&useSSL=false&allowPublicKeyRetrieval=true' \
  -e MARKET_SHOP_DB_USER='market_shop' \
  -e MARKET_SHOP_DB_PASSWORD='请从密钥管理服务注入' \
  -e MARKET_SHOP_REDIS_HOST='redis' \
  -e MARKET_SHOP_REDIS_PORT='6379' \
  -e MARKET_SHOP_REDIS_PASSWORD='请从密钥管理服务注入' \
  -e MARKET_SHOP_STORAGE_PROVIDER='s3' \
  -e MARKET_SHOP_RUSTFS_ENDPOINT='https://storage.example.com' \
  -e MARKET_SHOP_RUSTFS_ACCESS_KEY='请从密钥管理服务注入' \
  -e MARKET_SHOP_RUSTFS_SECRET_KEY='请从密钥管理服务注入' \
  -e MARKET_SHOP_RUSTFS_BUCKET='market-shop-private' \
  ghcr.io/OWNER/REPOSITORY@sha256:<64-hex-digest>
```

`MARKET_SHOP_RUSTFS_ENDPOINT` 必须是后端容器和用户浏览器都能访问的 HTTPS 地址，因为凭证预览会基于该地址生成短时签名链接。

如果使用本地磁盘，在上面的 `docker run` 命令中移除 `MARKET_SHOP_RUSTFS_*` 参数，加入以下参数和持久卷，并为所有重启实例保持同一签名密钥：

```bash
-v market-shop-uploads:/opt/market-shop/data/uploads
-e MARKET_SHOP_STORAGE_PROVIDER='local'
-e MARKET_SHOP_LOCAL_STORAGE_SIGNING_SECRET='请从密钥管理服务注入至少32位随机字符串'
```

本地磁盘模式适合单实例或共享持久卷部署；多实例无共享卷时应使用 S3/RustFS，避免不同实例读取不到对方写入的文件。运行后访问：

- 商城：`http://localhost:8080/`
- 运营后台：`http://localhost:8080/admin/`
- 健康检查：`http://localhost:8080/healthz`

## 生产注意事项

- 使用密钥管理服务注入数据库、Redis、对象存储签名密钥和微信密钥，不要提交 `.env`。
- 关闭 Bootstrap、模拟微信登录、Swagger UI 和详细健康信息。
- RustFS bucket 必须保持私有，付款凭证仅使用短时签名链接。生产环境应启用 TLS，并将 `MARKET_SHOP_RUSTFS_ENDPOINT` 配置为浏览器可访问的 HTTPS 地址。
- 本地磁盘模式不得由 Nginx 或静态目录直接暴露；持久卷只授予应用账户读写权限，签名密钥至少 32 位并在实例间一致。
- 在反向代理层配置 HTTPS、可信代理列表、CSP、上传限速和请求大小限制。
- 使用 TLS 终止代理时必须向商城容器传递 `X-Forwarded-Proto: https`，并建议传递 `X-Forwarded-Port: 443`；Cloudflare 链路还会优先读取其不可由访客变换规则修改的 `CF-Visitor`。容器 Nginx 会清洗这些值并覆盖客户端自带的 `Forwarded`，确保 Spring 正确执行同源/CORS 判断。
- 后台账号必须启用独立密码策略、定期轮换与最小权限；生产建议接入 MFA。
- 积分文案和分销规则上线前应由目标经营地区的法律与合规人员复核。

## 从 MinIO 切换

Compose 使用新的 `rustfs-data` 卷，不会删除或复用旧 `minio-data` 卷。旧环境如果已经保存付款/售后凭证，应在下线 MinIO 前通过 S3 客户端将对象复制到 RustFS 的同名私有 bucket，并核对对象数量与摘要；数据库中的对象键无需修改。确认迁移完成后再单独归档或删除旧卷。
