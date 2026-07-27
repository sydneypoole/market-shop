<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminApi, dateTime, queryString } from '../api'
import {
  auditActionLabel,
  auditActorLabel,
  auditActorOptions,
  auditResourceLabel
} from '../localization'
import PaginationBar from '../components/PaginationBar.vue'

type Audit = {
  id:number;actorType:string;actorId:string;action:string;resourceType:string;resourceId:string
  beforeJson?:string;afterJson?:string;reason?:string;requestId:string;maskedIp?:string
  userAgentSummary?:string;occurredAt:string
}
const rows = ref<Audit[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const filters = reactive({actorType:'',actorId:'',action:'',resourceType:'',resourceId:'',requestId:'',from:'',to:''})
const error = ref('')

function params(includePage = true) {
  return queryString({
    actorType:filters.actorType,actorId:filters.actorId,action:filters.action,
    resourceType:filters.resourceType,resourceId:filters.resourceId,requestId:filters.requestId,
    from:filters.from ? new Date(filters.from).toISOString() : '',
    to:filters.to ? new Date(filters.to).toISOString() : '',
    page:includePage ? page.value : undefined,pageSize:includePage ? size : undefined
  })
}
async function load(targetPage = 1) {
  page.value = targetPage
  try {
    const result = await adminApi<{items:Audit[];total:number;page:number;pageSize:number}>(`/audit?${params()}`)
    rows.value = result.items
    total.value = result.total
  } catch (e) { error.value = (e as Error).message }
}
function exportCsv() { location.href = `/api/v1/admin/audit/export?${params(false)}` }
function pretty(value?: string) {
  if (!value) return '无记录'
  try {
    const parsed = JSON.parse(value) as unknown
    return parsed === null ? '无记录' : JSON.stringify(parsed, null, 2)
  }
  catch { return value }
}
onMounted(() => load())
</script>

<template>
  <div>
    <div class="page-title"><div><h1>审计日志</h1><p>按主体、动作、资源、请求号和时间范围检索不可变审计记录。</p></div><div class="title-actions"><span class="tag green">{{ total }} 条</span><button class="secondary" @click="exportCsv">导出表格</button></div></div>
    <div class="toolbar filters">
      <select v-model="filters.actorType"><option value="">全部主体</option><option v-for="option in auditActorOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
      <input v-model="filters.actorId" placeholder="主体编号" /><input v-model="filters.action" placeholder="动作（精确）" />
      <input v-model="filters.resourceType" placeholder="资源类型" /><input v-model="filters.resourceId" placeholder="资源编号" />
      <input v-model="filters.requestId" placeholder="请求号" />
      <label>从<input v-model="filters.from" type="datetime-local" /></label><label>至<input v-model="filters.to" type="datetime-local" /></label>
      <button class="secondary" @click="load(1)">查询</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card table-wrap"><table><thead><tr><th>时间</th><th>主体</th><th>动作</th><th>资源</th><th>原因</th><th>请求来源</th><th>前后值</th></tr></thead><tbody>
      <tr v-for="row in rows" :key="row.id"><td>{{ dateTime(row.occurredAt) }}</td><td>{{ auditActorLabel(row.actorType) }} #{{ row.actorId }}</td><td><b>{{ auditActionLabel(row.action) }}</b></td><td>{{ auditResourceLabel(row.resourceType) }} #{{ row.resourceId }}</td><td>{{ row.reason || '—' }}</td><td><code>{{ row.requestId }}</code><small>{{ row.maskedIp || '—' }} · {{ row.userAgentSummary || '—' }}</small></td><td><details><summary>查看</summary><div class="diff"><pre>{{ pretty(row.beforeJson) }}</pre><span>→</span><pre>{{ pretty(row.afterJson) }}</pre></div></details></td></tr>
      <tr v-if="!rows.length"><td colspan="7" class="empty">暂无符合条件的审计记录。</td></tr>
    </tbody></table></div>
    <PaginationBar :page="page" :size="size" :total="total" @change="load" />
  </div>
</template>

<style scoped>
.title-actions,.filters{display:flex;gap:8px}.filters{flex-wrap:wrap}.filters label{display:flex;align-items:center;gap:5px;color:var(--muted);font-size:12px}.filters label input{min-width:185px}code{display:block;font-size:11px}.table-wrap small{display:block;color:var(--muted);margin-top:4px}.diff{display:grid;grid-template-columns:1fr auto 1fr;gap:7px;align-items:center}.diff pre{white-space:pre-wrap;max-width:320px;max-height:240px;overflow:auto;font-size:11px;background:#f5f7f5;padding:8px;border-radius:7px}summary{cursor:pointer;color:var(--green)}.empty{text-align:center;color:var(--muted);padding:35px}
</style>
