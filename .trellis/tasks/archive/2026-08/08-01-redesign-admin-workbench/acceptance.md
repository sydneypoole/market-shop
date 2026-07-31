# 后台运营工作台重设计验收记录

## 完成范围

- 全部 11 个受保护后台页面和登录页完成统一工作台迁移。
- 新增单源 typed navigation registry，派生路由、导航、面包屑、权限与默认落点。
- 新增 PageHeader、FilterBar、TableFrame、StatusTag、BaseDialog、DetailDrawer、BusinessActionDialog、InlineAlert、ToastRegion 等共享原语。
- 完成四项 P0：附件/流水陈旧响应隔离、商品与库存动作拆分、规则加载失败锁定、账号秘密字段全生命周期清理。
- 订单、售后、会员、审计、商品、内容、规则、账号、设置和模板工作室的高频流程均改为结构化操作。
- 原生 `prompt`、`confirm`、`alert` 调用归零；旧 `modal-mask` 和 `page-title` 页面壳归零。
- 390px 窄屏下筛选单列化、数据行卡片化、抽屉/弹窗全屏化，保留核心动作。

## 独立审查修复

- 已发布内容必须先显式下线，不能通过“保存草稿”静默改变线上状态。
- 模板未保存改动覆盖编辑器返回、SPA 路由离开和浏览器关闭/刷新。
- `ORDER_TIMERS` 只在系统配置发布，规则页不再形成第二入口。
- 规则/设置发布后必须成功回读权威版本；回读失败时锁定重复提交。
- 订单/审计导出统一通过认证下载客户端处理 401/403。
- Dialog 初始即打开时也执行聚焦、焦点圈定和滚动锁；敏感对象切换立即清除密码。
- 批量发货成功后同步刷新列表与当前详情；部分失败只保留失败项。

## 验证结果

```text
pnpm test:web       PASS（storefront 7 + admin 15 = 22）
pnpm typecheck:web  PASS
pnpm build:web      PASS
git diff --check    PASS
```

额外源码回归：原生浏览器弹窗、类型绕过、原始管理端导出请求扫描均为 0。

## 部署与迁移

- 无数据库迁移、后端 API 变更或环境变量新增。
- 发布时按现有 GitHub Workflow/Docker 构建流程重新生成镜像即可。
- 浏览器端旧页面状态不需要迁移；筛选状态改由 URL query 表达。

## 回滚

- 回滚到本任务之前的前端提交或上一个 Docker 镜像标签即可。
- 后端协议、数据库和持久化数据未变化，回滚不需要数据修复。
