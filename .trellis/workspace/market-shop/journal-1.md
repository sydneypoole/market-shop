# Journal - market-shop (Part 1)

> AI development session journal
> Started: 2026-07-26

---


## Session 1: 收敛管理员与优化容器日志

**Date**: 2026-07-27
**Task**: 收敛管理员与优化容器日志
**Branch**: `main`

### Summary

将默认后台身份收敛为唯一 admin，补齐 Flyway 迁移与测试；为 Compose 环境变量添加中文注释；增加可切换 ANSI 彩色 Spring 日志、无查询参数的 Nginx JSON 访问日志、请求 ID 与非 root Nginx 构建校验。

### Main Changes

- 完成商品多规格、动态内容、凭证与文件上传错误矩阵、请求链路日志等 P0 上线阻塞项。
- 建立 DDD 分层的商城模板领域、应用、基础设施与接口，并通过 Flyway V8 初始化三套模板。
- 完成后台模板中心、PC/H5 双端预览，以及编辑、复制、发布、归档和审计流程。
- 重构商城首页渲染器，提供杂志风、活力零售、极简精品三套响应式模板。
- 增加空库运行验证、真实 RustFS 集成验证、HTML 安全过滤及 GitHub Actions 质量门禁。

### Git Commits

| Hash | Message |
|------|---------|
| `d362144` | (see git log) |

### Testing

- [OK] Maven 全量单元测试与打包
- [OK] 前端 20 项测试、类型检查及生产构建
- [OK] Docker Compose 配置校验、空 MySQL Flyway V1-V8 运行验证
- [OK] 真实 RustFS 上传、签名下载与删除验证
- [OK] 三套模板在 1440/1024/390/360 视口下完成视觉与溢出检查

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 后台管理端中文化

**Date**: 2026-07-27
**Task**: 后台管理端中文化
**Branch**: `main`

### Summary

完成后台简体中文展示层，覆盖业务枚举、筛选项、权限和审计记录，并补充回归测试与规范。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `685b326` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: 完成 P0 多模板商城

**Date**: 2026-07-30
**Task**: 完成 P0 多模板商城
**Branch**: `main`

### Summary

完成 P0 上线阻塞项、SaaS 式商城模板中心、三套 PC/H5 模板及空库、RustFS、视觉质量验收。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `065b67d` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 完成商城 P1 业务能力与验收闭环

**Date**: 2026-07-31
**Task**: 完成商城 P1 业务能力与验收闭环
**Branch**: `main`

### Summary

完成 B 池 FIFO 冻结批次、释放明细与历史映射，补齐售后冲正、幂等与余额一致性校验；前后台增加积分来源追溯，补充 P1 应用与投影测试，并通过 Maven、前端构建、Compose 和 MySQL 8.4 空库 V9 运行验收。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `674e03b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: 完成后台运营工作台全量重构

**Date**: 2026-08-01
**Task**: 完成后台运营工作台全量重构
**Branch**: `main`

### Summary

统一后台导航与交互原语，完成全部页面、P0 生命周期安全、响应式列表和独立质量审查；全前端 22 项测试、类型检查与构建通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `802293c` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: 完成商城生产级 P0 闭环并通过发布验收

**Date**: 2026-08-01
**Task**: 完成商城生产级 P0 闭环并通过发布验收
**Branch**: `main`

### Summary

完成订单线下收款闭环、会话与 OAuth 安全、动态规则快照、Outbox 死信恢复、生产 Compose/备份发布、local/RustFS 业务 E2E 与 GitHub 多架构镜像工作流；记录后续 P1 前端改进项。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `1af34ec` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: 宏杉生物独立官网

**Date**: 2026-08-16
**Task**: 宏杉生物独立官网
**Branch**: `main`

### Summary

完成一个自包含的宏杉生物中文官网 HTML，内嵌四张生成式科研视觉，支持桌面与移动端导航、响应式布局、滚动动效和静态咨询表单，并完成浏览器与语法检查。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `dc0369e` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: 优化宏杉生物官网视觉与备案信息

**Date**: 2026-08-16
**Task**: 优化宏杉生物官网视觉与备案信息
**Branch**: `main`

### Summary

依据 design-taste-frontend 统一页面节奏与主题，完善移动导航、深色模式、动效性能和表单可读性，并在页脚加入粤ICP备2026115782号-1。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `8795eee` | (see git log) |
| `ad8363f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: Pivot to miniprogram: backend reliability batch verification and commit

