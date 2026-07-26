<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { money } from '../api'
import { useShopStore } from '../stores/shop'

const shop = useShopStore()
const router = useRouter()
const error = ref('')
const busySkuId = ref<number>()
const total = computed(() => shop.selectedItems.reduce((sum, item) => sum + item.priceFen * item.quantity, 0))

async function load() {
  error.value = ''
  try {
    await shop.loadCart()
  } catch (cause) {
    error.value = (cause as Error).message
  }
}

async function update(skuId: number, quantity: number, selected: boolean) {
  if (busySkuId.value) return
  busySkuId.value = skuId
  error.value = ''
  try {
    await shop.setCart(skuId, quantity, selected)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busySkuId.value = undefined
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="section-head">
      <div><span class="eyebrow">Shopping Bag</span><h1>购物车</h1><p>勾选需要提交给直属上级确认的商品。</p></div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="shop.loadingCart" class="empty card" aria-busy="true">正在加载购物车…</div>
    <div v-else-if="shop.cart.length" class="cart-layout">
      <section class="cart-list card">
        <article v-for="item in shop.cart" :key="item.id" class="cart-item">
          <input :checked="item.selected" :disabled="Boolean(busySkuId)" type="checkbox" :aria-label="`选择${item.productName}`" @change="update(item.skuId, item.quantity, !item.selected)" />
          <div class="thumb">{{ item.productName.slice(0, 2) }}</div>
          <div class="item-copy"><h3>{{ item.productName }}</h3><p>{{ item.skuName }}</p><span class="price">{{ money(item.priceFen) }}</span></div>
          <div class="stepper">
            <button :disabled="Boolean(busySkuId)" @click="update(item.skuId, Math.max(0, item.quantity - 1), item.selected)">−</button>
            <b>{{ item.quantity }}</b>
            <button :disabled="Boolean(busySkuId) || item.quantity >= item.inventory" @click="update(item.skuId, Math.min(item.inventory, item.quantity + 1), item.selected)">＋</button>
          </div>
        </article>
      </section>
      <aside class="summary card">
        <h2>订单摘要</h2>
        <p><span>已选商品</span><b>{{ shop.selectedItems.length }} 种</b></p>
        <p><span>商品合计</span><b>{{ money(total) }}</b></p>
        <p><span>支付方式</span><b>线下收款</b></p>
        <div class="notice">提交后不会跳转支付页面，请等待直属上级确认。</div>
        <button class="primary" :disabled="!shop.selectedItems.length || Boolean(busySkuId)" @click="router.push('/checkout')">去提交订单</button>
      </aside>
    </div>
    <div v-else class="empty card"><h2>购物车还是空的</h2><p>从精选组合里挑选一份适合自己的商品吧。</p><RouterLink class="primary" to="/">去逛逛</RouterLink></div>
  </div>
</template>

<style scoped>
.cart-layout { display: grid; grid-template-columns: 1fr 330px; gap: 20px; align-items: start; }
.cart-list { padding: 8px 22px; }
.cart-item { display: grid; grid-template-columns: auto 92px 1fr auto; gap: 16px; align-items: center; padding: 20px 0; border-bottom: 1px solid var(--line); }
.cart-item:last-child { border: 0; }
.cart-item input { width: 18px; height: 18px; accent-color: var(--coral); }
.thumb { display: grid; place-items: center; width: 92px; height: 92px; border-radius: 16px; color: white; background: #3d695b; font: 700 24px serif; }
.item-copy h3, .item-copy p { margin: 0 0 7px; }
.item-copy p { color: var(--muted); font-size: 13px; }
.item-copy .price { font-size: 18px; }
.stepper { display: flex; border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.stepper button { width: 34px; height: 34px; border: 0; background: #f1ece4; }
.stepper button:disabled { opacity: .45; cursor: not-allowed; }
.stepper b { width: 34px; text-align: center; line-height: 34px; }
.summary { padding: 24px; position: sticky; top: 96px; }
.summary h2 { font-family: serif; margin-top: 0; }
.summary > p { display: flex; justify-content: space-between; padding: 8px 0; color: var(--muted); }
.summary > p b { color: var(--ink); }
.summary .primary { width: 100%; margin-top: 18px; }
.empty .primary { display: inline-flex; align-items: center; margin-top: 12px; }
@media (max-width: 760px) {
  .cart-layout { grid-template-columns: 1fr; }
  .cart-list { padding: 4px 14px; }
  .cart-item { grid-template-columns: auto 70px 1fr; gap: 10px; }
  .thumb { width: 70px; height: 70px; }
  .stepper { grid-column: 3; width: fit-content; }
  .summary { position: static; }
}
</style>
