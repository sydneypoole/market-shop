<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useShopStore } from './stores/shop'

const route = useRoute()
const shop = useShopStore()
const hideShell = computed(() => route.path === '/login')

onMounted(() => {
  if (route.path !== '/' && !shop.storefrontTemplate) {
    void shop.loadStorefront().catch(() => undefined)
  }
})
</script>

<template>
  <div class="app-shell">
    <header v-if="!hideShell" class="topbar">
      <RouterLink class="brand" to="/">
        <span class="brand-mark" aria-hidden="true">拾</span>
        <span class="brand-copy"><strong>拾光优选</strong><small>SHIGUANG SELECT</small></span>
      </RouterLink>
      <nav class="desktop-nav" aria-label="主导航">
        <RouterLink to="/">本期精选</RouterLink>
        <a href="/#products">全部商品</a>
        <RouterLink to="/membership">会员礼遇</RouterLink>
        <RouterLink to="/orders">订单履约</RouterLink>
        <RouterLink to="/rules">规则透明</RouterLink>
      </nav>
      <div class="header-actions">
        <RouterLink class="orders-link" to="/notifications" aria-label="通知中心">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></svg>
        </RouterLink>
        <RouterLink class="cart-pill" to="/cart" aria-label="购物车">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 4h2l2.1 10.2a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 2-1.6L20 7H6M9 20h.01M17 20h.01" /></svg>
          <span>购物袋</span><b>{{ shop.cartCount }}</b>
        </RouterLink>
      </div>
    </header>

    <main>
      <RouterView />
    </main>

    <footer v-if="!hideShell" class="site-footer">
      <div>
        <RouterLink class="footer-brand" to="/"><span>拾</span><b>拾光优选</b></RouterLink>
        <p>把值得信任的日常好物，认真送到每一位会员手中。</p>
      </div>
      <div class="footer-links">
        <RouterLink to="/orders">订单履约</RouterLink>
        <RouterLink to="/after-sales">售后服务</RouterLink>
        <RouterLink to="/rules">会员规则</RouterLink>
        <RouterLink to="/membership">会员中心</RouterLink>
      </div>
      <small>线下收款 · 平台审核 · 全程留痕</small>
    </footer>

    <nav v-if="!hideShell" class="mobile-nav" aria-label="移动端导航">
      <RouterLink to="/">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 11 9-8 9 8v9H6v-9" /></svg>
        首页
      </RouterLink>
      <RouterLink to="/cart">
        <span class="nav-icon">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 4h2l2 10h11l2-7H6M9 20h.01M17 20h.01" /></svg>
          <b v-if="shop.cartCount">{{ shop.cartCount }}</b>
        </span>
        购物袋
      </RouterLink>
      <RouterLink to="/orders">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h10v4H7zM5 7h14v14H5zM8 12h8M8 16h5" /></svg>
        订单
      </RouterLink>
      <RouterLink to="/membership">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="4" /><path d="M4 21c.7-4 3.4-6 8-6s7.3 2 8 6" /></svg>
        我的
      </RouterLink>
    </nav>
  </div>
</template>
