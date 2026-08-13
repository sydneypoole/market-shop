# Phase 3 Implementation Plan: 小程序二期全量功能（会员/邀请/积分/售后/通知/规则/上级确认）

> **已被取代（2026-08-13）：** 本文仅作为历史实施记录，其中的 API 段与邀请分享路径不再是现行契约。当前以 [Miniprogram Public API Contracts](../../../.trellis/spec/backend/miniprogram-api-contracts.md) 为准；`InvitationView.registrationPath` 指向原生注册页 `/pages/register/register?inviteCode=<URL-encoded-code>`。

> **For agentic workers:** Task 0 地基必须先完成，Task 1-3 可并行。视觉沿用一期设计系统（无新 Pencil 稿——MCP 断连，恢复后补设计存档）。

**Goal:** 小程序补齐全量功能：会员中心、邀请码/直推会员、积分流水、售后全流程、消息通知、规则说明、上级订单确认。

**Tech Stack:** 同二期前（原生小程序，无依赖）。设计 token、组件（goods-card/stepper/empty/sku-sheet）、request/api 模式复用 `miniprogram/` 现有代码。

## Global Constraints

- 视觉：严格镜像已提交页面（参考 `pages/order/detail.*`、`pages/profile/profile.*` 的版式——serif 状态横幅、细线分隔、token 色、4rpx 圆角、无阴影）
- 状态色约定：待操作=coral、进行中=gold/green、终态=muted（对齐 `utils/order-status.js` 的 TONE 思路）
- 金额 fen → fenToYuan；时间 ISO → dateTime
- 邀请二维码本期不生成（无 npm 依赖、canvas 手绘成本高）——展示邀请码文本 + 复制即可，记录为已知偏差
- 不改动 backend 任何文件；全部使用既有 API

## 后端契约（已核对真实代码，字段名以此为准）

### 会员 `api/member.js`（base /membership）
- `me()` GET /membership/me → `{userId, nickname, levelCode, levelName, availablePoints, frozenPoints, qualifiedDirectCount}`
- `invitation()` GET /membership/invitation → `{code, status, useCount, registrationPath, expiresAt}`（无邀请码时 data 为 null，只读不创建）
- `createInvitation()` POST /membership/invitation → InvitationView
- `revokeInvitation()` POST /membership/invitation/revoke
- `regenerateInvitation(validityDays=365)` POST /membership/invitation/regenerate?validityDays=365
- `directMembers()` GET /membership/direct-members → `[{userId, publicId, nickname, levelName, completedOrdinal, performanceFen, performanceStatus}]`
- `ledger()` GET /membership/ledger → `[{id, entryType, availableDelta, frozenDelta, sourceType, sourceId, sourceOrderId, occurredAt, frozenBatchStatus, ...}]`

### 售后 `api/aftersale.js`（base /after-sales）
- `apply({orderId, clientRequestId, type, reason, description})` POST /after-sales；type ∈ `REFUND_ONLY | RETURN_REFUND`
- `list()` GET /after-sales（我申请的）；`superiorList()` GET /after-sales/superior（需我处理的）
- `detail(id)` GET /after-sales/{id} → `View{id, afterSaleNo, orderId, applicantUserId, superiorUserId, type, status, reason, adminReason, returnAddressJson, returnCarrier, returnTrackingNo, createdAt, completedAt}`
- `returnShipment(id, {carrier, trackingNo})` POST /{id}/return-shipment
- `confirmRefund(id)` POST /{id}/confirm-refund（买家确认到账）
- `cancel(id, reason)` POST /{id}/cancel body `{reason}`
- `superiorConfirmOfflineRefund(id)` POST /superior/{id}/confirm-offline-refund
- 凭证：`uploadProof(id, filePath)` POST /after-sales/{id}/proofs（multipart file）；`proofs(id)` GET 同径；`proofDownload(proofId)` GET /after-sale-proofs/{id}/download → `{signedUrl, expiresAt}`

### 售后状态文案（新增 `utils/aftersale-status.js`，镜像 order-status.js 模式）
PENDING_ADMIN_REVIEW 待后台审核(coral) / AWAITING_RETURN 待用户回寄(coral，买家视角可操作) / RETURN_SHIPPED 用户已回寄(gold) / PENDING_OFFLINE_REFUND 待线下退款(gold；上级可操作) / PENDING_BUYER_REFUND_CONFIRMATION 待用户确认退款(coral) / COMPLETED 已完成(muted) / REJECTED 已拒绝(muted) / CANCELLED 已撤销(muted)
类型文案：REFUND_ONLY 仅退款 / RETURN_REFUND 退货退款

### 通知 `api/notify.js`
- `list(page=1, size=20)` GET /notifications?page&size → `{items:[{id, channel, templateCode, title, content, businessType, businessId, status, readAt, createdAt}], total, page, size}`
- `unreadCount()` GET /notifications/unread-count → number
- `markRead(id)` POST /notifications/{id}/read

