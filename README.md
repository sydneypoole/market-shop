# 特殊分销商城

一套面向网页端与 H5 的线下收款商城 MVP。后端采用 Java 21、Spring Boot 4、MyBatis-Flex、Sa-Token、Redis、Hutool、MySQL 8.4 与 Flyway，图片可存入 RustFS/S3 或本地磁盘；前端包含 Vue 3 精品商城端和 Vue 3 运营后台。

系统没有在线支付。订单流程固定为：

`用户提交订单 → 直属上级确认线下收款 → 后台审核 → 发货 → 用户确认/自动收货`

只有完成订单才会生成会员升级证据、直属业绩和演示积分。积分不可提现、不可转账、不可兑换现金，奖励关系深度固定为一层。

## P0 / P1 已实现范围

- P0：微信 H5/网页 OAuth 与本地模拟登录、邀请注册、独立后台认证、RBAC、强制改密、敏感操作二次校验和不可变审计。
- P0：地址完整 CRUD、购物车与订单提交、用户取消、直属上级线下收款确认、后台审核、发货、主动/自动收货。
- P0：仅退款和退货退款闭环，由后台审核与验货、直属上级确认线下退款、买家确认到账；不调用线上支付或退款。
- P0：私有订单/售后凭证、真实图片类型校验、元数据清理、短时签名下载、访问审计和租约清理任务。
- P0：动态规则校验与版本发布、邀请二维码撤销/重建、升级/降级、直属业绩、A/B 积分池和幂等等级变更轨迹。
- P1：分类、商品、SKU、内容运营和幂等库存调整账本；会员检索、详情、状态与人工重算。
- P1：订单组合查询、CSV 安全导出、订单备注、批量发货部分成功结果、运营仪表盘和站内通知。
- P1：响应式商城端与独立运营后台均覆盖上述流程，用户与管理员使用隔离的 Sa-Token 会话。
- P1：运营后台已补齐订单/售后详情与凭证、服务端分页和时间筛选、RustFS/本地磁盘商品素材、多 SKU 与库存流水、可视化规则、账号解锁与自定义角色 CRUD、审计筛选导出及系统配置；菜单和路由按权限动态收敛。
- P1：商城端采用原创精品零售主视觉，真实商品封面贯穿首页、详情、购物车、结算和订单详情；无图片与加载失败均提供统一兜底。

## 目录结构

```text
backend/
  shop-domain/          领域模型和状态机
  shop-application/     用例、端口与业务编排
  shop-infrastructure/  MyBatis-Flex、Redis、微信、RustFS/S3、本地文件、调度器适配
  shop-interfaces/      REST API、Sa-Token 会话、RBAC 与审计
  shop-bootstrap/       Spring Boot 启动、配置与 Flyway
frontend/
  storefront/           响应式商城端（网页/H5）
  admin/                运营后台
docs/                   架构与业务说明
```

## Docker Compose 一键启动

只需要 Docker 和 Docker Compose：

1. 复制配置，至少替换数据库、Redis 和本地文件签名密钥：

   ```bash
   cp .env.example .env
   ```

   `.env` 已被 Git 忽略。`MARKET_SHOP_LOCAL_STORAGE_SIGNING_SECRET` 必须是至少 32 位的随机字符串。

2. 构建并启动商城、运营后台、Spring Boot、MySQL 和 Redis：

   ```bash
   docker compose --env-file .env up -d --build --remove-orphans
   ```

   默认使用本地文件存储，上传内容保存在命名卷 `market-shop-uploads` 中，不会由 Nginx 直接公开。应用会等待 MySQL 和 Redis 健康后启动，并自动执行 Flyway。

3. 查看状态和日志：

   ```bash
   docker compose --env-file .env ps
   docker compose --env-file .env logs -f app
   ```

   Spring Boot 日志默认按级别彩色显示，并包含时间、应用名、线程和 logger；Nginx 访问日志使用单行 JSON，包含请求 ID、状态码、请求耗时和上游耗时，且不会记录 URL 查询参数。若日志采集平台不处理 ANSI 控制符，可在 `.env` 中设置：

   ```dotenv
   MARKET_SHOP_LOG_ANSI=NEVER
   MARKET_SHOP_LOG_LEVEL=INFO
   MARKET_SHOP_APP_LOG_LEVEL=INFO
   ```

   运行地址：

   - 商城：`http://localhost:8080/`
   - 运营后台：`http://localhost:8080/admin/`
   - Swagger UI：`http://localhost:8080/docs`
   - 健康检查：`http://localhost:8080/actuator/health/readiness`

4. 如果首次启动需要创建后台账号，先在 `.env` 中配置：

   ```dotenv
   MARKET_SHOP_BOOTSTRAP_ADMIN_ENABLED=true
   MARKET_SHOP_BOOTSTRAP_ADMIN_PASSWORD=请替换为至少12位的强密码
   ```

   Bootstrap 只会创建超级管理员 `admin` 和初始超级会员。创建成功后，将开关改回 `false` 并执行：

   ```bash
   docker compose --env-file .env up -d app
   ```

5. 停止服务：

   ```bash
   docker compose --env-file .env down
   ```

   该命令保留数据库和上传卷。只有确认不再需要任何本地数据时才使用 `docker compose down -v`。

### 可选 RustFS 模式

将 `.env` 改为：

```dotenv
MARKET_SHOP_STORAGE_PROVIDER=s3
MARKET_SHOP_RUSTFS_ENDPOINT=http://rustfs.localhost:9000
```

然后启用 `rustfs` profile：

```bash
docker compose --env-file .env --profile rustfs up -d --build
```

