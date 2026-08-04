# 设计文档：用户端切换为微信小程序（移除 PC/H5 商城端与多模板系统）

日期：2026-08-05
状态：已获用户批准（2026-08-05）

## 1. 背景与决策

原需求（PC 用户端 + H5 + 多模板系统）作废。已确认的新决策：

| 决策点 | 结论 |
|---|---|
| 用户端入口 | 仅微信小程序；PC 网页端与移动 H5 全部下线 |
| 运营端 | 保留 PC 管理后台（`frontend/admin`），删除模板相关页面 |
| 小程序技术栈 | 原生微信小程序，从零开发 |
| 多模板系统 | 彻底删除，含数据表（新 Flyway 迁移 drop） |
| 小程序功能范围 | 核心链路优先；会员中心/积分/邀请海报/售后二期再补 |
| 小程序账号 | 已有真实 AppID/Secret，后端走真实 code2session，保留 mock 开关 |
| 当前未提交改动 | 全部丢弃（旧重设计需求作废） |

## 2. 总体架构变化

| 维度 | 现状 | 目标 |
|---|---|---|
| 用户端 | `frontend/storefront`（PC+H5 响应式网页） | 原生微信小程序（唯一用户入口） |
| 运营端 | `frontend/admin`（PC 后台） | 保留，删除模板管理/模板工作室 |
| 用户登录 | 公众号网页授权 + 开放平台扫码 | 小程序 `wx.login` → code2session |
| 商城页面来源 | 多模板（3 预设 + 模板工作室 + DB 模板表） | 删除；小程序 UI 固定一套"极简高级"设计 |
| 后端 API | `/api/v1/...` REST | 几乎全部复用，仅新增小程序登录端点 |

## 3. 减法：删除 storefront 与模板系统

### 3.1 前端

- 删除 `frontend/storefront/` 整目录。
- admin 删除：模板列表页、模板工作室（studio）、相关路由、菜单项、API client、`TemplatePreview.vue` 等模板组件。
- admin 其余功能（订单/商品/会员/规则/审计/仪表盘等）不受影响。

### 3.2 后端

- 删除 `AdminStorefrontTemplateController`、`StorefrontTemplateController`。
- 删除模板全链路：application 用例、domain 模型、infrastructure Mapper/适配器（模板 CRUD、区块白名单校验、发布事务）。
- 新迁移 `V13__drop_storefront_templates.sql`：drop V8 创建的模板表，并清理 V8 注入的模板相关权限/菜单种子数据。
- 删除微信 H5/网页 OAuth：`AuthController` 的 authorize/callback/complete 端点、`WeChatOAuthAdapter` 中公众号/开放平台链路。

### 3.3 配置与 spec

- `.env.example` / `.env.local.example`：移除 `MARKET_SHOP_WECHAT_OA_*`、`MARKET_SHOP_WECHAT_WEB_*`、`MARKET_SHOP_STOREFRONT_BASE_URL`；新增 `MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID` / `MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET`。
- `.trellis/spec/backend/storefront-templates.md`、`.trellis/spec/frontend/storefront-templates.md`：删除。
- `.trellis/spec/backend/storefront-query-contracts.md`：改写为小程序消费的公开接口契约（移除模板接口，保留商品/购物车/订单等）。

## 4. 后端：小程序登录

- 新增 `POST /api/v1/auth/wechat/miniprogram/login`，请求体 `{code, inviteCode?}`。
- 流程：code2session（AppID/Secret 换 openid/unionid/session_key）→ 查找/注册用户身份 → 建立用户侧 Sa-Token 会话。
- 首次注册强制邀请码（沿用现有邀请制规则，业务核心不变）；已有身份登录不再要求邀请码。
- 同一 unionid 连接身份的既有规则保留；直属上级绑定后不可自行修改。
- 响应返回 token 值；小程序存 storage，请求头携带，不依赖 cookie。Sa-Token 用户会话配置为同时接受 header 与 cookie（admin 仍用 cookie）。
- mock 模式保留：`MARKET_SHOP_WECHAT_MOCK_ENABLED=true` 时 code 直接作为 mock openid，本地开发无需真实 AppID。
- 不获取手机号、不解密 session_key 数据（本期不需要）。

