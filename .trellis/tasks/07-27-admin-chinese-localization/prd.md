# 后台管理端全量中文本地化

## Goal

将运营后台所有面向用户的界面文案统一为简体中文，消除菜单、页面标题、筛选选项、业务状态、角色权限、审计记录和详情弹窗中直接暴露的英文，同时保持 API 参数、数据库枚举和权限编码不变，避免破坏前后端契约。

## What I already know

- 用户明确要求后台显示中文。
- 当前后台位于 `frontend/admin`，是 Vue 3 + TypeScript 应用。
- 页面基础文案大部分已为中文，但会员、商品、内容、规则、账号、审计、订单和售后仍会直接显示英文枚举或英文装饰标题。
- `frontend/admin/src/api.ts` 已存在部分订单与售后状态中文映射，可以扩展为统一的展示文案层。
- 后台接口仍需要提交英文枚举值，因此下拉框必须采用“中文标签 + 英文 value”的方式。

## Assumptions

- 本次只提供简体中文界面，不增加语言切换功能。
- 商品编码、SKU 编码、规则编码、请求 ID 等业务标识按原值显示，不翻译。
- 未识别的新枚举值使用安全的中文兜底展示，同时保留可排查性，不能出现空白。
- 后端返回的自由文本（例如管理员填写的原因、商品标题、会员昵称）保持原样。

## Requirements

- 菜单、页面标题、按钮、表格列、表单标签、筛选项、空状态、校验提示、弹窗和登录页装饰文案均使用简体中文。
- 所有面向用户展示的业务枚举通过统一中文映射函数处理。
- 所有枚举选择器展示中文选项，但向接口提交原英文值。
- 会员等级与状态、销售场景、商品与分类状态、内容类型与状态、规则类型与状态、账号状态、角色与权限、审计主体/动作/资源、订单与售后相关枚举均需覆盖。
- 对后续新增或未知枚举提供一致的中文兜底，不直接将全大写下划线值暴露给用户。
- 不修改后端枚举、数据库存储、API 请求字段和权限判断代码。

## Acceptance Criteria

- [x] 后台所有页面不再出现用于界面装饰或操作说明的英文文案。
- [x] 列表、详情、筛选器和编辑表单中的已知业务枚举均显示中文。
- [x] 下拉框提交值仍与现有后端英文枚举完全一致。
- [x] 角色权限页面能够以中文说明权限，同时保留必要的编码辨识信息。
- [x] 审计记录中的主体、动作和资源类型有中文展示。
- [x] 未知枚举不会显示空白，并以稳定的中文兜底文案展示。
- [x] 管理端单元测试、类型检查和生产构建通过。

## Definition of Done

- Tests added/updated (unit/integration where appropriate)
- Lint / typecheck / CI green
- Docs/notes updated if behavior changes
- Rollout/rollback considered if risky

## Out of Scope

- 不增加中英文切换或第三方 i18n 框架。
- 不翻译用户自行录入的商品名、会员昵称、原因等自由文本。
- 不修改商城用户端、后端接口文案、数据库枚举或历史数据。
- 不翻译具有识别意义的编码、ID、版本号和文件格式名称。

## Technical Approach

- 建立集中式后台展示词典与格式化函数，页面只引用中文标签。
- 对选择器使用显式 `{ value, label }` 选项，避免将标签误作接口值。
- 对已知枚举使用领域词典；对未知全大写枚举使用“未知状态/未知类型”等上下文兜底。
- 增加源代码契约测试，覆盖主要词典、选择器 value/label 分离和禁止已知英文装饰文案回归。

## Decision (ADR-lite)

**Context**: 当前英文来自写死文案与后端英文枚举直出两条路径，逐页字符串替换容易遗漏且以后会回归。

**Decision**: 采用轻量集中词典，不引入完整 i18n 依赖；业务值保持英文，展示统一转换为中文。

**Consequences**: 改动范围小、接口兼容性稳定；新增枚举时需要同步补充展示词典，测试负责提示遗漏。

## Technical Notes

- 主要影响：`frontend/admin/src/api.ts`、`frontend/admin/src/App.vue`、`frontend/admin/src/views/*.vue`。
- 现有规范：`.trellis/spec/frontend/admin-console.md`。
- 初步发现直接英文展示：`MARKET OPERATIONS`、`STAFF ACCESS`、`ORDER DETAIL`、`AFTER-SALE DETAIL`，以及 `ACTIVE`、`ON_SALE`、`UPGRADE`、规则类型、内容类型、审计类型等枚举。
- 验证结果：`pnpm test:web`、`pnpm typecheck:web`、`pnpm build:web` 全部通过。
