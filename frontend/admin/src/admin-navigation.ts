import type { Component } from 'vue'
import type { AdminIconName } from './components/admin/AdminIcon.vue'
import { hasAnyPermission, type AdminPermissionRequirement } from './navigation-permissions'

export type AdminNavigationGroupId =
  | 'workbench'
  | 'fulfillment'
  | 'merchandising'
  | 'growth'
  | 'governance'

export type AdminNavigationGroup = Readonly<{
  id: AdminNavigationGroupId
  label: string
}>

export type AdminNavigationItem = Readonly<{
  name: string
  path: string
  label: string
  shortLabel: string
  title: string
  description: string
  icon: AdminIconName
  group: AdminNavigationGroupId
  permissions: AdminPermissionRequirement
  component: Component
}>

export const adminNavigationGroups = [
  { id: 'workbench', label: '工作台' },
  { id: 'fulfillment', label: '交易履约' },
  { id: 'merchandising', label: '商品运营' },
  { id: 'growth', label: '会员增长' },
  { id: 'governance', label: '平台治理' }
] as const satisfies readonly AdminNavigationGroup[]

export const adminNavigation = [
  {
    name: 'dashboard', path: '/', label: '业务概览', shortLabel: '概览', icon: 'dashboard',
    title: '业务概览', description: '查看需要处理的交易、售后、库存与会员任务。',
    group: 'workbench', permissions: ['order:read'], component: () => import('./views/DashboardView.vue')
  },
  {
    name: 'orders', path: '/orders', label: '订单审核', shortLabel: '订单', icon: 'orders',
    title: '订单审核与发货', description: '核对订单快照、凭证、处理时间线并完成履约。',
    group: 'fulfillment', permissions: ['order:read'], component: () => import('./views/OrdersView.vue')
  },
  {
    name: 'after-sales', path: '/after-sales', label: '售后处理', shortLabel: '售后', icon: 'after-sales',
    title: '售后处理', description: '在完整申请、凭证和退货上下文中处理售后。',
    group: 'fulfillment', permissions: ['aftersale:review'], component: () => import('./views/AfterSalesView.vue')
  },
  {
    name: 'catalog', path: '/catalog', label: '商品与库存', shortLabel: '商品', icon: 'catalog',
    title: '商品、规格与库存', description: '分别维护商品资料、规格与可追溯库存调整。',
    group: 'merchandising', permissions: ['catalog:read'], component: () => import('./views/CatalogView.vue')
  },
  {
    name: 'content', path: '/content', label: '内容运营', shortLabel: '内容', icon: 'content',
    title: '内容运营', description: '维护商城内容草稿，预览后再执行发布或下线。',
    group: 'merchandising', permissions: ['content:write'], component: () => import('./views/ContentView.vue')
  },
  {
    name: 'members', path: '/members', label: '会员管理', shortLabel: '会员', icon: 'members',
    title: '会员管理', description: '查看会员关系、任务证据、等级轨迹与积分流水。',
    group: 'growth', permissions: ['member:read'], component: () => import('./views/MembersView.vue')
  },
  {
    name: 'rules', path: '/rules', label: '动态规则', shortLabel: '规则', icon: 'rules',
    title: '动态规则版本', description: '校验并比较规则草稿，确认影响后发布新版本。',
    group: 'growth', permissions: ['rule:publish'], component: () => import('./views/RulesView.vue')
  },
  {
    name: 'accounts', path: '/accounts', label: '账号权限', shortLabel: '账号', icon: 'accounts',
    title: '后台账号与权限', description: '管理后台账号、角色和需要再认证的敏感操作。',
    group: 'governance', permissions: ['admin:account:manage'], component: () => import('./views/AccountsView.vue')
  },
  {
    name: 'audit', path: '/audit', label: '审计日志', shortLabel: '审计', icon: 'audit',
    title: '审计日志', description: '按已应用条件检索和导出不可变操作记录。',
    group: 'governance', permissions: ['audit:read'], component: () => import('./views/AuditView.vue')
  },
  {
    name: 'settings', path: '/settings', label: '系统配置', shortLabel: '配置', icon: 'settings',
    title: '系统配置', description: '维护退货、库存预警和版本化运营策略。',
    group: 'governance', permissions: ['system:setting:manage', 'rule:publish'], component: () => import('./views/SettingsView.vue')
  }
] as const satisfies readonly AdminNavigationItem[]

export type AdminNavigationName = (typeof adminNavigation)[number]['name']

export function navigationItemForPath(path: string) {
  return adminNavigation.find(item => item.path === path)
}

export function navigationGroupLabel(groupId: AdminNavigationGroupId) {
  return adminNavigationGroups.find(group => group.id === groupId)?.label ?? '运营后台'
}

export function firstAllowedNavigationPath(check: (permission: string) => boolean) {
  return adminNavigation.find(item => hasAnyPermission(item.permissions, check))?.path ?? '/login'
}

export function navigationBreadcrumbs(path: string) {
  const item = navigationItemForPath(path)
  if (!item) return [] as Array<{ label: string; path?: string }>
  return [
    { label: navigationGroupLabel(item.group), path: undefined },
    { label: item.label, path: item.path }
  ] satisfies Array<{ label: string; path?: string }>
}
