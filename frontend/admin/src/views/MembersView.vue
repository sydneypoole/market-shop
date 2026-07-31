<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminErrorMessage, dateTime, isConflictError, money, queryString } from '../api'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import DetailDrawer from '../components/admin/DetailDrawer.vue'
import FilterBar from '../components/admin/FilterBar.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { evidenceStatusLabel, evidenceTypeLabel, ledgerEntryLabel, levelTriggerLabel, memberLevelLabel, memberLevelOptions, memberStatusLabel, memberStatusOptions } from '../localization'
import { can } from '../session'
import { notifySuccess } from '../toast'

type Member = {
  userId: number; publicId: string; nickname: string; status: string; levelCode: string; levelName: string
  superiorUserId?: number; directCount: number; qualifiedDirectCount: number; availablePoints: number
  frozenPoints: number; createdAt: string
}
type Detail = {
  member: Member
  evidence: Array<{ id: number; type: string; status: string; valueJson: string; createdAt: string }>
  levelChanges: Array<{ id: number; beforeLevel: string; afterLevel: string; triggerType: string; reason?: string; occurredAt: string }>
  ledger: Array<{
    id: number; entryType: string; availableDelta: number; frozenDelta: number; sourceOrderId?: number
    ruleVersionId?: number; originalEntryId?: number; frozenBatchId?: number
    frozenBatchOriginalPoints?: number; frozenBatchRemainingPoints?: number
    frozenBatchStatus?: string; occurredAt: string
  }>
}
type Filters = { keyword: string; levelCode: string; status: string }
type ActionKind = 'status' | 'recompute'

const route = useRoute()
const router = useRouter()
const draftFilters = reactive<Filters>({ keyword: '', levelCode: '', status: '' })
const appliedFilters = reactive<Filters>({ keyword: '', levelCode: '', status: '' })
const rows = ref<Member[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const pageLoading = ref(true)
const listError = ref('')

const drawerOpen = ref(false)
const detailTarget = ref<Member>()
const detail = ref<Detail>()
const detailLoading = ref(false)
const detailError = ref('')
let detailSequence = 0

const actionKind = ref<ActionKind>()
const actionTarget = ref<Member>()
const actionReason = ref('')
const nextStatus = ref('')
const requestId = ref('')
const actionError = ref('')
const actionSubmitting = ref(false)

const appliedSummary = computed(() => {
  const result = []
  if (appliedFilters.keyword) result.push(`关键词：${appliedFilters.keyword}`)
  if (appliedFilters.levelCode) result.push(`等级：${memberLevelLabel(appliedFilters.levelCode)}`)
  if (appliedFilters.status) result.push(`状态：${memberStatusLabel(appliedFilters.status)}`)
  return result.length ? result : ['全部会员']
})

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  return status === 'ACTIVE' ? 'success' : status === 'LOCKED' ? 'warning' : status === 'DISABLED' ? 'danger' : 'neutral'
}

function readRouteState() {
  for (const key of Object.keys(draftFilters) as Array<keyof Filters>) {
    const value = route.query[key]
    draftFilters[key] = typeof value === 'string' ? value : ''
  }
  Object.assign(appliedFilters, draftFilters)
  const requested = Number(route.query.page)
  page.value = Number.isInteger(requested) && requested > 0 ? requested : 1
}

async function load() {
  pageLoading.value = true
  listError.value = ''
  try {
    const q = queryString({ ...appliedFilters, page: page.value, size })
    const result = await adminApi<{ items: Member[]; total: number; page: number; size: number }>(`/members?${q}`)
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    listError.value = adminErrorMessage(cause)
  } finally {
    pageLoading.value = false
  }
}

function routeQuery() {
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(appliedFilters)) if (value) result[key] = value
  if (page.value > 1) result.page = String(page.value)
  return result
}

async function navigateApplied() {
  const target = { path: '/members', query: routeQuery() }
  if (router.resolve(target).fullPath === route.fullPath) await load()
  else await router.push(target)
}
async function applyFilters() { Object.assign(appliedFilters, draftFilters); page.value = 1; await navigateApplied() }
async function resetFilters() { Object.assign(draftFilters, { keyword: '', levelCode: '', status: '' }); Object.assign(appliedFilters, draftFilters); page.value = 1; await navigateApplied() }
async function changePage(value: number) { page.value = value; await navigateApplied() }

async function fetchDetail(row: Member) {
  const request = ++detailSequence
  detail.value = undefined
  detailLoading.value = true
  detailError.value = ''
  try {
    const result = await adminApi<Detail>(`/members/${row.userId}`)
    if (request !== detailSequence || detailTarget.value?.userId !== row.userId) return
    detail.value = result
    detailTarget.value = result.member
  } catch (cause) {
    if (request === detailSequence) detailError.value = adminErrorMessage(cause)
  } finally {
    if (request === detailSequence) detailLoading.value = false
  }
}

