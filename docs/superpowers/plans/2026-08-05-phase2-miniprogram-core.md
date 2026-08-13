# Phase 2 Implementation Plan: 原生微信小程序核心链路

> **已被取代（2026-08-13）：** 本文仅作为历史实施记录，其中的登录/注册 API 与界面描述不再是现行契约。当前以 [Miniprogram Public API Contracts](../../../.trellis/spec/backend/miniprogram-api-contracts.md) 为准：登录严格为 `{code}`，邀请注册使用独立路由 `{code, inviteCode}`。

> **For agentic workers:** 按 Task 顺序执行；Task 0（骨架）必须先完成，其余页面 Task 可并行。每页实现以 Pencil 设计稿 PNG 为视觉基准。

**Goal:** 从零实现原生微信小程序「拾光优选」，覆盖登录/首页/分类/商品/购物车/结算/订单/地址/我的核心链路，UI 严格对齐 Pencil 设计稿。

**Architecture:** 原生小程序（无框架），直接消费后端 `/api/v1` REST。会话 = 登录返回的 token，存 storage，请求头 `market-shop-user-token` 携带。

**Tech Stack:** WeChat 原生小程序（WXML/WXSS/JS），无构建链、无 npm 依赖。

## Global Constraints

- 设计基准：`docs/design/miniprogram/png/*.png`（17 屏）+ 下表 frame 映射；实现者可用 Pencil MCP `get_screenshot`（filePath `docs/design/miniprogram/miniprogram.pen`）复查任一节点的最新设计
- 设计稿 375pt 宽 = 小程序 750rpx，**所有尺寸 ×2 转 rpx**（16px padding → 32rpx，2px 圆角 → 4rpx）
- 设计 token（写为 app.wxss CSS 变量）：ink `#111111`、ink-soft `#2A2A2A`、muted `#8A8781`、paper `#FFFFFF`、canvas `#F7F6F3`、line `#E5E2DC`、line-strong `#D4D0C8`、coral `#E4644F`、gold `#A07A3A`、green `#245F4E`、sage `#E8EDE8`；圆角恒 4rpx；无阴影无渐变；分隔线 1px（用 `1rpx` 视觉过细时用 `2rpx` 与 line 色，二选一全局统一为 1px 物理线 = `2rpx`）
- 衬线字体栈（标题/价格）：`font-family: "Noto Serif SC","Songti SC","SimSun",serif`；正文用系统默认
- 金额一律整数分（fen）传输，前端 `fenToYuan` 格式化显示 `¥129.00`
- API 契约以 `.trellis/spec/backend/miniprogram-api-contracts.md` 与 `docs/design/../..` 无关——以 spec 与探索报告 `research/explore-public-api.json` 为准
- 所有接口 envelope `{success,code,message,data}`；401 `NOT_LOGGED_IN` → 清 token 跳登录页
- 不引入任何 npm 依赖；不使用 vue/pinia 概念
- 图片域名：封面/凭证走 `/api/v1/catalog/assets/{id}` 或绝对 URL，直接拼接 BASE_URL

## 工程结构（Task 0 产出，后续 Task 依赖）

