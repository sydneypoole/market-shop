# 修复 Flyway 失败迁移导致容器启动失败

## Goal

定位生产数据库中已经失败的非 V17 Flyway 迁移，在不破坏数据库历史、不可变账本或已提交 DDL 的前提下完成针对性修复，并让应用容器恢复启动。

## What I already know

* 2026-08-22 21:12 的容器日志最终失败于 `LegacyAfterSaleMigrationPreflight.validateHistory`。
* 当前错误为 `Flyway history contains a failed migration other than V17`，并非 Tomcat、MyBatis 或 `AdminAuditFilter` 本身的问题。
* 现有预检只允许在严格校验脚本、checksum 和无部分 DDL 后自动修复 V17；其他失败迁移按生产规范 fail closed。
* 数据库历史已确认失败项为 V18：`db.migration.V18__repair_distribution_projections`，类型 `JDBC`，执行 24ms 后失败。
* 本机 OrbStack/Docker daemon 未运行，无法从本地容器读取生产数据库历史。

## Assumptions (temporary)

* 该日志来自另一台运行 Compose 的部署主机，生产数据库仍保留失败迁移现场。
* 首次迁移失败日志或 `flyway_schema_history` 中仍可取得准确版本、脚本、checksum 和失败前的对象状态。

## Confirmed Trigger

* 生产库存在同一 `(beneficiary_user_id=9, referred_user_id=14)` 的第二条 ACTIVE 直推业绩 `id=2`，但该重复业绩没有奖励流水，账户可用/冻结积分均为 0。
* 账户冻结积分与不可变账本汇总一致，基础奖励、释放、冲正来源和孤立批次检查均无异常。
* V18 仅从账本流水计算迁移修复时间；当重复业绩没有奖励流水且库内没有账本时间时，`repairTimestamp` 为 null，触发 `duplicate direct performance has no deterministic timestamp`。

## Requirements

* 先读取失败历史与首次异常，再选择版本专属修复方案。
* 不手工删除 `flyway_schema_history` 失败行，不直接执行无条件 `flyway repair`。
* 对任何自动修复都必须校验版本、脚本/checksum、部分对象和业务不变量。
* 错误日志必须包含失败迁移的版本、脚本与状态，后续重启不能再次把原始定位信息隐藏成笼统错误。
* 修复可重复执行，部署重试不会重复写账本事实或制造重复 DDL。
* 无奖励流水的重复业绩使用持久化 `created_at` 事实参与确定性修复时间计算，不新增或改写账本流水。
* 对脚本、描述、类型/checksum 全部匹配的失败 V18 JDBC 记录允许受保护的 `Flyway.repair()` 后正常重跑；其他非 V17/V18 失败记录继续 fail closed。

## Acceptance Criteria

* [x] 已记录准确的失败迁移版本、脚本和触发数据形态。
* [x] 已审计 V18 只有事务性 DML、没有 DDL，现有失败测试验证冲突时账本与账户不变。
* [x] 针对该版本提供并验证可重复、安全、fail-closed 的恢复路径。
* [x] 非 V17/V18 失败迁移的启动错误包含版本、脚本与状态。
* [x] V18 迁移 6/6、V18 预检 20/20 的 MySQL 8.4/Testcontainers 集成测试通过。
* [ ] 应用容器启动成功，`flyway_schema_history` 不再包含 `success = 0`。

## Definition of Done

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Database and container-operation specs updated if the recovery contract changes
* Rollback and backup requirements documented

## Out of Scope

* 清空数据库、删除生产卷或回退不可变账本事实。
* 对任意未知 Flyway 失败记录做通用自动 repair。
* 在未取得生产失败版本和对象状态前猜测性修改业务数据。

## Technical Notes

* 预检实现：`backend/shop-bootstrap/src/main/java/com/marketshop/bootstrap/config/LegacyAfterSaleMigrationPreflight.java`
* 迁移实现：`backend/shop-bootstrap/src/main/java/db/migration/V18__repair_distribution_projections.java`
* SQL 迁移：`backend/shop-bootstrap/src/main/resources/db/migration/V19__snapshot_order_timers_and_invitation_guards.sql`
* SQL 迁移：`backend/shop-bootstrap/src/main/resources/db/migration/V19_1__make_bootstrap_invitation_single_use.sql`
* 运维规范：`.trellis/spec/backend/database-guidelines.md`、`docs/production-operations.md`

## Implementation Plan

1. 让 V18 的确定性修复基准取不可变账本时间与 ACTIVE 直推业绩 `created_at` 的最大值再加 1ms。
2. 增加“重复业绩存在但没有任何账本流水”的 MySQL 集成测试，验证只将重复行标记为 `REVERSED`、不创建账本事实，并验证重跑幂等。
3. 将预检扩展为只接受精确匹配的失败 V18 JDBC 迁移记录，调用一次 `Flyway.repair()` 后重跑；所有其他未知失败迁移仍拒绝启动，并在错误中输出安全的版本/脚本/状态。
4. 增加失败 V18 历史恢复集成测试，并更新数据库/生产运维规范。

## Verification Notes

* `mvn -B -ntp -f backend/pom.xml -DskipTests test-compile` 通过。
* `DistributionProjectionMigrationIntegrationTest`：6/6 通过。
* `LegacyAfterSaleMigrationPreflightTest`：20/20 通过。
* `OrderControllerCapabilitiesTest`：8/8 通过；测试代理已补齐 `hasBlockingAfterSale`。
* `BootstrapInvitationConcurrencyIntegrationTest`：5/5 通过；MySQL 8.4 测试容器显式允许创建测试触发器。
* 完整后端测试仍暴露两个与 V18 无关的既有问题：V12 直推并发奖励在 REPEATABLE READ 快照下未生成第六笔奖励；V19 将四个 ORDER_TIMERS 默认值写成 JSON STRING，导致 due-time 回填为空。两者未通过本任务修改业务代码或已发布迁移。

## Research References

* [`research/incident-diagnosis.md`](research/incident-diagnosis.md) — 当前日志证据、代码路径与安全恢复边界。
