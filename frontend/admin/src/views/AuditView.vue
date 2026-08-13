<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminDownload, adminErrorMessage, dateTime, queryString } from '../api'
import AdminIcon from '../components/admin/AdminIcon.vue'
import BaseDialog from '../components/admin/BaseDialog.vue'
import DetailDrawer from '../components/admin/DetailDrawer.vue'
import FilterBar from '../components/admin/FilterBar.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { auditActionLabel, auditActorLabel, auditActorOptions, auditResourceLabel } from '../localization'
import { notifyError, notifySuccess } from '../toast'

type Audit = {
  id: number; actorType: string; actorId: string; action: string; resourceType: string; resourceId: string
  beforeJson?: string; afterJson?: string; reason?: string; requestId: string; maskedIp?: string
  userAgentSummary?: string; occurredAt: string
}
type Filters = { actorType: string; actorId: string; action: string; resourceType: string; resourceId: string; requestId: string; from: string; to: string }

const route = useRoute()
const router = useRouter()
const emptyFilters = (): Filters => ({ actorType: '', actorId: '', action: '', resourceType: '', resourceId: '', requestId: '', from: '', to: '' })
const draftFilters = reactive<Filters>(emptyFilters())
const appliedFilters = reactive<Filters>(emptyFilters())
const rows = ref<Audit[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const pageLoading = ref(true)
const listError = ref('')
const detail = ref<Audit>()
const exportOpen = ref(false)
const exportBusy = ref(false)
const exportError = ref('')

const appliedSummary = computed(() => {
  const result = []
  if (appliedFilters.actorType) result.push(`主体：${auditActorLabel(appliedFilters.actorType)}`)
  if (appliedFilters.actorId) result.push(`主体编号：${appliedFilters.actorId}`)
  if (appliedFilters.action) result.push(`动作：${appliedFilters.action}`)
  if (appliedFilters.resourceType) result.push(`资源：${appliedFilters.resourceType}`)
  if (appliedFilters.resourceId) result.push(`资源编号：${appliedFilters.resourceId}`)
  if (appliedFilters.requestId) result.push(`请求号：${appliedFilters.requestId}`)
  if (appliedFilters.from) result.push(`从：${appliedFilters.from.replace('T', ' ')}`)
  if (appliedFilters.to) result.push(`至：${appliedFilters.to.replace('T', ' ')}`)
  return result.length ? result : ['全部审计记录']
})

function readRouteState() {
  const next = emptyFilters()
  for (const key of Object.keys(next) as Array<keyof Filters>) {
    const value = route.query[key]
    next[key] = typeof value === 'string' ? value : ''
  }
  Object.assign(draftFilters, next)
  Object.assign(appliedFilters, next)
  const requested = Number(route.query.page)
  page.value = Number.isInteger(requested) && requested > 0 ? requested : 1
}

function params(includePage = true) {
  return queryString({
    actorType: appliedFilters.actorType, actorId: appliedFilters.actorId, action: appliedFilters.action,
    resourceType: appliedFilters.resourceType, resourceId: appliedFilters.resourceId, requestId: appliedFilters.requestId,
    from: appliedFilters.from ? new Date(appliedFilters.from).toISOString() : '',
    to: appliedFilters.to ? new Date(appliedFilters.to).toISOString() : '',
    page: includePage ? page.value : undefined, pageSize: includePage ? size : undefined
  })
}

async function load() {
  pageLoading.value = true
  listError.value = ''
  try {
    const result = await adminApi<{ items: Audit[]; total: number; page: number; pageSize: number }>(`/audit?${params()}`)
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    listError.value = adminErrorMessage(cause)
  } finally { pageLoading.value = false }
}

function routeQuery() {
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(appliedFilters)) if (value) result[key] = value
  if (page.value > 1) result.page = String(page.value)
  return result
}
async function navigateApplied() {
  const target = { path: '/audit', query: routeQuery() }
  if (router.resolve(target).fullPath === route.fullPath) await load()
  else await router.push(target)
}
async function applyFilters() { Object.assign(appliedFilters, draftFilters); page.value = 1; await navigateApplied() }
async function resetFilters() { Object.assign(draftFilters, emptyFilters()); Object.assign(appliedFilters, draftFilters); page.value = 1; await navigateApplied() }
async function changePage(value: number) { page.value = value; await navigateApplied() }

function pretty(value?: string) {
  if (!value) return '无记录'
  try {
    const parsed = JSON.parse(value) as unknown
    return parsed === null ? '无记录' : JSON.stringify(parsed, null, 2)
  } catch { return value }
}

