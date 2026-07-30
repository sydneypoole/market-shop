# 完成 P0 并实现多模板商城设计器

## Goal

完成当前审计中的全部 P0 上线阻塞项，并将商城升级为后台可配置的 SaaS 式区块模板系统。管理员可以维护多个草稿/已发布模板，在 PC 与 H5 预览后发布其中一个模板；商城端提供三套重新设计、业务能力一致但视觉和布局明显不同的响应式模板。

## Requirements

### P0 closure

- 商品列表按商品聚合，不因 SKU 数量产生重复卡片。
- 商品详情返回全部在售 SKU；用户必须选择具体 SKU 后才能加入购物车或立即下单。
- SKU 切换同步价格、划线价、库存、规格属性和按钮可用状态。
- 已发布内容支持按类型展示、合法目标地址跳转以及独立内容详情页，正文必须经过 HTML 安全过滤。
- 商城首页轮播/公告/快捷入口/故事内容不再依赖写死文案或固定图片。
- 商品素材上传区分空文件、超限、媒体类型不支持、图片损坏、存储失败和状态冲突，返回正确 HTTP 状态与中文业务错误。
- Java 提供统一请求 ID、结构化请求耗时日志、异常根因日志；本地/终端日志带颜色，容器中以 Java 日志为主。
- CI/交付验证覆盖 Maven、前端测试/类型检查/构建、Flyway 空库、Docker Compose 运行时健康检查和核心 HTTP 冒烟；RustFS 集成验证具有显式可运行入口。
- 微信 H5/网页 OAuth 配置和回调链路保留，并提供可执行的配置/冒烟检查；真实微信凭据之外的逻辑必须自动验证。

### Multi-template SaaS capability

- 新增商城模板聚合、Flyway 迁移、应用端口、MyBatis-Flex 适配器、公共查询 API 和后台管理 API。
- 后台可查看全部模板及状态，基于预设新建、复制、编辑、预览、发布和归档模板。
- 发布必须原子切换当前模板；草稿修改不得影响线上模板。
- 模板包含全局设计令牌与有序区块，支持启用/停用、上移/下移、修改设置。
- 同一份模板同时适配 PC 与 H5；后台可切换桌面/手机预览。
- 区块类型使用白名单组件注册表，不允许任意脚本、任意 Vue 组件或任意 CSS。
- 支持公告、主视觉、分类导航、商品集合、内容故事、服务权益和快捷入口区块。
- 模板运行时必须对未知/损坏配置安全降级到内置默认模板。

### Three redesigned presets

- `EDITORIAL`：杂志编辑感，非对称留白、大标题、暖色图片与精选陈列。
- `VIBRANT`：活力市集感，高对比色块、紧凑商品信息、明显的分类与活动入口。
- `MINIMAL`：现代极简精品感，克制单色、精细网格、大幅留白和沉浸式产品陈列。
- 三套模板均必须支持首页浏览、内容跳转、分类/场景筛选、商品详情、多 SKU、购物车、登录和会员/订单入口。
- 动效遵守 `prefers-reduced-motion`，移动端不依赖 hover，关键交互保持可访问键盘焦点。

## Acceptance Criteria

- [x] 同一商品存在三个 SKU 时，列表仅出现一张商品卡，详情可切换三个 SKU 并提交选中 SKU。
- [x] 后台发布的轮播、公告、快捷入口和正文可在商城正确展示与跳转，未发布内容不可见。
- [x] 上传空文件/超限/伪造格式/损坏图片分别返回 400/413/415/415，存储不可用返回 503，日志含 requestId 与错误代码。
- [x] 每个请求响应 `X-Request-Id`，Java 日志含 method/path/status/duration/requestId，500 日志保留堆栈。
- [x] 全新 MySQL 数据库可执行全部 Flyway 迁移并启动健康。
- [x] Docker Compose 可启动 MySQL、Redis、RustFS 和应用，健康检查及核心公共 API 冒烟通过。
- [x] 管理员可从任一预设创建模板、修改令牌和区块、分别预览 PC/H5、发布并切换线上模板。
- [x] 同一时间仅有一个活动模板，编辑草稿不改变活动模板。
- [x] 三套预设在 1440px、1024px、390px、360px 宽度无横向溢出且保持完整商城操作。
- [x] 前后端单元/契约测试、类型检查、生产构建全部通过。

## Definition of Done

- Flyway、领域/应用/基础设施/接口层和两个 Vue 应用保持 DDD/Phoenix 依赖约束。
- 新增关键业务和边界测试，更新现有静态契约测试。
- 文档包含模板配置、微信检查、存储模式和运行时冒烟命令。
- Docker/GitHub workflow 质量门禁可重复执行。
- 现有未提交的 `hero-note` 与商品页线下支付提示删除保留在交付中。

## Technical Approach

- 采用受控 typed-section 架构：模板聚合保存主题令牌与有序区块 JSON，后端严格解析/校验，前端通过固定组件注册表渲染。
- 模板分为 `DRAFT`、`PUBLISHED`、`ARCHIVED`；发布事务内取消旧活动模板并激活目标模板。
- 公共 storefront bootstrap API 一次返回活动模板、已发布运营内容、分类和首屏商品，后续搜索/分页继续复用目录 API。
- 产品查询拆分为聚合摘要与 SKU 列表，避免列表 join 造成重复。
- 请求关联由独立全局 filter 负责，后台审计 filter 复用相同 requestId。

## Decision (ADR-lite)

**Context**：需要 SaaS 式运行时模板配置，同时保证 Spring Boot 单镜像交付、PC/H5 一致性与安全边界。

**Decision**：选择受控的 JSON typed sections 和预设系统，不保存或执行任意模板代码。

**Consequences**：运营可以安全地组合和发布大量模板；增加新型区块需要开发并发布前后端组件，但不会让租户配置突破 CSP/XSS 和响应式约束。

## Expansion / Edge Cases

- 并发发布由事务和单活动模板约束解决。
- 损坏、未知版本或没有活动模板时返回内置 `EDITORIAL` 默认配置。
- 删除被活动模板引用的素材不阻止页面渲染，组件使用渐变/占位回退。
- 外链只允许 `https://`，站内链接只允许单斜杠开头且禁止 `//`。
- 模板区块最多 24 个，单个文本/数组/商品数量有明确上限。

## Out of Scope

- 允许管理员上传任意 Vue/Liquid/JavaScript/CSS 代码。
- 租户自定义域名、计费套餐和真正的多租户数据隔离。
- 第三方物流轨迹、在线支付和积分提现。
- 真实微信 AppID/Secret 的自动申请；系统只验证配置和 OAuth 链路。

## Research References

- [section-based-template-architecture.md](research/section-based-template-architecture.md) — 受 Shopify JSON templates 启发的受控区块架构与安全取舍。

## Technical Notes

- Existing storefront: `frontend/storefront/src`.
- Existing admin console: `frontend/admin/src`.
- Existing content/catalog APIs live under `shop-application`, `shop-infrastructure`, and `shop-interfaces`.
- Database migrations currently end at `V7`; this task starts with `V8`.
- The task is autonomous by explicit user request; non-blocking product preferences use the recommended defaults recorded above.