### 规则 `api/rules.js`
- `active()` GET /rules/active → `[{id, ruleCode, version, ruleType, parametersJson, status, effectiveFrom, effectiveTo}]`——parametersJson 为 JSON 字符串，解析后按 key-value 只读展示

### 上级订单（`api/order.js` 追加两方法）
- `superiorOrders()` GET /superior/orders → OrderView[]
- `superiorDecision(id, approve, reason)` POST /superior/orders/{id}/decision body `{approve, reason}`（拒绝必须给理由）

## 新页面（9 个，注册进 app.json）

| 页面 | 路径 | 内容 |
|---|---|---|
| 会员中心 | pages/member/center | 等级卡（serif levelName + levelCode tag）、双积分指标（可用 availablePoints / 冻结 frozenPoints，大数字 serif）、直推 qualifiedDirectCount；入口行：我的邀请码、积分流水、直推会员 |
| 邀请码 | pages/member/invite | 无邀请码：空态 + 「生成我的邀请码」(createInvitation)；有：大号 serif code、状态/已使用次数/有效期、复制按钮（wx.setClipboardData）、撤销(revoke, showModal)/重建(regenerate, showModal) 按钮；下方直推会员列表（nickname、levelName、业绩 performanceFen→fenToYuan、performanceStatus 文案、completedOrdinal） |
| 积分流水 | pages/member/points | 流水行：entryType 文案化 + availableDelta/frozenDelta 带符号着色（正 green/负 coral/冻结 gold）+ occurredAt + 来源（sourceOrderId 可跳 order/detail） |
| 通知列表 | pages/notify/list | 分页加载（onReachBottom page+1）；未读左侧 coral 圆点；点击 markRead + 按 businessType/businessId 跳转（ORDER→order/detail, AFTERSALE→aftersale/detail，无则展开文案） |
| 规则说明 | pages/rules/rules | active() 列表：ruleType 标题 + version/effectiveFrom + parametersJson 解析 key-value 行；空态 empty 组件 |
| 申请售后 | pages/aftersale/apply | 入参 orderId；类型二选一（细线卡片单选）；原因输入；补充说明 textarea；提交（clientRequestId 防重）→ 成功跳 aftersale/detail |
| 售后列表 | pages/aftersale/list | 双 tab：我申请的(list) / 我处理的(superiorList)；卡片：afterSaleNo、类型、状态(tone 色)、createdAt；点击进详情；空态 |
| 售后详情 | pages/aftersale/detail | serif 状态横幅 + 提示；凭证区（proofs 预览经 proofDownload signedUrl；可上传时机：我申请且状态未完成/未撤销）；进度时间线（createdAt→审核→回寄→退款→completedAt 按 status 推进）；操作区按状态+视角：AWAITING_RETURN 且我是申请人→填写物流(carrier/trackingNo 两输入+提交)；PENDING_BUYER_REFUND_CONFIRMATION 且申请人→确认退款(showModal)；PENDING_OFFLINE_REFUND 且我是 superior→确认线下退款；进行中的我的申请→撤销(showModal editable 理由)；REJECTED 显示 adminReason；returnAddressJson 有值时 JSON.parse 展示回寄地址 |
| 上级订单 | pages/order/superior | superiorOrders() 列表（OrderView 卡：orderNo/totalAmountFen/createdAt）；点击进 order/detail；空态（你不是任何订单的上级） |

## 既有页面改动

1. `pages/profile/profile`：服务列表新增入口——会员中心(→member/center)、消息通知(→notify/list，带 unreadCount badge，onShow 刷新)、退款/售后(→aftersale/list)、规则说明(→rules/rules)；我的订单行第 4 项「退款/售后」→ aftersale/list
2. `pages/order/detail`：`actorCapabilities.canSuperiorDecide===true` 时显示上级确认操作区（同意 → superiorDecision(id,true,null)；拒绝 → showModal editable 理由 → superiorDecision(id,false,reason)）
3. `pages/order/detail` 商品区下方加「申请售后」入口（订单 COMPLETED/SHIPPED 且非售后中时 → aftersale/apply?orderId=）
4. `app.json` pages 追加 9 项（Task 0 完成，避免并行冲突）

## 任务拆分

### Task 0（地基，先行）
- 4 个 api 模块 + order.js 追加 + utils/aftersale-status.js
- app.json 注册 9 页 + 9 页 stub（每页 4 文件，"页面建设中"）
- profile 页入口更新（含 unread badge 逻辑）
- order/detail 的 canSuperiorDecide 操作区 + 申请售后入口
- 验证：node --check + JSON.parse

### Task 1: member 三页（center/invite/points）
### Task 2: aftersale 三页（apply/list/detail）
### Task 3: notify/list + rules/rules + order/superior 三页

## 完成判定

1. 23 页全部无 stub；node --check + JSON.parse 全绿
2. trellis-check 对照本计划契约审查通过
3. 已知偏差记录：无邀请二维码；无 Pencil 新设计稿（MCP 断连）
