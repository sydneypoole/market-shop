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

商城用户 token 名为 `market-shop-user-token`，后台 token 名为 `market-shop-admin-token`，两类会话不能互换。

## 微信与身份

- `POST /api/v1/auth/wechat/authorize`：生成 H5 或 WEB 授权地址；`inviteCode` 与一次性 `sponsorClaimSecret` 放在 JSON 请求体中，不能放入 URL。
- `GET /api/v1/auth/wechat/callback`：微信回调，建立 cookie 会话并回跳前端。
- `POST /api/v1/auth/wechat/complete`：SPA 主动完成 OAuth。
- `POST /api/v1/auth/dev-login`：仅 local profile。
- `POST /api/v1/admin/auth/login`：后台独立登录。

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
- `POST /api/v1/membership/invitation`
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
