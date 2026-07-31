<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminDownload, adminErrorMessage, dateTime, fileSize, isConflictError, money, queryString } from '../api'
import BaseDialog from '../components/admin/BaseDialog.vue'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import DetailDrawer from '../components/admin/DetailDrawer.vue'
import FilterBar from '../components/admin/FilterBar.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { mediaTypeLabel, orderStatusLabel, orderStatusOptions, salesSceneLabel } from '../localization'
import { can } from '../session'
import { notifyError, notifySuccess } from '../toast'

type Order = {
  id: number; orderNo: string; buyerUserId: number; superiorUserId: number
  totalAmountFen: number; status: string; reason?: string; createdAt: string
}
type Item = {
  skuId: number; productName: string; skuName: string; coverUrl?: string
  salesScene: string; unitPriceFen: number; quantity: number; subtotalFen: number
}
type Detail = {
  order: Order; addressJson: string; items: Item[]
  shipment?: { carrierCode: string; carrierName: string; trackingNo: string; shippedAt: string }
  superiorConfirmedAt?: string; adminReviewedAt?: string; autoReceiveAt?: string; completedAt?: string
}
type Note = { id: number; adminId: number; note: string; createdAt: string }
type Proof = {
  proofId: number; orderId: number; mediaType: string; sizeBytes: number
  uploadedBy: number; retainUntil: string; createdAt: string
}
type Filters = { orderNo: string; buyerUserId: string; superiorUserId: string; status: string; from: string; to: string }
type BatchResult = { orderId: number; success: boolean; message: string }
type ActionKind = 'approve' | 'reject' | 'note' | 'delete-proof'

const emptyFilters = (): Filters => ({ orderNo: '', buyerUserId: '', superiorUserId: '', status: '', from: '', to: '' })
const route = useRoute()
const router = useRouter()
const draftFilters = reactive<Filters>(emptyFilters())
const appliedFilters = reactive<Filters>(emptyFilters())
const orders = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const pageLoading = ref(true)
const listError = ref('')
const selected = ref<number[]>([])

const drawerOpen = ref(false)
const detailTarget = ref<Order>()
const detail = ref<Detail>()
const detailNotes = ref<Note[]>([])
const detailProofs = ref<Proof[]>([])
const detailLoading = ref(false)
const detailError = ref('')
let detailRequestSequence = 0

const actionKind = ref<ActionKind>()
const actionReason = ref('')
const actionError = ref('')
const actionSubmitting = ref(false)
const deletingProof = ref<Proof>()

const shipOrder = ref<Order>()
const shipment = reactive({ carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: '' })
const shipError = ref('')
const shipSubmitting = ref(false)

const batchOpen = ref(false)
const batchForm = reactive({ carrierCode: 'SF', carrierName: '顺丰速运' })
const batchItems = ref<Array<{ orderId: number; orderNo: string; trackingNo: string }>>([])
const batchResults = ref<BatchResult[]>([])
const batchError = ref('')
const batchSubmitting = ref(false)

const address = computed<Record<string, string>>(() => {
  try { return detail.value ? JSON.parse(detail.value.addressJson) as Record<string, string> : {} }
  catch { return {} }
})

const appliedSummary = computed(() => {
  const values: string[] = []
  if (appliedFilters.orderNo) values.push(`订单号：${appliedFilters.orderNo}`)
  if (appliedFilters.buyerUserId) values.push(`买家：#${appliedFilters.buyerUserId}`)
  if (appliedFilters.superiorUserId) values.push(`上级：#${appliedFilters.superiorUserId}`)
  if (appliedFilters.status) values.push(`状态：${orderStatusLabel(appliedFilters.status)}`)
  if (appliedFilters.from) values.push(`从：${appliedFilters.from.replace('T', ' ')}`)
  if (appliedFilters.to) values.push(`至：${appliedFilters.to.replace('T', ' ')}`)
  return values.length ? values : ['全部订单']
})

