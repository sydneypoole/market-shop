# 宏杉生物 · 微信小程序

原生微信小程序（无 npm、无前端构建链）是会员唯一入口，消费后端 `/api/v1`。管理后台仍位于 PC Web；本项目不发起在线支付，订单按线下收款、直属上级确认、后台审核发货、用户确认收货的流程推进。

## 导入微信开发者工具

1. 打开微信开发者工具，选择「导入项目」。
2. 项目目录选择仓库中的 `miniprogram/`，不是仓库根目录。
3. 日常开发可以使用项目配置中的开发 AppID；体验版和正式版必须确认使用已认证主体下的真实 AppID。
4. `AppSecret` 只配置在服务端运行环境，禁止写入小程序源码、`project.config.json`、extConfig、GitHub Actions 日志或 Git 仓库。
5. 共享的 `project.config.json` 默认开启合法域名校验；本地联调可在个人配置中临时关闭，体验版和正式版发布前必须开启并通过真机验证。

## 品牌资产

- 小程序内置 Logo 位于 `assets/brand/logo.png`，登录页和注册页使用该本地品牌资产；“我的”页显示服务端权威会员头像，缺失或加载失败时使用昵称首字，不用品牌 Logo 冒充用户头像。
- 小程序与后台运营平台的对外名称统一为“宏杉生物”。`market-shop` 包名、Token 名和部署资源名是兼容性技术标识，不随 UI 品牌改名。
- 微信公众平台的小程序名称与头像不由代码仓库设置；提审前需在平台端核对名称“宏杉生物”并上传同一 Logo。

## FirstUI 开源 UI 基础

- UI 基础固定为 FirstUI-weixin 公开版 V2.4.0，commit `fa7863720afcf591aaf3ba6de29c42a88c6dde80`，按 Apache License 2.0 使用。
- 按需引入的原始组件位于 `components/firstui/`；完整许可证和版本说明分别见 `components/firstui/LICENSE` 与 `components/firstui/UPSTREAM.md`。该目录内的上游组件保持原样，项目修改放在业务 wrapper 和 `app.wxss` 主题映射中。
- 页面图标统一使用原版 `fui-icon`。原生 tabBar 使用同一图标字体生成的 8 张轮廓/填充 PNG；安装 Pillow 后执行 `python3 scripts/generate-miniprogram-tab-icons.py` 可同步刷新运行时资源与设计留档。
- 工程仍为无 npm 构建链的原生小程序；不包含 FirstUI VIP 组件。上传、时间线、导航、客服和地区选择继续使用项目组件或微信原生能力。

## Taste 重设计约束

- 项目已从 [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill) 本地安装 `design-taste-frontend`，入口为 `.agents/skills/design-taste-frontend/SKILL.md`；`skills-lock.json` 固定 GitHub 来源、skill 路径和内容哈希。
- Design Read：面向微信用户的“宏杉生物”原生商城，使用冷珍珠/烟灰中性色与单一梅紫强调色，强调可信、克制、易读的精品商城质感。
- 设计档位固定为 `DESIGN_VARIANCE: 6`、`MOTION_INTENSITY: 4`、`VISUAL_DENSITY: 5`。25 个注册页面统一使用系统中文无衬线字体、共享品牌 token、FirstUI 单一图标体系和节制动效。
- 重设计只调整信息层级与呈现，不改变订单、线下收款、直属上级确认、后台审核/发货、售后、凭证、登录或动态规则等业务契约。完整设计约束与验收表见 `../docs/design/miniprogram/taste-redesign.md`。

## API 环境与 extConfig

`utils/config.js` 每次请求根据 `wx.getAccountInfoSync().miniProgram.envVersion` 选择 API Origin，并移除末尾 `/`。Origin 不包含 `/api/v1`，实际请求为 `{apiBaseUrl}/api/v1/...`。

| `envVersion` | 默认 API Origin | 约束 |
|---|---|---|
| `develop` | `http://localhost:8080` | 仅开发者工具/本机联调；可以被 extConfig 覆盖 |
| `trial` | `https://shop.cllbmz.kdns.fr` | 必须是 HTTPS |
| `release` | `https://shop.cllbmz.kdns.fr` | 必须是 HTTPS |

平台或开发者工具可通过 `wx.getExtConfigSync()` 返回的 `apiBaseUrl` 覆盖默认值，例如本地 `ext.json`：

```json
{
  "extEnable": true,
  "ext": {
    "apiBaseUrl": "https://shop.example.com"
  }
}
```

- `apiBaseUrl` 只允许填写 Origin，例如 `https://shop.example.com`，不要附加 `/api/v1`、查询参数或密钥。
- `trial`、`release` 的 HTTP 覆盖值会被拒绝，避免体验版或正式版误连明文接口。
- 修改域名后应重新执行契约测试，并检查商品图片、订单凭证和售后凭证的相对 `/api/...` 地址均被解析到同一个 Origin。

## 微信后台合法域名

