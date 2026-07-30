import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import ProductView from './views/ProductView.vue'
import CartView from './views/CartView.vue'
import CheckoutView from './views/CheckoutView.vue'
import OrdersView from './views/OrdersView.vue'
import MembershipView from './views/MembershipView.vue'
import RulesView from './views/RulesView.vue'
import AddressesView from './views/AddressesView.vue'
import AfterSalesView from './views/AfterSalesView.vue'
import NotificationsView from './views/NotificationsView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import AfterSaleDetailView from './views/AfterSaleDetailView.vue'
import ContentDetailView from './views/ContentDetailView.vue'
import { requireUserSession } from './session'
import './styles.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/login', component: LoginView },
    { path: '/products/:id', component: ProductView },
    { path: '/content/:id', component: ContentDetailView },
    { path: '/cart', component: CartView, meta: { requiresAuth: true } },
    { path: '/checkout', component: CheckoutView, meta: { requiresAuth: true } },
    { path: '/orders', component: OrdersView, meta: { requiresAuth: true } },
    { path: '/orders/:id', component: OrderDetailView, meta: { requiresAuth: true } },
    { path: '/membership', component: MembershipView, meta: { requiresAuth: true } },
    { path: '/addresses', component: AddressesView, meta: { requiresAuth: true } },
    { path: '/after-sales', component: AfterSalesView, meta: { requiresAuth: true } },
    { path: '/after-sales/:id', component: AfterSaleDetailView, meta: { requiresAuth: true } },
    { path: '/notifications', component: NotificationsView, meta: { requiresAuth: true } },
    { path: '/rules', component: RulesView }
  ],
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return true
  return requireUserSession(to.fullPath)
})

createApp(App).use(createPinia()).use(router).mount('#app')
