<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, dateTime, money, orderStatusLabel } from '../api'
import ProductMedia from '../components/ProductMedia.vue'
import ProofGallery from '../components/ProofGallery.vue'
import { resolveOrderActions } from '../order-capabilities'
import type { OrderDetail, Proof } from '../types'
import { addressLines } from '../utils/address'

const route = useRoute()
const router = useRouter()
const detail = ref<OrderDetail>()
const proofs = ref<Proof[]>([])
const loading = ref(true)
const proofLoading = ref(true)
const error = ref('')
const actionReason = ref('')
const busy = ref<'receive' | 'upload' | 'cancel' | 'superiorApprove' | 'superiorReject'>()

const orderId = computed(() => Number(route.params.id))
const deliveryAddress = computed(() => addressLines(detail.value?.addressJson))
const actions = computed(() => resolveOrderActions(detail.value?.actorCapabilities))
const timeline = computed(() => {
  const value = detail.value
  if (!value) return []
  const order = value.order
  const status = order.status
  const reached = (states: string[]) => states.includes(status)
  return [
    { label: '提交订单', note: '订单已创建，等待线下付款确认', time: order.createdAt, done: true },
    {
      label: '上级确认',
      note: status === 'SUPERIOR_REJECTED' ? '直属上级已拒绝' : '直属上级确认线下收款',
      time: value.superiorConfirmedAt,
      done: Boolean(value.superiorConfirmedAt) || status === 'SUPERIOR_REJECTED',
      current: status === 'PENDING_SUPERIOR'
    },
    {
      label: '后台审核',
      note: status === 'ADMIN_REJECTED' ? '后台审核未通过' : '后台审核订单与凭证',
      time: value.adminReviewedAt,
      done: Boolean(value.adminReviewedAt) || status === 'ADMIN_REJECTED',
      current: status === 'PENDING_ADMIN_REVIEW'
    },
    {
      label: '仓库发货',
      note: value.shipment ? `${value.shipment.carrierName} · ${value.shipment.trackingNo}` : '等待后台发货',
      time: value.shipment?.shippedAt,
      done: Boolean(value.shipment),
      current: status === 'PENDING_SHIPMENT'
    },
    {
      label: '确认收货',
      note: status === 'COMPLETED' ? '订单履约完成' : '用户确认或到期自动收货',
      time: value.completedAt,
      done: status === 'COMPLETED',
      current: status === 'SHIPPED'
    }
  ].map(item => ({
    ...item,
    muted: reached(['CANCELLED', 'SUPERIOR_REJECTED', 'ADMIN_REJECTED']) && !item.done
  }))
})

async function load() {
  if (!Number.isInteger(orderId.value) || orderId.value < 1) {
    error.value = '订单编号无效'
    loading.value = false
    proofLoading.value = false
    return
  }
  loading.value = true
  proofLoading.value = true
  error.value = ''
  const [orderResult, proofResult] = await Promise.allSettled([
    api<OrderDetail>(`/orders/${orderId.value}`),
    api<Proof[]>(`/orders/${orderId.value}/proofs`)
  ])
  if (orderResult.status === 'fulfilled') detail.value = orderResult.value
  else error.value = orderResult.reason instanceof Error ? orderResult.reason.message : '订单加载失败'
  if (proofResult.status === 'fulfilled') proofs.value = proofResult.value
  else if (!error.value) error.value = proofResult.reason instanceof Error ? proofResult.reason.message : '凭证加载失败'
  loading.value = false
  proofLoading.value = false
}

async function receive() {
  if (!detail.value || busy.value) return
  busy.value = 'receive'
  error.value = ''
  try {
    await api(`/orders/${detail.value.order.id}/receive`, { method: 'POST' })
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = undefined
  }
}

async function submitOrderAction(action: 'cancel' | 'superiorApprove' | 'superiorReject') {
  if (!detail.value || busy.value) return
  if (action !== 'superiorApprove' && !actionReason.value.trim()) {
    error.value = '请填写处理原因后再提交'
    return
  }
  busy.value = action
  error.value = ''
  try {
    if (action === 'cancel') {
      await api(`/orders/${detail.value.order.id}/cancel`, {
        method: 'POST',
        body: JSON.stringify({ reason: actionReason.value.trim() })
      })
    } else {
      await api(`/superior/orders/${detail.value.order.id}/decision`, {
        method: 'POST',
        body: JSON.stringify({
          approve: action === 'superiorApprove',
          reason: actionReason.value.trim()
        })
      })
    }
    actionReason.value = ''
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = undefined
  }
}

async function uploadProof(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || busy.value) return
  busy.value = 'upload'
  error.value = ''
  const form = new FormData()
  form.append('file', file)
  try {
    await api(`/orders/${orderId.value}/proofs`, { method: 'POST', body: form })
    proofs.value = await api<Proof[]>(`/orders/${orderId.value}/proofs`)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = undefined
    input.value = ''
  }
}