**Date**: 2026-08-17
**Task**: Pivot to miniprogram: backend reliability batch verification and commit
**Branch**: `main`

### Summary

Verified and committed an uncommitted backend reliability batch in the pivot-to-miniprogram task. Dispatched trellis-check which found and reverted 3 contract violations (WeChat jscode2session retry logic violated PRD #10; BootstrapIdentityInitializer skip guard broke sponsor repair idempotency; MemberProfile nickname-only branch broke the legacy phone-code contract). The remaining 10 files were correct: AftersaleTimeoutJob/Processor with FOR UPDATE SKIP LOCKED + sys_job_lease, V16 state_entered_at migration, PRICE_CHANGED checkout guard using locked authoritative price, CartItemView.skuStatus, catalog zero-inventory filtering, admin password change invalidating other sessions and re-logging in the current admin, CatalogAdmin validateUrl. Backend tests passed (shop-application 110, shop-infrastructure 94, shop-interfaces 37), WeChatMiniprogramAdapter context test 9/9, miniprogram contract tests 63/63. Ran cold-cache Mockito validation in an isolated /tmp/cold-m2 repo confirming PRD acceptance #8 (mockito-core resolved before Surefire fork, shop-domain 6/6). Updated backend specs (database-guidelines, miniprogram-api-contracts, quality-guidelines) for the new contracts. Committed as bad99b4 and ba2b69b. Did NOT archive pivot-to-miniprogram because PRD acceptance criteria #3-7 and #11-16 remain unverified (admin build, CI/Docker, branding, shellcheck, FirstUI/26 pages, one-click registration, V15 migration).

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `bad99b4` | (see git log) |
| `ba2b69b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: Bootstrap single-use invitation repair

**Date**: 2026-08-21
**Task**: Bootstrap single-use invitation repair
**Branch**: `main`

### Summary

Made the bootstrap invitation single-use, added explicit legacy repair gating and transaction/concurrency coverage, updated the business E2E flow, and documented runtime-proof boundaries. Focused tests and compilation passed; Docker-backed tests were skipped because Docker was unavailable, while the full backend suite retained an unrelated baseline failure.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5ff2ff2` | (see git log) |
| `aed0cee` | (see git log) |
| `f32b2ef` | (see git log) |
| `c853a56` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 11: 注册微信资料与小程序 Babel 修复

**Date**: 2026-08-22
**Task**: 注册微信资料与小程序 Babel 修复
**Branch**: `main`

### Summary

注册时使用微信昵称和头像并保存资料；调整资料区到顶部且头像昵称分行；移除首页数组解构以避免 DevTools 缺失 arrayWithHoles，78 项小程序测试通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `42f736d` | (see git log) |
| `ce4a4e3` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: 调整注册隐私条款位置

**Date**: 2026-08-22
**Task**: 调整注册隐私条款位置
**Branch**: `main`

### Summary

将注册页隐私条款与错误提示移动到微信昵称下方，保留原有事件和禁用状态，新增顺序回归断言，78 项小程序测试通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `1cc9740` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 13: 修复新增收货地址请求体

**Date**: 2026-08-22
**Task**: 修复新增收货地址请求体
**Branch**: `main`

### Summary

复现 Jackson 3 对缺失 primitive version 的反序列化失败；新增地址恢复显式 version: 0，编辑保留权威版本，78 项小程序测试及后端接口模块编译通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `f2a9184` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 14: 固定会员邀请码

**Date**: 2026-08-23
**Task**: 固定会员邀请码
**Branch**: `main`

### Summary

注册事务内自动发放永久普通邀请码；所有正常会员可邀请；保留服务端有效性校验与 Bootstrap 单次邀请码；小程序移除撤销、重建和到期交互；V20 与完整测试通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `2bafdb8` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 15: 修复订单时效规则与后台配置入口

**Date**: 2026-08-23
**Task**: 修复订单时效规则与后台配置入口
**Branch**: `main`

### Summary

新增 V21 前向迁移修复 V19 写成 JSON 字符串的订单/售后时效字段，并从订单规则快照补齐空截止时间；后台系统配置支持系统配置或规则发布任一权限进入，两个配置区块继续按各自权限隔离。MySQL 迁移套件、相关后端测试、后台测试、类型检查和生产构建全部通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `7fa6a70` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete
