import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import OrdersView from './views/OrdersView.vue'
import CatalogView from './views/CatalogView.vue'
import RulesView from './views/RulesView.vue'
import AfterSalesView from './views/AfterSalesView.vue'
import MembersView from './views/MembersView.vue'
import ContentView from './views/ContentView.vue'
import AccountsView from './views/AccountsView.vue'
import AuditView from './views/AuditView.vue'
import SettingsView from './views/SettingsView.vue'
import TemplatesView from './views/TemplatesView.vue'
import { can, firstAllowedPath, loadAdminSession } from './session'
import '@vueup/vue-quill/dist/vue-quill.snow.css'
import './styles.css'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', component: DashboardView, meta: { permission: 'order:read' } },
    { path: '/orders', component: OrdersView, meta: { permission: 'order:read' } },
    { path: '/catalog', component: CatalogView, meta: { permission: 'catalog:read' } },
    { path: '/rules', component: RulesView, meta: { permission: 'rule:publish' } },
    { path: '/after-sales', component: AfterSalesView, meta: { permission: 'aftersale:review' } },
    { path: '/members', component: MembersView, meta: { permission: 'member:read' } },
    { path: '/content', component: ContentView, meta: { permission: 'content:write' } },
    { path: '/templates', component: TemplatesView, meta: { permission: 'storefront:template:manage' } },
    { path: '/accounts', component: AccountsView, meta: { permission: 'admin:account:manage' } },
    { path: '/audit', component: AuditView, meta: { permission: 'audit:read' } },
    { path: '/settings', component: SettingsView, meta: { permission: 'system:setting:manage' } }
  ]
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  try {
    await loadAdminSession()
    const permission = typeof to.meta.permission === 'string' ? to.meta.permission : undefined
    if (!can(permission)) return firstAllowedPath()
    return true
  } catch {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})

createApp(App).use(router).mount('#app')