function openDetail(row: Member) { detailTarget.value = row; drawerOpen.value = true; void fetchDetail(row) }
function closeDetail() { detailSequence++; drawerOpen.value = false; detailTarget.value = undefined; detail.value = undefined; detailError.value = '' }

function openAction(kind: ActionKind, row: Member) {
  actionKind.value = kind
  actionTarget.value = row
  actionReason.value = ''
  nextStatus.value = row.status
  requestId.value = crypto.randomUUID()
  actionError.value = ''
}
function closeAction() { actionKind.value = undefined; actionTarget.value = undefined; actionReason.value = ''; nextStatus.value = ''; requestId.value = ''; actionError.value = '' }

async function refreshMember(userId: number) {
  await load()
  if (drawerOpen.value && detailTarget.value?.userId === userId) {
    const row = rows.value.find(item => item.userId === userId) ?? detailTarget.value
    await fetchDetail(row)
  }
}

async function submitAction() {
  const row = actionTarget.value
  const kind = actionKind.value
  if (!row || !kind || actionSubmitting.value) return
  actionSubmitting.value = true
  actionError.value = ''
  try {
    if (kind === 'status') {
      await adminApi(`/members/${row.userId}/status`, {
        method: 'PUT', body: JSON.stringify({ status: nextStatus.value, reason: actionReason.value.trim(), requestId: requestId.value })
      })
      notifySuccess('会员状态已更新')
    } else {
      await adminApi(`/members/${row.userId}/recompute`, {
        method: 'POST', body: JSON.stringify({ reason: actionReason.value.trim(), requestId: requestId.value })
      })
      notifySuccess('会员资格已重新计算')
    }
    closeAction()
    await refreshMember(row.userId)
  } catch (cause) {
    actionError.value = adminErrorMessage(cause)
    if (isConflictError(cause)) await refreshMember(row.userId)
  } finally {
    actionSubmitting.value = false
  }
}

function evidenceValue(valueJson: string) {
  try {
    const value = JSON.parse(valueJson) as { amountFen?: number; targetLevel?: string }
    const parts = []
    if (typeof value.amountFen === 'number') parts.push(`订单金额 ${money(value.amountFen)}`)
    if (value.targetLevel) parts.push(`目标等级 ${memberLevelLabel(value.targetLevel)}`)
    return parts.join(' · ') || '任务参数已记录'
  } catch { return '任务参数暂时无法解析' }
}

watch(() => route.fullPath, () => { if (route.path === '/members') { readRouteState(); void load() } })
onMounted(() => { readRouteState(); void load() })
</script>