```text
miniprogram/
  project.config.json      # appid 用 "touristappid" 占位 + 注释说明替换；urlCheck false（dev）
  project.private.config.json # 可空
  sitemap.json             # 默认允许索引
  app.json                 # pages 注册 + tabBar(4) + window 配置
  app.js                   # globalData {baseUrl, token}, env 切换常量
  app.wxss                 # token 变量 + 通用类(.serif/.hairline-b/.btn-primary/.btn-outline/.tag...)
  assets/tab/              # 从 docs/design/miniprogram/icons/ 复制 8 个 tab 图标
  utils/
    config.js              # const BASE_URL = 'http://localhost:8080'（顶部注释：prod 换 https 域名并配置 request 合法域名）
    request.js             # request(path, opts) + uploadFile(path, filePath, name)
    format.js              # fenToYuan(fen) -> "1,299.00"、dateTime(iso)、fileSize(bytes)
    order-status.js        # STATUS_TEXT 映射 + resolveOrderActions(capabilities)
  api/
    auth.js                # login(code, inviteCode?), me(), logout()
    catalog.js             # products(), product(id), categories(), contents()
    cart.js                # list(), setItem(skuId, quantity, selected)
    order.js               # submit(payload), list(), detail(id), cancel(id, reason), receive(id), uploadProof(id, filePath), proofs(id)
    address.js             # list(), create(body), update(id, body), remove(id, version)
    system.js              # about(), capabilities()
  components/
    goods-card/            # properties: cover, name, priceFen, marketPriceFen; tap 事件
    stepper/               # properties: value, max; triggerEvent('change', value)
    empty/                 # properties: text, hint, buttonText; triggerEvent('action')
    sku-sheet/             # properties: visible, product(detail对象), mode('cart'|'buy'); 内部维护 selectedSkuId/qty；triggerEvent('confirm', {skuId, quantity})
  pages/
    login/ index/ category/ cart/ profile/          # tab: index/category/cart/profile
    goods/detail/ goods/list/ search/
    order/confirm/ order/success/ order/list/ order/detail/
    address/list/ address/edit/
```

### request.js 契约（所有页面依赖此签名）

```js
// utils/request.js
const { BASE_URL } = require('./config')
const TOKEN_KEY = 'market-shop-user-token'

function getToken() { return wx.getStorageSync(TOKEN_KEY) || '' }
function setToken(t) { t ? wx.setStorageSync(TOKEN_KEY, t) : wx.removeStorageSync(TOKEN_KEY) }

// request('/cart') → GET；opts: {method, data, auth=true}
// 成功 resolve(data)；!success 或网络错 reject({code, message})
// 401/NOT_LOGGED_IN：setToken('') + wx.reLaunch('/pages/login/login') + reject
function request(path, opts = {}) { /* 拼接 BASE_URL+'/api/v1'+path；header 带 token */ }

// 凭证上传：wx.uploadFile，name 固定 'file'，同样带 token 头、解 envelope
function uploadFile(path, filePath) {}

module.exports = { request, uploadFile, getToken, setToken, TOKEN_KEY }
```

### app.json 骨架

```json
{
  "pages": [
    "pages/index/index", "pages/category/category", "pages/cart/cart", "pages/profile/profile",
    "pages/login/login", "pages/goods/detail", "pages/goods/list", "pages/search/search",
    "pages/order/confirm", "pages/order/success", "pages/order/list", "pages/order/detail",
    "pages/address/list", "pages/address/edit"
  ],
  "window": { "navigationBarBackgroundColor": "#FFFFFF", "navigationBarTitleText": "拾光优选", "navigationBarTextStyle": "black", "backgroundColor": "#F7F6F3" },
  "tabBar": {
    "color": "#8A8781", "selectedColor": "#111111", "backgroundColor": "#FFFFFF",
    "borderStyle": "white",
    "list": [
      { "pagePath": "pages/index/index", "text": "首页", "iconPath": "assets/tab/tab-home.png", "selectedIconPath": "assets/tab/tab-home-active.png" },
      { "pagePath": "pages/category/category", "text": "分类", "iconPath": "assets/tab/tab-category.png", "selectedIconPath": "assets/tab/tab-category-active.png" },
      { "pagePath": "pages/cart/cart", "text": "购物车", "iconPath": "assets/tab/tab-cart.png", "selectedIconPath": "assets/tab/tab-cart-active.png" },
      { "pagePath": "pages/profile/profile", "text": "我的", "iconPath": "assets/tab/tab-profile.png", "selectedIconPath": "assets/tab/tab-profile-active.png" }
    ]
  },
  "style": "v2",
  "lazyCodeLoading": "requiredComponents"
}
```

### 关键 API 形状（来自探索报告，字段名以此为准）

