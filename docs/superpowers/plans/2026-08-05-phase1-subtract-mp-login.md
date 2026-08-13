# Phase 1 Implementation Plan: 删除 storefront/模板系统 + 小程序 wx.login 后端登录

> **已被取代（2026-08-13）：** 本文仅作为历史实施记录，其中的小程序登录/注册 API 描述不再是现行契约。当前以 [Miniprogram Public API Contracts](../../../.trellis/spec/backend/miniprogram-api-contracts.md) 为准：登录严格为 `{code}`，邀请注册使用独立路由 `{code, inviteCode}`。

> **For agentic workers:** 按任务顺序执行。每完成一个 Task 运行其验证命令。全局约束先读。

**Goal:** 工程收敛为「PC 后台 + 小程序 API」：删除 storefront SPA 与多模板系统，微信登录从公众号/网页 OAuth 切换为小程序 code2session。

**Architecture:** Spring Boot 多模块（domain/application/infrastructure/interfaces/bootstrap）+ Vue 3 admin + pnpm workspace。模板系统为自包含垂直切片，整体删除；小程序登录复用现有 `UserIdentityPort.findOrRegister` 与 `StpUserKit` 会话。

**Tech Stack:** Java 21, Spring Boot 4, Sa-Token, MyBatis-Flex, Flyway, Vue 3, Vite, pnpm 10。

## Global Constraints

- 设计 spec：`docs/superpowers/specs/2026-08-05-miniprogram-pivot-design.md`（已获批准，不可偏离）
- 新配置键：`MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID` / `MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET`；删除 `WECHAT_OA_*` / `WECHAT_WEB_*` / `WECHAT_CALLBACK_BASE_URL` / `STOREFRONT_BASE_URL`
- Flyway：不删除已应用的 V8，新增 `V13__drop_storefront_templates.sql` 做清理
- 小程序登录端点：`POST /api/v1/auth/wechat/miniprogram/login`，provider 标识 `WECHAT_MP`
- 会话：Sa-Token 用户会话（StpUserKit）增加 header 读取，登录响应返回 token 值
- 管理端中文界面；禁浏览器原生 alert/confirm（沿用现状）
- 每 Task 结束：相关测试必须通过

---

### Task 1: 后端删除模板系统 + V13 迁移

**Files:**
- Delete: `backend/shop-interfaces/src/main/java/com/marketshop/interfaces/storefront/AdminStorefrontTemplateController.java`
- Delete: `backend/shop-interfaces/src/main/java/com/marketshop/interfaces/storefront/StorefrontTemplateController.java`
- Delete: `backend/shop-application/src/main/java/com/marketshop/application/storefront/` 整包（4 个文件：StorefrontTemplateApplicationService / StorefrontTemplateUseCase / StorefrontTemplatePort / StorefrontTemplatePresets）
- Delete: `backend/shop-domain/src/main/java/com/marketshop/domain/storefront/StorefrontTemplate.java`
- Delete: `backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/storefront/MyBatisStorefrontTemplateAdapter.java`
- Delete: `backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/persistence/mapper/StorefrontTemplateMapper.java`
- Delete: `backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/persistence/model/StorefrontTemplatePersistenceModels.java`
- Delete: `backend/shop-domain/src/test/java/com/marketshop/domain/storefront/StorefrontTemplateTest.java`
- Delete: `backend/shop-application/src/test/java/com/marketshop/application/storefront/StorefrontTemplateApplicationServiceTest.java`
- Create: `backend/shop-bootstrap/src/main/resources/db/migration/V13__drop_storefront_templates.sql`
- Modify: `backend/shop-interfaces/src/main/java/com/marketshop/interfaces/security/AdminAuditFilter.java`（删 `/storefront/templates` → `STOREFRONT_TEMPLATE` 分支，约 L82-84）
- Modify: `backend/shop-interfaces/src/main/java/com/marketshop/interfaces/security/SaTokenSecurityConfiguration.java`（删公开放行 `/api/v1/storefront/**`，约 L54）
- Modify: `backend/shop-interfaces/src/test/java/com/marketshop/interfaces/shared/GlobalExceptionHandlerTest.java`（删 `mapsMalformedTemplateConfigurationToBadRequest` 测试方法，约 L66-73）
- Modify: `scripts/business-e2e.sh`（删模板场景 ~L624-642 及末尾成功日志中的模板表述 ~L1201）
- Modify: `scripts/runtime-smoke.sh`（删 `assert_get "/api/v1/storefront/template"` ~L37）
- Delete: `.trellis/spec/backend/storefront-templates.md`、`.trellis/spec/frontend/storefront-templates.md`
- Modify: `docs/architecture.md`（删模板架构段落）

