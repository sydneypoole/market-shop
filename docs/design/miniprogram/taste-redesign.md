# 宏杉生物小程序 Taste 重设计

## 1. 来源与边界

- 设计审查能力：项目本地 `.agents/skills/design-taste-frontend/SKILL.md`。
- 官方来源：[Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill)。
- 锁定信息：根目录 `skills-lock.json` 中的 `design-taste-frontend` 条目，来源为 `Leonxlnx/taste-skill`，上游路径为 `skills/taste-skill/SKILL.md`，内容哈希为 `6d838b246d0e35d0b53f4f23f98ba7a1dd561937e64f7d0c7553b0928e376c3e`。
- Taste skill 用于 Redesign 审查、反模板化和交付前检查；原生微信小程序继续使用 WXML/WXSS、微信原生能力和 FirstUI，不引入 React/Tailwind 或第二套组件体系。

## 2. Design Read

面向微信用户的“宏杉生物”原生微信商城，以冷珍珠与烟灰中性色承载商品内容，以梅紫作为唯一装饰/交互强调色，呈现可信、克制、简洁且有品牌辨识度的精品生物商城。

| 档位 | 值 | 落地要求 |
|---|---:|---|
| `DESIGN_VARIANCE` | 6 | 保持原生商城熟悉路径，通过留白、层级和局部非对称建立品牌感 |
| `MOTION_INTENSITY` | 4 | 仅使用约 160–240ms 的有目的反馈，并支持减少动态效果 |
| `VISUAL_DENSITY` | 5 | 商品、订单与售后信息可快速扫描，不堆叠装饰卡片 |

## 3. 视觉系统

- 画布采用冷珍珠/烟灰色，页面导航背景使用近白色；交互强调色为梅紫 `#7A284F`，深色状态为 `#4B1731`。
- 标题、正文、价格统一使用系统中文无衬线字体；不使用宋体、装饰性字距、暖粉光晕或图片叠加品牌胶囊制造“高级感”。
- 内容卡、表单控件、按钮分别复用全局半径；层级优先依靠留白、排版和单一分隔线，避免卡片套卡片。
- 成功、警告、危险色只表达真实业务状态，不扩展为装饰色。
- 图标按钮具有可读标签，固定底部操作区包含安全区，主操作具有 loading/disabled 与重复提交保护。

## 4. 25 页覆盖与业务不变量

重设计覆盖 `app.json` 注册的 25 个页面：

- 发现与内容：首页、分类、登录、注册、商品详情、商品列表、搜索、内容详情、会员中心、邀请、积分、通知、分销规则。
- 交易与履约：购物车、订单确认、订单成功、订单列表、订单详情、上级确认、地址列表、地址编辑、售后申请、售后列表、售后详情。
- 账户入口：“我的”。

页面可改变布局、间距、字体层级、图标和状态呈现，但必须保持以下契约：

- 微信登录与注册保持独立页面，`market-shop-user-token`、401 清理并回到登录页；登录和注册均可返回公共首页；
- 动态商品、内容、分销规则、会员能力和服务端状态为权威数据；
- 线下收款、直属上级确认、后台审核与发货、用户确认收货的订单链路；
- 409 后刷新权威状态、未知状态安全降级及操作者权限门控；
- 订单/售后 `clientRequestId` 生命周期和重复提交保护；
- 地址版本控制与页面返回通道；
- 凭证数量/大小限制、阶段类型、短期 URL 刷新、上传与预览；
- 售后退货、直属上级线下退款确认和用户确认退款；
- 微信原生客服、分页、搜索筛选、加载/错误/重试状态。

## 5. FirstUI 单一图标体系

- 页面和业务 wrapper 只使用固定版本的 `components/firstui/fui-icon`，不使用文字箭头、CSS 手绘图标或第二套 icon font。
- 原生 tabBar 不支持组件，故从同一 FirstUI 字体生成 PNG：
  - 首页：`home` / `home-fill`
  - 分类：`classify` / `classify-fill`
  - 购物车：`cart` / `cart-fill`
  - 我的：`my` / `my-fill`
- 生成命令：

```bash
python3 scripts/generate-miniprogram-tab-icons.py
```

- 生成器需要 Pillow。输出为 81 × 81 RGBA，普通色 `#6F656B`、选中色 `#7A284F`，单文件小于 40 KiB。
- 运行时目录 `miniprogram/assets/tab/` 与设计留档目录 `docs/design/miniprogram/icons/` 必须字节一致。

## 6. 验收门禁

在仓库根目录执行：

```bash
python3 scripts/generate-miniprogram-tab-icons.py
pnpm test:miniprogram
git diff --check
```

静态门禁检查页面/组件注册、WXML 事件、FirstUI 来源与许可证、主题映射、25 页图标体系、tab PNG 规格和业务消费者契约。上传源文件按 `project.config.json` 的 `packOptions.ignore` 计算，Taste 设计改动目标小于 1.4 MiB，主包必须小于 2 MiB。

最后使用微信开发者工具导入 `miniprogram/`，完成 WXML/WXSS 编译，并按 `miniprogram/README.md` 的体验版/正式版清单进行真机回归；静态测试不替代微信渲染器和真机验收。