async function copyTracking() {
  const trackingNo = detail.value?.shipment?.trackingNo
  if (trackingNo) await navigator.clipboard.writeText(trackingNo)
}

onMounted(load)
</script>

<template>
  <div class="page detail-page">
    <button class="back-link" type="button" @click="router.push('/orders')">← 返回订单中心</button>
    <div v-if="loading" class="empty card" aria-busy="true">正在加载订单详情…</div>
    <div v-else-if="detail">
      <div class="detail-title">
        <div>
          <span class="eyebrow">Order Detail</span>
          <h1>{{ detail.order.orderNo }}</h1>
          <p>创建于 {{ dateTime(detail.order.createdAt) }}</p>
        </div>
        <span class="status">{{ orderStatusLabel(detail.order.status) }}</span>
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="detail.order.reason" class="reason">处理原因：{{ detail.order.reason }}</p>

      <section class="timeline card" aria-label="订单进度">
        <article
          v-for="item in timeline"
          :key="item.label"
          :class="{ done: item.done, current: item.current, muted: item.muted }"
        >
          <i></i>
          <div><b>{{ item.label }}</b><p>{{ item.note }}</p><small>{{ dateTime(item.time) }}</small></div>
        </article>
      </section>

      <div class="detail-grid">
        <section class="card panel">
          <h2>商品明细</h2>
          <article v-for="item in detail.items" :key="item.skuId" class="order-item">
            <ProductMedia class="item-art" :src="item.coverUrl" :alt="item.productName" :scene="item.salesScene" />
            <div><b>{{ item.productName }}</b><small>{{ item.skuName }} · 数量 {{ item.quantity }}</small><small>{{ money(item.unitPriceFen) }} / 件</small></div>
            <strong>{{ money(item.subtotalFen) }}</strong>
          </article>
          <div class="total"><span>订单合计</span><b>{{ money(detail.order.totalAmountFen) }}</b></div>
        </section>

        <div class="side-panels">
          <section class="card panel">
            <h2>收货信息</h2>
            <p v-for="line in deliveryAddress" :key="line">{{ line }}</p>
            <p v-if="!deliveryAddress.length" class="muted">暂无收货地址快照。</p>
          </section>
          <section class="card panel">
            <h2>物流信息</h2>
            <template v-if="detail.shipment">
              <p><span>承运商</span><b>{{ detail.shipment.carrierName }}</b></p>
              <p><span>物流单号</span><b>{{ detail.shipment.trackingNo }}</b></p>
              <p><span>发货时间</span><b>{{ dateTime(detail.shipment.shippedAt) }}</b></p>
              <button class="secondary" type="button" @click="copyTracking">复制物流单号</button>
            </template>
            <p v-else class="muted">订单尚未发货，物流信息会在后台发货后显示。</p>
            <p v-if="detail.autoReceiveAt" class="auto-note">预计自动收货：{{ dateTime(detail.autoReceiveAt) }}</p>
          </section>
        </div>
      </div>

      <section v-if="actions.canUploadProof" class="upload-panel card">
        <div><b>补充付款凭证</b><p>现金付款可以不上传；转账图片请遮盖无关的账号、余额等敏感信息。</p></div>
        <label class="secondary upload">
          {{ busy === 'upload' ? '安全处理中…' : '选择图片上传' }}
          <input :disabled="Boolean(busy)" type="file" accept="image/jpeg,image/png,image/webp" @change="uploadProof" />
        </label>
      </section>

      <section v-if="actions.canCancel || actions.canSuperiorDecide" class="order-actions card">
        <div>
          <b>{{ actions.canSuperiorDecide ? '直属上级订单确认' : '取消待确认订单' }}</b>
          <p>服务端已根据当前身份和订单状态授权下列操作。</p>
        </div>
        <label v-if="actions.canCancel || actions.canSuperiorDecide" class="action-reason">
          <span>处理原因（取消或拒绝时必填）</span>
          <textarea v-model="actionReason" rows="2" :disabled="Boolean(busy)" />
        </label>
        <div class="action-buttons">
          <button
            v-if="actions.canCancel"
            class="danger"
            type="button"
            :disabled="Boolean(busy)"
            @click="submitOrderAction('cancel')"
          >{{ busy === 'cancel' ? '取消中…' : '取消订单' }}</button>
          <template v-if="actions.canSuperiorDecide">
            <button
              class="danger"
              type="button"
              :disabled="Boolean(busy)"
              @click="submitOrderAction('superiorReject')"
            >{{ busy === 'superiorReject' ? '拒绝中…' : '拒绝订单' }}</button>
            <button
              class="primary"
              type="button"
              :disabled="Boolean(busy)"
              @click="submitOrderAction('superiorApprove')"
            >{{ busy === 'superiorApprove' ? '确认中…' : '确认线下收款' }}</button>
          </template>
        </div>
      </section>

      <ProofGallery :proofs="proofs" kind="order" :loading="proofLoading" />

      <div v-if="actions.canReceive" class="sticky-action">
        <span>收到商品并核对无误后再确认收货。</span>
        <button class="primary" type="button" :disabled="Boolean(busy)" @click="receive">
          {{ busy === 'receive' ? '确认中…' : '确认收货' }}
        </button>
      </div>
    </div>
    <div v-else class="empty card">
      <p>{{ error || '未找到订单。' }}</p>
      <button class="secondary" type="button" @click="load">重新加载</button>
    </div>
  </div>
