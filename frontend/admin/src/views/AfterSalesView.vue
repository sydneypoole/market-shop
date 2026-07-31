<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminErrorMessage, dateTime, fileSize, isConflictError } from '../api'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import DetailDrawer from '../components/admin/DetailDrawer.vue'
import FilterBar from '../components/admin/FilterBar.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { afterSaleStatusLabel, afterSaleStatusOptions, afterSaleTypeLabel, mediaTypeLabel, proofTypeLabel } from '../localization'
import { notifyError, notifySuccess } from '../toast'

type AfterSale = {
  id: number; afterSaleNo: string; orderId: number; applicantUserId: number; superiorUserId: number
  type: string; status: string; reason: string; adminReason?: string; returnAddressJson?: string
  returnCarrier?: string; returnTrackingNo?: string; createdAt: string; completedAt?: string
}
type Proof = {
  id: number; afterSaleId: number; proofType: string; mediaType: string; sizeBytes: number
  uploadedByUserId?: number; retainUntil: string; createdAt: string
}
type Settings = { afterSaleReturnReceiver: string; afterSaleReturnPhone: string; afterSaleReturnAddress: string }
type ActionKind = 'approve' | 'reject' | 'confirm-return'

const route = useRoute()
const router = useRouter()
const rows = ref<AfterSale[]>([])
const draftFilters = reactive({ status: '', keyword: '' })
const appliedFilters = reactive({ status: '', keyword: '' })
const page = ref(1)
const size = 20
const pageLoading = ref(true)
const listError = ref('')
const settings = ref<Settings>()
const settingsError = ref('')

const drawerOpen = ref(false)
const detail = ref<AfterSale>()
const proofs = ref<Proof[]>([])
const proofLoading = ref(false)
const proofError = ref('')
let proofRequestSequence = 0

const actionKind = ref<ActionKind>()
const actionReason = ref('')
const actionError = ref('')
const actionSubmitting = ref(false)

const filtered = computed(() => {
  const value = appliedFilters.keyword.trim().toLowerCase()
  if (!value) return rows.value
  return rows.value.filter(row =>
    row.afterSaleNo.toLowerCase().includes(value)
    || String(row.orderId).includes(value)
    || String(row.applicantUserId).includes(value)
  )
})
const visible = computed(() => filtered.value.slice((page.value - 1) * size, page.value * size))
const appliedSummary = computed(() => {
  const result = []
  if (appliedFilters.status) result.push(`状态：${afterSaleStatusLabel(appliedFilters.status)}`)
  if (appliedFilters.keyword) result.push(`关键词：${appliedFilters.keyword}`)
  return result.length ? result : ['全部售后单']
})
const actionTitle = computed(() => actionKind.value === 'approve' ? '通过售后审核' : actionKind.value === 'reject' ? '拒绝售后申请' : '确认退货已验收')
const actionImpact = computed(() => {
  if (actionKind.value === 'approve') return detail.value?.type === 'RETURN_REFUND'
    ? '通过后将向用户展示下方退货信息，并进入待用户回寄流程。'
    : '通过后进入线下退款确认流程，系统只记录处理事实。'
  if (actionKind.value === 'reject') return '售后申请将被拒绝，原因会展示在售后详情并写入审计记录。'
  return '确认验收后流程进入线下退款阶段；库存与积分状态由服务端状态机处理。'
})

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' | 'info' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'REJECTED' || status === 'CANCELLED') return 'danger'
  if (status === 'RETURN_SHIPPED') return 'info'
  return 'warning'
}

function readRouteState() {
  draftFilters.status = typeof route.query.status === 'string' ? route.query.status : ''
  draftFilters.keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  Object.assign(appliedFilters, draftFilters)
  const requested = Number(route.query.page)
  page.value = Number.isInteger(requested) && requested > 0 ? requested : 1
}

async function loadSettings() {
  settingsError.value = ''
  try { settings.value = await adminApi<Settings>('/settings') }
  catch (cause) {
    settings.value = undefined
    settingsError.value = adminErrorMessage(cause)
  }
}

async function load() {
  pageLoading.value = true
  listError.value = ''
  try {
    rows.value = await adminApi<AfterSale[]>(`/after-sales${appliedFilters.status ? `?status=${encodeURIComponent(appliedFilters.status)}` : ''}`)
  } catch (cause) {
    listError.value = adminErrorMessage(cause)
  } finally {
    pageLoading.value = false
  }
}

function queryForApplied() {
  return {
    ...(appliedFilters.status ? { status: appliedFilters.status } : {}),
    ...(appliedFilters.keyword ? { keyword: appliedFilters.keyword } : {}),
    ...(page.value > 1 ? { page: String(page.value) } : {})
  }
}

