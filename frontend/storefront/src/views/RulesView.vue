<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, dateTime, money } from '../api'
import type { RuleView } from '../types'

type Parameters = Record<string, unknown>

const rules = ref<RuleView[]>([])
const loading = ref(true)
const error = ref('')

function parameters(rule?: RuleView): Parameters {
  if (!rule) return {}
  try {
    const value: unknown = JSON.parse(rule.parametersJson)
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Parameters : {}
  } catch {
    return {}
  }
}

function rule(code: string) {
  return rules.value.find(item => item.ruleCode === code)
}

function numberValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function textValue(value: unknown) {
  return typeof value === 'string' ? value : undefined
}

function amount(ruleCode: string, field: string) {
  const value = numberValue(parameters(rule(ruleCode))[field])
  return value === undefined ? '未配置' : money(value)
}

function count(ruleCode: string, field: string, suffix = '') {
  const value = numberValue(parameters(rule(ruleCode))[field])
  return value === undefined ? '未配置' : `${value}${suffix}`
}

const experienceAmount = computed(() => amount('EXPERIENCE_OFFICER_UPGRADE', 'minimumCompletedOrderAmountFen'))
const superAmount = computed(() => amount('SUPER_MEMBER_UPGRADE', 'minimumCompletedOrderAmountFen'))
const referralAmount = computed(() => amount('DIVIDEND_MEMBER_QUALIFICATION', 'minimumReferralOrderAmountFen'))
const qualificationCount = computed(() => count('DIVIDEND_MEMBER_QUALIFICATION', 'requiredCompletedDirectReferrals', ' 位'))
const pointsStart = computed(() => count('DIRECT_REFERRAL_POINTS', 'pointsStartOrdinal', ' 位'))
const aPoints = computed(() => count('DIRECT_REFERRAL_POINTS', 'availableAPoints', ' 分'))
const bPoints = computed(() => count('DIRECT_REFERRAL_POINTS', 'frozenBPoints', ' 分'))
const totalPoints = computed(() => {
  const values = parameters(rule('DIRECT_REFERRAL_POINTS'))
  const configured = numberValue(values.totalPoints)
  const a = numberValue(values.availableAPoints)
  const b = numberValue(values.frozenBPoints)
  const total = configured ?? (a !== undefined && b !== undefined ? a + b : undefined)
  return total === undefined ? '未配置' : `${total} 分`
})
const repurchaseAmount = computed(() => amount('REPURCHASE_RELEASE', 'minimumCompletedOrderAmountFen'))
const releasePoints = computed(() => count('REPURCHASE_RELEASE', 'releasePointsPerOrder', ' 分'))
const inactiveMonths = computed(() => count('DIVIDEND_INACTIVITY_DOWNGRADE', 'inactiveMonths', ' 个月'))
const timerParameters = computed(() => parameters(rule('ORDER_TIMERS')))
const autoReceiveDays = computed(() => numberValue(timerParameters.value.autoReceiveDaysAfterShipment))
const afterSaleDays = computed(() => numberValue(timerParameters.value.afterSaleDaysAfterCompletion))
const maxProofFiles = computed(() => numberValue(timerParameters.value.maxProofFiles))
const maxProofSize = computed(() => {
  const bytes = numberValue(timerParameters.value.maxProofSizeBytes)
  return bytes === undefined ? undefined : `${Math.round(bytes / 1024 / 1024)} MB`
})
const effectiveAt = computed(() => {
  const dates = rules.value.map(item => item.effectiveFrom).filter(Boolean).sort()
  return dates.at(-1)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    rules.value = await api<RuleView[]>('/rules/active')
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page rules-page">
    <div class="section-head">
      <div><span class="eyebrow">Clear & Traceable</span><h1>当前会员与订单规则</h1><p>以下内容直接读取后台当前生效的规则版本，不再使用页面内固定金额。</p></div>
      <button class="secondary" type="button" :disabled="loading" @click="load">{{ loading ? '同步中…' : '同步规则' }}</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <section class="rule-intro card">
      <b>重要说明</b>
      <div>
        <p>本系统采用线下收款，不提供在线支付；A/B 积分均为商城演示积分，不可提现、不可转账、不可兑换现金或承诺收益。奖励关系深度固定为一层直属关系。</p>
        <small v-if="effectiveAt">当前展示 {{ rules.length }} 个规则版本，最近生效时间 {{ dateTime(effectiveAt) }}</small>
      </div>
    </section>

    <div v-if="loading" class="rule-loading" aria-busy="true">
      <div v-for="index in 6" :key="index" class="card"></div>
    </div>
    <section v-else-if="rules.length" class="timeline">
      <article class="card">
        <span>01</span><div><h2>体验官</h2><p>完成符合升级专区条件、金额达到 <b>{{ experienceAmount }}</b> 的订单后，由系统记录升级证据。</p><small>规则 v{{ rule('EXPERIENCE_OFFICER_UPGRADE')?.version || '—' }}</small></div>
      </article>
      <article class="card">
        <span>02</span><div><h2>超级会员</h2><p>完成符合升级专区条件、金额达到 <b>{{ superAmount }}</b> 的订单后，开放专属邀请码。</p><small>规则 v{{ rule('SUPER_MEMBER_UPGRADE')?.version || '—' }}</small></div>
      </article>
      <article class="card">
        <span>03</span><div><h2>分红会员资格</h2><p>直属邀请的前 <b>{{ qualificationCount }}</b> 新用户，需要完成注册、关系绑定并各自完成达到 {{ referralAmount }} 的有效订单。</p><small>规则 v{{ rule('DIVIDEND_MEMBER_QUALIFICATION')?.version || '—' }}</small></div>
      </article>
      <article class="card accent">
        <span>04</span><div><h2>推荐积分</h2><p>从第 <b>{{ pointsStart }}</b> 符合条件的直属用户起，每位产生 {{ totalPoints }}：A 池 {{ aPoints }}、B 池 {{ bPoints }}。不计算第二层及更深关系。</p><small>规则 v{{ rule('DIRECT_REFERRAL_POINTS')?.version || '—' }}</small></div>
      </article>
      <article class="card">
        <span>05</span><div><h2>B 池释放</h2><p>会员自己完成复购专区金额达到 <b>{{ repurchaseAmount }}</b> 的订单后，每单按当前配置释放 {{ releasePoints }}。</p><small>{{ textValue(parameters(rule('REPURCHASE_RELEASE')).batchOrder) === 'FIFO' ? '按冻结批次先进先出' : '按后台发布规则执行' }} · v{{ rule('REPURCHASE_RELEASE')?.version || '—' }}</small></div>
      </article>
      <article class="card">
        <span>06</span><div><h2>售后与降级</h2><p>售后完成时以反向账本撤销关联积分并重算等级；连续无有效贡献达到 <b>{{ inactiveMonths }}</b> 后，可按规则降级。</p><small>规则 v{{ rule('DIVIDEND_INACTIVITY_DOWNGRADE')?.version || '—' }}</small></div>
      </article>
    </section>
    <div v-else class="empty card">后台暂未发布当前可用的规则版本。</div>

    <section class="timer-card card">
      <div><span>自动收货</span><b>{{ autoReceiveDays === undefined ? '未配置' : `${autoReceiveDays} 天` }}</b></div>
      <div><span>完成后售后期</span><b>{{ afterSaleDays === undefined ? '未配置' : `${afterSaleDays} 天` }}</b></div>
      <div><span>单订单凭证数</span><b>{{ maxProofFiles === undefined ? '未配置' : `${maxProofFiles} 张` }}</b></div>
      <div><span>单张凭证上限</span><b>{{ maxProofSize || '未配置' }}</b></div>
    </section>

    <section class="order-flow card">
      <h2>订单状态顺序</h2>
      <div><span>提交订单</span><i>→</i><span>上级确认线下收款</span><i>→</i><span>后台审核</span><i>→</i><span>发货</span><i>→</i><span>确认/自动收货</span></div>
    </section>
  </div>
</template>

<style scoped>
.rule-intro { padding: 24px; display: grid; grid-template-columns: 120px 1fr; gap: 20px; border-left: 5px solid var(--coral); }
.rule-intro p { margin: 0; color: var(--muted); line-height: 1.8; }
.rule-intro small { display:block; color:var(--green); margin-top:8px; }
.timeline, .rule-loading { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; margin-top: 20px; }
.timeline article { display: grid; grid-template-columns: 62px 1fr; gap: 16px; padding: 26px; }
.timeline article > span { color: var(--coral); font: 750 36px serif; }
.timeline h2 { margin: 2px 0 10px; font-family: serif; }
.timeline p { margin: 0; color: var(--muted); line-height: 1.75; }
.timeline small { display:block; color:#9a918a; margin-top:12px; }
.timeline .accent { color: white; background: #31584d; }
.timeline .accent p, .timeline .accent small { color: rgba(255,255,255,.75); }
.timeline .accent > span { color: #f5b18a; }
.rule-loading .card { min-height:190px; background:linear-gradient(100deg,#eee8de 20%,#faf7f1 40%,#eee8de 60%); background-size:200% 100%; animation:shine 1.2s infinite; }
.timer-card { display:grid; grid-template-columns:repeat(4,1fr); gap:1px; overflow:hidden; margin-top:20px; }
.timer-card div { display:grid; gap:7px; padding:20px; background:var(--paper); }
.timer-card span { color:var(--muted); font-size:13px; }
.timer-card b { font-size:20px; }
.order-flow { padding: 28px; margin-top: 20px; }
.order-flow h2 { font-family: serif; }
.order-flow div { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.order-flow span { padding: 10px 12px; border-radius: 10px; background: #f1ece4; text-align: center; }
.order-flow i { color: var(--coral); font-style: normal; }
@keyframes shine { to { background-position:-200% 0; } }
@media (max-width: 700px) {
  .rule-intro { grid-template-columns: 1fr; }
  .timeline, .rule-loading { grid-template-columns: 1fr; }
  .timeline article { grid-template-columns: 46px 1fr; padding: 20px 16px; }
  .timer-card { grid-template-columns:1fr 1fr; }
  .order-flow div { align-items: stretch; flex-direction: column; }
  .order-flow i { transform: rotate(90deg); text-align: center; }
}
</style>
