<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, dateTime, money, statusText } from '../api'
import PaginationBar from '../components/PaginationBar.vue'
import type { OrderSummary } from '../types'

type DialogAction = 'approve' | 'reject' | 'cancel' | 'after-sale'

const router = useRouter()
const orders = ref<OrderSummary[]>([])
const superior = ref<OrderSummary[]>([])
const tab = ref<'mine' | 'superior'>('mine')
const query = ref('')
const status = ref('')
const page = ref(1)
const pageSize = 6
const loading = ref(true)
const error = ref('')
const busyOrderId = ref<number>()
const uploadOrderId = ref<number>()
const dialog = ref<{ action: DialogAction; order: OrderSummary }>()
const reason = ref('')
const description = ref('')
const afterSaleType = ref('REFUND_ONLY')

const source = computed(() => tab.value === 'mine' ? orders.value : superior.value)
const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return source.value.filter(order =>
    (!status.value || order.status === status.value)
    && (!keyword
      || order.orderNo.toLowerCase().includes(keyword)
      || String(order.id).includes(keyword)
      || String(order.buyerUserId).includes(keyword))
  )
})
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const availableStatuses = computed(() => Array.from(new Set(source.value.map(order => order.status))))

watch([tab, query, status], () => { page.value = 1 })

async function load() {
  loading.value = true
  error.value = ''
  const results = await Promise.allSettled([
    api<OrderSummary[]>('/orders'),
    api<OrderSummary[]>('/superior/orders')
  ])
  if (results[0].status === 'fulfilled') orders.value = results[0].value
  else error.value = results[0].reason instanceof Error ? results[0].reason.message : '订单加载失败'
  if (results[1].status === 'fulfilled') superior.value = results[1].value
  else if (!error.value) error.value = results[1].reason instanceof Error ? results[1].reason.message : '待确认订单加载失败'
  loading.value = false
}

function openDialog(action: DialogAction, order: OrderSummary) {
  dialog.value = { action, order }
  reason.value = ''
  description.value = ''
  afterSaleType.value = 'REFUND_ONLY'
}

async function submitDialog() {
  if (!dialog.value || busyOrderId.value) return
  const { action, order } = dialog.value
  if (['reject', 'cancel', 'after-sale'].includes(action) && !reason.value.trim()) {
    error.value = '请填写原因后再提交'
    return
  }
  busyOrderId.value = order.id
  error.value = ''
  try {
    if (action === 'approve' || action === 'reject') {
      await api(`/superior/orders/${order.id}/decision`, {
        method: 'POST',
        body: JSON.stringify({ approve: action === 'approve', reason: reason.value.trim() })
      })
    } else if (action === 'cancel') {
      await api(`/orders/${order.id}/cancel`, {
        method: 'POST',
        body: JSON.stringify({ reason: reason.value.trim() })
      })
    } else {
      await api('/after-sales', {
        method: 'POST',
        body: JSON.stringify({
          orderId: order.id,
          clientRequestId: crypto.randomUUID(),
          type: afterSaleType.value,
          reason: reason.value.trim(),
          description: description.value.trim()
        })
      })
    }
    dialog.value = undefined
    await load()
    if (action === 'after-sale') await router.push('/after-sales')
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busyOrderId.value = undefined
  }
}

async function receive(order: OrderSummary) {
  if (busyOrderId.value) return
  busyOrderId.value = order.id
  error.value = ''
  try {
    await api(`/orders/${order.id}/receive`, { method: 'POST' })
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busyOrderId.value = undefined
  }
}