async function navigateApplied() {
  const target = { path: '/after-sales', query: queryForApplied() }
  if (router.resolve(target).fullPath === route.fullPath) await load()
  else await router.push(target)
}

async function applyFilters() {
  Object.assign(appliedFilters, draftFilters)
  page.value = 1
  await navigateApplied()
}

async function resetFilters() {
  Object.assign(draftFilters, { status: '', keyword: '' })
  Object.assign(appliedFilters, draftFilters)
  page.value = 1
  await navigateApplied()
}

async function changePage(value: number) {
  page.value = value
  await navigateApplied()
}

async function loadProofs(row: AfterSale) {
  const request = ++proofRequestSequence
  proofs.value = []
  proofError.value = ''
  proofLoading.value = true
  try {
    const result = await adminApi<Proof[]>(`/after-sales/${row.id}/proofs`)
    if (request !== proofRequestSequence || detail.value?.id !== row.id) return
    proofs.value = result
  } catch (cause) {
    if (request === proofRequestSequence) proofError.value = adminErrorMessage(cause)
  } finally {
    if (request === proofRequestSequence) proofLoading.value = false
  }
}

function openDetail(row: AfterSale) {
  detail.value = row
  drawerOpen.value = true
  void loadProofs(row)
}

function closeDetail() {
  proofRequestSequence++
  drawerOpen.value = false
  detail.value = undefined
  proofs.value = []
  proofError.value = ''
  proofLoading.value = false
}

function openAction(kind: ActionKind) {
  if (!detail.value) return
  if (kind === 'approve' && detail.value.type === 'RETURN_REFUND' && !settings.value) {
    actionError.value = '退货信息尚未成功加载，请先重试系统配置。'
  } else {
    actionError.value = ''
  }
  actionReason.value = kind === 'confirm-return' ? '退货已验收入库' : ''
  actionKind.value = kind
}

function closeAction() {
  actionKind.value = undefined
  actionReason.value = ''
  actionError.value = ''
}

function returnAddress(row: AfterSale) {
  if (!row.returnAddressJson) return undefined
  try { return JSON.parse(row.returnAddressJson) as { receiver: string; phone: string; address: string } }
  catch { return undefined }
}

async function refreshCurrent(rowId: number) {
  await load()
  if (!drawerOpen.value) return
  const current = rows.value.find(row => row.id === rowId)
  if (!current) {
    closeDetail()
    return
  }
  detail.value = current
  await loadProofs(current)
}

async function submitAction() {
  const row = detail.value
  const kind = actionKind.value
  if (!row || !kind || actionSubmitting.value) return
  if (kind === 'approve' && row.type === 'RETURN_REFUND' && !settings.value) {
    actionError.value = '退货信息尚未成功加载，请先重试系统配置。'
    return
  }
  actionSubmitting.value = true
  actionError.value = ''
  try {
    if (kind === 'approve' || kind === 'reject') {
      const returnAddressJson = kind === 'approve' && row.type === 'RETURN_REFUND' && settings.value
        ? JSON.stringify({ receiver: settings.value.afterSaleReturnReceiver, phone: settings.value.afterSaleReturnPhone, address: settings.value.afterSaleReturnAddress })
        : null
      await adminApi(`/after-sales/${row.id}/review`, {
        method: 'POST',
        body: JSON.stringify({ approve: kind === 'approve', reason: actionReason.value.trim(), returnAddressJson })
      })
    } else {
      await adminApi(`/after-sales/${row.id}/confirm-return-received`, {
        method: 'POST', body: JSON.stringify({ reason: actionReason.value.trim() })
      })
    }
    notifySuccess(kind === 'approve' ? '售后审核已通过' : kind === 'reject' ? '售后申请已拒绝' : '退货验收已确认')
    closeAction()
    await refreshCurrent(row.id)
  } catch (cause) {
    actionError.value = adminErrorMessage(cause)
    if (isConflictError(cause)) await refreshCurrent(row.id)
  } finally {
    actionSubmitting.value = false
  }
}

async function openProof(proof: Proof) {
  try {
    const value = await adminApi<{ signedUrl: string; expiresAt: string }>(`/after-sale-proofs/${proof.id}/download`)
    window.open(value.signedUrl, '_blank', 'noopener,noreferrer')
  } catch (cause) {
    notifyError('凭证打开失败', adminErrorMessage(cause))
  }
}

watch(() => route.fullPath, () => {
  if (route.path !== '/after-sales') return
  readRouteState()
  void load()
})

onMounted(() => {
  readRouteState()
  void Promise.all([load(), loadSettings()])
})
</script>

