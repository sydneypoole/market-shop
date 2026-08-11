# 微信注册资料与后台会员展示审计（2026-08-12）

## 已确认事实

- `jscode2session` 只返回 `openid` / `unionid` / `session_key`，不会返回头像、昵称或手机号。
- 头像昵称应使用微信当前开放能力：`button open-type="chooseAvatar"` 与原生 `input type="nickname"`。头像回调只给临时文件路径，必须上传到自有存储；昵称属于用户选择/填写的会员资料，不是服务端可证明的微信实名资料。
- 手机号必须由用户点击 `button open-type="getPhoneNumber"` 获取一次性动态 `code`；该 code 与 `wx.login` code 不同，约五分钟有效且只能由服务端消费一次。客户端不得提交或伪造原始手机号。
- 微信公众平台必须声明收集头像、昵称和手机号；注册页应在资料控件启用前处理隐私授权并提供查看隐私保护指引入口。
- 快速手机号能力需要非个人且已认证的小程序主体，并可能产生微信平台调用费用。

## 仓库现状

- 小程序注册页目前只提交邀请码或发起人认领密钥；登录页应继续保持纯 `wx.login` code。
- `iam_user_account` 已有 `nickname`、`avatar_url`、`phone_masked`，但真实小程序登录将昵称/头像置空，手机号没有读写链路。
- 后台会员列表和详情只投影昵称；`MemberSummary`、MyBatis 行模型/SELECT 与 `MembersView.vue` 都缺少头像和手机号字段。
- V11 的 `chk_bootstrap_claim_transition_data` 仅允许 `WECHAT_H5` / `WECHAT_WEB`，与当前真实小程序认领写入 `WECHAT_MP` 冲突，必须通过新的前向 Flyway 迁移修复。

## 实施契约

1. 登录仍只交换 `wx.login` code；注册成功取得 Token 后，在同一注册页继续完成资料，不把资料采集重新塞回登录页。
2. 新增受会员会话保护的微信资料接口：昵称 + phone code 由后端验证并保存；手机号只保存和返回后端生成的脱敏值，不把完整号码放入分页、会话、日志或前端自行遮罩。
3. 新增受会员会话保护的头像 multipart 上传接口和 identity 语义的头像存储 port；local / S3(RustFS) 两种 provider 都要支持。复用现有图片真实类型检查与元数据清洗，头像 URL 指向自有稳定公开读取端点。
4. 注册页必须保留阶段状态。账号已创建后，资料或头像失败只重试当前阶段，不重放已消费的 phone code、邀请码或一次性认领密钥。
5. 后台列表显示会员头像、注册昵称和脱敏手机号；详情提供微信注册资料区。头像失败使用昵称首字降级，禁止用品牌 Logo 冒充会员头像。
6. 小程序“我的”同步使用权威会员头像和昵称；现有会员资料为空时继续兼容品牌占位。

## 关键测试

- 小程序：隐私同意/拒绝、chooseAvatar、nickname、getPhoneNumber、资料齐全校验、重复点击、阶段重试、返回首页；payload 不含原始手机号或临时头像 URL。
- 后端：phone code 成功/过期/上游失败与 access-token 缓存；昵称/手机号验证；头像类型/大小/存储失败；资料更新后权威读取；接口鉴权。
- Flyway：V1→最新和 V14→最新升级，nullable 旧会员兼容，`WECHAT_MP` 发起人认领约束可通过。
- 后台：头像 fallback、手机号空值/脱敏值、列表与详情字段、响应式布局、type-check/build。