const actionTitle = computed(() => {
  if (actionKind.value === 'approve') return '通过订单审核'
  if (actionKind.value === 'reject') return '拒绝订单'
  if (actionKind.value === 'note') return '添加内部备注'
  return '删除付款凭证'
})
const actionTarget = computed(() => deletingProof.value
  ? `凭证 #${deletingProof.value.proofId}`
  : detail.value?.order.orderNo ?? detailTarget.value?.orderNo ?? '当前订单')
const actionImpact = computed(() => {
  if (actionKind.value === 'approve') return '审核通过后订单进入待发货状态，仓库可继续履约。'
  if (actionKind.value === 'reject') return '订单将被拒绝，后续履约动作不再开放；原因会写入审计记录。'
  if (actionKind.value === 'note') return '备注仅供后台协作查看，不改变订单业务状态。'
  return '凭证将从订单资料中移除，操作原因会写入审计日志。'
})

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' | 'info' {
  if (status === 'COMPLETED') return 'success'
  if (status.endsWith('REJECTED') || status === 'CANCELLED') return 'danger'
  if (status === 'SHIPPED') return 'info'
  return 'warning'
}

function readRouteState() {
  const next = emptyFilters()
  for (const key of Object.keys(next) as Array<keyof Filters>) {
    const value = route.query[key]
    next[key] = typeof value === 'string' ? value : ''
  }
  Object.assign(draftFilters, next)
  Object.assign(appliedFilters, next)
  const requestedPage = Number(route.query.page)
  page.value = Number.isInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1
}

function apiParams(includePage = true) {
  return queryString({
    orderNo: appliedFilters.orderNo,
    buyerUserId: appliedFilters.buyerUserId,
    superiorUserId: appliedFilters.superiorUserId,
    status: appliedFilters.status,
    from: appliedFilters.from ? new Date(appliedFilters.from).toISOString() : '',
    to: appliedFilters.to ? new Date(appliedFilters.to).toISOString() : '',
    page: includePage ? page.value : undefined,
    size: includePage ? size : undefined
  })
}

function routeQuery() {
  const values: Record<string, string> = {}
  for (const [key, value] of Object.entries(appliedFilters)) if (value) values[key] = value
  if (page.value > 1) values.page = String(page.value)
  return values
}

async function load() {
  pageLoading.value = true
  listError.value = ''
  try {
    const result = await adminApi<{ items: Order[]; total: number; page: number; size: number }>(`/orders/search?${apiParams()}`)
    orders.value = result.items
    total.value = result.total
    selected.value = selected.value.filter(id => result.items.some(row => row.id === id && row.status === 'PENDING_SHIPMENT'))
  } catch (cause) {
    listError.value = adminErrorMessage(cause)
  } finally {
    pageLoading.value = false
  }
}

async function navigateToApplied(push = true) {
  const target = { path: '/orders', query: routeQuery() }
  const current = router.resolve(target).fullPath
  if (current === route.fullPath) await load()
  else await (push ? router.push(target) : router.replace(target))
}

async function applyFilters() {
  Object.assign(appliedFilters, draftFilters)
  page.value = 1
  await navigateToApplied()
}

async function resetFilters() {
  Object.assign(draftFilters, emptyFilters())
  Object.assign(appliedFilters, emptyFilters())
  page.value = 1
  await navigateToApplied()
}

async function changePage(value: number) {
  page.value = value
  await navigateToApplied()
}

async function fetchDetail(orderId: number, showLoading = true) {
  const request = ++detailRequestSequence
  if (showLoading) detailLoading.value = true
  detailError.value = ''
  detail.value = undefined
  detailNotes.value = []
  detailProofs.value = []
  try {
    const [value, notes, proofs] = await Promise.all([
      adminApi<Detail>(`/orders/${orderId}`),
      adminApi<Note[]>(`/orders/${orderId}/notes`),
      adminApi<Proof[]>(`/orders/${orderId}/proofs`)
    ])
    if (request !== detailRequestSequence || detailTarget.value?.id !== orderId) return
    detail.value = value
    detailTarget.value = value.order
    detailNotes.value = notes
    detailProofs.value = proofs
  } catch (cause) {
    if (request === detailRequestSequence) detailError.value = adminErrorMessage(cause)
  } finally {
    if (request === detailRequestSequence) detailLoading.value = false
  }
}