<template>
  <div>
    <PageHeader title="售后处理" description="从列表进入售后详情，核对申请、凭证、退货配置与处理进度后再执行状态动作。" />
    <FilterBar :busy="pageLoading" :applied-summary="appliedSummary" @apply="applyFilters" @reset="resetFilters">
      <label class="field"><span>关键词</span><input v-model="draftFilters.keyword" placeholder="售后单号 / 订单 / 用户编号" /></label>
      <label class="field"><span>售后状态</span><select v-model="draftFilters.status"><option value="">全部状态</option><option v-for="option in afterSaleStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
    </FilterBar>
    <InlineAlert v-if="settingsError" tone="warning" title="退货配置暂不可用" :message="`${settingsError}；退货退款审核将保持锁定。`" retryable @retry="loadSettings" />
    <TableFrame :loading="pageLoading" :error="listError" :empty="!visible.length" empty-title="暂无符合条件的售后记录" label="售后列表" @retry="load">
      <table class="responsive-table"><thead><tr><th>售后单号</th><th>订单 / 用户</th><th>类型</th><th>申请原因</th><th>状态</th><th>回寄物流</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in visible" :key="row.id"><td data-label="售后单号"><button class="link-button" type="button" @click="openDetail(row)">{{ row.afterSaleNo }}</button></td><td data-label="订单 / 用户">#{{ row.orderId }} / #{{ row.applicantUserId }}</td><td data-label="类型">{{ afterSaleTypeLabel(row.type) }}</td><td class="reason" data-label="申请原因">{{ row.reason }}</td><td data-label="状态"><StatusTag :tone="statusTone(row.status)" :label="afterSaleStatusLabel(row.status)" /></td><td data-label="回寄物流">{{ row.returnCarrier ? `${row.returnCarrier} ${row.returnTrackingNo}` : '—' }}</td><td data-label="操作"><button class="primary" type="button" @click="openDetail(row)">查看并处理</button></td></tr>
      </tbody></table>
      <template #footer><PaginationBar :page="page" :size="size" :total="filtered.length" @change="changePage" /></template>
    </TableFrame>

    <DetailDrawer :model-value="drawerOpen" :title="detail ? `售后 ${detail.afterSaleNo}` : '售后详情'" description="申请资料、退货信息、处理进度与服务端允许动作" width="min(860px, 100vw)" @update:model-value="value => { if (!value) closeDetail() }">
      <template v-if="detail">
        <div class="summary"><div><small>状态</small><StatusTag :tone="statusTone(detail.status)" :label="afterSaleStatusLabel(detail.status)" /></div><div><small>订单 / 买家 / 上级</small><b>#{{ detail.orderId }} / #{{ detail.applicantUserId }} / #{{ detail.superiorUserId }}</b></div><div><small>类型</small><b>{{ afterSaleTypeLabel(detail.type) }}</b></div></div>
        <div class="columns"><section><h3>申请信息</h3><dl><dt>申请原因</dt><dd>{{ detail.reason }}</dd><dt>后台意见</dt><dd>{{ detail.adminReason || '—' }}</dd><dt>申请时间</dt><dd>{{ dateTime(detail.createdAt) }}</dd><dt>完成时间</dt><dd>{{ dateTime(detail.completedAt) }}</dd></dl><h3>退货信息</h3><dl v-if="returnAddress(detail)"><dt>收件人</dt><dd>{{ returnAddress(detail)?.receiver }} · {{ returnAddress(detail)?.phone }}</dd><dt>地址</dt><dd>{{ returnAddress(detail)?.address }}</dd></dl><p v-else class="muted">当前流程没有退货地址。</p><dl><dt>回寄物流</dt><dd>{{ detail.returnCarrier ? `${detail.returnCarrier} ${detail.returnTrackingNo}` : '—' }}</dd></dl></section><section><h3>处理进度</h3><ol class="timeline"><li class="done"><b>提交售后申请</b><span>{{ dateTime(detail.createdAt) }}</span></li><li :class="{ done: detail.status !== 'PENDING_ADMIN_REVIEW' }"><b>后台审核</b><span>{{ detail.adminReason || '等待处理' }}</span></li><li :class="{ done: ['RETURN_SHIPPED','PENDING_OFFLINE_REFUND','PENDING_BUYER_REFUND_CONFIRMATION','COMPLETED'].includes(detail.status) }"><b>退货验收（如需要）</b><span>{{ detail.returnTrackingNo || '—' }}</span></li><li :class="{ done: ['PENDING_BUYER_REFUND_CONFIRMATION','COMPLETED'].includes(detail.status) }"><b>直属上级线下退款</b><span>系统只记录确认事实</span></li><li :class="{ done: detail.status === 'COMPLETED' }"><b>买家确认到账</b><span>{{ dateTime(detail.completedAt) }}</span></li></ol></section></div>
        <section><h3>售后凭证</h3><div v-if="proofLoading" class="proof-state" role="status"><span class="state-spinner"></span>正在加载当前售后单凭证…</div><InlineAlert v-else-if="proofError" title="凭证加载失败" :message="proofError" retryable @retry="loadProofs(detail)" /><div v-else class="proofs"><article v-for="proof in proofs" :key="proof.id"><span><b>{{ proofTypeLabel(proof.proofType) }}</b><small>{{ mediaTypeLabel(proof.mediaType) }} · {{ fileSize(proof.sizeBytes) }} · 上传人 #{{ proof.uploadedByUserId }}</small></span><button class="secondary" type="button" @click="openProof(proof)">查看凭证</button></article><p v-if="!proofs.length" class="muted">未上传售后凭证。</p></div></section>
      </template>
      <template v-if="detail" #footer><button class="secondary" type="button" :disabled="actionSubmitting" @click="closeDetail">关闭</button><template v-if="detail.status === 'PENDING_ADMIN_REVIEW'"><button class="danger" type="button" @click="openAction('reject')">拒绝</button><button class="primary" type="button" :disabled="detail.type === 'RETURN_REFUND' && !settings" @click="openAction('approve')">通过审核</button></template><button v-if="detail.status === 'RETURN_SHIPPED'" class="primary" type="button" @click="openAction('confirm-return')">确认退货验收</button></template>
    </DetailDrawer>

    <BusinessActionDialog :model-value="Boolean(actionKind)" :title="actionTitle" :target="detail?.afterSaleNo || '当前售后单'" :impact="actionImpact" :current-state="detail ? afterSaleStatusLabel(detail.status) : ''" :next-state="actionKind === 'approve' ? (detail?.type === 'RETURN_REFUND' ? '待用户回寄' : '待线下退款') : actionKind === 'reject' ? '已拒绝' : '待线下退款'" v-model:reason="actionReason" :requires-reason="actionKind !== 'approve'" :danger="actionKind === 'reject'" :confirm-label="actionKind === 'approve' ? '确认通过' : actionKind === 'reject' ? '确认拒绝' : '确认已验收'" :submitting="actionSubmitting" :error="actionError" @update:model-value="value => { if (!value) closeAction() }" @submit="submitAction">
      <div v-if="actionKind === 'approve' && detail?.type === 'RETURN_REFUND'" class="return-card"><small>将发送给用户的退货信息</small><template v-if="settings"><b>{{ settings.afterSaleReturnReceiver }} · {{ settings.afterSaleReturnPhone }}</b><p>{{ settings.afterSaleReturnAddress }}</p></template><InlineAlert v-else tone="warning" title="退货配置不可用" message="请先重试加载系统配置。" /></div>
    </BusinessActionDialog>
  </div>