function openExport() { exportError.value = ''; exportOpen.value = true }
async function exportCsv() {
  if (exportBusy.value) return
  exportBusy.value = true
  exportError.value = ''
  try {
    const blob = await adminDownload(`/audit/export?${params(false)}`)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `审计日志-${new Date().toISOString().slice(0, 10)}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
    exportOpen.value = false
    notifySuccess('审计日志导出已开始', `使用当前已应用条件，最多包含 ${Math.min(total.value, 10000)} 条记录。`)
  } catch (cause) {
    exportError.value = adminErrorMessage(cause)
    notifyError('审计日志导出失败', exportError.value)
  } finally { exportBusy.value = false }
}

watch(() => route.fullPath, () => { if (route.path === '/audit') { readRouteState(); void load() } })
onMounted(() => { readRouteState(); void load() })
</script>

<template>
  <div>
    <PageHeader title="审计日志" description="按主体、动作、资源、请求号和时间范围检索不可变审计记录。"><template #actions><StatusTag tone="info" :label="`${total} 条`" /><button class="secondary" type="button" :disabled="pageLoading" @click="openExport">导出当前结果</button></template></PageHeader>
    <FilterBar :busy="pageLoading" :applied-summary="appliedSummary" @apply="applyFilters" @reset="resetFilters"><label class="field"><span>操作主体</span><select v-model="draftFilters.actorType"><option value="">全部主体</option><option v-for="option in auditActorOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>请求号</span><input v-model="draftFilters.requestId" placeholder="精确请求号" /></label><template #advanced><label class="field"><span>主体编号</span><input v-model="draftFilters.actorId" /></label><label class="field"><span>动作（精确）</span><input v-model="draftFilters.action" /></label><label class="field"><span>资源类型</span><input v-model="draftFilters.resourceType" /></label><label class="field"><span>资源编号</span><input v-model="draftFilters.resourceId" /></label><label class="field"><span>发生时间从</span><input v-model="draftFilters.from" type="datetime-local" /></label><label class="field"><span>发生时间至</span><input v-model="draftFilters.to" type="datetime-local" /></label></template></FilterBar>
    <TableFrame :loading="pageLoading" :error="listError" :empty="!rows.length" empty-title="暂无符合条件的审计记录" label="审计日志列表" @retry="load"><table class="responsive-table"><thead><tr><th>时间</th><th>主体</th><th>动作</th><th>资源</th><th>原因</th><th>请求来源</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td data-label="时间">{{ dateTime(row.occurredAt) }}</td><td data-label="主体">{{ auditActorLabel(row.actorType) }} #{{ row.actorId }}</td><td data-label="动作"><b>{{ auditActionLabel(row.action) }}</b></td><td data-label="资源">{{ auditResourceLabel(row.resourceType) }} #{{ row.resourceId }}</td><td data-label="原因">{{ row.reason || '未记录' }}</td><td data-label="请求来源"><code>{{ row.requestId }}</code><small>{{ row.maskedIp || '未记录' }} · {{ row.userAgentSummary || '未记录' }}</small></td><td data-label="操作"><button class="primary" type="button" @click="detail = row">查看前后值</button></td></tr></tbody></table><template #footer><PaginationBar :page="page" :size="size" :total="total" @change="changePage" /></template></TableFrame>

    <DetailDrawer :model-value="Boolean(detail)" :title="detail ? `${auditActionLabel(detail.action)} · 审计详情` : '审计详情'" :description="detail ? `请求号 ${detail.requestId}` : ''" width="min(900px, 100vw)" @update:model-value="value => { if (!value) detail = undefined }"><template v-if="detail"><dl class="audit-meta"><dt>发生时间</dt><dd>{{ dateTime(detail.occurredAt) }}</dd><dt>操作主体</dt><dd>{{ auditActorLabel(detail.actorType) }} #{{ detail.actorId }}</dd><dt>业务资源</dt><dd>{{ auditResourceLabel(detail.resourceType) }} #{{ detail.resourceId }}</dd><dt>操作原因</dt><dd>{{ detail.reason || '未记录' }}</dd><dt>请求来源</dt><dd>{{ detail.maskedIp || '未记录' }} · {{ detail.userAgentSummary || '未记录' }}</dd></dl><div class="diff"><section><b>变更前</b><pre>{{ pretty(detail.beforeJson) }}</pre></section><AdminIcon class="diff-arrow" name="arrow-right" :size="20" /><section><b>变更后</b><pre>{{ pretty(detail.afterJson) }}</pre></section></div></template></DetailDrawer>

    <BaseDialog v-model="exportOpen" title="确认导出审计日志" description="导出只使用已经执行查询的条件，不包含尚未应用的输入。" :submitting="exportBusy"><div class="export-summary"><b>导出范围</b><span v-for="item in appliedSummary" :key="item">{{ item }}</span><p>当前结果约 {{ total }} 条；单次最多导出 10,000 条，本次最多包含 {{ Math.min(total, 10000) }} 条。</p></div><InlineAlert v-if="exportError" title="导出失败" :message="exportError" /><template #footer="{ close }"><button class="secondary" type="button" autofocus :disabled="exportBusy" @click="close">取消</button><button class="primary" type="button" :disabled="exportBusy" @click="exportCsv">{{ exportBusy ? '生成中…' : '确认导出' }}</button></template></BaseDialog>
  </div>
</template>

<style scoped>
.table-frame small{display:block;color:var(--color-text-muted);margin-top:3px}.table-frame code{display:block;max-width:240px;overflow:hidden;text-overflow:ellipsis;font-size:11px}.audit-meta{display:grid;grid-template-columns:90px 1fr;gap:9px;padding:12px 0;border-block:1px solid var(--color-border)}.audit-meta dt{color:var(--color-text-muted)}.audit-meta dd{margin:0}.diff{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);align-items:center;gap:10px;margin-top:18px}.diff-arrow{color:var(--color-brand)}.diff pre{min-height:240px;max-height:55vh;overflow:auto;padding:12px;white-space:pre-wrap;word-break:break-word;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface-subtle);font:12px/1.6 ui-monospace,monospace}.export-summary{display:grid;gap:8px}.export-summary span{padding:6px 9px;border-left:2px solid var(--color-border-strong);background:var(--color-surface-subtle)}.export-summary p{color:var(--color-text-muted);line-height:1.6}
@media(max-width:700px){.diff{grid-template-columns:1fr}.diff-arrow{justify-self:center;transform:rotate(90deg)}.audit-meta{grid-template-columns:1fr}.audit-meta dd{margin-top:-6px}}
</style>