**Interfaces:**
- Produces: V13 迁移后 `operation_storefront_template` 表与 `storefront:template:manage` 权限不复存在；Task 2/3 依赖本 Task 的编译通过状态。

- [ ] **Step 1: 写 V13 迁移**

```sql
-- V13__drop_storefront_templates.sql
DELETE rp FROM iam_role_permission rp
JOIN iam_permission p ON p.id = rp.permission_id
WHERE p.code = 'storefront:template:manage';

DELETE FROM iam_permission WHERE code = 'storefront:template:manage';

DROP TABLE IF EXISTS operation_storefront_template;
```

（先核对 `iam_role_permission`/`iam_permission` 的实际列名与关联方式——以 V8 的 INSERT 语句为准，V13 做其精确逆操作。表与权限的实际定义以 V8 文件内容为准。）

- [ ] **Step 2: 删除上述全部 Delete 文件与空包目录**

- [ ] **Step 3: 三处 surgical modify（AdminAuditFilter / SaTokenSecurityConfiguration / GlobalExceptionHandlerTest）**

- [ ] **Step 4: 编译 + 全量后端测试**

Run: `mvn -f backend/pom.xml clean test -q`
Expected: BUILD SUCCESS，无模板相关编译错误/测试失败。若有其他文件引用被删类（grep `StorefrontTemplate` 全 backend 应为 0 命中，除 V8 迁移文件与 V13），一并清理。

- [ ] **Step 5: 脚本与 spec/文档清理**（business-e2e.sh、runtime-smoke.sh、两个 spec md、docs/architecture.md）

- [ ] **Step 6: 提交**

```bash
git add -A backend scripts docs .trellis/spec
git commit -m "feat!: remove storefront template system (backend + V13 drop)"
```

---

### Task 2: 后端小程序 wx.login（code2session）

**Files:**
- Modify: `backend/shop-interfaces/src/main/java/com/marketshop/interfaces/identity/AuthController.java` — 删 `/wechat/authorize`、`/wechat/callback`、`/wechat/complete` 及 OAuth cookie 绑定逻辑；新增 miniprogram login 端点
- Modify: `backend/shop-application/src/main/java/com/marketshop/application/identity/AuthApplicationService.java`（实际路径以现有为准）— 删 H5/WEB scene 逻辑，新增 `miniprogramLogin(MiniprogramLoginCommand)`
- Modify: `backend/shop-application/.../AuthUseCase.java` — 端口接口同步
- Delete/Replace: `backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/identity/WeChatOAuthAdapter.java` → 新 `WeChatMiniprogramAdapter`（code2session）
- Modify: 端口 `WeChatOAuthPort` → 重命名/替换为 `WeChatMiniprogramPort`：`WeChatIdentity exchangeMiniprogramCode(String jsCode)`
- Modify: StpUserKit 配置类（用户侧 Sa-Token 配置）— `isReadHeader=true`（保留 cookie 读取）
- Modify: `MyBatisIdentityAdapter` 或 sponsor claim 相关 — `SPONSOR_CLAIM_PROVIDERS` 集合由 `WECHAT_H5|WECHAT_WEB` 改为 `WECHAT_MP`
- Modify: `backend/shop-bootstrap/src/main/resources/application.yml`、`application-prod.yml` — `market-shop.wechat.*` 键替换（删 official-account-*/website-*/oauth-callback-base-url/storefront-base-url，增 miniprogram-app-id/miniprogram-secret）
- Modify: `ProductionRuntimeProperties`（`market-shop.production` 嵌套 Wechat）— 字段与校验同步
- Modify 测试：`AuthApplicationServiceTest`、`MyBatisIdentityAdapterTest`、`LoginResponseContractTest`、`SaTokenSecurityConfigurationTest`、`ProductionRuntimePropertiesTest`、`ProductionEnvironmentPostProcessorTest`、`SystemControllerTest`
- Delete 测试：`WeChatOAuthAdapterTest`、`AuthControllerRedirectTest`（OAuth 特有）；新增 `WeChatMiniprogramAdapterTest`