- 登录：`POST /auth/wechat/miniprogram/login` `{code, inviteCode?}` → `{token, publicId, nickname, newlyRegistered}`
- 商品列表：`GET /catalog/products` → `ProductView[] {productId, categoryId, categoryName, name, subtitle, coverUrl, salesScene, skuId, skuName, priceFen, marketPriceFen, inventory, minPriceFen, maxPriceFen, skuCount}`
- 商品详情：`GET /catalog/products/{id}` → `{product: ProductView, descriptionHtml, skus: SkuView[] {skuId, skuCode, skuName, priceFen, marketPriceFen, inventory, attributesJson}}`
- 分类：`GET /catalog/categories` → `CategoryView[]`；内容：`GET /content` → `ContentView[]`（公告/轮播按 type 过滤）
- 购物车：`GET /cart` → `{id, skuId, productName, skuName, coverUrl, priceFen, quantity, selected, inventory}[]`；`PUT /cart/items/{skuId}` `{quantity(0=删), selected}`
- 下单：`POST /orders` `{clientRequestId, source:"MINIPROGRAM", address:{recipientName,phone,province,city,district,detailAddress,postalCode?}, items:[{skuId,quantity}]}` → OrderView
- 订单：`GET /orders`、`GET /orders/{id}` → `{order, addressJson(JSON 字符串需 parse), items, shipment, actorCapabilities:{canReceive,canUploadProof,canCancel,canSuperiorDecide}}`；`POST /orders/{id}/cancel {reason}`；`POST /orders/{id}/receive`
- 凭证：`POST /orders/{id}/proofs` multipart `file`（wx.uploadFile）；`GET /orders/{id}/proofs`
- 地址：`GET /addresses`；`POST /addresses`；`PUT /addresses/{id}`（body 带 version）；`DELETE /addresses/{id}?version=n`；`SaveAddressRequest {recipientName,phone,province,city,district,detailAddress,postalCode?,defaultAddress,version}`
- 订单状态文案（order-status.js 内置）：PENDING_SUPERIOR 待上级确认 / SUPERIOR_REJECTED 上级已拒绝 / PENDING_ADMIN_REVIEW 待后台审核 / ADMIN_REJECTED 审核未通过 / PENDING_SHIPMENT 待发货 / SHIPPED 待收货 / COMPLETED 已完成 / CANCELLED 已取消
- 状态色：待上级确认/待操作 = coral；待发货 = gold；待收货/进行中 = green；已完成/已取消/拒绝 = muted

### SKU 选择逻辑（对齐原 storefront）

- 默认选中：第一个 `inventory>0` 的 sku，否则 skus[0]
- 切换 sku 时 qty 重置为 1；maxQuantity = min(99, inventory)
- 规格展示：解析 `attributesJson`（JSON 对象）values join ' · '
- 价格展示：min===max ? `¥x` : `¥x 起`

---

### Task 0: 工程骨架（必须先完成）

**Files:** 上述「工程结构」全部基础文件 + login 页 + profile 页（两页验证全链路：登录拿 token → profile 调 `GET /auth/me` / `GET /membership/me` 可选）

- [ ] 创建全部骨架文件；assets/tab 从 `docs/design/miniprogram/icons/` 复制
- [ ] login 页：对齐 `png/Yz3MQ.png`；点击「微信一键登录」→ `wx.login()` 取 code → 若 storage 无邀请码记录且输入框有值则带上 → `api/auth.login` → setToken → reLaunch 首页；`newlyRegistered` 时 toast 欢迎。mock 环境 code 可直接当 openid
- [ ] profile 页：对齐 `png/yeoPA.png`；`GET /auth/me` 显示昵称；入口行：我的订单（待确认/待发货/待收货 → 跳 order/list?tab=x）、收货地址、联系客服（button open-type contact 占位或「暂未开放」toast）、关于（system/about 弹窗）；未登录进入时 reLaunch login
- [ ] 验证：`node --check` 全部 js；`JSON.parse` 校验 app.json/project.config.json/sitemap.json

### Task 1: 首页 + 分类 + 商品列表 + 搜索（4 页）

设计基准：`lqKPi.png` / `NMaRe.png` / `b3tvY.png` / `Iu2uC.png` + 空状态 `npLlV.png`
- 首页：公告条（/content 过滤 ANNOUNCEMENT 类）、搜索框（跳 search）、分类 chips（/catalog/categories，点击跳 goods/list?categoryId=）、HERO（取 content BANNER 类，无则用静态文案+占位图区）、甄选合集（/catalog/products 前 6，goods-card 双列）、内容故事（/content 其余类型首条）、服务权益（静态）
- 分类页：左栏分类列表 + 右侧该分类商品（products 按 categoryId 前端过滤）；顶部搜索框
- 商品列表页：onLoad 收 categoryId；排序栏（综合/价格升降/新品=按 productId 倒序）前端排序；双列网格全量展示
- 搜索页：输入框自动聚焦，确认后按 name/subtitle 前端过滤 products；空结果用 empty 组件

