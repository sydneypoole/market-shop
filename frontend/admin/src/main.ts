import { createApp } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import App from './App.vue'
import LoginView from './views/LoginView.vue'
import { adminNavigation } from './admin-navigation'
import { can, firstAllowedPath, loadAdminSession } from './session'
import '@vueup/vue-quill/dist/vue-quill.snow.css'
import './styles.css'

const protectedRoutes: RouteRecordRaw[] = adminNavigation.map(item => ({
  path: item.path,
  name: item.name,
  component: item.component,
  meta: {
    permission: item.permission,
    title: item.title,
    description: item.description,
    navigationGroup: item.group
  }
}))

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '后台登录' } },
    ...protectedRoutes,
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach(async to => {
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
