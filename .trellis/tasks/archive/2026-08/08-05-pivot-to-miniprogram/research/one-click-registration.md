# Research: 微信小程序仅邀请码+一次点击注册的能力边界

- Query: 注册页只让用户填写邀请码并点击一次注册时，头像、昵称、手机号能否由微信静默提供，以及最终应采用什么契约。
- Scope: mixed（项目内部实现 + 微信官方文档）
- Date: 2026-08-12
- Final decision: 2026-08-13

## 能力结论

1. `wx.login`/code2Session 只提供身份所需的 openid、条件满足时的 unionid 与 session key，不返回头像、昵称或手机号。
2. 真实头像需要用户主动使用 `chooseAvatar`；真实昵称需要用户主动使用 `input type="nickname"`。服务端不能根据 OpenID 静默反查，`wx.getUserProfile` 也不是稳定的自动同步通道。
3. 手机号需要独立的用户授权和一次性动态 code；本产品最终确认注册不需要手机号，因此注册不得调用该能力。

## 最终实施决策（取代此前所有分阶段资料/手机号方案）

- 普通注册页只显示邀请码输入和“一键注册”按钮，不显示或采集头像、昵称、手机号。
- 邀请码通过本地非空校验后，每次点击取得一个全新 `wx.login` code，严格 JSON POST `/api/v1/auth/wechat/miniprogram/register`：`{code, inviteCode}`；邀请码有效性由后端判定。
- 显式 `pages/register/register?mode=sponsor` 以 `{code, sponsorClaimSecret}` 认领预置发起人；密钥不进入 URL，也不在普通注册 UI 暴露模式切换。
- 后端 code2Session 在数据库事务外；单个本地事务基于唯一完整 `publicId` 生成 `宏杉会员-{publicId}` 平台昵称、保持 `avatarUrl=null`，并创建身份、UnionID、直属关系、会员/积分账户、邀请计数、登录时间及必要审计。会话在事务提交后签发。
- 生成值只称为“平台会员昵称”，不声称是微信昵称；空头像使用昵称首字降级，不用品牌 Logo 冒充用户头像。
- 失败保留邀请码但不保存或重放登录 code；再次点击必须重新执行 `wx.login`。已绑定身份幂等返回已有账号，不重绑上级、不覆盖资料、不重复邀请码计数。
- `pages/profile/edit` 保留为“我的”中的可选入口；只有用户日后主动进入时才使用 `chooseAvatar`/`input type="nickname"` 更新自选资料。登录和注册成功均直接进入首页。
- 旧 `/membership/wechat-profile` 手机验证接口仅为历史兼容能力，不参与新注册链路。

## 官方外部参考

- [wx.login](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/login/wx.login.html)
- [code2Session](https://developers.weixin.qq.com/miniprogram/dev/server/API/user-login/api_code2session.html)
- [头像昵称填写](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/userProfile.html)
- [wx.getUserProfile](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/user-info/wx.getUserProfile.html)

## 验收重点

- 注册 WXML/JS/API wrapper 不包含 profile/phone 能力或字段；普通 UI 只有一个邀请码输入与一个主按钮。
- login 路由严格 `{code}`；register 路由严格 `{code, inviteCode}` 或显式 claim 的 `{code, sponsorClaimSecret}`，未知字段 400。
- 平台昵称使用完整唯一 publicId，空头像；新建账号图谱和邀请码计数同一事务。
- 重复点击保护、失败保留邀请码、重试 fresh code、成功 token 后直达首页均有消费者测试。
