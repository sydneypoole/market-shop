<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, money } from '../api'
import ProductMedia from '../components/ProductMedia.vue'
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
  <div v-if="loading" class="page product-page">
    <div class="detail-loading" aria-busy="true"><i></i><span>正在准备这件好物…</span></div>
  </div>
  <div v-else-if="detail" class="page product-page">
    <nav class="breadcrumbs" aria-label="面包屑">
      <RouterLink to="/">首页</RouterLink><span>/</span><a href="/#products">全部商品</a><span>/</span><b>{{ detail.product.name }}</b>
    </nav>
    <div class="product-detail">
      <div class="detail-media">
        <ProductMedia
          :src="detail.product.coverUrl"
          :alt="detail.product.name"
          :scene="detail.product.salesScene"
          ratio="portrait"
          eager
        />
        <span class="media-index">SHIGUANG / {{ detail.product.salesScene === 'UPGRADE' ? 'MEMBER EDIT' : 'DAILY SELECT' }}</span>
      </div>
      <div class="detail-copy">
        <div class="detail-kicker">
          <span class="chip" :class="{ green: detail.product.salesScene === 'REPURCHASE' }">
            {{ detail.product.salesScene === 'UPGRADE' ? '会员成长精选' : '日常品质复购' }}
          </span>
          <small>库存 {{ detail.product.inventory }}</small>
        </div>
        <h1>{{ detail.product.name }}</h1>
        <p class="lead">{{ detail.product.subtitle }}</p>
        <div class="price-row">
          <span class="price">{{ money(detail.product.priceFen) }}</span>
          <span v-if="detail.product.marketPriceFen > detail.product.priceFen">日常价 <del>{{ money(detail.product.marketPriceFen) }}</del></span>
        </div>

        <div class="purchase-options">
          <div class="sku-row">
            <span>选择规格</span><button type="button" class="selected-sku">{{ detail.product.skuName }}</button>
          </div>
          <div class="quantity-row">
            <span>购买数量</span>
            <div>
              <button type="button" aria-label="减少数量" @click="quantity = Math.max(1, quantity - 1)">−</button>
              <b>{{ quantity }}</b>
              <button type="button" aria-label="增加数量" @click="quantity = Math.min(Math.min(99, detail.product.inventory), quantity + 1)">＋</button>
            </div>
            <small>最多可选 {{ Math.min(99, detail.product.inventory) }} 件</small>
          </div>
        </div>

        <div class="offline-note">
          <span aria-hidden="true">i</span>
          <p><b>本商城不发起在线支付</b>提交订单后，请按约定在线下付款，并等待直属上级确认收款。</p>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <div class="detail-actions">
          <button type="button" class="secondary" :disabled="Boolean(busyAction) || detail.product.inventory < 1" @click="add(false)">
            {{ busyAction === 'cart' ? '加入中…' : '加入购物车' }}
          </button>
          <button type="button" class="primary" :disabled="Boolean(busyAction) || detail.product.inventory < 1" @click="add(true)">
            {{ busyAction === 'checkout' ? '处理中…' : '立即选购' }}
          </button>
        </div>
        <ul class="service-points">
          <li><span>01</span>价格与库存实时同步</li>
          <li><span>02</span>订单全流程状态可查</li>
          <li><span>03</span>支持售后与凭证留痕</li>
        </ul>
      </div>
    </div>
    <section class="description">
      <header>
        <span class="eyebrow">Details & story</span>
        <h2>关于这件好物</h2>
        <p>商品介绍由运营后台维护；涉及会员任务的条件，以订单完成时生效的后台规则快照为准。</p>
      </header>
      <div class="description-body">
        <div v-if="safeDescription" class="rich-text" v-html="safeDescription"></div>
        <div v-else class="description-empty">商品详情正在整理中，你仍可以根据商品名称、规格与实时价格完成选购。</div>
      </div>
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
.product-page { padding-top: 24px; }
.breadcrumbs { display: flex; align-items: center; gap: 10px; padding: 10px 0 24px; color: var(--muted); font-size: 10px; }
.breadcrumbs a:hover { color: var(--green); }
.breadcrumbs b { max-width: 280px; overflow: hidden; color: var(--ink); text-overflow: ellipsis; white-space: nowrap; }
.product-detail { display: grid; grid-template-columns: minmax(0, 1.16fr) minmax(390px, .84fr); gap: clamp(48px, 7vw, 108px); align-items: start; }
.detail-media { position: sticky; top: 106px; min-width: 0; }
.detail-media :deep(.product-media) { border-radius: 5px 34px 5px 34px; box-shadow: var(--shadow-lg); }
.media-index { display: block; margin-top: 14px; color: var(--muted); font-size: 8px; font-weight: 750; letter-spacing: .2em; }
.detail-copy { padding: clamp(24px, 4vw, 58px) 0 20px; }
.detail-kicker { display: flex; align-items: center; justify-content: space-between; }
.detail-kicker small { color: var(--muted); font-size: 10px; }
.detail-copy h1 { max-width: 600px; margin: 26px 0 12px; font: 650 clamp(42px, 5vw, 66px)/1.12 var(--font-display); letter-spacing: -.07em; }
.lead { max-width: 560px; color: var(--muted); font-size: 15px; line-height: 1.8; }
.price-row { display: flex; align-items: baseline; gap: 16px; margin: 30px 0 26px; }
.price-row .price { font-size: 36px; }
.price-row > span:not(.price) { color: var(--muted); font-size: 11px; }
.purchase-options { border-top: 1px solid var(--line); }
.sku-row, .quantity-row { display: grid; grid-template-columns: 76px 1fr auto; align-items: center; gap: 16px; min-height: 76px; border-bottom: 1px solid var(--line); }
.sku-row > span, .quantity-row > span { color: var(--ink-soft); font-size: 11px; font-weight: 700; }
.selected-sku { justify-self: start; min-height: 38px; padding: 0 15px; color: var(--green); border: 1px solid var(--green); border-radius: 8px; background: #f7faf6; font-size: 11px; font-weight: 700; }
.quantity-row div { justify-self: start; display: flex; align-items: center; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 9px; }
.quantity-row button { width: 36px; height: 36px; border: 0; background: #f5f4ee; }
.quantity-row b { width: 42px; text-align: center; font-size: 12px; }
.quantity-row small { color: var(--muted); font-size: 10px; }
.offline-note { display: grid; grid-template-columns: 28px 1fr; gap: 11px; margin-top: 24px; padding: 15px; color: #625337; border-radius: 12px; background: #faf2e2; }
.offline-note > span { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid #b99c65; border-radius: 50%; font: italic 650 13px var(--font-display); }
.offline-note p { margin: 0; font-size: 11px; line-height: 1.65; }
.offline-note b { display: block; color: #493d27; }
.detail-actions { display: grid; grid-template-columns: .9fr 1.25fr; gap: 10px; margin-top: 24px; }
.detail-actions button { min-height: 54px; }
.service-points { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 28px 0 0; padding: 24px 0 0; border-top: 1px solid var(--line); list-style: none; }
.service-points li { color: var(--muted); font-size: 9px; line-height: 1.55; }
.service-points span { display: block; margin-bottom: 5px; color: var(--coral); font: 650 15px var(--font-display); }
.description { display: grid; grid-template-columns: .7fr 1.3fr; gap: clamp(40px, 8vw, 120px); margin-top: 120px; padding: 72px 4%; border-top: 1px solid var(--line-strong); line-height: 1.8; }
.description header h2 { margin: 13px 0 18px; font: 650 clamp(38px, 4.5vw, 58px)/1.1 var(--font-display); letter-spacing: -.06em; }
.description header p { max-width: 400px; color: var(--muted); font-size: 12px; }
.description-body { min-width: 0; color: var(--ink-soft); }
.description-empty { min-height: 220px; display: grid; place-items: center; padding: 40px; color: var(--muted); border-radius: 18px; background: #eeeee7; text-align: center; }
.detail-loading { min-height: 70vh; display: grid; place-items: center; align-content: center; gap: 20px; color: var(--muted); }
.detail-loading i { display: block; width: 44px; height: 44px; border: 2px solid var(--line); border-top-color: var(--green); border-radius: 50%; animation: spin .8s linear infinite; }
.rich-text :deep(h2), .rich-text :deep(h3) { margin: 1.4em 0 .55em; color: var(--ink); line-height: 1.25; }
.rich-text :deep(p) { margin: 0 0 1em; }
.rich-text :deep(ol) { margin: 0 0 1em; padding-left: 1.6em; }
.rich-text :deep(li[data-list="ordered"]) { list-style: decimal; }
.rich-text :deep(li[data-list="bullet"]) { list-style: disc; }
.rich-text :deep(.ql-ui) { display: none; }
.rich-text :deep(blockquote) { margin: 1.2em 0; padding: .2em 0 .2em 1.2em; color: var(--muted); border-left: 3px solid var(--coral); }
.rich-text :deep(img) { max-width: 100%; height: auto; border-radius: 12px; }
.rich-text :deep(a) { color: var(--green); text-decoration: underline; overflow-wrap: anywhere; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 800px) {
  .product-page { padding-top: 8px; }
  .breadcrumbs { padding-left: 4px; }
  .product-detail { grid-template-columns: 1fr; gap: 14px; }
  .detail-media { position: static; margin: 0 -14px; }
  .detail-media :deep(.product-media) { aspect-ratio: 1 / 1.08; border-radius: 0 0 28px 28px; box-shadow: none; }
  .media-index { padding-left: 14px; }
  .detail-copy { padding: 20px 2px; }
  .detail-copy h1 { font-size: 44px; }
  .lead { font-size: 14px; }
  .quantity-row { grid-template-columns: 76px 1fr; }
  .quantity-row small { grid-column: 2; padding-bottom: 15px; }
  .detail-actions { position: sticky; z-index: 20; bottom: 78px; margin: 22px -6px 0; padding: 9px 6px; border-radius: 18px; background: rgba(245,244,239,.94); backdrop-filter: blur(14px); }
  .service-points { gap: 8px; }
  .description { grid-template-columns: 1fr; gap: 30px; margin-top: 60px; padding: 52px 4px; }
}
</style>
