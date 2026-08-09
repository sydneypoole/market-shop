# PRD：微信小程序接口闭环与上线门禁

## 背景

用户端最终形态是原生微信小程序，不恢复 PC/H5 商城与多模板系统。当前页面和 API wrapper 已覆盖大部分业务，但代码审计发现下单、售后退款、凭证预览、动态规则与上线验证仍有断点。

## 目标

1. 打通“微信登录 → 商品/购物车 → 提交订单 → 上级确认 → 后台审核/发货 → 用户收货 → 售后”闭环。
2. 小程序以 `market-shop-user-token` Header 作为主会话传输方式。
3. 公开配置、上传限制和存储 URL 兼容 local/RustFS，不在小程序中复制后台动态规则。
4. 把小程序消费者契约与核心 Header 链路纳入 CI，防止再次漂移。

## 功能与契约要求

### P0：交易与售后阻断

- 后端订单来源接受 `MINIPROGRAM`，并保持 `H5`/`WEB` 兼容。
- 订单备注作为真实契约字段：小程序提交、后端校验/持久化、订单详情可读；使用 Flyway 迁移，不直接改库。
- “上级确认线下退款”必须发送符合后端 `ConfirmRefundRequest` 的 JSON body，可选原因不得造成缺失 body。
- 客户端保留 HTTP status 与稳定错误码；409 冲突后刷新服务端权威状态。
- 下单重复点击和网络重试复用同一个 `clientRequestId`，成功后才清理。

### P0：运行时与凭证

- API Base URL 按微信 `envVersion` 选择，支持 extConfig 覆盖；开发环境可 localhost，trial/release 必须是 HTTPS，且不包含密钥。
- 对后端返回的 `/api/...` 相对媒体地址统一补全 Base URL，订单与售后凭证的缩略图、预览、下载均使用同一解析器。
- 订单/售后凭证数量与大小限制来自服务端 capabilities，不硬编码 3 张。
- 售后凭证按所在阶段传递 `APPLICATION` / `RETURN` / `REFUND` `proofType`。

### P1：小程序功能完整性

- 登录支持可选 `sponsorClaimSecret`，不影响普通邀请码登录。
- 个人中心提供退出登录和上级待确认订单入口。
- 首页内容卡片可进入内容详情，复用后端 `/content/{id}`。
- 客服入口使用微信原生 `open-type="contact"`，不再显示“暂未开放”。
- 加购已存在 SKU 时增量累加，不覆盖原数量。
- 核心页面不得把请求失败伪装为成功/空数据；必须有加载、错误、重试或可理解提示。

### P1：品牌资产与名称统一

- 小程序与后台运营平台的用户可见品牌名统一为“宏杉生物”，不再显示“拾光优选”或演示版名称。
- 用户提供的 `logo.png` 作为品牌源文件，小程序登录页、个人中心品牌占位以及后台登录/侧边栏使用仓库内本地副本，不依赖外部 URL。
- `/api/v1/system/about` 的默认平台名与前端 fallback 必须一致为“宏杉生物”；内部包名、Token 名、Docker 资源名仍保持 `market-shop` 以避免破坏兼容性。
- 微信公众平台中的小程序名称和头像是发布环境配置，仓库发布清单需明确使用“宏杉生物”和同一 Logo 核对。

### P1：自动化验收

- 添加无需真实微信密钥的小程序静态/消费者契约测试，覆盖 API path/method/body、Token Header、401 清理与跳转、相对 URL 解析。
- GitHub Actions 必须执行小程序门禁，失败时不得构建/发布镜像。
- Runtime smoke 在 mock 小程序登录后，使用 `market-shop-user-token` Header 访问至少一个受保护资源，证明 Header 会话而非 Cookie 会话。
- 保留真实微信 AppID/Secret、合法域名、微信开发者工具/真机的发布检查清单，不将私钥提交仓库。

## 质量与架构约束

- 后端继续遵循 DDD/凤凰架构依赖方向；Controller 不直连 Mapper，业务不落在 SQL/Controller。
- 数据库变更只能通过 Flyway。
- 继续保持无在线支付、线下收款与直属上级确认流程。
- 不恢复 `frontend/storefront`、Web OAuth 或模板 CMS。
- 运维脚本必须同时通过 `bash -n` 与 ShellCheck；容器内命令及 jq 变量使用带原因的逐命令 SC2016 指令保留延迟展开，不得在宿主 shell 中插值密钥。
- 备份校验和生成必须分离：先完成文件枚举和哈希计算，再同文件系统原子替换 `SHA256SUMS`。
- 不修改/提交与本任务无关的 `pencil-new.pen` 及既有未跟踪文件。

## 验收标准

1. Java 单测与项目 Maven 验证通过。
2. 小程序 JS/JSON 静态检查和新增契约测试通过。
3. Admin 现有 type-check/test/build 不回归。
4. Runtime smoke 证明 mock code2session 返回 token，且 Header 可访问受保护 API。
5. Docker/compose 配置验证通过，无真实密钥落库。
6. 小程序、后台运营平台和公开 about 接口统一显示“宏杉生物”，所有品牌 Logo 位使用用户提供的本地 PNG 资产。
7. `shellcheck scripts/*.sh scripts/ops/*.sh` 零告警，且 `ops_write_manifest` 对旧清单、空目录和带空格文件名都能生成可验证的原子清单。

## 非代码依赖

- 真实 AppID/Secret、微信合法域名、业务域名 TLS 和微信后台客服能力由部署环境注入/配置；仓库只提供可验证的代码与检查清单。