## 5. 小程序工程（`miniprogram/`，原生）

独立于 pnpm workspace 的原生小程序工程。

### 5.1 页面（核心链路）

| 模块 | 页面 |
|---|---|
| 登录 | 微信一键登录 + 邀请码注册（`pages/login`） |
| 首页 | 公告 + 分类导航 + 商品合集，固定"极简高级"设计（`pages/index`） |
| 商品 | 分类列表/搜索（`pages/category`）、详情含 SKU 选择/数量/划线价（`pages/goods/detail`） |
| 交易 | 购物车（`pages/cart`）、结算含地址选择（`pages/order/confirm`）、订单列表/详情、取消、确认收货、付款凭证上传（`pages/order/list`、`pages/order/detail`） |
| 地址 | 完整 CRUD（`pages/address/*`） |

二期（本设计不含）：会员中心、积分流水、邀请海报、售后流程。

### 5.2 结构

- `app.js` / `app.json` / `app.wxss`：全局配置、环境切换占位（dev: `http://localhost:8080`，prod: HTTPS API 域名，由运维配置到小程序后台 request 合法域名）。
- `utils/request.js`：token 注入、401 跳登录、统一错误提示。
- `utils/api/`：按域分模块（auth/catalog/cart/order/address）。
- `components/`：`goods-card`、`sku-picker`、`price`、`empty` 等。
- 视觉：黑白灰基调 + 单一强调色、细线条、大留白，固定一套，无模板概念。

### 5.3 接口

全部复用现有公开 REST API（商品/分类/购物车/订单/地址/凭证上传等），仅登录为新增端点。小程序端不引入新的后端契约变更。

## 6. 部署 / CI / 文档

- `Dockerfile`：移除 storefront 构建与拷贝层；Nginx 只服务 `/admin/`、`/api/`、`/healthz`；根路径 `/` 下线（返回 404）。
- `package.json`：移除 `dev:storefront`，`build:web`/`test:web` 只保留 admin。
- `pnpm-workspace.yaml`：`frontend/*` 仅剩 admin，glob 保留。
- CI（`.github/workflows/docker-image.yml`）：移除 storefront 的 test/typecheck/build 步骤。
- `docker-compose*.yml`、`scripts/`：检查并清理 storefront 引用。
- `README.md`：重写用户端与登录配置章节（商城端 → 小程序；OAuth 配置 → 小程序配置）。

## 7. 实施顺序

**阶段一 · 减法 + 后端登录**（本阶段完成后工程处于"仅后台 + API"的可验证状态）：

1. 丢弃未提交改动（`git checkout --` 6 个修改文件；删除未跟踪的 `docs/design/`）；归档作废任务 `08-04-redesign-frontend-with-pencil`。
2. 删除 storefront 与模板系统（前端、后端、V13 迁移、配置、spec）。
3. 替换微信登录为小程序 code2session（含 mock）。
4. 更新 Dockerfile / Nginx / CI / compose / README。
5. 验证：`mvn -f backend/pom.xml clean test package`、`pnpm --filter @market-shop/admin test|typecheck|build`、`pnpm test:container`、`docker compose --env-file .env config --quiet`。

**阶段二 · 小程序核心链路**：

1. 新建 `miniprogram/` 工程骨架（app/request/api/组件）。
2. 登录 + 邀请码注册联调。
3. 首页/分类/详情/购物车/结算/地址/订单/凭证上传。
4. 验证：微信开发者工具编译通过；核心链路以 mock 登录联调通过。

## 8. 兼容性与风险

- 已有数据库升级到 V13 时模板表被 drop，模板数据丢失（用户已确认彻底删除）。
- H5/网页端用户会话随 storefront 下线而失效；用户身份数据（openid/unionid 绑定）保留，小程序登录可复用同一身份记录。
- 小程序需 HTTPS API 域名并配置 request 合法域名后才能真机使用（运维事项，代码留占位）。
- 后端公开接口保持向后兼容，小程序直接消费，不需要版本变更。
