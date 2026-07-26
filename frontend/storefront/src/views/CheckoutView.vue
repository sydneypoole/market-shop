<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, money } from '../api'
import ProductMedia from '../components/ProductMedia.vue'
import { useShopStore } from '../stores/shop'

const shop = useShopStore()
const router = useRouter()
const busy = ref(false)
const loading = ref(true)
const error = ref('')
type SavedAddress = {
  id:number; recipientName:string; phone:string; province:string; city:string; district:string
  detailAddress:string; postalCode?:string; defaultAddress:boolean
}
const savedAddresses = ref<SavedAddress[]>([])
const selectedAddressId = ref<number>()
const address = reactive({
  recipientName: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: '',
  postalCode: ''
})
const total = computed(() => shop.selectedItems.reduce((sum, item) => sum + item.priceFen * item.quantity, 0))

onMounted(async () => {
  error.value = ''
  try {
    if (!shop.cart.length) await shop.loadCart()
    savedAddresses.value = await api<SavedAddress[]>('/addresses')
    const preferred = savedAddresses.value.find(item => item.defaultAddress) || savedAddresses.value[0]
    if (preferred) chooseAddress(preferred)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
})

function chooseAddress(value: SavedAddress) {
  selectedAddressId.value = value.id
  Object.assign(address, value)
}

async function submit() {
  if (busy.value || loading.value) return
  busy.value = true
  error.value = ''
  try {
    await api('/orders', {
      method: 'POST',
      body: JSON.stringify({
        clientRequestId: crypto.randomUUID(),
        source: innerWidth <= 720 ? 'H5' : 'WEB',
        address,
        items: shop.selectedItems.map(item => ({ skuId: item.skuId, quantity: item.quantity }))
      })
    })
    await router.replace('/orders')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page checkout">
    <div class="section-head"><div><span class="eyebrow">Submit Order</span><h1>提交订单</h1><p>请确认收货信息，订单提交后由直属上级核对线下收款。</p></div></div>
    <div v-if="loading" class="empty card" aria-busy="true">正在准备订单信息…</div>
    <form v-else class="checkout-grid" @submit.prevent="submit">
      <section class="card form-card">
        <h2>收货信息</h2>
        <div v-if="savedAddresses.length" class="saved-list">
          <button v-for="item in savedAddresses" :key="item.id" type="button" :class="{active:selectedAddressId===item.id}" @click="chooseAddress(item)">
            <b>{{ item.recipientName }} {{ item.phone }}</b><small>{{ item.province }}{{ item.city }}{{ item.district }}{{ item.detailAddress }}</small>
          </button>
        </div>
        <RouterLink class="address-link" to="/addresses">管理地址簿 →</RouterLink>
        <div class="grid-2">
          <div class="field"><label>收货人</label><input v-model="address.recipientName" required /></div>
          <div class="field"><label>联系电话</label><input v-model="address.phone" required inputmode="tel" /></div>
          <div class="field"><label>省份</label><input v-model="address.province" required /></div>
          <div class="field"><label>城市</label><input v-model="address.city" required /></div>
          <div class="field"><label>区县</label><input v-model="address.district" required /></div>
          <div class="field"><label>邮编（选填）</label><input v-model="address.postalCode" /></div>
        </div>
        <div class="field detail-field"><label>详细地址</label><textarea v-model="address.detailAddress" required rows="3"></textarea></div>
        <h2>已选商品</h2>
        <div v-for="item in shop.selectedItems" :key="item.id" class="checkout-item">
          <ProductMedia class="checkout-thumb" :src="item.coverUrl" :alt="item.productName" />
          <div><span>{{ item.productName }}</span><small>{{ item.skuName }} × {{ item.quantity }}</small></div>
          <b>{{ money(item.priceFen * item.quantity) }}</b>
        </div>
      </section>
      <aside class="card confirm-card">
        <h2>本次订单</h2>
        <p><span>商品金额</span><b>{{ money(total) }}</b></p>
        <p><span>线上支付</span><b>不支持</b></p>
        <p class="grand"><span>订单合计</span><b>{{ money(total) }}</b></p>
        <div class="notice">请在线下完成约定收款。可在订单详情中上传脱敏后的付款凭证，最多 3 个文件。</div>
        <label class="consent"><input required type="checkbox" /> 我已知晓本系统不提供在线支付、积分提现或现金兑换。</label>
        <p v-if="error" class="error">{{ error }}</p>
        <button class="primary" :disabled="busy || !shop.selectedItems.length">{{ busy ? '提交中…' : '确认提交订单' }}</button>
      </aside>
    </form>
  </div>
</template>

<style scoped>
.checkout-grid { display: grid; grid-template-columns: 1fr 350px; gap: 20px; align-items: start; }
.form-card, .confirm-card { padding: 28px; }
h2 { font-family: serif; margin: 0 0 20px; }
.detail-field { margin: 18px 0 34px; }
.saved-list{display:grid;gap:8px;margin-bottom:10px}.saved-list button{text-align:left;padding:12px;border:1px solid var(--line);border-radius:11px;background:white}.saved-list button.active{border-color:var(--coral);box-shadow:0 0 0 2px rgba(244,93,72,.1)}.saved-list small{display:block;color:var(--muted);margin-top:4px}.address-link{display:inline-block;color:var(--green);font-size:13px;font-weight:700;margin-bottom:18px}
.checkout-item { display: grid; grid-template-columns: 64px 1fr auto; align-items: center; gap: 14px; padding: 14px 0; border-top: 1px solid var(--line); }
.checkout-item span, .checkout-item small { display: block; }
.checkout-item span { font-family: var(--font-display); font-weight: 650; }
.checkout-item small { color: var(--muted); margin-top: 5px; }
.checkout-thumb { width: 64px; height: 64px; border-radius: 4px 11px 4px 11px; }
.checkout-thumb :deep(.media-fallback) { padding: 9px; }
.checkout-thumb :deep(.media-fallback span), .checkout-thumb :deep(.media-fallback i) { display: none; }
.checkout-thumb :deep(.media-fallback b) { font-size: 15px; }
.confirm-card { position: sticky; top: 96px; }
.confirm-card > p { display: flex; justify-content: space-between; color: var(--muted); }
.confirm-card > p b { color: var(--ink); }
.confirm-card .grand { padding-top: 15px; border-top: 1px solid var(--line); font-size: 18px; }
.confirm-card .grand b { color: var(--coral); font-size: 24px; }
.consent { display: flex; gap: 8px; align-items: flex-start; margin: 18px 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.consent input { accent-color: var(--coral); margin-top: 3px; }
.confirm-card .primary { width: 100%; }
@media (max-width: 760px) {
  .checkout-grid { grid-template-columns: 1fr; }
  .form-card, .confirm-card { padding: 20px 16px; }
  .confirm-card { position: static; }
  .checkout-item { grid-template-columns: 58px 1fr auto; }
  .checkout-thumb { width: 58px; height: 58px; }
}
</style>
