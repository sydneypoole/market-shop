# 微信网页注册与登录调研

## 结论

- 微信内 H5 使用公众号/服务号网页授权；电脑网页使用微信开放平台“网站应用微信登录”扫码授权。两条授权链路都只把微信返回的临时 `code` 交给后端，由后端换取并校验渠道身份，再由 Sa-Token 签发商城会话。
- 商城统一用户不能只以 OpenID 识别。OpenID 是应用维度标识，应保存 `(provider, app_id, open_id)` 唯一键；同一微信开放平台主体下可使用 UnionID 辅助打通公众号和网站应用，但 UnionID 可能缺失，因此必须允许身份先独立保存、经过可信匹配后再合并。
- AppSecret、网页授权 access token 和网站应用 access token 只保存在后端；前端不得接触密钥，也不得自行提交 OpenID/UnionID 作为登录凭据。
- OAuth `state` 使用 Redis 保存一次性随机值并绑定发起端、回跳地址与邀请上下文；回调后立即消费，防止登录 CSRF、重放和邀请人参数篡改。
- 首次可信微信授权可创建商城账号，后续登录复用同一身份；账号创建与邀请关系绑定必须在一个事务/幂等用例中完成，避免重复回调产生重复会员。
- 用户资料遵循最小化原则：微信昵称、头像等展示资料可由用户授权后同步，但不作为稳定身份主键；用户应能查看隐私提示并注销商城账号。

## 推荐接入面

1. 微信内 H5：公众号网页 OAuth。
2. 电脑网页：开放平台网站应用扫码登录。
3. 非微信环境的移动浏览器：首版展示“请在微信打开”或登录二维码，不模拟微信授权。
4. 预留小程序渠道身份类型，但小程序不纳入本轮 MVP。

## 领域与安全映射

- IAM 限界上下文包含 `UserAccount`、`ExternalIdentity`、`LoginChallenge` 与账号合并审计。
- Customer/Membership 只引用商城 `userId`，不得直接依赖 OpenID。
- `LoginChallenge` 包含一次性随机值、渠道、过期时间、回跳白名单、签名邀请上下文和消费时间。
- 唯一约束至少覆盖外部身份键、可用 UnionID 键和登录回调幂等键。
- 账号合并属于敏感操作，要求重新授权、冲突检测和审计；不得仅凭客户端传入的 UnionID 合并。

## 官方参考

- 微信服务号网页开发与网页授权：<https://developers.weixin.qq.com/doc/offiaccount/OA_Web_Apps/Wechat_webpage_authorization.html>
- 微信开放平台网站应用微信登录：<https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html>
- 微信 UnionID 机制：<https://developers.weixin.qq.com/doc/offiaccount/User_Management/UnionID.html>
