# PRD: 前端重设计（pen.dev 极简高级方向）

## 背景

拾光优选（邀请制会员商城，线下转账确认，无在线支付）现有前端为手写 CSS、无组件库。本次用 pen.dev CLI 产出重设计稿，作为后续代码实现的视觉基准。

## 设计方向

- **极简高级**：黑白灰基调 + 单一强调色，大留白、细线条（1px hairline）、衬线大标题、买手店/高端杂志质感。
- 三模板预设（EDITORIAL / VIBRANT / MINIMAL）在新方向下仍需保持可辨识的视觉差异：
  - EDITORIAL：衬线大标题、杂志栅格、黑白摄影感
  - VIBRANT：同一骨架下用强调色与大胆排印表达活力（克制用色，不渐变堆砌）
  - MINIMAL：极致留白、无装饰、单色
- Admin 与 storefront 共享同一套设计语言，admin 更偏工具密度（紧凑表格、清晰层级）。

## 覆盖屏（核心屏全覆盖）

### Storefront（会员端）
1. 设计系统板：色彩 / 字体 / 间距 / 圆角 / 按钮 / 卡片 / 标签
2. 首页（EDITORIAL 演绎）：公告条、HERO、分类导航、快捷入口、商品合集（搜索/场景筛选）、内容故事、服务权益 —— 桌面 1440 + 移动 390
3. 首页三预设对比：EDITORIAL / VIBRANT / MINIMAL 同构变体
4. 商品详情：媒体、SKU 选择、数量步进、价格/划线价、服务说明、图文详情
5. 会员中心：等级、A/B 积分指标、邀请码/二维码、直推会员、积分流水
6. 订单中心：双 Tab（我的订单 / 上级待确认）、筛选、订单卡片、凭证上传入口

### Admin（运营端）
7. 工作台 Dashboard：KPI、待办队列、合规边界
8. 订单管理：FilterBar + 表格 + 详情抽屉（快照/凭证/时间线/备注）
9. 商品目录：分类卡片、商品/SKU 表格、库存调整
10. 模板列表：模板卡片（状态/预设/当前生效）、新建/复制/发布/归档
11. 模板设计器（studio）：360px 设计侧栏（基础信息/全局风格 tokens/区块列表与设置）+ PC/H5 实时预览

## 约束（来自 spec，实现时不可破坏）

- 区块类型白名单 7 种；设计 tokens 有界（radius 0–60px、色彩 #RRGGBB、headingFont serif|sans）
- 设计器：草稿编辑、乐观锁 version、发布需自定义确认弹窗、PC/H5 同源预览
- 管理端中文界面；禁浏览器原生 alert/confirm
- 390px 无横向溢出

## 交付物

- `docs/design/exports/*.pen` + PNG 预览
- 实现（本任务）：
  - storefront 全局 token / shell / 三预设 renderer 对齐极简高级
  - admin shell + 模板 studio 默认 token / preview 对齐同一语言
  - 验证：`pnpm --filter @market-shop/storefront test|typecheck`、`pnpm --filter @market-shop/admin test|typecheck` 全过

## 实现备注

- DB 已发布模板的 `design_tokens_json` 仍是旧色板；前端 fallback 与新建草稿默认已切新色。要全站生效需在模板工作室重新发布。
- 合约未改：7 种 section、三预设枚举、乐观锁、自定义确认弹窗、CSS 直系选择器约束。