**Interfaces:**
- Consumes: `UserIdentityPort.findOrRegister(WeChatIdentity, inviteCode, sponsorClaimSecretHash)`（现有，不动签名）；`StpUserKit.logic().login(userId)`；devLogin 的 mock 模式（`market-shop.wechat.mock-enabled`）
- Produces（阶段二小程序依赖）:
  - `POST /api/v1/auth/wechat/miniprogram/login` 请求体 `{code: string, inviteCode?: string, sponsorClaimSecret?: string}`，响应 `ApiResponse<MiniprogramLoginView>`，`MiniprogramLoginView = {token: string, publicId: string, nickname: string, newlyRegistered: boolean}`
  - token 在后续请求以 header 携带，header 名 = StpUserKit tokenName `market-shop-user-token`
  - mock 模式（`market-shop.wechat.mock-enabled=true`）：`code` 直接作为 openId，unionId = `mock-union-` + code，不调微信服务器
  - 真实模式：`GET https://api.weixin.qq.com/sns/jscode2session?appid={appId}&secret={secret}&js_code={code}&grant_type=authorization_code` → 取 `openid`/`unionid`；微信返回 `errcode` 时抛 `DomainException("WECHAT_CODE_EXCHANGE_FAILED")`（400/409 映射沿用 GlobalExceptionHandler 后缀规则）
  - provider 字符串 `WECHAT_MP`，appId = 配置的 miniprogram appId（mock 时为 `local`）

- [ ] **Step 1: 先写失败测试**

`AuthApplicationServiceTest` 新增用例（参照现有 devLogin 用例模式）：

```java
@Test
void miniprogramLoginRegistersNewUserWithInviteCode() {
    // fake port: exchangeMiniprogramCode("code-1") → WeChatIdentity("WECHAT_MP","mp-app","openid-1","union-1",null)
    // act: miniprogramLogin(new MiniprogramLoginCommand("code-1","INVITE-1",null))
    // assert: LoginResult newlyRegistered=true; identity port received provider WECHAT_MP
}

@Test
void miniprogramLoginRequiresInviteCodeForNewIdentity() {
    // new identity + blank inviteCode → DomainException code startsWith INVITE_CODE
}
```

`LoginResponseContractTest` 更新：SessionView 约定改为 MiniprogramLoginView 含 token 字段（token 非空）。

- [ ] **Step 2: 运行确认失败** `mvn -f backend/shop-application/pom.xml test -Dtest=AuthApplicationServiceTest -q` → 编译失败/断言失败（方法不存在）

- [ ] **Step 3: 实现 application 层**（UseCase + Service：删 scene/OAuth state 逻辑，新增 miniprogramLogin，复用 findOrRegister + 返回 LoginResult）

- [ ] **Step 4: 实现 infrastructure 适配器**

```java
@Component
public class WeChatMiniprogramAdapter implements WeChatMiniprogramPort {
    // mockEnabled=true → WeChatIdentity("WECHAT_MP","local", jsCode, "mock-union-"+jsCode, null)
    // 否则 RestClient GET jscode2session，解析 openid/unionid，errcode != null → DomainException("WECHAT_CODE_EXCHANGE_FAILED")
}
```

- [ ] **Step 5: 接口层**：AuthController 新端点；establishSession 后取 `StpUserKit.logic().getTokenValue()` 放入 MiniprogramLoginView.token；删除 OAuth 三端点与 binding cookie 代码

- [ ] **Step 6: StpUserKit `isReadHeader=true`**；SaTokenSecurityConfiguration 确认 `/api/v1/auth/wechat/**` 仍在排除清单（新端点天然覆盖）

- [ ] **Step 7: 配置键替换**（application.yml / application-prod.yml / ProductionRuntimeProperties / 相关测试）

- [ ] **Step 8: SPONSOR_CLAIM_PROVIDERS → WECHAT_MP**，sponsor claim 用例更新

- [ ] **Step 9: 全量后端测试** `mvn -f backend/pom.xml clean test -q` → BUILD SUCCESS

- [ ] **Step 10: 提交** `git commit -m "feat: replace wechat OAuth with miniprogram code2session login"`

---

### Task 3: admin 前端删除模板功能

**Files:**
- Delete: `frontend/admin/src/views/TemplatesView.vue`
- Delete: `frontend/admin/src/components/TemplatePreview.vue`
- Modify: `frontend/admin/src/admin-navigation.ts` — 删 templates 项（name templates, path /templates, permission storefront:template:manage）
- Modify: `frontend/admin/src/localization.ts` — 删 `'storefront:template:manage': '商城模板管理'`（~L201）
- Modify: `frontend/admin/tests/admin-pages.test.mjs` — 删导航期望 `['/templates','storefront:template:manage']` 与测试 `'storefront template studio supports presets...'`