<template>
  <div>
    <PageHeader title="会员管理" description="查询不可变上下级关系、任务证据、升降级轨迹与演示积分账本。"><template #actions><StatusTag tone="info" :label="`共 ${total} 人`" /></template></PageHeader>
    <FilterBar :busy="pageLoading" :applied-summary="appliedSummary" @apply="applyFilters" @reset="resetFilters"><label class="field"><span>会员关键词</span><input v-model="draftFilters.keyword" placeholder="会员编号 / 昵称" /></label><label class="field"><span>会员等级</span><select v-model="draftFilters.levelCode"><option value="">全部等级</option><option v-for="option in memberLevelOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>会员状态</span><select v-model="draftFilters.status"><option value="">全部状态</option><option v-for="option in memberStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label></FilterBar>
    <TableFrame :loading="pageLoading" :error="listError" :empty="!rows.length" empty-title="暂无符合条件的会员" label="会员列表" @retry="load"><table class="responsive-table"><thead><tr><th>会员</th><th>等级</th><th>直属上级</th><th>直属 / 有效</th><th>A / B 积分</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.userId"><td data-label="会员"><b>{{ row.nickname }}</b><br /><small>{{ row.publicId }} · #{{ row.userId }}</small></td><td data-label="等级">{{ memberLevelLabel(row.levelCode) }}</td><td data-label="直属上级">{{ row.superiorUserId ? `#${row.superiorUserId}` : '根会员' }}</td><td data-label="直属 / 有效">{{ row.directCount }} / {{ row.qualifiedDirectCount }}</td><td data-label="A / B 积分">{{ row.availablePoints }} / {{ row.frozenPoints }}</td><td data-label="状态"><StatusTag :tone="statusTone(row.status)" :label="memberStatusLabel(row.status)" /></td><td data-label="注册时间">{{ dateTime(row.createdAt) }}</td><td data-label="操作"><button class="primary" type="button" @click="openDetail(row)">查看详情</button></td></tr></tbody></table><template #footer><PaginationBar :page="page" :size="size" :total="total" @change="changePage" /></template></TableFrame>

    <DetailDrawer :model-value="drawerOpen" :title="detailTarget ? `${detailTarget.nickname} · 会员详情` : '会员详情'" description="关系、等级、任务证据与积分批次均来自服务端" width="min(900px, 100vw)" @update:model-value="value => { if (!value) closeDetail() }"><div v-if="detailLoading" class="detail-loading" role="status"><span class="state-spinner"></span>正在加载会员资料…</div><InlineAlert v-else-if="detailError" title="会员详情加载失败" :message="detailError" retryable @retry="detailTarget && fetchDetail(detailTarget)" /><template v-else-if="detail"><div class="metrics"><div><small>直属会员</small><b>{{ detail.member.directCount }}</b></div><div><small>有效直属</small><b>{{ detail.member.qualifiedDirectCount }}</b></div><div><small>A 池可用</small><b>{{ detail.member.availablePoints }}</b></div><div><small>B 池冻结</small><b>{{ detail.member.frozenPoints }}</b></div></div><h3>升降级轨迹</h3><div class="history"><p v-for="row in detail.levelChanges" :key="row.id"><b>{{ memberLevelLabel(row.beforeLevel) }} → {{ memberLevelLabel(row.afterLevel) }}</b><span>{{ levelTriggerLabel(row.triggerType) }} · {{ row.reason || '系统规则' }}</span><small>{{ dateTime(row.occurredAt) }}</small></p><span v-if="!detail.levelChanges.length" class="muted">暂无变更</span></div><h3>任务证据</h3><div class="history"><p v-for="row in detail.evidence" :key="row.id"><b>{{ evidenceTypeLabel(row.type) }} · {{ evidenceStatusLabel(row.status) }}</b><span>{{ evidenceValue(row.valueJson) }}</span><small>{{ dateTime(row.createdAt) }}</small></p><span v-if="!detail.evidence.length" class="muted">暂无证据</span></div><h3>积分流水（最近 {{ detail.ledger.length }}）</h3><div class="history"><p v-for="row in detail.ledger.slice(0, 50)" :key="row.id"><b>{{ ledgerEntryLabel(row.entryType) }}</b><span>A 池 {{ row.availableDelta }} / B 池 {{ row.frozenDelta }}<small v-if="row.sourceOrderId">来源订单 #{{ row.sourceOrderId }} · 规则 #{{ row.ruleVersionId || '—' }}</small><small v-if="row.frozenBatchId">B 池批次 #{{ row.frozenBatchId }} · 剩余 {{ row.frozenBatchRemainingPoints }} / {{ row.frozenBatchOriginalPoints }}</small></span><small>{{ dateTime(row.occurredAt) }}</small></p></div></template><template v-if="detail" #footer><button class="secondary" type="button" @click="closeDetail">关闭</button><button v-if="can('member:write')" class="secondary" type="button" @click="openAction('status', detail.member)">调整状态</button><button v-if="can('member:write')" class="primary" type="button" @click="openAction('recompute', detail.member)">资格重算</button></template></DetailDrawer>

    <BusinessActionDialog :model-value="Boolean(actionKind)" :title="actionKind === 'status' ? '调整会员状态' : '重新计算会员资格'" :target="actionTarget ? `${actionTarget.nickname}（${actionTarget.publicId}）` : '当前会员'" :impact="actionKind === 'status' ? '状态变化会影响会员登录或业务参与资格，并写入不可变审计日志。' : '服务端将依据当前生效规则重新计算等级；不会修改上下级关系。'" :current-state="actionTarget ? (actionKind === 'status' ? memberStatusLabel(actionTarget.status) : memberLevelLabel(actionTarget.levelCode)) : ''" :next-state="actionKind === 'status' ? memberStatusLabel(nextStatus) : '按当前规则重新计算'" v-model:reason="actionReason" reason-label="变更原因" :confirm-label="actionKind === 'status' ? '确认调整状态' : '确认资格重算'" :submitting="actionSubmitting" :error="actionError" @update:model-value="value => { if (!value) closeAction() }" @submit="submitAction"><label v-if="actionKind === 'status'" class="field"><span>目标状态</span><select v-model="nextStatus" :disabled="actionSubmitting"><option v-for="option in memberStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><p class="request-id">稳定请求号：<code>{{ requestId }}</code></p></BusinessActionDialog>
  </div>
</template>

<style scoped>
.detail-loading{min-height:260px;display:flex;align-items:center;justify-content:center;gap:10px;color:var(--color-text-muted)}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.metrics div{padding:13px;background:var(--color-surface-subtle);border-radius:9px}.metrics small,.metrics b{display:block}.metrics small,.muted{color:var(--color-text-muted)}.metrics b{font-size:22px;margin-top:4px}.admin-dialog__body h3{font-family:serif}.history p{display:grid;grid-template-columns:180px minmax(0,1fr) auto;gap:12px;padding:10px 0;border-bottom:1px solid var(--color-border);margin:0}.history span,.history>p>small{color:var(--color-text-muted)}.history span>small{display:block;margin-top:4px}.request-id{color:var(--color-text-muted);font-size:12px}.request-id code{word-break:break-all}
@media(max-width:700px){.metrics{grid-template-columns:1fr 1fr}.history p{grid-template-columns:1fr}}
</style>