function openDetail(row: Order) {
  detailTarget.value = row
  drawerOpen.value = true
  void fetchDetail(row.id)
}

function closeDetail() {
  detailRequestSequence++
  drawerOpen.value = false
  detailTarget.value = undefined
  detail.value = undefined
  detailNotes.value = []
  detailProofs.value = []
  detailError.value = ''
}

function openAction(kind: ActionKind, proof?: Proof) {
  actionKind.value = kind
  deletingProof.value = proof
  actionReason.value = ''
  actionError.value = ''
}

function closeAction() {
  actionKind.value = undefined
  deletingProof.value = undefined
  actionReason.value = ''
  actionError.value = ''
}

async function refreshAfterMutation(orderId: number) {
  await load()
  if (drawerOpen.value && detailTarget.value?.id === orderId) await fetchDetail(orderId, false)
}

async function submitAction() {
  const order = detail.value?.order
  if (!actionKind.value || !order || actionSubmitting.value) return
  actionSubmitting.value = true
  actionError.value = ''
  try {
    if (actionKind.value === 'approve' || actionKind.value === 'reject') {
      await adminApi(`/orders/${order.id}/review`, {
        method: 'POST',
        body: JSON.stringify({ approve: actionKind.value === 'approve', reason: actionReason.value.trim() })
      })
      notifySuccess(actionKind.value === 'approve' ? '订单审核已通过' : '订单已拒绝')
    } else if (actionKind.value === 'note') {
      await adminApi(`/orders/${order.id}/notes`, {
        method: 'POST', body: JSON.stringify({ note: actionReason.value.trim() })
      })
      notifySuccess('内部备注已添加')
    } else if (deletingProof.value) {
      await adminApi(`/order-proofs/${deletingProof.value.proofId}`, {
        method: 'DELETE', body: JSON.stringify({ reason: actionReason.value.trim() })
      })
      notifySuccess('付款凭证已删除')
    }
    closeAction()
    await refreshAfterMutation(order.id)
  } catch (cause) {
    actionError.value = adminErrorMessage(cause)
    if (isConflictError(cause)) await refreshAfterMutation(order.id)
  } finally {
    actionSubmitting.value = false
  }
}

function openShip(row: Order) {
  shipOrder.value = row
  shipment.carrierCode = 'SF'
  shipment.carrierName = '顺丰速运'
  shipment.trackingNo = ''
  shipError.value = ''
}

async function ship() {
  const current = shipOrder.value
  if (!current || shipSubmitting.value) return
  shipSubmitting.value = true
  shipError.value = ''
  try {
    await adminApi(`/orders/${current.id}/ship`, { method: 'POST', body: JSON.stringify(shipment) })
    shipOrder.value = undefined
    shipment.trackingNo = ''
    notifySuccess('订单已发货', `${current.orderNo} 的物流信息已记录。`)
    await refreshAfterMutation(current.id)
  } catch (cause) {
    shipError.value = adminErrorMessage(cause)
    if (isConflictError(cause)) await refreshAfterMutation(current.id)
  } finally {
    shipSubmitting.value = false
  }
}

function openBatchShip() {
  const selectedRows = orders.value.filter(row => selected.value.includes(row.id) && row.status === 'PENDING_SHIPMENT')
  batchItems.value = selectedRows.map(row => ({ orderId: row.id, orderNo: row.orderNo, trackingNo: '' }))
  batchResults.value = []
  batchError.value = ''
  batchOpen.value = true
}