</template>

<style scoped>
.back-link { border: 0; padding: 8px 0; color: var(--muted); background: transparent; }
.detail-title { display: flex; justify-content: space-between; align-items: end; gap: 18px; margin: 22px 0; }
.detail-title h1 { margin: 4px 0; font: 750 clamp(25px, 4vw, 40px) serif; overflow-wrap: anywhere; }
.detail-title p { color: var(--muted); margin: 0; }
.status { flex: none; padding: 8px 12px; color: #92601b; background: #fff0d5; border-radius: 99px; font-weight: 750; }
.reason { padding: 12px 14px; color: #a23d31; background: #fde9e5; border-radius: 10px; }
.timeline { display: grid; grid-template-columns: repeat(5, 1fr); padding: 22px; margin-bottom: 18px; }
.timeline article { position: relative; display: grid; grid-template-columns: 20px 1fr; gap: 7px; min-width: 0; color: #a59c95; }
.timeline article:not(:last-child)::after { content: ""; position: absolute; top: 7px; left: 12px; right: -8px; height: 2px; background: var(--line); }
.timeline i { z-index: 1; width: 15px; height: 15px; border: 3px solid #d7cec4; border-radius: 50%; background: var(--paper); }
.timeline article.done i { border-color: var(--green); background: var(--green); }
.timeline article.current i { border-color: var(--coral); box-shadow: 0 0 0 4px rgba(244,93,72,.12); }
.timeline article.done { color: var(--ink); }
.timeline p, .timeline small { margin: 5px 0 0; color: var(--muted); font-size: 12px; }
.detail-grid { display: grid; grid-template-columns: 1.45fr .8fr; gap: 18px; }
.panel { padding: 22px; }
.panel h2 { margin: 0 0 16px; font-family: serif; }
.order-item { display: grid; grid-template-columns: 58px 1fr auto; align-items: center; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.item-art { width: 58px; height: 58px; border-radius: 4px 12px 4px 12px; }
.item-art :deep(.media-fallback) { padding: 8px; }
.item-art :deep(.media-fallback span), .item-art :deep(.media-fallback i) { display: none; }
.item-art :deep(.media-fallback b) { font-size: 14px; }
.order-item b, .order-item small { display: block; }
.order-item small { color: var(--muted); margin-top: 4px; }
.total { display: flex; justify-content: space-between; align-items: baseline; padding-top: 18px; }
.total b { color: var(--coral); font-size: 24px; }
.side-panels { display: grid; gap: 18px; }
.side-panels p { line-height: 1.6; }
.side-panels p span { display: inline-block; min-width: 72px; color: var(--muted); }
.auto-note { padding: 10px; color: #79531c; background: #fff2d8; border-radius: 10px; font-size: 13px; }
.upload-panel { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 18px 22px; margin-top: 18px; }
.upload-panel p { color: var(--muted); margin: 5px 0 0; }
.upload { position: relative; display: inline-flex; align-items: center; flex: none; }
.upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.order-actions { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(260px, 1fr) auto; align-items: end; gap: 16px; padding: 18px 22px; margin-top: 18px; }
.order-actions p { color: var(--muted); margin: 5px 0 0; }
.action-reason { display: grid; gap: 6px; color: var(--muted); font-size: 13px; }
.action-reason textarea { resize: vertical; }
.action-buttons { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.sticky-action { position: sticky; z-index: 10; bottom: 16px; display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 14px 16px; margin-top: 20px; color: white; background: rgba(43,37,34,.94); border-radius: 16px; box-shadow: 0 12px 28px rgba(43,37,34,.2); }
@media (max-width: 860px) {
  .timeline { grid-template-columns: 1fr; gap: 12px; }
  .timeline article:not(:last-child)::after { top: 12px; bottom: -16px; left: 7px; right: auto; width: 2px; height: auto; }
  .detail-grid { grid-template-columns: 1fr; }
  .order-actions { grid-template-columns: 1fr; align-items: stretch; }
}
@media (max-width: 560px) {
  .detail-title { align-items: start; flex-direction: column; }
  .order-item { grid-template-columns: 48px 1fr; }
  .item-art { width: 48px; height: 48px; }
  .order-item > strong { grid-column: 2; }
  .upload-panel, .sticky-action { align-items: stretch; flex-direction: column; }
  .action-buttons { justify-content: stretch; }
  .action-buttons button { flex: 1; }
}
</style>
