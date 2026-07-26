<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, dateTime, statusText } from '../api'
import ProofGallery from '../components/ProofGallery.vue'
import { loadUserSession, type UserSession } from '../session'
import type { AfterSale, AfterSaleProof, OrderDetail } from '../types'
import { addressLines } from '../utils/address'

type ActionName = 'return' | 'cancel' | 'buyer-refund' | 'superior-refund'

const route = useRoute()
const router = useRouter()
const sale = ref<AfterSale>()
const order = ref<OrderDetail>()
const proofs = ref<AfterSaleProof[]>([])
const session = ref<UserSession>()
const loading = ref(true)
const proofLoading = ref(true)
const error = ref('')
const busy = ref<ActionName | 'upload'>()
const panel = ref<ActionName>()
const carrier = ref('')
const trackingNo = ref('')
const reason = ref('')
const proofType = ref('APPLICATION')

const afterSaleId = computed(() => Number(route.params.id))
const isApplicant = computed(() => session.value?.userId === sale.value?.applicantUserId)
const isSuperior = computed(() => session.value?.userId === sale.value?.superiorUserId)
const returnAddress = computed(() => addressLines(sale.value?.returnAddressJson))
const timeline = computed(() => {
  if (!sale.value) return []
  const statuses = [
    'PENDING_ADMIN_REVIEW',
    'AWAITING_RETURN',
    'RETURN_SHIPPED',
    'PENDING_OFFLINE_REFUND',
    'PENDING_BUYER_REFUND_CONFIRMATION',
    'COMPLETED'
  ]
  const currentIndex = statuses.indexOf(sale.value.status)
  const terminal = ['REJECTED', 'CANCELLED'].includes(sale.value.status)
  const stages = sale.value.type === 'RETURN_REFUND'
    ? [
        ['提交申请', '等待后台审核'],
        ['审核处理', '后台确认退货方案'],
        ['寄回商品', sale.value.returnTrackingNo ? `${sale.value.returnCarrier} · ${sale.value.returnTrackingNo}` : '买家填写回寄物流'],
        ['线下退款', '直属上级在线下完成退款'],
        ['买家确认', '买家核对线下退款'],
        ['售后完成', '冲正相关任务证据与积分']
      ]
    : [
        ['提交申请', '等待后台审核'],
        ['审核处理', '后台确认仅退款方案'],
        ['线下退款', '直属上级在线下完成退款'],
        ['买家确认', '买家核对线下退款'],
        ['售后完成', '冲正相关任务证据与积分']
      ]
  const indices = sale.value.type === 'RETURN_REFUND' ? [0, 1, 2, 3, 4, 5] : [0, 1, 3, 4, 5]
  return stages.map((stage, index) => {
    const statusIndex = indices[index]
    return {
      label: stage[0],
      note: terminal && index === Math.max(currentIndex, 0) ? statusText[sale.value!.status] : stage[1],
      done: terminal ? index === 0 : currentIndex >= statusIndex,
      current: terminal ? index === 1 : currentIndex === statusIndex,
      time: index === 0 ? sale.value?.createdAt : index === stages.length - 1 ? sale.value?.completedAt : undefined
    }
  })
})

async function load() {
  if (!Number.isInteger(afterSaleId.value) || afterSaleId.value < 1) {
    error.value = '售后单编号无效'
    loading.value = false
    proofLoading.value = false
    return
  }
  loading.value = true
  proofLoading.value = true
  error.value = ''
  try {
    const [current, currentSession, currentProofs] = await Promise.all([
      api<AfterSale>(`/after-sales/${afterSaleId.value}`),
      loadUserSession(),
      api<AfterSaleProof[]>(`/after-sales/${afterSaleId.value}/proofs`)
    ])
    sale.value = current
    session.value = currentSession
    proofs.value = currentProofs
    try {
      order.value = await api<OrderDetail>(`/orders/${current.orderId}`)
    } catch {
      order.value = undefined
    }
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
    proofLoading.value = false
  }
}

function openPanel(action: ActionName) {
  panel.value = action
  reason.value = ''
  carrier.value = ''
  trackingNo.value = ''
}