</template>

<style scoped>
.reason{max-width:240px;overflow:hidden;text-overflow:ellipsis}.summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.summary>div{padding:13px;background:var(--color-surface-subtle);border-radius:10px}.summary small,.summary b{display:block}.summary small{margin-bottom:5px;color:var(--color-text-muted)}.columns{display:grid;grid-template-columns:1fr 1fr;gap:24px}.columns h3,.admin-dialog__body h3{font-family:serif;margin:22px 0 10px}dl{display:grid;grid-template-columns:90px 1fr;gap:8px;margin:0;padding:13px;background:var(--color-surface-subtle);border-radius:10px}dt{color:var(--color-text-muted)}dd{margin:0}.muted{color:var(--color-text-muted)}.timeline{list-style:none;margin:0;padding:0}.timeline li{padding:10px 10px 10px 28px;position:relative;border-left:2px solid var(--color-border)}.timeline li:before{content:'';position:absolute;left:-7px;top:15px;width:10px;height:10px;border-radius:50%;background:#ccd3cf}.timeline li.done{border-color:var(--color-brand)}.timeline li.done:before{background:var(--color-brand)}.timeline b,.timeline span{display:block}.timeline span{color:var(--color-text-muted);font-size:12px;margin-top:3px}.proof-state{display:flex;align-items:center;gap:10px;padding:24px;color:var(--color-text-muted)}.proofs article{display:flex;align-items:center;gap:12px;padding:10px;border:1px solid var(--color-border);border-radius:9px;margin-bottom:8px}.proofs article>span{flex:1}.proofs small{display:block;color:var(--color-text-muted);margin-top:4px}.return-card{padding:13px;margin-bottom:13px;border:1px solid var(--color-border);border-radius:10px;background:var(--color-surface-subtle)}.return-card small,.return-card b{display:block}.return-card small{color:var(--color-text-muted)}.return-card b{margin-top:5px}.return-card p{margin:5px 0 0;line-height:1.6}
@media(max-width:720px){.summary,.columns{grid-template-columns:1fr}.proofs article{align-items:flex-start;flex-wrap:wrap}}
</style>
