<script setup lang="ts">
import { onMounted, ref } from 'vue'
import StorefrontRenderer from '../components/StorefrontRenderer.vue'
import { useShopStore } from '../stores/shop'

const shop = useShopStore()
const error = ref('')

async function load() {
  error.value = ''
  try {
    await shop.loadStorefront()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '商城内容加载失败'
  }
}

onMounted(load)
</script>

<template>
  <div>
    <p v-if="error" class="home-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" @click="load">重新加载</button>
    </p>
    <StorefrontRenderer
      :template="shop.storefrontTemplate"
      :products="shop.products"
      :contents="shop.contents"
      :categories="shop.categories"
      :loading="shop.loadingStorefront"
    />
  </div>
</template>

<style scoped>
.home-error{width:min(calc(100% - 28px),1380px);display:flex;align-items:center;justify-content:space-between;gap:18px;margin:14px auto 0;padding:12px 16px;color:#8f3529;border:1px solid #eac5be;border-radius:12px;background:#fff0ed}
.home-error button{padding:0;color:inherit;border:0;border-bottom:1px solid currentColor;background:transparent;font-weight:750}
</style>