async function submitBatchShip() {
  if (batchSubmitting.value || !batchItems.value.length || batchItems.value.some(item => !item.trackingNo.trim())) return
  batchSubmitting.value = true
  batchError.value = ''
  try {
    const items = batchItems.value.map(item => ({
      orderId: item.orderId,
      carrierCode: batchForm.carrierCode.trim(),
      carrierName: batchForm.carrierName.trim(),
      trackingNo: item.trackingNo.trim()
    }))
    const result = await adminApi<BatchResult[]>('/orders/batch-ship', { method: 'POST', body: JSON.stringify({ items }) })
    batchResults.value = result
    const succeeded = new Set(result.filter(item => item.success).map(item => item.orderId))
    selected.value = selected.value.filter(id => !succeeded.has(id))
    batchItems.value = batchItems.value.filter(item => !succeeded.has(item.orderId))
    if (batchItems.value.length) notifyError('部分订单发货失败', '失败项已保留，可核对后重试。')
    else notifySuccess('批量发货完成', `已成功处理 ${succeeded.size} 笔订单。`)
    await load()
    const openOrderId = detailTarget.value?.id
    if (drawerOpen.value && openOrderId && succeeded.has(openOrderId)) await fetchDetail(openOrderId, false)
  } catch (cause) {
    batchError.value = adminErrorMessage(cause)
  } finally {
    batchSubmitting.value = false
  }
}

async function openProof(proof: Proof) {
  try {
    const result = await adminApi<{ signedUrl: string; expiresAt: string }>(`/order-proofs/${proof.proofId}/download`)
    window.open(result.signedUrl, '_blank', 'noopener,noreferrer')
  } catch (cause) {
    notifyError('凭证打开失败', adminErrorMessage(cause))
  }
}