- [ ] **Step 1:** 删除/修改上述文件
- [ ] **Step 2:** `grep -ri "template" frontend/admin/src frontend/admin/tests | grep -vi "<template\|grid-template\|template-"` → 应无功能残留
- [ ] **Step 3:** 验证

Run: `pnpm --filter @market-shop/admin test && pnpm --filter @market-shop/admin typecheck && pnpm --filter @market-shop/admin build`
Expected: 全过

- [ ] **Step 4:** 提交 `git commit -m "feat: remove template studio from admin console"`

---

### Task 4: 删除 storefront + 工程脚本

**Files:**
- Delete: `frontend/storefront/` 整目录
- Modify: `package.json`（root）— 删 `dev:storefront`；`build:web`/`build:container:web`/`test:web` 去掉 storefront filter，只留 admin；`typecheck:web` 保持 `pnpm -r typecheck`
- `pnpm-workspace.yaml` 不动（glob `frontend/*` 自动只剩 admin）
- Modify: `.env.local.example` — `ADDITIONAL_WRITE_ORIGINS` 删 5173 两条，保留 5174

- [ ] **Step 1:** `rm -rf frontend/storefront` + package.json 修改
- [ ] **Step 2:** `pnpm install`（刷新 lockfile）+ `pnpm test:web && pnpm typecheck:web && pnpm build:web` 全过
- [ ] **Step 3:** 提交 `git commit -m "feat!: remove storefront SPA package"`

---

### Task 5: 部署 / CI / 环境样例 / 文档

**Files（精确改动以探索报告为准）:**
- Modify: `Dockerfile` — L16 删 storefront package.json COPY；L22-23 构建只 admin、map 清理只 admin dist；L42-44 删 storefront dist COPY
- Modify: `deploy/nginx.conf` — 删根路径 SPA 服务块（`/assets/` 与 `/` 的 try_files /index.html）；保留 `/api/`、`= /healthz`、actuator/docs/swagger 404 块、`/admin` 与 `^~ /admin/`；根 `/` 返回 404
- Modify: `docker-compose.yml` L95-106 / `docker-compose.release.yml` L53-58 — OA/WEB 键 → MINIPROGRAM_APP_ID/SECRET；删 STOREFRONT_BASE_URL
- Modify: `.env.example` L59-64、`.env.local.example` L52-57 — 同上；L13 注释改写
- Modify: `scripts/production-verify.sh` — 删 `/` storefront SPA 断言（L48），文案 SPA→admin（L10/L63）
- Modify: `scripts/runtime-smoke.sh` — 删 `/` 断言（L34），保留 /admin/ 与 /healthz
- Modify: `tests/container-package.test.mjs` — 删 storefront dist 断言（L16）、root try_files 断言改 admin-only（L30/L76）、删 storefront/template smoke（L145）
- Modify: `tests/production-operations.test.mjs` — STOREFRONT_BASE_URL 断言（L27/L65）与 application-prod.yml 正则（L87）改为 miniprogram 键形态
- Modify: `.github/workflows/docker-image.yml` — quality 阶段 storefront 步骤移除（L69-97 区域）、L114 文案、recovery env L187-192 键替换
- Modify: `README.md` — 目录结构/启动命令/微信登录配置章节重写为小程序版
- Modify: `.trellis/spec/backend/storefront-query-contracts.md` — 改写为小程序公开接口契约（删模板接口，保留 catalog/cart/orders/addresses/proofs，注明新登录端点与 header 会话）

- [ ] **Step 1:** 按上表逐文件修改
- [ ] **Step 2:** 验证

Run: `pnpm test:container`（node --test tests/*.test.mjs）
Run: `docker compose --env-file .env config --quiet`（若无 .env 用 .env.example 拷贝临时验证）
Run: `docker build -t market-shop:local .`（可选，时间允许则跑）
Expected: 全过

- [ ] **Step 3:** 提交 `git commit -m "chore: drop storefront from deploy, CI and docs"`

---

## 完成判定（Definition of Done）

1. `mvn -f backend/pom.xml clean test package` 全绿
2. `pnpm test && pnpm typecheck:web && pnpm build:web` 全绿
3. `grep -ri "storefront" backend/ frontend/ scripts/ deploy/ tests/ --include="*.java" --include="*.ts" --include="*.vue" --include="*.mjs" --include="*.sh" --include="*.conf" -l` 仅剩历史迁移/无关命中（如 notification template_code 不在此列）
4. `POST /api/v1/auth/wechat/miniprogram/login` 在 mock 模式下可用（curl 验证）
5. docker compose config 校验通过
