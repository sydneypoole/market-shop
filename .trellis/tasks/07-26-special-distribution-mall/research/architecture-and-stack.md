# 架构与技术栈调研

调研时间：2026-07-26

## 结论

首版推荐采用“DDD 模块化单体 + 可靠事件/任务机制”，而不是直接拆微服务。它能在一个数据库事务内保证订单、资格和账本的关键一致性，同时通过清晰限界上下文、模块 API、领域事件和 Outbox 为未来拆分保留边界。

凤凰架构不是某个固定的包结构或框架。官方项目强调的是：承认组件会失败，通过可替换、可恢复、可观测和自动化机制让整体系统保持可靠；官方也同时提供 Spring Boot 单体、微服务、服务网格和无服务等不同实现。来源：

- <https://icyfenix.cn/introduction/about-the-fenix-project.html>
- <https://icyfenix.cn/exploration/guide/quick-start.html>

## 建议运行基线

- Java 21 LTS。
- Spring Boot 4.1.x。
- Maven 多模块、单一可部署应用。
- MyBatis-Flex 1.11.x，使用 `mybatis-flex-spring-boot4-starter`。
- Sa-Token 1.45.x，使用 `sa-token-spring-boot4-starter`，用户端与管理端采用不同 loginType。
- Spring Data Redis + Lettuce；Sa-Token 会话和业务缓存使用不同 key namespace。
- MySQL 8.4 LTS。
- Flyway 12.x（由 Spring Boot BOM 管理）+ `flyway-mysql`。
- Hutool 5.8.x 稳定版，仅按需引入模块；不让 Hutool 类型进入领域模型或公共 API。

依据：

- Spring Boot 4.1.0 当前稳定文档要求至少 Java 17，并支持至 Java 26：<https://docs.spring.io/spring-boot/system-requirements.html>
- Spring Boot 4.1.0 BOM 当前管理 Flyway 12.4.0、MySQL Connector/J 9.7.0 与 Lettuce 7.5.2：<https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html>
- MyBatis-Flex 1.11.8 提供独立的 Boot 4 starter，且提醒 Boot 4 场景显式加入 JDBC starter：<https://mybatis-flex.com/zh/intro/maven.html>
- Sa-Token 1.45.0 新增 Boot 4 starter 与 Jackson 3 插件：<https://gitee.com/dromara/sa-token/releases?force_mobile=true>
- Flyway 的 MySQL 支持需要单独的 `org.flywaydb:flyway-mysql`：<https://documentation.red-gate.com/fd/mysql-277579322.html>
- Hutool 5.8 当前稳定 API：<https://plus.hutool.cn/apidocs/overview-summary.html>

正式编码前仍需用 Maven 解析和最小启动测试验证实际兼容矩阵；第三方 starter 的宣称支持不替代构建验证。

## 限界上下文

1. **IAM**：账号、手机号验证、Sa-Token 会话、角色、权限、数据权限。
2. **Customer**：客户资料、地址、邀请关系、会员身份。
3. **Catalog**：类目、SPU、SKU、价格、库存可售视图。
4. **Trade**：购物车、线下收款报单、上级收款确认、后台审核、价格快照、订单状态机、取消与售后。
5. **Fulfillment**：发货、物流、收货。
6. **Membership**：等级方案、升级任务、审核、有效期、降级。
7. **Distribution**：直接邀请资格、业绩归属、资格计算。
8. **Ledger**：演示积分账户、双向流水、冻结、解冻、冲正、结算。
9. **Operation**：商品与规则配置、内容、审核、任务运维、审计日志。

付款凭证通过 `ObjectStoragePort` 接入私有 S3 兼容对象存储（本地演示可使用 MinIO），业务数据库仅保存对象键、摘要、类型、大小和保留期限，不保存公开 URL 或图片二进制。

## 模块内分层

每个业务模块按依赖向内组织：

```text
interfaces -> application -> domain
                    \       ^
                     -> infrastructure
```

- `interfaces`：REST DTO、参数校验、鉴权适配。
- `application`：用例编排、事务边界、命令/查询、幂等。
- `domain`：聚合、实体、值对象、领域服务、领域事件、仓储接口。
- `infrastructure`：MyBatis-Flex PO/Mapper、Redis、外部通道、仓储实现。
- 领域层不依赖 Spring、MyBatis-Flex、Sa-Token、Hutool。

## 凤凰式可靠性约束

- 应用无状态；会话在 Redis，持久业务事实在 MySQL。
- 上级确认、后台审核、订单关闭、发货、收货、会员升级、积分入账、降级任务均以业务幂等键去重。
- 领域事件采用事务 Outbox；消费者采用 Inbox 去重，可安全重放。
- 规则发布生成不可变版本；订单和结算记录规则版本与结果快照。
- 所有账本变更只能新增流水；退款、撤销通过反向冲正，不修改历史金额。
- 定时任务使用数据库/Redis 租约，支持并发抢占、失败重试和人工补偿。
- 健康检查、结构化日志、traceId、指标、慢查询与审计事件开箱可用。
- Flyway 只前向迁移；破坏性字段变更采用 expand/migrate/contract。
- Docker Compose 提供 MySQL、Redis 与应用依赖；配置通过环境变量注入。

## 动态规则策略

不采用任意 JavaScript/Groovy 脚本，也不在数据库中存可执行 SQL。推荐“类型化规则模板 + JSON 参数 + 不可变版本”：

- 条件类型：订单实付、指定商品/SKU、直推人数、有效业绩、时间窗口、审核状态。
- 动作类型：授予等级、进入候选审核、发放指定账户积分、冻结奖励、降级。
- 每个规则类型由后端 Java 解释器校验与执行。
- 发布时做结构校验、交叉校验、模拟试算；发布后不修改，只能新建版本。