async function exportCsv() {
  try {
    const blob = await adminDownload(`/orders/export?${apiParams(false)}`)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `订单-${new Date().toISOString().slice(0, 10)}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
    notifySuccess('订单导出已开始', '导出条件与当前已查询结果一致。')
  } catch (cause) {
    notifyError('订单导出失败', adminErrorMessage(cause))
  }
}

watch(() => route.fullPath, () => {
  if (route.path !== '/orders') return
  readRouteState()
  void load()
})

onMounted(() => {
  readRouteState()
  void load()
})
</script>

<template>
  <div>
    <PageHeader title="订单审核与发货" description="从已应用筛选进入订单详情，在完整快照、凭证和时间线中完成处理。">
      <template #actions>
        <button class="secondary" type="button" :disabled="pageLoading" @click="exportCsv">导出当前结果</button>
        <button v-if="can('order:ship')" class="primary" type="button" :disabled="!selected.length" @click="openBatchShip">批量发货（{{ selected.length }}）</button>
      </template>
    </PageHeader>

    <FilterBar :busy="pageLoading" :applied-summary="appliedSummary" @apply="applyFilters" @reset="resetFilters">
      <label class="field"><span>订单号</span><input v-model="draftFilters.orderNo" placeholder="完整或部分订单号" /></label>
      <label class="field"><span>订单状态</span><select v-model="draftFilters.status"><option value="">全部状态</option><option v-for="option in orderStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
      <template #advanced>
        <label class="field"><span>买家编号</span><input v-model="draftFilters.buyerUserId" type="number" min="1" /></label>
        <label class="field"><span>上级编号</span><input v-model="draftFilters.superiorUserId" type="number" min="1" /></label>
        <label class="field"><span>提交时间从</span><input v-model="draftFilters.from" type="datetime-local" /></label>
        <label class="field"><span>提交时间至</span><input v-model="draftFilters.to" type="datetime-local" /></label>
      </template>
    </FilterBar>

    <TableFrame :loading="pageLoading" :error="listError" :empty="!orders.length" empty-title="暂无符合条件的订单" empty-text="可重置筛选或调整查询条件。" label="订单列表" @retry="load">
      <table class="responsive-table">
        <thead><tr><th class="selection-column">选择</th><th>订单号</th><th>买家 / 上级</th><th>金额</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in orders" :key="row.id">
            <td data-label="选择"><input v-if="row.status === 'PENDING_SHIPMENT' && can('order:ship')" v-model="selected" :value="row.id" type="checkbox" :aria-label="`选择订单 ${row.orderNo}`" /></td>
            <td data-label="订单号"><button class="link-button" type="button" @click="openDetail(row)">{{ row.orderNo }}</button></td>
            <td data-label="买家 / 上级">#{{ row.buyerUserId }} / #{{ row.superiorUserId }}</td>
            <td data-label="金额">{{ money(row.totalAmountFen) }}</td>
            <td data-label="状态"><StatusTag :tone="statusTone(row.status)" :label="orderStatusLabel(row.status)" /></td>
            <td data-label="提交时间">{{ dateTime(row.createdAt) }}</td>
            <td data-label="操作"><button class="primary" type="button" @click="openDetail(row)">查看并处理</button></td>
          </tr>
        </tbody>
      </table>
      <template #footer><PaginationBar :page="page" :size="size" :total="total" @change="changePage" /></template>
    </TableFrame>

    <DetailDrawer
      :model-value="drawerOpen"
      :title="detailTarget ? `订单 ${detailTarget.orderNo}` : '订单详情'"
      description="服务端最新订单快照、凭证、备注与可执行动作"
      width="min(900px, 100vw)"
      @update:model-value="value => { if (!value) closeDetail() }"
    >
      <div v-if="detailLoading" class="drawer-loading" role="status"><span class="state-spinner"></span>正在加载订单完整资料…</div>
      <InlineAlert v-else-if="detailError" title="订单详情加载失败" :message="detailError" retryable @retry="detailTarget && fetchDetail(detailTarget.id)" />
      <template v-else-if="detail">
        <div class="summary-grid">
          <div><small>订单状态</small><StatusTag :tone="statusTone(detail.order.status)" :label="orderStatusLabel(detail.order.status)" /></div>
          <div><small>线下应收</small><b>{{ money(detail.order.totalAmountFen) }}</b></div>
          <div><small>买家 / 上级</small><b>#{{ detail.order.buyerUserId }} / #{{ detail.order.superiorUserId }}</b></div>
        </div>
        <section class="detail-section"><h3>商品明细</h3><article v-for="item in detail.items" :key="item.skuId" class="order-item"><img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.productName" /><div><b>{{ item.productName }}</b><small>{{ item.skuName }} · {{ salesSceneLabel(item.salesScene) }}</small></div><span>{{ money(item.unitPriceFen) }} × {{ item.quantity }}<b>{{ money(item.subtotalFen) }}</b></span></article></section>
        <div class="detail-columns">
          <section class="detail-section"><h3>收货地址快照</h3><p class="address">{{ address.recipientName }} · {{ address.phone }}<br />{{ address.province }}{{ address.city }}{{ address.district }}{{ address.detailAddress }} {{ address.postalCode }}</p><h3>物流信息</h3><p v-if="detail.shipment" class="address">{{ detail.shipment.carrierName }}（{{ detail.shipment.carrierCode }}）<br />{{ detail.shipment.trackingNo }} · {{ dateTime(detail.shipment.shippedAt) }}</p><p v-else class="muted">尚未发货。</p></section>
          <section class="detail-section"><h3>处理时间线</h3><ol class="timeline"><li><b>订单提交</b><span>{{ dateTime(detail.order.createdAt) }}</span></li><li><b>上级确认</b><span>{{ dateTime(detail.superiorConfirmedAt) }}</span></li><li><b>后台审核</b><span>{{ dateTime(detail.adminReviewedAt) }}</span></li><li><b>发货</b><span>{{ dateTime(detail.shipment?.shippedAt) }}</span></li><li><b>自动收货截止</b><span>{{ dateTime(detail.autoReceiveAt) }}</span></li><li><b>完成</b><span>{{ dateTime(detail.completedAt) }}</span></li></ol></section>
        </div>
        <section class="detail-section"><h3>付款凭证</h3><article v-for="proof in detailProofs" :key="proof.proofId" class="proof"><div><b>{{ mediaTypeLabel(proof.mediaType) }}</b><small>{{ fileSize(proof.sizeBytes) }} · 上传人 #{{ proof.uploadedBy }} · 保留至 {{ dateTime(proof.retainUntil) }}</small></div><button class="secondary" type="button" @click="openProof(proof)">查看</button><button v-if="can('order:audit')" class="danger" type="button" @click="openAction('delete-proof', proof)">删除</button></article><p v-if="!detailProofs.length" class="muted">此订单没有上传付款凭证（现金付款可不上传）。</p></section>
        <section class="detail-section"><h3>内部备注</h3><article v-for="note in detailNotes" :key="note.id" class="note"><b>#{{ note.adminId }}</b><span>{{ note.note }}</span><small>{{ dateTime(note.createdAt) }}</small></article><p v-if="!detailNotes.length" class="muted">暂无内部备注。</p></section>
      </template>
      <template v-if="detail" #footer>
        <button class="secondary" type="button" :disabled="actionSubmitting" @click="closeDetail">关闭</button>
        <button class="secondary" type="button" :disabled="actionSubmitting" @click="openAction('note')">添加备注</button>
        <template v-if="detail.order.status === 'PENDING_ADMIN_REVIEW' && can('order:review')"><button class="danger" type="button" @click="openAction('reject')">拒绝</button><button class="primary" type="button" @click="openAction('approve')">通过审核</button></template>
        <button v-if="detail.order.status === 'PENDING_SHIPMENT' && can('order:ship')" class="primary" type="button" @click="openShip(detail.order)">登记发货</button>
      </template>
    </DetailDrawer>

    <BusinessActionDialog
      :model-value="Boolean(actionKind)"
      :title="actionTitle"
      :target="actionTarget"
      :impact="actionImpact"
      :current-state="detail ? orderStatusLabel(detail.order.status) : ''"
      :next-state="actionKind === 'approve' ? '待发货' : actionKind === 'reject' ? '后台已拒绝' : ''"
      v-model:reason="actionReason"
      :reason-label="actionKind === 'note' ? '内部备注' : '操作原因'"
      :requires-reason="actionKind !== 'approve'"
      :confirm-label="actionKind === 'approve' ? '确认通过' : actionKind === 'reject' ? '确认拒绝' : actionKind === 'note' ? '保存备注' : '确认删除'"
      :danger="actionKind === 'reject' || actionKind === 'delete-proof'"
      :submitting="actionSubmitting"
      :error="actionError"
      @update:model-value="value => { if (!value) closeAction() }"
      @submit="submitAction"
    />

    <BaseDialog :model-value="Boolean(shipOrder)" title="登记订单发货" :description="shipOrder?.orderNo" :submitting="shipSubmitting" @update:model-value="value => { if (!value) shipOrder = undefined }">
      <form id="ship-order-form" class="dialog-form" @submit.prevent="ship"><div class="business-summary"><div><small>当前状态</small><b>待发货 → 已发货</b></div><div class="business-summary__impact"><small>影响说明</small><p>物流信息提交后将开放买家收货流程，并开始计算自动收货期限。</p></div></div><label class="field"><span>承运商编码</span><input v-model="shipment.carrierCode" required /></label><label class="field"><span>承运商名称</span><input v-model="shipment.carrierName" required /></label><label class="field"><span>物流单号</span><input v-model="shipment.trackingNo" required /></label><InlineAlert v-if="shipError" title="发货未完成" :message="shipError" /></form>
      <template #footer="{ close }"><button class="secondary" type="button" autofocus :disabled="shipSubmitting" @click="close">取消</button><button class="primary" form="ship-order-form" :disabled="shipSubmitting">{{ shipSubmitting ? '提交中…' : '确认发货' }}</button></template>
    </BaseDialog>

    <BaseDialog v-model="batchOpen" title="批量发货预检" description="逐项核对物流单号；成功项自动移出，失败项保留重试。" width="min(760px, calc(100vw - 32px))" :submitting="batchSubmitting">
      <form id="batch-ship-form" class="dialog-form" @submit.prevent="submitBatchShip"><div class="batch-carrier"><label class="field"><span>承运商编码</span><input v-model="batchForm.carrierCode" required /></label><label class="field"><span>承运商名称</span><input v-model="batchForm.carrierName" required /></label></div><div class="batch-list"><label v-for="item in batchItems" :key="item.orderId" class="batch-row"><span><b>{{ item.orderNo }}</b><small>#{{ item.orderId }}</small></span><input v-model="item.trackingNo" required placeholder="逐单填写物流单号" /><StatusTag v-if="batchResults.find(result => result.orderId === item.orderId)" :tone="batchResults.find(result => result.orderId === item.orderId)?.success ? 'success' : 'danger'" :label="batchResults.find(result => result.orderId === item.orderId)?.message" /></label><p v-if="!batchItems.length" class="empty-copy">全部选中订单均已发货成功。</p></div><InlineAlert v-if="batchError" title="批量发货请求失败" :message="batchError" /></form>
      <template #footer="{ close }"><button class="secondary" type="button" :disabled="batchSubmitting" @click="close">{{ batchItems.length ? '稍后处理' : '完成' }}</button><button v-if="batchItems.length" class="primary" form="batch-ship-form" :disabled="batchSubmitting || batchItems.some(item => !item.trackingNo.trim())">{{ batchSubmitting ? '处理中…' : batchResults.length ? '重试失败项' : `确认发货（${batchItems.length}）` }}</button></template>
    </BaseDialog>
  </div>
</template>

<style scoped>
.selection-column{width:54px}.drawer-loading{min-height:260px;display:flex;align-items:center;justify-content:center;gap:12px;color:var(--color-text-muted)}.summary-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.summary-grid>div{padding:14px;background:var(--color-surface-subtle);border-radius:10px}.summary-grid small,.summary-grid b{display:block}.summary-grid small{margin-bottom:6px;color:var(--color-text-muted)}.detail-columns{display:grid;grid-template-columns:1.2fr .8fr;gap:20px}.detail-section h3{margin:24px 0 10px;font-family:serif}.order-item{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid var(--color-border)}.order-item img{width:54px;height:54px;object-fit:cover;border-radius:8px}.order-item small,.order-item span b{display:block}.order-item small,.order-item span{color:var(--color-text-muted);font-size:12px}.order-item span{text-align:right}.address{padding:12px;background:var(--color-surface-subtle);border-radius:9px;line-height:1.7}.muted,.empty-copy{color:var(--color-text-muted)}.timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid var(--color-border)}.timeline span{color:var(--color-text-muted);font-size:12px}.proof{display:flex;align-items:center;gap:8px;padding:10px;border:1px solid var(--color-border);border-radius:10px;margin-bottom:8px}.proof>div{flex:1}.proof small{display:block;color:var(--color-text-muted);margin-top:4px}.note{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:12px;padding:10px 0;border-bottom:1px solid var(--color-border)}.note small{color:var(--color-text-muted)}.dialog-form{display:grid;gap:13px}.batch-carrier{display:grid;grid-template-columns:1fr 1fr;gap:12px}.batch-list{display:grid;gap:8px;margin-top:4px}.batch-row{display:grid;grid-template-columns:minmax(150px,.7fr) minmax(180px,1fr) auto;align-items:center;gap:10px;padding:10px;border:1px solid var(--color-border);border-radius:10px}.batch-row span b,.batch-row span small{display:block}.batch-row span small{color:var(--color-text-muted)}.batch-row>input{min-height:40px;padding:8px 10px;border:1px solid var(--color-border-strong);border-radius:8px}
@media(max-width:720px){.summary-grid,.detail-columns,.batch-carrier{grid-template-columns:1fr}.proof{align-items:flex-start;flex-wrap:wrap}.note{grid-template-columns:auto 1fr}.note small{grid-column:2}.batch-row{grid-template-columns:1fr}.order-item{grid-template-columns:auto 1fr}.order-item>span{grid-column:2;text-align:left}}
</style>
