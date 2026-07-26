<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { api, dateTime } from '../api'
import PaginationBar from '../components/PaginationBar.vue'
import type { RuleView } from '../types'

type Profile = {
  userId: number
  nickname: string
  levelCode: string
  levelName: string
  availablePoints: number
  frozenPoints: number
  qualifiedDirectCount: number
}
type Invitation = { code: string; status: string; useCount: number; registrationPath: string; expiresAt: string }
type Direct = { userId: number; publicId: string; nickname: string; levelName: string; completedOrdinal: number; performanceStatus: string }
type Entry = { id: number; entryType: string; availableDelta: number; frozenDelta: number; occurredAt: string }

const profile = ref<Profile>()
const invitation = ref<Invitation>()
const directs = ref<Direct[]>([])
const entries = ref<Entry[]>([])
const rules = ref<RuleView[]>([])
const error = ref('')
const success = ref('')
const qrDataUrl = ref('')
const loading = ref(true)
const busy = ref<'create' | 'regenerate' | 'revoke' | 'copy'>()
const confirmation = ref<'regenerate' | 'revoke'>()
const directQuery = ref('')
const directPage = ref(1)
const directPageSize = 6
const ledgerType = ref('')
const ledgerPage = ref(1)
const ledgerPageSize = 10

const filteredDirects = computed(() => {
  const keyword = directQuery.value.trim().toLowerCase()
  return directs.value.filter(row => !keyword
    || row.nickname.toLowerCase().includes(keyword)
    || row.publicId.toLowerCase().includes(keyword)
    || row.levelName.toLowerCase().includes(keyword))
})
const pagedDirects = computed(() => filteredDirects.value.slice(
  (directPage.value - 1) * directPageSize,
  directPage.value * directPageSize
))
const entryTypes = computed(() => Array.from(new Set(entries.value.map(row => row.entryType))))
const filteredEntries = computed(() => entries.value.filter(row => !ledgerType.value || row.entryType === ledgerType.value))
const pagedEntries = computed(() => filteredEntries.value.slice(
  (ledgerPage.value - 1) * ledgerPageSize,
  ledgerPage.value * ledgerPageSize
))
const qualificationTarget = computed(() => {
  const current = rules.value.find(item => item.ruleCode === 'DIVIDEND_MEMBER_QUALIFICATION')
  if (!current) return undefined
  try {
    const value: unknown = JSON.parse(current.parametersJson)
    if (!value || typeof value !== 'object') return undefined
    const count = (value as Record<string, unknown>).requiredCompletedDirectReferrals
    return typeof count === 'number' ? count : undefined
  } catch {
    return undefined
  }
})
const pointsStart = computed(() => {
  const current = rules.value.find(item => item.ruleCode === 'DIRECT_REFERRAL_POINTS')
  if (!current) return undefined
  try {
    const value: unknown = JSON.parse(current.parametersJson)
    if (!value || typeof value !== 'object') return undefined
    const count = (value as Record<string, unknown>).pointsStartOrdinal
    return typeof count === 'number' ? count : undefined
  } catch {
    return undefined
  }
})

watch(directQuery, () => { directPage.value = 1 })
watch(ledgerType, () => { ledgerPage.value = 1 })

async function load() {
  loading.value = true
  error.value = ''
  const results = await Promise.allSettled([
    api<Profile>('/membership/me'),
    api<Direct[]>('/membership/direct-members'),
    api<Entry[]>('/membership/ledger'),
    api<RuleView[]>('/rules/active')
  ])
  if (results[0].status === 'fulfilled') profile.value = results[0].value
  else error.value = results[0].reason instanceof Error ? results[0].reason.message : '会员信息加载失败'
  if (results[1].status === 'fulfilled') directs.value = results[1].value
  if (results[2].status === 'fulfilled') entries.value = results[2].value
  if (results[3].status === 'fulfilled') rules.value = results[3].value
  if (profile.value && ['SUPER_MEMBER', 'DIVIDEND_MEMBER'].includes(profile.value.levelCode)) {
    try {
      invitation.value = await api<Invitation | null>('/membership/invitation') || undefined
      if (invitation.value) await renderQr()
    } catch (cause) {
      if (!error.value) error.value = (cause as Error).message
    }
  }
  loading.value = false
}

async function createInvitation() {
  if (busy.value) return
  busy.value = 'create'
  error.value = ''
  try {
    invitation.value = await api<Invitation>('/membership/invitation', { method: 'POST' })
    await renderQr()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = undefined
  }
}

async function renderQr() {
  if (!invitation.value) return
  const url = `${location.origin}/login?inviteCode=${encodeURIComponent(invitation.value.code)}`
  qrDataUrl.value = await QRCode.toDataURL(url, { width: 420, margin: 2, errorCorrectionLevel: 'M' })
}

