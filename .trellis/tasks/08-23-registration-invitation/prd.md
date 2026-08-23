# 注册即生成并校验邀请码

## Goal

让每个通过有效邀请码完成首次注册的新会员，在同一注册事务中自动获得一个终身固定的普通邀请码；后续新用户注册仍必须提交邀请码，并由服务端验证邀请码及邀请人的有效性。

## What I already know

* 小程序注册页已经要求填写邀请码，并调用独立注册接口提交 `{code, inviteCode}`。
* 后端已经校验邀请码存在、状态为 `ACTIVE`、未过期、未超使用次数、归属匹配，以及邀请人账号和会员等级是否允许邀请。
* 当前邀请码通过 `/membership/invitation` 按需生成，而不是在注册成功时生成。
* 当前等级配置仅允许 `SUPER_MEMBER` 和 `DIVIDEND_MEMBER` 邀请；`BASIC` 与 `EXPERIENCE_OFFICER` 的 `invitation_enabled=0`。
* 普通邀请码现有默认格式为 `MS` 加 10 位大写十六进制字符；现有页面和接口仍支持撤销、重建与自定义有效期，这些行为与“固定邀请码”冲突。
* 初始系统 Bootstrap 邀请码仍是独立的一次性凭证，不应被普通邀请码规则覆盖。

## Assumptions (temporary)

* 自动发放仅发生在真正创建新会员时；已有微信身份的幂等登录不重复创建邀请码。
* 固定普通邀请码不设置到期时间和使用次数上限，会员账号正常且会员等级有效时可持续使用。
* 现有会员继续通过现有邀请码接口按需补齐，不在本任务中批量猜测或重写历史邀请码。

## Open Questions

* 无阻塞问题；按“每个人的邀请码都是固定的”收敛为所有正常会员都具备邀请资格，一人一码且不提供撤销或重建入口。

## Requirements (evolving)

* 新会员注册前必须提交非空邀请码。
* 服务端必须在写入会员、上下级关系之前锁定并校验邀请码及邀请人资格。
* 新会员账号、微信身份、上下级关系、基础会员账户、积分账户、输入邀请码消费和新邀请码创建必须处于同一数据库事务。
* 新邀请码必须属于新会员、状态为 `ACTIVE`、`expires_at=NULL`、`max_uses=NULL`，并与系统 Bootstrap 邀请码区分。
* 基础会员、体验官、超级会员和分红会员均可使用自己的固定邀请码邀请下一位会员；账号停用、锁定或等级本身停用时，邀请码通过服务端资格校验失效，但码值不改变。
* 普通邀请码不再允许会员主动撤销或重新生成；旧客户端调用相关接口时返回稳定的不可变邀请码错误且不修改数据。
* 注册重试或已有身份登录不得生成第二个邀请码或修改既有上下级关系。
* 邀请码无效、停用、过期、耗尽或邀请人无资格时，注册不得产生会员或新邀请码数据。

## Acceptance Criteria (evolving)

* [x] 使用有效邀请码完成新会员注册后，数据库中立即存在一条归属于新会员的普通 `ACTIVE` 邀请码。
* [x] 新生成邀请码可被下一位新用户提交并通过完整服务端校验。
* [x] 同一会员多次查看、登录、分享后获得的码值保持不变，且邀请码无到期时间与次数上限。
* [x] 基础会员和体验官的邀请码也可通过邀请资格校验。
* [x] 撤销/重建接口不再改变普通邀请码，小程序不再显示撤销、重建或到期日期操作。
* [x] 未填写、错误、停用、过期或耗尽的邀请码继续返回稳定业务错误，并且不产生注册副作用。
* [x] 同一微信身份重复登录不新增邀请码。
* [x] Bootstrap 邀请码仍保持单次消费及修复门禁规则。
* [x] 单元测试覆盖创建顺序、幂等路径、异常回滚边界和邀请资格。

## Verification

* `mvn test`：通过（默认隔离环境中 Testcontainers 用例按设计跳过）。
* MySQL 8.4 聚焦集成测试：29/29 通过，覆盖固定邀请码链路、V20、Bootstrap V19.1 修复与 Flyway 预检。
* `pnpm test:miniprogram`：78/78 通过。
* `pnpm -r typecheck`：通过。
* `git diff --check`：通过。

## Definition of Done

* Tests added/updated (unit/integration where appropriate)
* Full affected-module tests green
* Database/API specs updated when invitation eligibility changes
* Forward-only migration used for production membership-level changes
* Rollout and rollback behavior documented

## Out of Scope

* 改为无需邀请码的公开注册。
* 多级分销或超过一层的邀请关系。
* 修改已建立的直属上级关系。
* 将普通邀请码改造成发起人认领密钥。

## Technical Notes

* 注册持久化：`backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/identity/MyBatisIdentityAdapter.java`
* 邀请码 SQL：`backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/persistence/mapper/IdentityMapper.java`
* 现有生成逻辑：`backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/membership/MyBatisMembershipAdapter.java`
* 等级初始配置：`backend/shop-bootstrap/src/main/resources/db/migration/V2__seed_demo_configuration.sql`
* 注册页面：`miniprogram/pages/register/register.js`

## Proposed Implementation

1. 在注册事务内、会员基础数据创建完成后插入新会员的永久普通邀请码，再消费输入的邀请码。
2. 保持现有普通邀请码格式，不改变注册接口请求体；新码不设置到期时间或次数上限。
3. 新增 forward-only Flyway 迁移，将所有内置会员等级的 `invitation_enabled` 开启，并把当前有效的普通邀请码改为永久；不修改已应用的 V2，Bootstrap 码仍由其专属修复路径恢复单次限制。
4. 将按需生成逻辑改为只补齐缺失的固定码；撤销/重建接口返回稳定错误且不写库，会员状态和等级变化只影响运行时有效性，不改变码值。
5. 更新小程序邀请页，移除生成、撤销、重建和到期日期交互，只显示固定邀请码、复制、分享和小程序码。
6. 增加注册适配器、会员邀请、管理员状态变更、迁移契约和小程序页面测试。
