# P0 审计定位

## 订单状态

- Domain 与持久化使用 `PENDING_SUPERIOR`；商城、后台本地化、待确认 KPI 和订单凭证删除策略已统一到该值。
- 关键文件：`Order.java`、`OrderStatus.java`、`OrdersView.vue`、`OrderDetailView.vue`、两端 `api.ts/localization.ts`、`MyBatisOrderOperationsAdapter.java`、`OrderProofApplicationService.java`。

## 会话生命周期

- `IdentityMapper` 与 `UserLoginRow` 未读取会员状态，现有用户登录不校验 ACTIVE。
- Sa-Token session 缓存管理员权限；状态、密码和角色变更只写数据库，没有 kickout。
- `StpAdminKit`/`StpUserKit` 的 Cookie 未配置生产 Secure/HttpOnly/SameSite，登录响应仍返回 token 值。

## 规则编辑

- `RulesView.vue` 的 `parseParameters()` 将非法 JSON/数组降级为 `{}`，随后套用默认值并解锁发布；必须将“无首版”与“已有版本损坏”区分。

## Outbox 与历史规则

- `OutboxProjectionJob` 失败只记录日志并退出；`rescheduleOutbox` 未调用，最老毒事件会反复阻塞。
- 完成事件 payload 没有冻结规则版本，投影使用 `CURRENT_TIMESTAMP` 读取当前规则，队列延迟跨发布边界时旧订单会套用新规则。

## 生产与灾备

- Compose 默认 local，MySQL/Redis 默认绑定所有网卡，配置含开发默认凭据。
- readiness 未明确包含当前对象存储，Nginx 公开代理 actuator/Swagger/OpenAPI。
- 仓库缺少成套的数据库+对象恢复、定期演练、digest 晋级和回滚流程。

## 测试现状

- runtime smoke 主要覆盖健康与公开 GET；Web 测试大多是源码字符串断言，尚未真实走完线下收款订单和会话失效链路。

## 直属业绩并发序号验收

- 根因：投影事务只锁各自 buyer 的 `membership_account`，再以 ACTIVE `COUNT + 1`
  分配 `completed_ordinal`；两个实例处理同一 superior 时不共享锁，可同时读到相同计数。
- 修复：资格校验后先 `FOR UPDATE` 锁 superior 的稳定会员行，再用锁定式当前读取最大
  历史序号并分配下一值；源订单保持幂等。V12 按 `created_at, id` 重建旧序号后添加
  `(beneficiary_user_id, completed_ordinal)` 唯一约束，不改写或伪造历史积分流水。
- `completed_ordinal` 明确是包含 `REVERSED` 记录的全历史完成序列；售后只冲正来源订单的
  业绩/奖励，不释放、不复用已分配序号。资格重算仍以 ACTIVE 数量为准，与历史序号语义分离。
- 真实 MySQL 8.4 验收：两个线程/事务在 superior 行锁前同时就位，释放后断言序号
  `1..6`，只有第 6 笔产生一笔奖励与冻结批次，重放第 6 笔后行数和奖励不变。

## Identity close-out notes (2026-08-01)

- `auth_epoch` is checked against the database on every member/admin request;
  `/auth/me` endpoints call the same guard explicitly so direct controller
  invocation cannot bypass the check.  Status/role/password mutations bump the
  epoch and invalidate sessions; a first forced password change updates the
  current token's epoch instead of kicking the browser.
- OAuth state is single-use and atomically browser-bound.  Redirects are
  reduced to a relative location after strict storefront-origin validation.
  Sponsor claim hashes are sealed before Redis persistence (legacy plain rows
  are consumed once for rolling upgrades), and ordinary invitation registration
  never enters the claim path.
- Failed administrator logins use one atomic compare-and-set update.  A locked
  row is capped at five attempts; an expired lock resets to attempt one, and
  only the threshold transition increments `auth_epoch`.  Missing rows return
  the generic credentials error rather than an existence-revealing not-found
  code.  Successful login re-reads the credential after resetting counters so
  concurrent lifecycle changes cannot seed a stale session.
- Bootstrap mock sponsor identity repair is conditional on the local/mock
  profile; production bootstrap only creates/updates the independent claim row
  and never manufactures a `WECHAT_MOCK` identity.

## Quality gate follow-ups (P1, outside this P0 scope)

- Storefront WeChat login should clear its busy state when the mutually
  exclusive invite-code and sponsor-claim fields are both supplied.
- Order and after-sale detail routes should clear dependent data and ignore
  late responses when Vue Router reuses the component for another `:id`.
- The admin proof-size editor should round-trip valid values from 1 KiB to
  below 1 MiB instead of applying a one-MiB HTML minimum.
- Rule-editor health should account for malformed non-current ACTIVE versions,
  matching the backend's all-active validation before publication.
- Non-JSON 401 responses from a proxy should still clear the storefront
  session and route to login.

These are recorded for the next P1 task; they do not block the P0 acceptance
criteria above.