### Task 2: 商品详情 + SKU 面板 + 购物车（2 页 + 1 组件用法）

设计基准：`MVgV0.png` / `bBa8P.png` / `vqBXG.png`
- 详情：`/catalog/products/{id}`；主图 coverUrl；价格区（sku 选中价）；sku-sheet 组件（加入购物车=调 cart.setItem 后 toast；立即购买=带 skuId+qty 跳 order/confirm）；descriptionHtml 用 rich-text 渲染；购买须知静态
- 购物车：`/cart`；行内 stepper 改数量（PUT）、checkbox 改选中（PUT）；底部合计 = 选中项 priceFen×quantity 求和；结算跳 order/confirm（带选中 sku 摘要）；空态用 empty 组件

### Task 3: 结算 + 提交成功（2 页）

设计基准：`tnN1U.png` / `R8cbts.png`
- 结算：地址卡（/addresses 取默认或首个；点击跳 address/list?select=1 回传）；商品清单（来源：购物车选中项 或 立即购买参数 {skuId, quantity}——立即购买需先 product(id) 取 sku 快照）；金额明细（合计求和，运费 0）；clientRequestId = 时间戳+随机串防重；提交 → POST /orders → 成功跳 order/success?orderNo=&total=；地址为空时地址卡显示「请选择收货地址」并阻断提交
- 成功页：订单号+金额展示、线下转账指引（静态三步）、查看订单（跳 order/detail?id=）、返回首页（switchTab）

### Task 4: 订单列表 + 订单详情 + 地址两页（4 页）

设计基准：`o3JUj.png` / `VIBgu.png` / `akCXh.png` / `o894Gs.png`
- 订单列表：状态 tabs（全部/待确认=PENDING_SUPERIOR/待发货=PENDING_SHIPMENT/待收货=SHIPPED/已完成=COMPLETED）前端过滤；卡片按 actorCapabilities 显示按钮（取消/上传凭证/确认收货）；确认收货 wx.showModal 二次确认后调 receive
- 订单详情：状态横幅（serif 状态文案+提示）；凭证块（proofs 列表缩略图 + canUploadProof 时 wx.chooseMedia 上传 uploadFile，限 3 张）；地址块（JSON.parse(addressJson)）；商品行；金额明细；时间线（createdAt/superiorConfirmedAt/adminReviewedAt/shipment/completedAt 按存在性渲染）；底部按 capabilities 出按钮；取消订单弹输入原因（wx.showModal editable）
- 地址列表：/addresses；默认 tag（defaultAddress）；编辑跳 edit?id=；select 模式（query select=1）点击整卡回传上一页
- 地址编辑：表单校验（必填：收货人/手机号/省市区/详细地址；手机号 11 位正则）；新建 POST / 编辑 PUT（带 version）；删除（edit 页底部删除按钮，wx.showModal 确认后 DELETE）；设为默认 defaultAddress

### Task 5: 联调验证

- [ ] 起本地后端（local profile + mock 微信）或用 mock：登录 code 任意串即 openid
- [ ] 走通链路：登录(邀请码 BOOTSTRAP2026) → 首页 → 详情 → SKU → 加购 → 购物车 → 结算 → 提交 → 成功页 → 订单列表 → 详情 → 上传凭证
- [ ] 验证工具：开发者工具编译无报错；或至少 `node --check` 全 js + JSON 校验 + 逐页 review 对照 PNG

## 完成判定

1. 14 页 + 4 组件全部实现，`node --check` 与 JSON 校验全过
2. 每页视觉对照对应 PNG 无结构性偏差（布局/留白/层级一致；图片内容可不同）
3. mock 环境下核心链路手工联调通过（或明确记录未能联调的原因）
