<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminApi, dateTime, queryString } from '../api'
import { can } from '../session'
import PaginationBar from '../components/PaginationBar.vue'

type Member = {
  userId:number;publicId:string;nickname:string;status:string;levelCode:string;levelName:string
  superiorUserId?:number;directCount:number;qualifiedDirectCount:number;availablePoints:number
  frozenPoints:number;createdAt:string
}
type Detail = {
  member:Member
  evidence:Array<{id:number;type:string;status:string;valueJson:string;createdAt:string}>
  levelChanges:Array<{id:number;beforeLevel:string;afterLevel:string;triggerType:string;reason?:string;occurredAt:string}>
  ledger:Array<{id:number;entryType:string;availableDelta:number;frozenDelta:number;occurredAt:string}>
}
const rows = ref<Member[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const detail = ref<Detail>()
const error = ref('')
const busy = ref('')
const filters = reactive({keyword:'',levelCode:'',status:''})

async function load(targetPage = 1) {
  page.value = targetPage
  try {
    const q = queryString({...filters,page:page.value,size})
    const result = await adminApi<{items:Member[];total:number;page:number;size:number}>(`/members?${q}`)
    rows.value = result.items
    total.value = result.total
  } catch (e) { error.value = (e as Error).message }
}
async function open(row: Member) { detail.value = await adminApi<Detail>(`/members/${row.userId}`) }
async function status(row: Member) {
  const next = prompt('新状态：ACTIVE / DISABLED / LOCKED',row.status) || ''
  const reason = prompt('修改原因') || ''
  if (!next || !reason) return
  busy.value = `status-${row.userId}`
  try {
    await adminApi(`/members/${row.userId}/status`, {method:'PUT',body:JSON.stringify({status:next,reason,requestId:crypto.randomUUID()})})
    await load(page.value)
  } finally { busy.value = '' }
}
async function recompute(row: Member) {
  const reason = prompt('资格重算原因') || ''
  if (!reason) return
  busy.value = `recompute-${row.userId}`
  try {
    await adminApi(`/members/${row.userId}/recompute`, {method:'POST',body:JSON.stringify({reason,requestId:crypto.randomUUID()})})
    await load(page.value)
    if (detail.value) await open(row)
  } finally { busy.value = '' }
}
onMounted(() => load())
</script>

<template>
  <div>
    <div class="page-title"><div><h1>会员管理</h1><p>查询不可变上下级关系、任务证据、升降级轨迹与演示积分账本。</p></div><span class="tag green">共 {{ total }} 人</span></div>
    <div class="toolbar"><input v-model="filters.keyword" placeholder="编号 / 昵称 / ID" /><select v-model="filters.levelCode"><option value="">全部等级</option><option>BASIC</option><option>EXPERIENCE_OFFICER</option><option>SUPER_MEMBER</option><option>DIVIDEND_MEMBER</option></select><select v-model="filters.status"><option value="">全部状态</option><option>ACTIVE</option><option>DISABLED</option><option>LOCKED</option></select><button class="secondary" @click="load(1)">查询</button></div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card table-wrap"><table><thead><tr><th>会员</th><th>等级</th><th>直属上级</th><th>直属 / 有效</th><th>A / B 积分</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in rows" :key="row.userId"><td><b>{{ row.nickname }}</b><br /><small>{{ row.publicId }} · #{{ row.userId }}</small></td><td>{{ row.levelName }}</td><td>{{ row.superiorUserId ? `#${row.superiorUserId}` : '根会员' }}</td><td>{{ row.directCount }} / {{ row.qualifiedDirectCount }}</td><td>{{ row.availablePoints }} / {{ row.frozenPoints }}</td><td><span class="tag" :class="{green:row.status === 'ACTIVE'}">{{ row.status }}</span></td><td>{{ dateTime(row.createdAt) }}</td><td class="actions"><button class="secondary" @click="open(row)">详情</button><button v-if="can('member:write')" class="secondary" :disabled="Boolean(busy)" @click="status(row)">状态</button><button v-if="can('member:write')" class="primary" :disabled="Boolean(busy)" @click="recompute(row)">资格重算</button></td></tr>
    </tbody></table></div>
    <PaginationBar :page="page" :size="size" :total="total" @change="load" />
    <div v-if="detail" class="modal-mask" @click.self="detail = undefined"><section class="modal detail card">
      <div class="detail-head"><div><h2>{{ detail.member.nickname }} · {{ detail.member.levelName }}</h2><p>关系只读：直属上级 {{ detail.member.superiorUserId ? `#${detail.member.superiorUserId}` : '根会员' }}</p></div><button class="secondary" @click="detail = undefined">关闭</button></div>
      <div class="metrics"><div><small>直属会员</small><b>{{ detail.member.directCount }}</b></div><div><small>有效直属</small><b>{{ detail.member.qualifiedDirectCount }}</b></div><div><small>A池可用</small><b>{{ detail.member.availablePoints }}</b></div><div><small>B池冻结</small><b>{{ detail.member.frozenPoints }}</b></div></div>
      <h3>升降级轨迹</h3><div class="history"><p v-for="row in detail.levelChanges" :key="row.id"><b>{{ row.beforeLevel }} → {{ row.afterLevel }}</b><span>{{ row.triggerType }} · {{ row.reason || '系统规则' }}</span><small>{{ dateTime(row.occurredAt) }}</small></p><span v-if="!detail.levelChanges.length" class="muted">暂无变更</span></div>
      <h3>任务证据</h3><div class="history"><p v-for="row in detail.evidence" :key="row.id"><b>{{ row.type }} · {{ row.status }}</b><code>{{ row.valueJson }}</code><small>{{ dateTime(row.createdAt) }}</small></p><span v-if="!detail.evidence.length" class="muted">暂无证据</span></div>
      <h3>积分流水（最近 {{ detail.ledger.length }}）</h3><div class="history"><p v-for="row in detail.ledger.slice(0,50)" :key="row.id"><b>{{ row.entryType }}</b><span>A {{ row.availableDelta }} / B {{ row.frozenDelta }}</span><small>{{ dateTime(row.occurredAt) }}</small></p></div>
    </section></div>
  </div>
</template>

<style scoped>
.actions,.detail-head{display:flex;gap:6px}.detail{width:min(940px,100%);max-height:92vh;overflow:auto}.detail-head{justify-content:space-between}.detail-head h2,.detail-head p{margin:0}.detail-head p{color:var(--muted);margin-top:5px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:16px 0}.metrics div{padding:13px;background:#f5f7f5;border-radius:9px}.metrics small,.metrics b{display:block}.metrics small,.muted{color:var(--muted)}.metrics b{font-size:22px;margin-top:4px}.detail h3{font-family:serif}.history p{display:grid;grid-template-columns:180px 1fr auto;gap:12px;padding:10px 0;border-bottom:1px solid var(--line);margin:0}.history span,.history small{color:var(--muted)}code{white-space:pre-wrap;font-size:11px}
@media(max-width:700px){.metrics{grid-template-columns:1fr 1fr}.history p{grid-template-columns:1fr}.actions{flex-wrap:wrap}}
</style>
