# 后台运营工作台技术设计

## 目标结构

```text
frontend/admin/src/
├── admin-navigation.ts       # 路由、导航、权限与页面元数据单一来源
├── api.ts                    # AdminApiError、envelope 与共享请求类型
├── components/
│   ├── admin/                # 无领域状态的后台 UI 原语
│   └── ...                   # 素材、富文本、模板等领域组件
└── views/                    # 路由编排、领域类型、请求与状态机
```

## 组件边界

- `PageHeader`：标题、说明、面包屑槽和主操作槽。
- `FilterBar`：常用筛选、高级筛选、查询/重置；不拥有请求参数语义。
- `TableFrame`：加载、错误、空状态和横向容器；列与行仍由页面模板定义。
- `StatusTag`：只处理 success/warning/danger/neutral 视觉语义，领域中文由 localization 提供。
- `BaseDialog` / `DetailDrawer`：Teleport、焦点、Escape、滚动锁、提交关闭保护和响应式形态。
- `BusinessActionDialog`：对象、影响、原因、再认证等通用布局；请求与业务校验由页面传入。
- `ToastRegion`：非阻断成功/失败反馈；页面级加载失败仍使用 InlineAlert。

## 状态规则

- 每页区分 `pageLoading`、`detailLoading`、`actionSubmitting`，不复用一个全局 busy 掩盖状态来源。
- 切换详情对象时先清空附属数据，并以请求序号拒绝过期响应。
- 筛选使用 draft/applied 两份状态；URL 和导出只使用 applied。
- 所有状态变更成功后重新请求服务端权威数据；409 显示冲突并刷新。
- 密码、临时密码、短期凭证 URL 在关闭或成功后立即清空。

## 页面迁移

1. Dashboard：权限化工作队列、深链筛选、加载/错误/空状态。
2. Orders：筛选快照、详情抽屉内处理、发货弹窗、批量发货结果与失败保留。
3. AfterSales：详情抽屉内审核/验收、退货地址核对、凭证隔离。
4. Members/Audit：统一筛选与详情；敏感状态/重算、审计 diff 与导出说明。
5. Catalog：商品资料与库存动作分离，分类/商品/流水明确分区。
6. Content/Rules/Templates：草稿、预览/校验、差异与发布确认、离开保护。
7. Accounts/Settings：账号与角色分区，统一敏感操作；策略加载失败锁定与发布后回读。

## 验证

- 静态回归：禁止浏览器原生弹窗、路由权限单一来源、中文枚举保持不变。
- 组件行为：Dialog/Drawer 的焦点、Escape、取消、提交锁与秘密字段清理。
- 命令：后台测试与类型检查；全前端测试、类型检查和生产构建。
- 视口：1440、1024、768、390 像素，无不可达操作或页面级横向溢出。