在[微信公众平台](https://mp.weixin.qq.com)「开发管理 → 开发设置 → 服务器域名」中配置实际 HTTPS Origin：

- **request 合法域名**：登录、商品、购物车、订单、会员和规则接口；
- **uploadFile 合法域名**：会员头像、订单及售后凭证上传；
- **downloadFile 合法域名**：会员头像、凭证预览/下载（使用时）；
- 如启用微信原生客服，同时在对应小程序主体下完成客服能力和人员配置。

域名必须使用有效公网 HTTPS 证书，不填写路径，不使用自签名证书，也不要依赖开发者工具的「不校验合法域名」设置完成发布验收。

## 微信登录与服务端环境

正式环境通过部署系统注入：

```dotenv
MARKET_SHOP_WECHAT_ENABLED=true
MARKET_SHOP_WECHAT_MOCK_ENABLED=false
MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID=wx真实AppID
MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET=服务端密钥
```

本地/CI 可设置 `MARKET_SHOP_WECHAT_MOCK_ENABLED=true`，无需真实微信密钥。首次普通会员注册仍需有效邀请码；发起人首次认领可以提交一次性的 `sponsorClaimSecret`，普通登录不需要该字段。

身份入口拆分为两个原生页面：登录页只提交 `{code}`；注册页的邀请码模式提交 `{code, inviteCode}`，发起人认领模式提交 `{code, sponsorClaimSecret}`。两种注册凭证不会同时提交；获得 Token 后留在注册页，使用 `chooseAvatar`、`input type="nickname"` 和 `getPhoneNumber` 分阶段完善资料。登录页和注册页均可直接返回商城首页。

### 微信注册资料与隐私

- 注册页必须先明确勾选同意，再由 `wx.requirePrivacyAuthorize` 确认微信隐私授权；页面的《用户隐私保护指引》入口通过 `wx.openPrivacyContract` 打开平台权威文档。
- `app.json` 保持 `"__usePrivacyCheck__": true`，体验版与正式版必须使用已在微信公众平台生效的隐私保护指引。
- 手机号能力只提交 `getPhoneNumber` 返回的一次性动态 code，由服务端向微信换取并仅返回脱敏号码；小程序不读取、传输、存储或记录原始手机号。
- 权威资料和头像分两步保存：资料接口成功后，头像失败只重试 multipart 头像上传；资料失败后需重新获取手机号 code，不重放旧 code、邀请码或发起人密钥。
- `getPhoneNumber` 需要符合微信要求的非个人且已认证主体，微信平台可能按最新规则对调用收费；上线前由运营人员核对主体资质、额度和费用。

## 会话契约

- Storage key 和请求头名均为 `market-shop-user-token`。
- `utils/request.js` 对受保护请求自动注入 Header，不依赖 Cookie。
- HTTP 401 或稳定错误码 `NOT_LOGGED_IN` 会清除本地 token，并 `reLaunch` 到登录页。
- API 错误保留 HTTP status 与稳定错误码；页面应显示重试/冲突后的服务端权威状态，不把失败伪装成空数据或成功。

## 自动化门禁

小程序不需要安装 npm 依赖，仓库根目录执行：

```bash
pnpm test:miniprogram
```

门禁检查 JavaScript/JSON/WXML 与页面/组件/静态资源一致性，并执行 API path、method、body、Token Header、401 清理跳转、环境选择及相对媒体 URL 消费者契约；同时检查 Taste token、25 页 FirstUI 图标注册、tab PNG 规格/同源性以及上传源文件包体。设计改动目标小于 1.4 MiB，主包始终必须小于微信 2 MiB 限制。GitHub Actions 的镜像构建和发布依赖该门禁及 runtime smoke；runtime smoke 会用 mock 登录 token 的 `market-shop-user-token` Header 访问受保护接口，不使用 Cookie 会话。

## 体验版/正式版发布清单

- [ ] `pnpm test:miniprogram`、后端 Maven 测试、Admin 测试/type-check/build 全部通过。
- [ ] 微信公众平台与开发者工具中的名称为“宏杉生物”，小程序头像与 `assets/brand/logo.png` 一致。
- [ ] 微信开发者工具使用仓库 `miniprogram/` 编译无错误，未忽略上传文件。
- [ ] AppID 与服务端 `MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID` 属于同一小程序；Secret 仅存在于部署密钥系统。
- [ ] 体验版/正式版 `apiBaseUrl` 为预期 HTTPS Origin，request/uploadFile/downloadFile 合法域名均已生效；关闭开发者工具「不校验合法域名」后在真机分别验证资料 request、头像 uploadFile 和头像/凭证 downloadFile。
- [ ] 微信公众平台「用户隐私保护指引」明确声明：昵称与头像用于会员资料展示，手机号用于账号联系与订单履约；同时核对 `getPhoneNumber` 主体认证、额度与可能费用。
- [ ] 关闭开发者工具「不校验合法域名」后，分别验证已有会员登录、新会员邀请码注册、昵称/头像/手机号完善及可选发起人认领。
- [ ] 在真机完成：商品 → SKU → 购物车 → 地址 → 提交订单 → 上级确认 → 后台审核/发货 → 用户收货。
- [ ] 在真机完成售后申请、阶段凭证上传、退货物流、上级线下退款确认、用户确认退款。
- [ ] 验证 local 与 RustFS 两种存储下的商品图片、订单/售后凭证缩略图、预览和下载；数量与大小限制以 capabilities 为准。
- [ ] 验证 token 失效后清理并回到登录页，409 冲突后页面刷新为服务端状态，重复提交不会产生重复订单。
- [ ] 原生微信客服入口可用；会员头像/昵称/手机号的用途、服务类目和审核材料已在公众平台配置。
- [ ] 上传体验版并至少完成一轮不同网络/机型真机回归；确认版本号和发布备注后再提交审核、全量发布。

## 目录速览

```text
miniprogram/
  app.js / app.json / app.wxss
  utils/       config · request · format · order-status · aftersale-status
  api/         auth · catalog · cart · order · address · member · aftersale · notify · rules · system
  components/  brand-shell · goods-card · stepper · empty · sku-sheet · firstui/
  pages/       登录 / 注册 / 首页 / 分类 / 购物车 / 我的 / 商品 / 订单 / 地址 / 会员 / 售后 / 规则 …
  tests/       静态检查与消费者契约
  assets/      brand/logo.png · tab/
```
