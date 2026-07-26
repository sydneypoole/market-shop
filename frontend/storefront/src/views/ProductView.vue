<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, money } from '../api'
import { useShopStore, type Product } from '../stores/shop'
import { sanitizeRichText } from '../utils/sanitize'

type Detail = { product: Product; descriptionHtml: string; attributesJson: string }
const route = useRoute()
const router = useRouter()
const shop = useShopStore()
const detail = ref<Detail>()
const quantity = ref(1)
const error = ref('')
const loading = ref(true)
const busyAction = ref<'cart' | 'checkout'>()
const safeDescription = computed(() => sanitizeRichText(detail.value?.descriptionHtml || ''))

onMounted(async () => {
  error.value = ''
  try {
    detail.value = await api<Detail>(`/catalog/products/${route.params.id}`)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
})

async function add(goCheckout = false) {
  if (!detail.value || busyAction.value) return
  busyAction.value = goCheckout ? 'checkout' : 'cart'
  error.value = ''
  try {
    await shop.setCart(detail.value.product.skuId, quantity.value)
    await router.push(goCheckout ? '/checkout' : '/cart')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busyAction.value = undefined
  }
}
</script>

<template>
  <div v-if="loading" class="page">
    <div class="detail-loading card" aria-busy="true">正在加载商品详情…</div>
  </div>
  <div v-else-if="detail" class="page">
    <div class="product-detail">
      <div class="detail-art" :class="{ repurchase: detail.product.salesScene === 'REPURCHASE' }">
        <span>{{ detail.product.salesScene === 'UPGRADE' ? 'GROWTH' : 'SELECT' }}</span>
        <b>拾光<br />优选</b>
        <small>{{ detail.product.skuName }}</small>
      </div>
      <div class="detail-copy">
        <span class="chip" :class="{ green: detail.product.salesScene === 'REPURCHASE' }">
          {{ detail.product.salesScene === 'UPGRADE' ? '会员升级任务商品' : '会员复购任务商品' }}
        </span>
        <h1>{{ detail.product.name }}</h1>
        <p class="lead">{{ detail.product.subtitle }}</p>
        <div class="price-row">
          <span class="price">{{ money(detail.product.priceFen) }}</span>
          <del>{{ money(detail.product.marketPriceFen) }}</del>
        </div>
        <div class="notice">订单不在线支付。提交后请按约定线下付款，并等待直属上级确认。</div>
        <div class="sku-row">
          <span>规格</span><b>{{ detail.product.skuName }}</b>
        </div>
        <div class="quantity-row">
          <span>数量</span>
          <div><button @click="quantity = Math.max(1, quantity - 1)">−</button><b>{{ quantity }}</b><button @click="quantity = Math.min(99, quantity + 1)">＋</button></div>
          <small>剩余 {{ detail.product.inventory }}</small>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <div class="detail-actions">
          <button class="secondary" :disabled="Boolean(busyAction) || detail.product.inventory < 1" @click="add(false)">
            {{ busyAction === 'cart' ? '加入中…' : '加入购物车' }}
          </button>
          <button class="primary" :disabled="Boolean(busyAction) || detail.product.inventory < 1" @click="add(true)">
            {{ busyAction === 'checkout' ? '处理中…' : '立即提交订单' }}
          </button>
        </div>
      </div>
    </div>
    <section class="description card">
      <h2>商品与任务说明</h2>
      <div class="rich-text" v-html="safeDescription"></div>
      <p>具体升级、积分和释放条件使用订单完成时生效的后台规则版本，并保留规则快照和审计记录。</p>
    </section>
  </div>
  <div v-else class="page">
    <div class="empty card">
      <p>{{ error || '商品不存在或暂时无法查看。' }}</p>
      <button class="secondary" type="button" @click="router.replace('/')">返回首页</button>
    </div>
  </div>
</template>

<style scoped>
.product-detail { display: grid; grid-template-columns: 1fr 1fr; gap: 46px; padding: 26px 0 40px; }
.detail-art { min-height: 560px; border-radius: 28px; padding: 38px; display: flex; flex-direction: column; justify-content: space-between; color: white; background: linear-gradient(145deg, #f09a5c, #bd4234); box-shadow: inset 0 0 0 12px rgba(255,255,255,.12); }
.detail-art.repurchase { background: linear-gradient(145deg, #527667, #203e36); }
.detail-art > span { letter-spacing: .3em; opacity: .7; }
.detail-art b { font: 800 78px/.95 "Songti SC", serif; }
.detail-art small { font-size: 18px; }
.detail-copy { padding: 24px 0; }
.detail-copy h1 { font: 750 44px/1.2 "Songti SC", serif; margin: 18px 0 10px; }
.lead { color: var(--muted); line-height: 1.7; }
.price-row { display: flex; align-items: baseline; gap: 12px; margin: 28px 0 18px; }
.price-row .price { font-size: 34px; }
.price-row del { color: #a49a93; }
.sku-row, .quantity-row { display: flex; align-items: center; gap: 18px; padding: 20px 0; border-bottom: 1px solid var(--line); }
.sku-row > span, .quantity-row > span { color: var(--muted); width: 44px; }
.quantity-row div { display: flex; align-items: center; border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.quantity-row button { width: 36px; height: 34px; border: 0; background: #f0ebe3; }
.quantity-row b { width: 38px; text-align: center; }
.quantity-row small { color: var(--muted); margin-left: auto; }
.detail-actions { display: grid; grid-template-columns: 1fr 1.4fr; gap: 12px; margin-top: 28px; }
.description { padding: 30px; line-height: 1.8; }
.description h2 { font-family: serif; }
.detail-loading { min-height: 360px; display: grid; place-items: center; color: var(--muted); }
.rich-text :deep(img) { max-width: 100%; height: auto; border-radius: 12px; }
.rich-text :deep(a) { color: var(--green); text-decoration: underline; overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .product-detail { grid-template-columns: 1fr; gap: 16px; padding-top: 0; }
  .detail-art { min-height: 380px; border-radius: 0 0 24px 24px; margin: 0 -14px; }
  .detail-art b { font-size: 58px; }
  .detail-copy { padding: 12px 0; }
  .detail-copy h1 { font-size: 34px; }
  .detail-actions { position: sticky; bottom: 68px; z-index: 10; padding: 10px 0; background: rgba(248,245,238,.94); }
}
</style>
