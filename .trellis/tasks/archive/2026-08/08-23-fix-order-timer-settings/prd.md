# 修复订单时效规则并补充后台配置

## Goal

恢复生产环境 `ORDER_TIMERS` 当前规则的可解析状态，解除“订单时效规则缺失或无效”对下单、售后和后台配置页的阻断，并确保拥有规则发布权限的后台账号能够发现和进入订单时效配置入口。

## What I already know

* 后端报错码为 `ORDER_TIMER_SETTINGS_INVALID`，运行时和后台当前规则接口都会在持久化参数解析失败时返回该错误。
* 后台已经有“系统配置 → 订单与凭证策略”表单以及 `/api/v1/admin/settings/order-timers` 专用接口。
* 该入口的导航权限目前仅为 `system:setting:manage`，而订单时效接口要求 `rule:publish`；拥有规则发布权限但没有系统配置权限的账号看不到入口。
* V19 使用 `COALESCE(JSON_EXTRACT(...), 数字)` 给四个新增字段补默认值，MySQL 会把这些回退值存成 JSON 字符串；Java 规则解码器只接受 JSON 整数。
* V19 之后可能已有订单快照引用受影响规则，因此只新增一个有效版本不能修复既有订单，必须前向修复已知迁移造成的数据类型错误。

## Requirements

* 新增前向 Flyway 迁移，不修改已经执行过的 V19/V20。
* 将受 V19 影响的四个订单/售后时效字段规范化为 JSON 整数：`awaitingReturnTimeoutDays`、`returnShippedTimeoutDays`、`offlineRefundTimeoutDays`、`buyerRefundConfirmTimeoutDays`。
* 保留合法整数；可安全识别的整数数字字符串转为整数；缺失或无效值使用既有业务默认值 15、15、7、7。
* 对已有规则快照所关联、且截止时间为空的订单/售后单重新计算截止时间，不覆盖已经持久化的非空截止时间。
* 后台“系统配置”路由对 `system:setting:manage` 或 `rule:publish` 任一权限可见；运营基础配置只对 `system:setting:manage` 可编辑，订单时效策略只对 `rule:publish` 可见。
* 页面继续使用现有专用订单时效接口、差异确认和不可变新版本发布流程。

## Acceptance Criteria

* [x] 从 V20 升级后，V19 产生的四个 JSON 字符串字段变为 `INTEGER`，当前订单时效规则可以被 `RuleParameterCodec` 解析。
* [x] 合法的既有整数配置不会被默认值覆盖。
* [x] 缺失/无效的四个已知字段按 15、15、7、7 恢复。
* [x] 使用修复后快照的待处理订单、已发货订单和进行中售后可补齐空截止时间；非空截止时间保持不变。
* [x] 只有 `rule:publish` 的后台账号能看到并进入订单时效配置，且不会获得运营基础配置写权限。
* [x] 只有 `system:setting:manage` 的账号仍能进入系统配置并维护运营基础配置。
* [x] 后端迁移测试、后台单元测试、后台类型检查与构建通过。

## Definition of Done

* Tests added/updated for migration repair, permission routing, visibility and payload safety.
* Backend targeted tests and frontend test/typecheck/build green.
* Database and admin-console specs updated with the recovered invariant.
* Rollout uses forward-only migration; rollback is application-image rollback without deleting migrated data.

## Technical Approach

1. 新增 V21 SQL 迁移，使用显式数值 `CAST` 写回四个字段，避免 MySQL JSON 类型推断再次产生字符串。
2. 仅为截止时间为空的旧记录从不可变订单规则快照回填 `status_due_at`、`auto_receive_at`、`state_due_at`。
3. 扩展后台导航模型支持“任一权限”，路由守卫与侧栏共用同一权限判断；系统配置页按区块权限加载和显示。
4. 扩展 V21 MySQL 集成测试和前端静态契约测试，覆盖升级路径与权限组合。

## Decision (ADR-lite)

**Context**: 仅发布一个新版本会留下既有订单的无效快照；仅手工改生产库又无法保证后续环境一致。配置页已存在，真正缺失的是数据恢复和权限可发现性。

**Decision**: 采用 V21 前向、窄范围数据修复，并让配置路由以 OR 权限可见、页面按区块继续执行最小权限控制。

**Consequences**: 已知由 V19 造成的四个字段会原位规范化，以恢复历史快照；其他未知字段损坏仍保持失败关闭，不被静默覆盖。

## Out of Scope

* 不允许通用规则工作台发布 `ORDER_TIMERS`。
* 不改变所有时效字段的业务默认值或范围。
* 不为未知格式的其他规则字段提供通用自动修复。
* 不覆盖已存在的订单或售后截止时间。

## Technical Notes

* `backend/shop-bootstrap/src/main/resources/db/migration/V19__snapshot_order_timers_and_invitation_guards.sql`
* `backend/shop-bootstrap/src/test/java/com/marketshop/bootstrap/migration/OrderTimerMigrationIntegrationTest.java`
* `backend/shop-application/src/main/java/com/marketshop/application/membership/RuleParameterCodec.java`
* `frontend/admin/src/views/SettingsView.vue`
* `frontend/admin/src/admin-navigation.ts`
* `frontend/admin/src/main.ts`
* `frontend/admin/src/session.ts`
