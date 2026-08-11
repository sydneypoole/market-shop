# 登录后头像与昵称确认研究

## 决策

- 新增独立 `pages/profile/edit` 页，不复用注册页。注册页是“注册凭据 → 一次性手机号 code → 头像”的恢复型状态机，与已有会员可跳过的资料确认流程复用会引入 phone code 重放和状态污染风险。
- 每次从登录页完成真实登录后，先写 Token，再 `reLaunch('/pages/profile/edit')`；资料页保存或跳过后 `switchTab('/pages/index/index')`。登录页的已有 Token 旁路仍直接首页。
- 确认页只使用 `chooseAvatar` 与 `input type="nickname"`，不出现 `getPhoneNumber`、`getUserProfile`、`getUserInfo` 或第二次 `wx.login`。
- 该页先读权威 `/membership/me`，仅在昵称变化时发昵称请求，仅在用户选择新临时头像时上传。无变化、跳过和返回首页都是零写入。
- 保存顺序为昵称后头像。昵称已成功而头像失败时，页面只重试头像，不重复更新昵称；临时路径仅位于页面内存。

## 后端契约

- 新增会员会话保护的 `PUT /api/v1/membership/nickname`，body 严格为 `{nickname}`。保留注册专用 `/membership/wechat-profile` 的 `{nickname, phoneCode}` 契约，不把 phone code 改成可选。
- `MemberProfileUseCase` 新增 `updateNickname`，应用服务复用已有 Unicode 昵称规范化，读取当前 profile/version，同昵称直接返回 no-op。
- 实际变化使用列级 CAS：仅更新 `nickname` 和 `version`，`WHERE id = ? AND version = ?`。不修改 `phone_masked`、`phone_verified_at` 或头像元数据；冲突返回 `MEMBER_PROFILE_CONFLICT`。
- Controller 只从 `StpUserKit` 取当前会员 ID，成功后复用 session profile 同步；无数据库迁移。
- 头像继续复用 `POST /api/v1/membership/avatar`、图片安全净化、local/S3 存储和事务补偿。

## 必要测试

- 登录仍只发 fresh code，连点只发一次；成功后只 `reLaunch` 资料确认页，不直接进首页。
- 资料页：权威预读、缺 Token/401、加载重试、跳过/回首页、无变化零写、只昵称、只头像、防重、昵称成功后头像失败只重试头像、临时路径不进 JSON/storage/URL。
- 应用/持久化：昵称 trim、32 Unicode 码点、控制字符、同值 no-op、零次微信手机号交换、phone 字段保持、version CAS 与稳定冲突。
- 接口：`PUT /membership/nickname` 的 DTO 只有 nickname，路由受会员会话和同源写保护，当前 Token Session 昵称即时同步。
