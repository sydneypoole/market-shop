# 登录后头像与昵称确认研究（最终结论更新于 2026-08-13）

## 最终决策

- 已有会员登录严格只提交 fresh `wx.login` code，成功后保存 Token 并直接进入首页；不再每次登录强制跳转资料确认页。
- 新会员使用独立注册接口，只提交 fresh code 与邀请码（显式发起人模式提交 claim secret），注册成功同样直接进入首页。
- `pages/profile/edit` 是“我的”中的可选入口，不复用注册页，也不参与登录/注册门禁。
- 可选资料页先读取权威 `/membership/me`；只有用户主动修改时才通过原生头像选择和昵称输入更新。临时头像路径只存在于页面内存。

## 后端可选资料契约

- `PUT /api/v1/membership/nickname` 的 body 严格为 `{nickname}`，使用当前会员 ID 和 version CAS；同昵称为零写入。
- `POST /api/v1/membership/avatar` 继续采用受会员会话保护的 multipart、自有 local/S3 存储、真实图片校验和失败补偿。
- 历史 `/membership/wechat-profile` 手机验证 API 保留兼容旧账户，但登录、注册和当前可选资料页均不调用。
- 昵称更新不修改 phone 或头像字段；头像更新不重复提交昵称。

## 必要测试

- 登录：fresh code、防重复提交、Token 后直接首页，不跳 profile/edit。
- 注册：邀请码 + 一个主按钮、credential-only JSON、失败保留邀请码且重试 fresh code、成功首页。
- 可选资料页：权威预读、缺 Token/401、加载重试、返回首页、无变化零写、只昵称、只头像、防重、昵称成功后头像失败只重试头像、临时路径不进 JSON/storage/URL。
- 应用/持久化：昵称 trim、32 Unicode 码点、控制字符、同值 no-op、phone 字段保持、version CAS 与稳定冲突。