async function uploadProof(order: OrderSummary, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || uploadOrderId.value) return
  uploadOrderId.value = order.id
  error.value = ''
  const form = new FormData()
  form.append('file', file)
  try {
    await api(`/orders/${order.id}/proofs`, { method: 'POST', body: form })
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    uploadOrderId.value = undefined
    input.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="section-head">
      <div><span class="eyebrow">Order Journey</span><h1>订单中心</h1><p>搜索订单、查看商品和物流明细，并跟进线下确认流程。</p></div>
      <button class="secondary" type="button" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>
    <div class="tabs">
      <button :class="{ active: tab === 'mine' }" @click="tab = 'mine'">我的订单 <b>{{ orders.length }}</b></button>
      <button :class="{ active: tab === 'superior' }" @click="tab = 'superior'">待我确认 <b>{{ superior.filter(order => order.status === 'PENDING_SUPERIOR_CONFIRMATION').length }}</b></button>
    </div>
    <section class="filters card">
      <div class="field"><label for="order-query">搜索</label><input id="order-query" v-model="query" placeholder="订单号、订单 ID 或用户 ID" /></div>
      <div class="field"><label for="order-status">状态</label><select id="order-status" v-model="status"><option value="">全部状态</option><option v-for="value in availableStatuses" :key="value" :value="value">{{ statusText[value] || value }}</option></select></div>
      <button v-if="query || status" class="secondary" type="button" @click="query = ''; status = ''">清除筛选</button>
    </section>
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <div v-if="loading" class="order-list" aria-busy="true">
      <div v-for="index in 3" :key="index" class="order-card card skeleton"></div>
    </div>
    <section v-else-if="paged.length" class="order-list">
      <article v-for="order in paged" :key="order.id" class="order-card card">
        <header>
          <span><small>订单号</small><b>{{ order.orderNo }}</b></span>
          <span class="status">{{ statusText[order.status] || order.status }}</span>
        </header>
        <div class="order-body">
          <div class="order-art">拾光</div>
          <div><small>{{ tab === 'mine' ? '我的线下订单' : `直属用户 #${order.buyerUserId}` }}</small><h3>商城商品订单</h3><p>{{ dateTime(order.createdAt) }}</p></div>
          <strong>{{ money(order.totalAmountFen) }}</strong>
        </div>
        <p v-if="order.reason" class="reason">原因：{{ order.reason }}</p>
        <footer>
          <RouterLink class="secondary button-link" :to="`/orders/${order.id}`">详情 / 物流 / 凭证</RouterLink>
          <label v-if="tab === 'mine' && order.status === 'PENDING_SUPERIOR_CONFIRMATION'" class="secondary upload" :class="{ disabled: Boolean(uploadOrderId) }">
            {{ uploadOrderId === order.id ? '上传中…' : '上传凭证' }}
            <input :disabled="Boolean(uploadOrderId)" type="file" accept="image/jpeg,image/png,image/webp" @change="uploadProof(order, $event)" />
          </label>
          <button v-if="tab === 'mine' && order.status === 'PENDING_SUPERIOR_CONFIRMATION'" class="danger" :disabled="Boolean(busyOrderId)" @click="openDialog('cancel', order)">取消订单</button>
          <template v-if="tab === 'superior' && order.status === 'PENDING_SUPERIOR_CONFIRMATION'">
            <button class="danger" :disabled="Boolean(busyOrderId)" @click="openDialog('reject', order)">拒绝</button>
            <button class="primary" :disabled="Boolean(busyOrderId)" @click="openDialog('approve', order)">确认线下收款</button>
          </template>
          <button v-if="tab === 'mine' && order.status === 'SHIPPED'" class="primary" :disabled="Boolean(busyOrderId)" @click="receive(order)">{{ busyOrderId === order.id ? '确认中…' : '确认收货' }}</button>
          <button v-if="tab === 'mine' && ['SHIPPED','COMPLETED'].includes(order.status)" class="secondary" :disabled="Boolean(busyOrderId)" @click="openDialog('after-sale', order)">申请售后</button>
        </footer>
      </article>
    </section>
    <div v-else class="empty card">{{ source.length ? '没有符合筛选条件的订单。' : '这里还没有订单记录。' }}</div>
    <PaginationBar :page="page" :page-size="pageSize" :total="filtered.length" @change="page = $event" />

    <div v-if="dialog" class="modal-mask" @click.self="dialog = undefined">
      <form class="modal action-modal card" @submit.prevent="submitDialog">
        <h2>{{ dialog.action === 'approve' ? '确认线下收款' : dialog.action === 'reject' ? '拒绝订单' : dialog.action === 'cancel' ? '取消订单' : '申请售后' }}</h2>
        <p class="notice">系统不发起在线收付款，只记录线下确认事实与处理意见。</p>
        <div v-if="dialog.action === 'after-sale'" class="field">
          <label for="sale-type">售后方式</label>
          <select id="sale-type" v-model="afterSaleType"><option value="REFUND_ONLY">仅线下退款</option><option value="RETURN_REFUND">退货并线下退款</option></select>
        </div>
        <div v-if="dialog.action !== 'approve'" class="field">
          <label for="action-reason">{{ dialog.action === 'after-sale' ? '售后原因' : '处理原因' }}</label>
          <textarea id="action-reason" v-model="reason" rows="4" required />
        </div>
        <div v-if="dialog.action === 'after-sale'" class="field">
          <label for="sale-description">补充说明（可选）</label>
          <textarea id="sale-description" v-model="description" rows="3" />
        </div>
        <div class="modal-actions">
          <button class="secondary" type="button" :disabled="Boolean(busyOrderId)" @click="dialog = undefined">返回</button>
          <button class="primary" :disabled="Boolean(busyOrderId)">{{ busyOrderId ? '提交中…' : '确认提交' }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 14px; }
.tabs button { border: 0; padding: 11px 16px; border-radius: 99px; background: #eee8df; color: var(--muted); }
.tabs button.active { color: white; background: var(--ink); }
.tabs b { margin-left: 5px; }
.filters { display: grid; grid-template-columns: 1fr 240px auto; align-items: end; gap: 12px; padding: 16px; margin-bottom: 18px; }
.order-list { display: grid; gap: 14px; }
.order-card { min-height: 250px; padding: 20px; }
.skeleton { min-height: 250px; background: linear-gradient(100deg,#eee8de 20%,#faf7f1 40%,#eee8de 60%); background-size:200% 100%; animation:shine 1.2s infinite; }
.order-card header { display: flex; justify-content: space-between; align-items: center; gap: 12px; border-bottom: 1px solid var(--line); padding-bottom: 14px; }
.order-card header small, .order-card header b { display: block; }
.order-card header small { color: var(--muted); margin-bottom: 3px; }
.status { flex: none; color: #9a641e; background: #fff0d5; border-radius: 99px; padding: 7px 11px; font-weight: 750; font-size: 13px; }
.order-body { display: grid; grid-template-columns: 72px 1fr auto; align-items: center; gap: 16px; padding: 18px 0; }
.order-art { display: grid; place-items: center; width: 72px; height: 72px; color: white; background: #31584d; border-radius: 14px; font-family: serif; }
.order-body h3, .order-body p { margin: 4px 0; }
.order-body small, .order-body p { color: var(--muted); font-size: 12px; }
.reason { color: #a23d31; background: #fde9e5; padding: 10px; border-radius: 10px; }
.order-card footer { display: flex; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }
.button-link, .upload { display: inline-flex; align-items: center; justify-content: center; }
.upload { position: relative; }
.upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.disabled { opacity: .5; cursor: not-allowed; }
.action-modal { width: min(520px, 100%); padding: 24px; }
.action-modal h2 { margin-top: 0; font-family: serif; }
.action-modal .field { margin-top: 14px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
@keyframes shine { to { background-position:-200% 0; } }
@media (max-width: 700px) {
  .filters { grid-template-columns: 1fr; }
  .order-card { min-height: 0; padding: 15px; }
  .order-card header b { max-width: 210px; overflow: hidden; text-overflow: ellipsis; }
  .order-body { grid-template-columns: 58px 1fr; }
  .order-art { width: 58px; height: 58px; }
  .order-body > strong { grid-column: 2; }
  .order-card footer > * { flex: 1 1 145px; }
}
</style>