async function confirmInvitationAction() {
  if (!confirmation.value || busy.value) return
  const action = confirmation.value
  busy.value = action
  error.value = ''
  try {
    if (action === 'regenerate') {
      invitation.value = await api<Invitation>('/membership/invitation/regenerate?validityDays=365', { method: 'POST' })
      await renderQr()
      success.value = '邀请码已重新生成，旧邀请码已失效。'
    } else {
      await api('/membership/invitation/revoke', { method: 'POST' })
      invitation.value = undefined
      qrDataUrl.value = ''
      success.value = '邀请码已撤销。'
    }
    confirmation.value = undefined
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = undefined
  }
}

async function copyInvite() {
  if (!invitation.value || busy.value) return
  busy.value = 'copy'
  error.value = ''
  try {
    await navigator.clipboard.writeText(`${location.origin}/login?inviteCode=${encodeURIComponent(invitation.value.code)}`)
    success.value = '邀请链接已复制到剪贴板。'
  } catch {
    error.value = '复制失败，请检查浏览器剪贴板权限'
  } finally {
    busy.value = undefined
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div v-if="loading" class="empty card" aria-busy="true">正在加载会员中心…</div>
    <template v-else-if="profile">
      <section class="member-hero">
        <div><span class="eyebrow">Member Profile</span><h1>{{ profile.nickname }}</h1><p>会员编号 #{{ profile.userId }}</p></div>
        <div class="level-seal"><small>当前等级</small><b>{{ profile.levelName }}</b></div>
      </section>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="success" class="success" role="status">{{ success }}</p>
      <section class="metric-grid">
        <div class="card"><span>A 池可用积分</span><b>{{ profile.availablePoints }}</b><small>演示积分，不可提现</small></div>
        <div class="card"><span>B 池冻结积分</span><b>{{ profile.frozenPoints }}</b><small>符合复购任务后释放</small></div>
        <div class="card"><span>有效直属业绩</span><b>{{ profile.qualifiedDirectCount }}<template v-if="qualificationTarget"> / {{ qualificationTarget }}</template></b><small>{{ pointsStart ? `当前规则从第 ${pointsStart} 位起计分` : '人数门槛以后台规则为准' }}</small></div>
      </section>
      <nav class="quick-links card"><RouterLink to="/addresses">管理收货地址 →</RouterLink><RouterLink to="/after-sales">查看售后进度 →</RouterLink><RouterLink to="/notifications">查看站内通知 →</RouterLink></nav>
      <div class="member-grid">
        <section class="card invite-card">
          <span class="eyebrow">My Invitation</span><h2>专属邀请</h2>
          <template v-if="invitation">
            <img v-if="qrDataUrl" class="real-qr" :src="qrDataUrl" alt="专属邀请二维码" />
            <p class="invite-code">邀请码：<b>{{ invitation.code }}</b></p>
            <p>已成功绑定 {{ invitation.useCount }} 位直属用户</p>
            <small>有效期至 {{ dateTime(invitation.expiresAt) }}</small>
            <button class="primary" type="button" :disabled="Boolean(busy)" @click="copyInvite">{{ busy === 'copy' ? '复制中…' : '复制邀请链接' }}</button>
            <div class="invite-actions"><button class="secondary" type="button" :disabled="Boolean(busy)" @click="confirmation = 'regenerate'">重新生成</button><button class="danger" type="button" :disabled="Boolean(busy)" @click="confirmation = 'revoke'">撤销</button></div>
          </template>
          <template v-else>
            <p class="muted">超级会员及以上等级可生成专属邀请码。</p>
            <button class="primary" type="button" :disabled="Boolean(busy)" @click="createInvitation">{{ busy === 'create' ? '生成中…' : '生成我的邀请码' }}</button>
          </template>
        </section>
        <section class="card direct-card">
          <div class="section-head"><div><h2>直属成员</h2><p>关系只计算一层，不向下穿透。</p></div></div>
          <div class="field"><label for="direct-query">搜索成员</label><input id="direct-query" v-model="directQuery" placeholder="昵称、会员编号或等级" /></div>
          <article v-for="direct in pagedDirects" :key="direct.userId">
            <span class="avatar">{{ direct.nickname.slice(0, 1) }}</span>
            <div><b>{{ direct.nickname }}</b><small>{{ direct.levelName }} · {{ direct.performanceStatus }}</small></div>
            <strong>{{ direct.completedOrdinal ? `第 ${direct.completedOrdinal} 位` : '待完成' }}</strong>
          </article>
          <div v-if="!pagedDirects.length" class="empty">{{ directs.length ? '没有符合条件的直属成员。' : '暂时还没有直属成员。' }}</div>
          <PaginationBar :page="directPage" :page-size="directPageSize" :total="filteredDirects.length" @change="directPage = $event" />
        </section>
      </div>
      <section class="card ledger-card">
        <div class="section-head">
          <div><h2>积分账本</h2><p>账本仅追加，售后通过反向分录冲正。</p></div>
          <select v-model="ledgerType" aria-label="积分流水类型"><option value="">全部类型</option><option v-for="type in entryTypes" :key="type" :value="type">{{ type }}</option></select>
        </div>
        <div v-for="entry in pagedEntries" :key="entry.id" class="ledger-row">
          <span>{{ entry.entryType }}</span>
          <small>{{ dateTime(entry.occurredAt) }}</small>
          <b :class="{ plus: entry.availableDelta > 0 }">A {{ entry.availableDelta > 0 ? '+' : '' }}{{ entry.availableDelta }}</b>
          <b>B {{ entry.frozenDelta > 0 ? '+' : '' }}{{ entry.frozenDelta }}</b>
        </div>
        <div v-if="!pagedEntries.length" class="empty">{{ entries.length ? '没有符合筛选条件的流水。' : '还没有积分变动。' }}</div>
        <PaginationBar :page="ledgerPage" :page-size="ledgerPageSize" :total="filteredEntries.length" @change="ledgerPage = $event" />
      </section>

      <div v-if="confirmation" class="modal-mask" @click.self="confirmation = undefined">
        <form class="modal confirm-modal card" @submit.prevent="confirmInvitationAction">
          <h2>{{ confirmation === 'regenerate' ? '重新生成邀请码' : '撤销邀请码' }}</h2>
          <p>{{ confirmation === 'regenerate' ? '旧邀请码会立即失效，已经分享的旧链接将不能继续注册。' : '撤销后，该邀请码和二维码将不能再注册新会员。' }}</p>
          <div><button class="secondary" type="button" :disabled="Boolean(busy)" @click="confirmation = undefined">返回</button><button class="danger" :disabled="Boolean(busy)">{{ busy ? '处理中…' : '确认操作' }}</button></div>
        </form>
      </div>
    </template>
    <div v-else class="empty card"><p>{{ error || '会员信息暂时不可用。' }}</p><button class="secondary" type="button" @click="load">重新加载</button></div>
  </div>
</template>

<style scoped>
.member-hero { min-height:250px; padding:44px; border-radius:26px; display:flex; justify-content:space-between; align-items:center; color:white; background:linear-gradient(120deg,#263f38,#315e51 65%,#d56b49); }
.member-hero h1 { font:700 48px serif; margin:12px 0 4px; }.member-hero p{opacity:.65}
.level-seal { width:150px; height:150px; border:1px solid rgba(255,255,255,.45); border-radius:50%; display:grid; place-content:center; text-align:center; box-shadow:inset 0 0 0 8px rgba(255,255,255,.08); }
.level-seal small,.level-seal b{display:block}.level-seal b{margin-top:7px;font:700 22px serif}
.success{padding:10px 12px;color:var(--green);background:#e5f1eb;border-radius:10px}
.metric-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:18px 0}.metric-grid .card{padding:22px}.metric-grid span,.metric-grid b,.metric-grid small{display:block}.metric-grid span{color:var(--muted)}.metric-grid b{font:750 32px serif;margin:8px 0}.metric-grid small{color:#9a918a}
.member-grid{display:grid;grid-template-columns:340px 1fr;gap:18px}.invite-card,.direct-card,.ledger-card{padding:26px}.invite-card h2,.direct-card h2,.ledger-card h2{font-family:serif}
.real-qr{display:block;width:100%;aspect-ratio:1;object-fit:contain;margin:20px 0 10px;border:12px solid white;box-shadow:0 0 0 1px var(--line)}.invite-code{text-align:center}.invite-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}.invite-actions button,.invite-card .primary{width:100%}
.quick-links{display:flex;gap:12px;padding:14px 18px;margin-bottom:18px;flex-wrap:wrap}.quick-links a{color:var(--green);font-weight:700}
.direct-card .section-head,.ledger-card .section-head{margin-top:0}.direct-card>.field{margin-bottom:12px}.direct-card article{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;padding:13px 0;border-top:1px solid var(--line)}.avatar{display:grid;place-items:center;width:40px;height:40px;border-radius:50%;color:white;background:var(--coral)}.direct-card small{display:block;color:var(--muted);margin-top:4px}.direct-card strong{font-size:13px;color:var(--green)}
.ledger-card{margin-top:18px}.ledger-card .section-head select{padding:9px;border:1px solid var(--line);border-radius:9px;background:white}.ledger-row{display:grid;grid-template-columns:1fr 1fr auto auto;gap:16px;padding:13px 0;border-top:1px solid var(--line)}.ledger-row small{color:var(--muted)}.ledger-row b{min-width:60px;text-align:right}.ledger-row .plus{color:var(--coral)}
.confirm-modal{width:min(460px,100%);padding:24px}.confirm-modal h2{font-family:serif}.confirm-modal p{color:var(--muted);line-height:1.7}.confirm-modal div{display:flex;justify-content:flex-end;gap:10px}
@media(max-width:760px){.member-hero{padding:28px 22px;min-height:210px}.member-hero h1{font-size:36px}.level-seal{width:105px;height:105px}.metric-grid{grid-template-columns:1fr}.member-grid{grid-template-columns:1fr}.ledger-row{grid-template-columns:1fr auto auto}.ledger-row small{grid-column:1/-1;grid-row:2}.ledger-card .section-head{align-items:stretch;flex-direction:column}}
</style>
