# 拾光优选 · 微信小程序

原生微信小程序（无 npm / 无构建链），消费后端 `/api/v1`。

## 导入开发者工具

1. 打开微信开发者工具 → 导入项目
2. 目录选择本仓库下的 `miniprogram/`（不是仓库根目录）
3. AppID：开发可用 `touristappid`（见 `project.config.json`）；正式环境替换为真实 AppID
4. 开发阶段建议关闭「校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」（`project.config.json` 已设 `urlCheck: false`）

## 环境切换

- 配置文件：`utils/config.js`
- 开发默认：`BASE_URL = 'http://localhost:8080'`
- 生产：改为 `https://你的域名`，并在[小程序后台](https://mp.weixin.qq.com) → 开发管理 → 开发设置 → 服务器域名中配置 **request 合法域名**（及 uploadFile 域名，若上传凭证走同域）

请求实际路径：`{BASE_URL}/api/v1/...`

## 登录

| 模式 | 后端条件 | 小程序行为 |
|------|----------|------------|
| Mock | `MARKET_SHOP_WECHAT_MOCK_ENABLED=true`（或 `market-shop.wechat.mock-enabled=true`） | `wx.login` 的 `code` 可任意字符串，后端当 openId；**首次注册**需邀请码，本地种子常用 `BOOTSTRAP2026` |
| 正式 | 微信小程序已启用 + 真实 AppID/Secret | `wx.login` → code2session 换 openId |

流程：`pages/login` → `wx.login` → `POST /auth/wechat/miniprogram/login` `{code, inviteCode?}` → 存 token → `reLaunch` 首页。

## 会话

- Storage key / 请求头名：`market-shop-user-token`
- 登录成功后 `setToken(token)` 写入 storage；`utils/request.js` 在 `auth !== false` 时自动带头
- 401 / `NOT_LOGGED_IN`：清 token 并 `reLaunch` 登录页

## 设计基准

- 稿：`docs/design/miniprogram/png/`（PNG）+ `docs/design/miniprogram/miniprogram.pen`
- Tab 图标源：`docs/design/miniprogram/icons/` → 已复制到 `assets/tab/`
- 尺寸：设计稿 **375pt = 750rpx**，实现时 **px × 2 → rpx**（如 16px → 32rpx）
- Token 与通用类见 `app.wxss`（ink / coral / serif / btn-primary 等）

## 目录速览

```
miniprogram/
  app.js / app.json / app.wxss
  utils/     config · request · format · order-status
  api/       auth · catalog · cart · order · address · system
  components/ goods-card · stepper · empty · sku-sheet
  pages/     登录 / 首页 / 分类 / 购物车 / 我的 / 商品 / 订单 / 地址 …
  assets/tab/
```