async function submitAction() {
  if (!sale.value || !panel.value || busy.value) return
  const action = panel.value
  if (action === 'return' && (!carrier.value.trim() || !trackingNo.value.trim())) {
    error.value = '请完整填写承运商和回寄物流单号'
    return
  }
  if (action === 'cancel' && !reason.value.trim()) {
    error.value = '请填写撤销售后的原因'
    return
  }
  busy.value = action
  error.value = ''
  try {
    if (action === 'return') {
      await api(`/after-sales/${sale.value.id}/return-shipment`, {
        method: 'POST',
        body: JSON.stringify({ carrier: carrier.value.trim(), trackingNo: trackingNo.value.trim() })
      })
    } else if (action === 'cancel') {
      await api(`/after-sales/${sale.value.id}/cancel`, {
        method: 'POST',
        body: JSON.stringify({ reason: reason.value.trim() })
      })
    } else if (action === 'buyer-refund') {
      await api(`/after-sales/${sale.value.id}/confirm-refund`, { method: 'POST' })
    } else {
      await api(`/after-sales/superior/${sale.value.id}/confirm-offline-refund`, {
        method: 'POST',
        body: JSON.stringify({ reason: reason.value.trim() || '已在线下完成退款' })
      })
    }
    panel.value = undefined
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
    await api(`/after-sales/${afterSaleId.value}/proofs?proofType=${proofType.value}`, {
      method: 'POST',
      body: form
    })
    proofs.value = await api<AfterSaleProof[]>(`/after-sales/${afterSaleId.value}/proofs`)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = undefined
    input.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <button class="back-link" type="button" @click="router.push('/after-sales')">← 返回售后中心</button>
    <div v-if="loading" class="empty card" aria-busy="true">正在加载售后详情…</div>
    <div v-else-if="sale">
      <div class="detail-title">
        <div><span class="eyebrow">After-sale Detail</span><h1>{{ sale.afterSaleNo }}</h1><p>关联订单 {{ order?.order.orderNo || `#${sale.orderId}` }}</p></div>
        <span class="status">{{ statusText[sale.status] || sale.status }}</span>
      </div>
      <p v-if="error" class="error">{{ error }}</p>

      <section class="summary card">
        <div><small>售后类型</small><b>{{ sale.type === 'RETURN_REFUND' ? '退货并线下退款' : '仅线下退款' }}</b></div>
        <div><small>申请时间</small><b>{{ dateTime(sale.createdAt) }}</b></div>
        <div><small>申请原因</small><b>{{ sale.reason }}</b></div>
        <div><small>后台意见</small><b>{{ sale.adminReason || '暂未填写' }}</b></div>
      </section>

      <section class="progress card" aria-label="售后进度">
        <h2>处理进度</h2>
        <article v-for="item in timeline" :key="item.label" :class="{ done: item.done, current: item.current }">
          <i></i><div><b>{{ item.label }}</b><p>{{ item.note }}</p><small v-if="item.time">{{ dateTime(item.time) }}</small></div>
        </article>
      </section>

      <div class="detail-grid">
        <section class="card panel">
          <h2>退货地址</h2>
          <template v-if="returnAddress.length">
            <p v-for="line in returnAddress" :key="line">{{ line }}</p>
          </template>
          <p v-else class="muted">{{ sale.type === 'RETURN_REFUND' ? '后台审核通过后显示退货地址。' : '仅退款无需寄回商品。' }}</p>
        </section>
        <section class="card panel">
          <h2>回寄物流</h2>
          <template v-if="sale.returnTrackingNo">
            <p><span>承运商</span><b>{{ sale.returnCarrier }}</b></p>
            <p><span>物流单号</span><b>{{ sale.returnTrackingNo }}</b></p>
          </template>
          <p v-else class="muted">暂未提交回寄物流。</p>
        </section>
      </div>

      <section v-if="isApplicant && !['COMPLETED','REJECTED','CANCELLED'].includes(sale.status)" class="proof-upload card">
        <div><b>补充售后凭证</b><p>可按申请、退货或退款阶段上传图片，敏感信息请先遮盖。</p></div>
        <select v-model="proofType" aria-label="凭证类型">
          <option value="APPLICATION">申请凭证</option>
          <option value="RETURN">退货凭证</option>
          <option value="REFUND">退款凭证</option>
        </select>
        <label class="secondary upload">
          {{ busy === 'upload' ? '上传中…' : '选择图片' }}
          <input :disabled="Boolean(busy)" type="file" accept="image/jpeg,image/png,image/webp" @change="uploadProof" />
        </label>
      </section>

      <ProofGallery :proofs="proofs" kind="after-sale" :loading="proofLoading" />

      <div class="actions">
        <button v-if="isApplicant && sale.status === 'AWAITING_RETURN'" class="primary" type="button" :disabled="Boolean(busy)" @click="openPanel('return')">填写回寄物流</button>
        <button v-if="isApplicant && sale.status === 'PENDING_BUYER_REFUND_CONFIRMATION'" class="primary" type="button" :disabled="Boolean(busy)" @click="openPanel('buyer-refund')">确认收到线下退款</button>
        <button v-if="isApplicant && ['PENDING_ADMIN_REVIEW','AWAITING_RETURN'].includes(sale.status)" class="danger" type="button" :disabled="Boolean(busy)" @click="openPanel('cancel')">撤销售后</button>
        <button v-if="isSuperior && sale.status === 'PENDING_OFFLINE_REFUND'" class="primary" type="button" :disabled="Boolean(busy)" @click="openPanel('superior-refund')">确认已线下退款</button>
      </div>

      <div v-if="panel" class="modal-mask" @click.self="panel = undefined">
        <form class="modal action-modal card" @submit.prevent="submitAction">
          <h2>{{ panel === 'return' ? '填写回寄物流' : panel === 'cancel' ? '撤销售后' : panel === 'buyer-refund' ? '确认收到线下退款' : '确认已线下退款' }}</h2>
          <p v-if="panel.includes('refund')" class="notice">系统只记录线下退款确认事实，不会发起线上退款或代付。</p>
          <div v-if="panel === 'return'" class="form-grid">
            <div class="field"><label for="carrier">承运商</label><input id="carrier" v-model="carrier" required /></div>
            <div class="field"><label for="tracking">物流单号</label><input id="tracking" v-model="trackingNo" required /></div>
          </div>
          <div v-if="panel === 'cancel' || panel === 'superior-refund'" class="field">
            <label for="reason">{{ panel === 'cancel' ? '撤销原因' : '退款确认备注（可选）' }}</label>
            <textarea id="reason" v-model="reason" rows="4" :required="panel === 'cancel'" />
          </div>
          <div class="modal-actions">
            <button class="secondary" type="button" :disabled="Boolean(busy)" @click="panel = undefined">返回</button>
            <button class="primary" :disabled="Boolean(busy)">{{ busy ? '提交中…' : '确认提交' }}</button>
          </div>
        </form>
      </div>
    </div>
    <div v-else class="empty card"><p>{{ error || '未找到售后单。' }}</p><button class="secondary" type="button" @click="load">重新加载</button></div>
  </div>
</template>

<style scoped>
.back-link { border: 0; padding: 8px 0; color: var(--muted); background: transparent; }
.detail-title { display: flex; justify-content: space-between; align-items: end; gap: 18px; margin: 22px 0; }
.detail-title h1 { margin: 4px 0; font: 750 clamp(25px, 4vw, 40px) serif; overflow-wrap: anywhere; }
.detail-title p { color: var(--muted); margin: 0; }
.status { flex: none; padding: 8px 12px; color: #92601b; background: #fff0d5; border-radius: 99px; font-weight: 750; }
.summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; overflow: hidden; margin-bottom: 18px; }
.summary > div { display: grid; gap: 5px; padding: 18px; background: var(--paper); }
.summary small { color: var(--muted); }
.progress { padding: 24px; }
.progress h2, .panel h2 { margin: 0 0 18px; font-family: serif; }
.progress article { position: relative; display: grid; grid-template-columns: 24px 1fr; gap: 8px; padding-bottom: 22px; color: #9b928b; }
.progress article:not(:last-child)::after { content: ""; position: absolute; top: 15px; bottom: 0; left: 7px; width: 2px; background: var(--line); }
.progress i { z-index: 1; width: 16px; height: 16px; border: 3px solid #d4cbc2; border-radius: 50%; background: var(--paper); }
.progress article.done i { border-color: var(--green); background: var(--green); }
.progress article.current i { border-color: var(--coral); box-shadow: 0 0 0 4px rgba(244,93,72,.12); }
.progress article.done, .progress article.current { color: var(--ink); }
.progress p, .progress small { color: var(--muted); margin: 5px 0 0; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-top: 18px; }
.panel { padding: 22px; }
.panel p { line-height: 1.7; }
.panel p span { display: inline-block; min-width: 75px; color: var(--muted); }
.proof-upload { display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 12px; padding: 18px 22px; margin-top: 18px; }
.proof-upload p { color: var(--muted); margin: 5px 0 0; }
.proof-upload select { min-height: 44px; padding: 0 10px; border: 1px solid var(--line); border-radius: 11px; background: white; }
.upload { position: relative; display: inline-flex; align-items: center; }
.upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.action-modal { width: min(520px, 100%); padding: 24px; }
.action-modal h2 { margin-top: 0; font-family: serif; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
@media (max-width: 720px) {
  .summary { grid-template-columns: 1fr 1fr; }
  .detail-grid { grid-template-columns: 1fr; }
  .proof-upload { grid-template-columns: 1fr; align-items: stretch; }
}
@media (max-width: 520px) {
  .detail-title { align-items: start; flex-direction: column; }
  .summary { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr; }
  .actions { align-items: stretch; flex-direction: column; }
}
</style>