RustFS S3 API 为 `http://rustfs.localhost:9000`，管理控制台为 `http://localhost:9001`。`rustfs.localhost` 在宿主机解析为回环地址，在 Compose 网络中解析为 RustFS 服务，因此后端和浏览器使用同一个签名地址。RustFS 默认只绑定 `127.0.0.1`；对外部署时必须改用双方都可访问的 HTTPS 域名，并配置防火墙和强凭据。

## 源码开发启动

需要 Java 21+、Maven 3.9+、Node.js 20+、pnpm 10+ 和 Docker。

1. 复制 `.env.example` 后，只启动 MySQL 和 Redis：

   ```bash
   cp .env.example .env
   docker compose --env-file .env up -d mysql redis
   ```

   如果源码开发需要 RustFS，将存储模式改为 `s3`，再执行：

   ```bash
   docker compose --env-file .env --profile rustfs up -d mysql redis rustfs
   ```

2. 首次本地演示启动后端：

   ```bash
   set -a
   source .env
   set +a
   export SPRING_PROFILES_ACTIVE=local
   export MARKET_SHOP_BOOTSTRAP_ADMIN_ENABLED=true
   export MARKET_SHOP_BOOTSTRAP_ADMIN_PASSWORD='请替换为至少12位的强密码'
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
   - 首个超级会员与邀请码：`MARKET_SHOP_BOOTSTRAP_INVITE_CODE`，默认 `BOOTSTRAP2026`

   Bootstrap 超级管理员使用配置的临时密码并标记为必须修改密码。创建完成后应关闭 Bootstrap 开关。

3. 启动前端：

   ```bash
   pnpm install
   pnpm dev:storefront
   pnpm dev:admin
   ```

   商城端默认地址为 `http://localhost:5173`，后台端为 `http://localhost:5174`，后端 API 为 `http://localhost:8080`，Swagger UI 为 `http://localhost:8080/docs`。

4. `local` profile 才开放模拟微信登录。商城登录页可使用邀请码 `BOOTSTRAP2026` 创建演示买家；`bootstrap-sponsor` 是首个直属上级的本地模拟微信标识。

## 真实微信登录配置

生产环境不要启用 `local` profile 或 `MARKET_SHOP_WECHAT_MOCK_ENABLED`。需要在服务端配置：

```dotenv
MARKET_SHOP_WECHAT_ENABLED=true
MARKET_SHOP_WECHAT_OA_APP_ID=
MARKET_SHOP_WECHAT_OA_SECRET=
MARKET_SHOP_WECHAT_WEB_APP_ID=
MARKET_SHOP_WECHAT_WEB_SECRET=
MARKET_SHOP_WECHAT_CALLBACK_BASE_URL=https://api.example.com
```

- H5 使用微信公众号网页授权 `snsapi_userinfo`。
- 网页端使用微信开放平台网站应用扫码登录 `snsapi_login`。
- 微信后台授权回调域名应允许 `https://api.example.com/api/v1/auth/wechat/callback`。
- OAuth state 存在 Redis 中，5 分钟过期且只能消费一次。
- 首次注册强制邀请码；已有身份登录不再要求邀请码。
- 同一 `unionid` 会连接 H5 与网页端身份，直属上级绑定后不可自行修改。

## 验证

```bash
mvn -f backend/pom.xml clean test package
pnpm test
pnpm typecheck:web
pnpm build:web
docker compose --env-file .env config --quiet
```

当前 Flyway 空库基线为 V1–V7。V7 将默认后台身份收敛为唯一的 `admin` 超级管理员；升级已有数据库时会安全停用旧版自动创建的 `ops-*` 账号，并保留其历史审计身份。

完整 API 状态顺序、积分投影和售后冲正说明见 [docs/architecture.md](docs/architecture.md)。

## GitHub 自动构建镜像

仓库提供 `.github/workflows/docker-image.yml`。每次 Push 和 Pull Request 都会先执行后端测试、两个前端的测试与类型检查，再构建单一应用镜像：

- Spring Boot 可执行 JAR 在镜像内部监听 `8081`。
- Nginx 对外监听 `8080`，商城端位于 `/`，运营后台位于 `/admin/`。
- `/api/`、`/actuator/`、`/docs` 和 OpenAPI 路径由 Nginx 转发给 Spring Boot。
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
  ghcr.io/OWNER/REPOSITORY:latest
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
- 健康检查：`http://localhost:8080/actuator/health/readiness`

## 生产注意事项

- 使用密钥管理服务注入数据库、Redis、对象存储签名密钥和微信密钥，不要提交 `.env`。
- 关闭 Bootstrap、模拟微信登录、Swagger UI 和详细健康信息。
- RustFS bucket 必须保持私有，付款凭证仅使用短时签名链接。生产环境应启用 TLS，并将 `MARKET_SHOP_RUSTFS_ENDPOINT` 配置为浏览器可访问的 HTTPS 地址。
- 本地磁盘模式不得由 Nginx 或静态目录直接暴露；持久卷只授予应用账户读写权限，签名密钥至少 32 位并在实例间一致。
- 在反向代理层配置 HTTPS、可信代理列表、CSP、上传限速和请求大小限制。
- 后台账号必须启用独立密码策略、定期轮换与最小权限；生产建议接入 MFA。
- 积分文案和分销规则上线前应由目标经营地区的法律与合规人员复核。

## 从 MinIO 切换

Compose 使用新的 `rustfs-data` 卷，不会删除或复用旧 `minio-data` 卷。旧环境如果已经保存付款/售后凭证，应在下线 MinIO 前通过 S3 客户端将对象复制到 RustFS 的同名私有 bucket，并核对对象数量与摘要；数据库中的对象键无需修改。确认迁移完成后再单独归档或删除旧卷。
