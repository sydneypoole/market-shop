<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useShopStore } from './stores/shop'

const route = useRoute()
const shop = useShopStore()
const hideShell = computed(() => route.path === '/login')
</script>

<template>
  <div class="app-shell">
    <header v-if="!hideShell" class="topbar">
      <RouterLink class="brand" to="/">
        <span class="brand-mark">拾</span>
        <span><strong>拾光优选</strong><small>线下确认 · 安心履约</small></span>
      </RouterLink>
      <nav class="desktop-nav" aria-label="主导航">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/orders">订单</RouterLink>
        <RouterLink to="/after-sales">售后</RouterLink>
        <RouterLink to="/membership">会员中心</RouterLink>
        <RouterLink to="/notifications">通知</RouterLink>
        <RouterLink to="/rules">规则说明</RouterLink>
      </nav>
      <RouterLink class="cart-pill" to="/cart">购物车 <b>{{ shop.cartCount }}</b></RouterLink>
    </header>

    <main :class="{ 'with-nav': !hideShell }">
      <RouterView />
    </main>

    <nav v-if="!hideShell" class="mobile-nav" aria-label="移动端导航">
      <RouterLink to="/"><span>⌂</span>首页</RouterLink>
      <RouterLink to="/cart"><span>◫</span>购物车</RouterLink>
      <RouterLink to="/orders"><span>≡</span>订单</RouterLink>
      <RouterLink to="/membership"><span>◎</span>我的</RouterLink>
    </nav>
  </div>
</template>
