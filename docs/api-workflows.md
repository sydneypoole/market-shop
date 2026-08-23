# API 工作流摘要

所有响应统一为：

```json
{
  "success": true,
  "code": "OK",
  "message": "成功",
  "data": {},
  "timestamp": "2026-07-26T12:00:00Z"
}
```

会员会话 token 名为 `market-shop-user-token`（小程序以 header 携带，服务端同时可读 cookie），后台 token 名为 `market-shop-admin-token`，两类会话不能互换。

## 微信与身份

- `POST /api/v1/auth/wechat/miniprogram/login`：已有会员使用 `wx.login` 拿到的 `code` 换会话，请求体严格为 `{code}`。
- `POST /api/v1/auth/wechat/miniprogram/register`：公开一键注册严格使用 `{code, inviteCode}`；显式发起人认领使用 `{code, sponsorClaimSecret}`。登录 code 一次性使用，认领密钥不放入 URL。注册不采集手机号、头像或昵称；后端生成唯一 `宏杉会员-{publicId}` 平台昵称并保持空头像。
- 新会员的账号、微信身份、直属关系、会员/积分账户、输入邀请码消费和本人固定邀请码在同一本地事务中完成。固定普通邀请码长期有效、不限使用次数；账号或当前等级停用时只会在注册校验时暂时失效，码值不变。
- 响应 `data`：`{token, publicId, nickname, newlyRegistered}`。后续会员请求在 header 携带 `market-shop-user-token: <token>`（也可继续走 cookie，两者并存）。
- mock 模式（`market-shop.wechat.mock-enabled=true`）：`code` 直接作为 openId，`unionId = mock-union-` + code，不调用微信。
- 真实模式：后端调用 `jscode2session`，provider 记为 `WECHAT_MP`。微信以 `text/plain` 或 `application/json` 返回的 JSON 均可正常解析；上游返回空内容、非法 JSON 或 HTTP/网络错误时，统一返回 HTTP 502 + `WECHAT_CODE_EXCHANGE_FAILED`，不暴露 AppSecret、登录 code 或上游响应。
- `POST /api/v1/auth/dev-login`：仅 local / mock 场景的开发登录（cookie 会话，响应不含 token 字段）。
- `POST /api/v1/admin/auth/login`：后台独立登录（cookie 会话，`market-shop-admin-token`）。

## 商城

- `GET /api/v1/catalog/products`
- `GET /api/v1/catalog/products/{id}`
- `GET/PUT /api/v1/cart`
- `POST /api/v1/orders`
- `GET /api/v1/orders`
- `GET /api/v1/superior/orders`
- `POST /api/v1/superior/orders/{id}/decision`
- `POST /api/v1/orders/{id}/receive`
- `POST /api/v1/orders/{id}/proofs`
- `GET /api/v1/membership/me`
- `GET /api/v1/membership/invitation`：只读查询当前固定普通邀请码。
- `POST /api/v1/membership/invitation`：幂等补齐历史会员缺失的固定邀请码；已存在时返回原码。
- `GET /api/v1/membership/invitation/wxacode`：返回官方小程序码 JSON。
- `POST /api/v1/membership/invitation/revoke` 与 `/regenerate`：旧客户端兼容路由，统一返回 HTTP 409 `INVITATION_IMMUTABLE`，不修改数据。
- `GET /api/v1/membership/direct-members`
- `GET /api/v1/membership/ledger`：积分流水包含来源订单、规则版本、原冻结分录和当前 B 池批次剩余量。

## 后台

- `GET /api/v1/admin/orders/search`：按订单号、买家、直属上级、状态和时间分页查询。
- `GET /api/v1/admin/orders/{id}`：订单快照、商品、地址、物流与处理时间。
- `GET /api/v1/admin/orders/{id}/notes`
- `GET /api/v1/admin/orders/{id}/proofs`
- `POST /api/v1/admin/orders/{id}/review`
- `POST /api/v1/admin/orders/{id}/ship`
- `POST /api/v1/admin/orders/batch-ship`
- `GET /api/v1/admin/orders/export`
- `GET/POST/PUT/DELETE /api/v1/admin/catalog/categories`
- `GET/POST/PUT /api/v1/admin/catalog/products`
- `GET/POST /api/v1/admin/catalog/skus/{skuId}/inventory-adjustments`
- `GET/POST/DELETE /api/v1/admin/catalog/assets`
- `GET /api/v1/catalog/assets/{assetId}`：商品/内容图片的稳定公开读取地址。
- `GET/POST /api/v1/admin/rules`
- `GET /api/v1/admin/members/{userId}`：会员详情中的积分流水包含 FIFO 冻结批次追溯字段。
- `POST /api/v1/admin/rules/validate`
- `GET/POST /api/v1/admin/after-sales`
- `GET /api/v1/admin/after-sales/{id}/proofs`
- `GET/POST /api/v1/admin/accounts`
- `PUT /api/v1/admin/accounts/{id}/status`
- `PUT /api/v1/admin/accounts/{id}/roles`
- `PUT /api/v1/admin/accounts/{id}/linked-user`
- `POST /api/v1/admin/accounts/{id}/reset-password`
- `POST /api/v1/admin/accounts/{id}/unlock`
- `GET/POST/DELETE /api/v1/admin/roles`
- `GET /api/v1/admin/permissions`
- `GET /api/v1/admin/audit`
- `GET /api/v1/admin/audit/export`
- `GET/PUT /api/v1/admin/settings`

精确请求结构和 OpenAPI schema 以运行时 `/docs` 为准。
