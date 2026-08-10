# FirstUI 原生微信小程序接入审计

## 决策

- 使用 FirstUI-weixin 公开 Apache-2.0 源码，固定于 commit `fa7863720afcf591aaf3ba6de29c42a88c6dde80`（V2.4.0，2024-06-12）。
- 原生微信版官方尚未发布 npm 包。本项目也是无 npm 构建链的原生小程序，因此按官方快速上手文档将需要的组件源码 vendoring 到 `miniprogram/components/firstui/`。
- 只引入公开仓库中实际存在的组件。NavBar、Upload、TimeAxis、SearchBar 等 VIP/非公开组件不纳入公开仓库，由微信原生能力或项目业务组件继续承载。
- 保留 FirstUI Apache-2.0 `LICENSE`、上游版本和本地修改说明。不把 VIP 源码或商业模板引入开源仓库。

## 官方契约

- 快速上手：<https://wxdoc.firstui.cn/docs/started.html>
- 主题：<https://wxdoc.firstui.cn/docs/theme.html>
- FAQ：<https://wxdoc.firstui.cn/docs/FAQ.html>
- 开源仓库：<https://github.com/FirstUI/FirstUI-weixin>
- 页面级按需注册形式为 `"fui-button": "/components/firstui/fui-button/fui-button"`。
- 主题样式通过 `app.wxss` 引入 `fui-theme/fui-theme.wxss`，再以 `--fui-*` 变量完成宏杉生物品牌映射。
- FirstUI FAQ 明确建议移除微信新版基础样式 `app.json` 的 `style: "v2"`，避免组件样式混乱。

## 现有工程与门禁

- `miniprogram` 是原生微信工程；`project.config.json` 的 `nodeModules`/`bundle`/`packNpmManually` 均未启用，不应为 FirstUI 增加一条伪 npm 链路。
- 现有 24 个页面、4 个公共组件，所有业务请求、路由、权限、幂等、凭证和冲突恢复都在页面 JS/API utils 中。迁移应以 WXML/WXSS 与业务 wrapper 为主，不使用 FirstUI 接管业务请求、上传或路由。
- 引入前小程序目录约 850 KB，其中品牌原图约 560 KB。完整拷贝 FirstUI 公开组件会增加约 370 KB/200 余文件；即使仍在 2 MiB 主包阈值内，也应按需引入并在微信开发者工具核验最终上传包。
- 现有静态测试把 `components/**` 下每个 `.js` 都当成组件四件套，会误判 FirstUI 的 config/icon/utils 工具文件。测试应改为以 `*.json` 中 `component: true` 识别真实组件，并继续检查 `usingComponents`、WXML 事件 handler 和资产存在性。

## 设计与迁移边界

- 主题以宏杉 Logo 的梅紫/藤紫/柔粉为主色，使用温暖米白底、深墨文字、统一的阴影、间距、触控高度和安全区；商品、价格、状态和主操作始终是首要信息。
- 保留原生 tabBar/导航、微信登录、客服 `open-type="contact"`、地区 picker 和现有路由。
- 优先把 FirstUI 组件包装在现有公共业务组件中：`empty`、`goods-card`、`stepper`、`sku-sheet`；将状态面板、标签、列表、弹层、数量输入和按钮语义收敛为一套可复用系统。
- 迁移次序：基础主题/公共组件 → 只读页试点 → 首页/分类/搜索/个人中心 → 商品/SKU/购物车 → 地址/结算/订单 → 售后/凭证/角色操作。

## 不得回归的业务契约

- Token Header 与 401 `reLaunch`；409 后刷新服务端权威状态。
- 结算与售后 `clientRequestId` 在失败重试时复用，仅成功后清理。
- 详情操作由服务端 `actorCapabilities`/角色决定；未知状态不暴露变更操作。
- 购物车同 SKU 增量累加，数量归零仍需确认。
- 地址 `version` 冲突和 `globalData + eventChannel` 双通道回传。
- 凭证数量/大小来自 capabilities，签名 URL 按需重取，售后传递 `APPLICATION`/`RETURN`/`REFUND` 类型。
- 上级线下退款确认即使理由为空也必须发送 JSON body；动态规则/内容/about 不回退为硬编码文案。

## 实施结果（2026-08-11）

- 实际按需 vendoring 9 个公开组件：`fui-badge`、`fui-bottom-popup`、`fui-button`、`fui-empty`、`fui-input-number`、`fui-list-cell`、`fui-loading`、`fui-loadmore`、`fui-tag`，以及原始 `fui-theme.wxss`。每个 vendored 组件都有真实 WXML 用途，未为装饰性注册保留未使用源码。
- `app.wxss` 以相对路径引入上游主题，再映射宏杉生物梅紫/柔粉/暖白/深墨 token；`app.json` 已移除 `style: "v2"`。
- 项目自有 `brand-shell`、`empty`、`goods-card`、`stepper`、`sku-sheet` 封装 FirstUI 并保留稳定业务事件。`brand-shell` 不使用纵向 overflow 或页内 scroll-view，避免破坏长列表与 `onReachBottom`。
- 24 个注册页面的 JSON/WXML/WXSS 全部迁移并真实使用 `brand-shell`；原生 tabBar/导航/客服/地区选择/上传/预览保留。
- `fui-input-number` 的 `{value,index,params}` 通过 `stepper` 适配回标量；`fui-tag` 的 `{index}` 通过 SKU wrapper 还原为原有 SKU 确认 payload。`fui-input`/`fui-tabs` 因与现有 handler detail 不兼容而未引入。
- 按 `project.config.json` `packOptions.ignore` 排除 `tests/` 和 `README.md` 后，主包源文件为 187 个、994122 bytes（0.948 MiB）；License 与版本说明继续随源码分发。
- `pnpm test:miniprogram` 现包含 37 项 API/运行时/幂等/FirstUI 事件/地址加载/库存闭合/角色门禁/签名凭证竞态/状态色/静态/包体契约。本地微信 `wcc`/`wcsc` 分别通过 38 个 WXML 与 40 个 WXSS；微信开发者工具已实际渲染首页、商品详情与个人中心，编辑器 Problems 为 0。
