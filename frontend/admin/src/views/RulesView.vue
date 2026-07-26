<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { adminApi, dateTime } from '../api'

type Rule = { id:number;ruleCode:string;version:number;ruleType:string;parametersJson:string;status:string;effectiveFrom:string }
type RuleType = 'SELF_ORDER_TASK'|'DIRECT_REFERRAL_TASK'|'DIRECT_REFERRAL_POINTS'|'FROZEN_POINTS_RELEASE'|'INACTIVITY_DOWNGRADE'|'ORDER_TIMER'

const rules = ref<Rule[]>([])
const error = ref('')
const success = ref('')
const publishing = ref(false)
const advanced = ref(false)
const busy = ref(false)
const compare = ref<Rule>()
const compareTarget = ref<Rule>()
const form = reactive({
  ruleCode:'DIRECT_REFERRAL_POINTS',
  ruleType:'DIRECT_REFERRAL_POINTS' as RuleType,
  effectiveFrom:'',
  parametersJson:''
})
const fields = reactive({
  minimumCompletedOrderAmountFen:199800,
  targetLevel:'SUPER_MEMBER',
  requiredCompletedDirectReferrals:5,
  minimumReferralOrderAmountFen:199800,
  requiredReferralLevel:'SUPER_MEMBER',
  pointsStartOrdinal:6,
  availableAPoints:160,
  frozenBPoints:160,
  releasePointsPerOrder:160,
  inactiveMonths:5,
  sourceLevel:'DIVIDEND_MEMBER',
  autoReceiveDaysAfterShipment:7,
  afterSaleDaysAfterCompletion:7,
  proofRetentionDays:180,
  maxProofFiles:3,
  maxProofSizeMb:8
})

const parameters = computed<Record<string, unknown>>(() => {
  switch (form.ruleType) {
    case 'SELF_ORDER_TASK': return {
      minimumCompletedOrderAmountFen:fields.minimumCompletedOrderAmountFen,
      eligibleSalesScenes:['UPGRADE'],
      targetLevel:fields.targetLevel
    }
    case 'DIRECT_REFERRAL_TASK': return {
      requiredCompletedDirectReferrals:fields.requiredCompletedDirectReferrals,
      minimumReferralOrderAmountFen:fields.minimumReferralOrderAmountFen,
      requiredReferralLevel:fields.requiredReferralLevel,
      targetLevel:'DIVIDEND_MEMBER'
    }
    case 'DIRECT_REFERRAL_POINTS': return {
      qualificationCount:fields.requiredCompletedDirectReferrals,
      pointsStartOrdinal:fields.pointsStartOrdinal,
      totalPoints:fields.availableAPoints + fields.frozenBPoints,
      availableAPoints:fields.availableAPoints,
      frozenBPoints:fields.frozenBPoints,
      maxRewardDepth:1
    }
    case 'FROZEN_POINTS_RELEASE': return {
      eligibleSalesScenes:['REPURCHASE'],
      minimumCompletedOrderAmountFen:fields.minimumCompletedOrderAmountFen,
      releaseMode:'FIXED',
      releasePointsPerOrder:fields.releasePointsPerOrder,
      batchOrder:'FIFO'
    }
    case 'INACTIVITY_DOWNGRADE': return {
      inactiveMonths:fields.inactiveMonths,
      sourceLevel:fields.sourceLevel,
      targetLevel:fields.targetLevel
    }
    case 'ORDER_TIMER': return {
      autoReceiveDaysAfterShipment:fields.autoReceiveDaysAfterShipment,
      afterSaleDaysAfterCompletion:fields.afterSaleDaysAfterCompletion,
      proofRetentionDays:fields.proofRetentionDays,
      maxProofFiles:fields.maxProofFiles,
      maxProofSizeBytes:fields.maxProofSizeMb * 1024 * 1024
    }
  }
})

watch(parameters, value => {
  if (!advanced.value) form.parametersJson = JSON.stringify(value, null, 2)
}, { immediate:true })

watch(() => form.ruleType, type => {
  const codes:Record<RuleType,string> = {
    SELF_ORDER_TASK:'SUPER_MEMBER_UPGRADE',
    DIRECT_REFERRAL_TASK:'DIVIDEND_MEMBER_QUALIFICATION',
    DIRECT_REFERRAL_POINTS:'DIRECT_REFERRAL_POINTS',
    FROZEN_POINTS_RELEASE:'REPURCHASE_RELEASE',
    INACTIVITY_DOWNGRADE:'DIVIDEND_INACTIVITY_DOWNGRADE',
    ORDER_TIMER:'ORDER_TIMERS'
  }
  form.ruleCode = codes[type]
  advanced.value = false
})

async function load() {
  try { rules.value = await adminApi<Rule[]>('/rules') }
  catch (e) { error.value = (e as Error).message }
}

async function publish() {
  error.value = ''
  success.value = ''
  busy.value = true
  try {
    JSON.parse(form.parametersJson)
    const payload = {
      ...form,
      effectiveFrom:form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : new Date().toISOString()
    }
    await adminApi('/rules/validate', {method:'POST',body:JSON.stringify(payload)})
    await adminApi('/rules', {method:'POST',body:JSON.stringify(payload)})
    publishing.value = false
    success.value = '规则校验通过并已发布为新版本。'
    await load()
  } catch (e) { error.value = e instanceof SyntaxError ? '规则参数不是有效 JSON' : (e as Error).message }
  finally { busy.value = false }
}

async function cancel(rule: Rule) {
  const reason = prompt('取消未来规则版本的原因') || ''
  if (!reason) return
  await adminApi(`/rules/${rule.id}`, {method:'DELETE',body:JSON.stringify({reason})})
  await load()
}

function summary(rule: Rule) {
  try {
    const value = JSON.parse(rule.parametersJson) as Record<string, string|number>
    return Object.entries(value).slice(0, 3).map(([key, item]) => `${key}: ${item}`).join(' · ')
  } catch { return rule.parametersJson }
}

function openCompare(rule: Rule) {
  compare.value = rule
  compareTarget.value = rules.value.find(item => item.ruleCode === rule.ruleCode && item.id !== rule.id)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title"><div><h1>动态规则版本</h1><p>通过业务字段配置升级、人数、积分、复购释放和降级规则，高级模式仍可查看原始 JSON。</p></div><button class="primary" @click="publishing = true">发布新版本</button></div>
    <div class="boundary-note"><b>硬安全边界不可配置：</b>在线支付关闭、积分不可提现/转账/兑现金、奖励深度固定为 1 层。</div>
    <p v-if="error" class="error">{{ error }}</p><p v-if="success" class="success">{{ success }}</p>
    <div class="card table-wrap"><table><thead><tr><th>规则编码</th><th>版本</th><th>类型</th><th>状态</th><th>生效时间</th><th>业务摘要</th><th>操作</th></tr></thead><tbody>
      <tr v-for="rule in rules" :key="rule.id"><td><b>{{ rule.ruleCode }}</b></td><td>v{{ rule.version }}</td><td>{{ rule.ruleType }}</td><td><span class="tag" :class="{green:rule.status === 'ACTIVE'}">{{ rule.status }}</span></td><td>{{ dateTime(rule.effectiveFrom) }}</td><td class="summary">{{ summary(rule) }}</td><td class="actions"><button class="secondary" @click="openCompare(rule)">详情 / 对比</button><button v-if="new Date(rule.effectiveFrom) > new Date() && rule.status === 'ACTIVE'" class="danger" @click="cancel(rule)">取消</button></td></tr>
    </tbody></table></div>

    <div v-if="publishing" class="modal-mask" @click.self="publishing = false">
      <form class="modal rule-modal card" @submit.prevent="publish">
        <div class="modal-title"><div><h2>发布规则新版本</h2><p>旧版本不会被修改，历史订单仍保留原规则快照。</p></div><button type="button" class="secondary" @click="publishing = false">关闭</button></div>
        <div class="grid">
          <div class="field"><label>规则类型</label><select v-model="form.ruleType"><option>SELF_ORDER_TASK</option><option>DIRECT_REFERRAL_TASK</option><option>DIRECT_REFERRAL_POINTS</option><option>FROZEN_POINTS_RELEASE</option><option>INACTIVITY_DOWNGRADE</option><option>ORDER_TIMER</option></select></div>
          <div class="field"><label>规则编码</label><input v-model="form.ruleCode" required /></div>
          <div class="field"><label>生效时间（留空立即生效）</label><input v-model="form.effectiveFrom" type="datetime-local" /></div>
        </div>
        <section v-if="form.ruleType === 'SELF_ORDER_TASK'" class="fields"><div class="field"><label>完成订单金额门槛（分）</label><input v-model.number="fields.minimumCompletedOrderAmountFen" type="number" min="1" /></div><div class="field"><label>升级目标等级</label><select v-model="fields.targetLevel"><option>EXPERIENCE_OFFICER</option><option>SUPER_MEMBER</option></select></div></section>
        <section v-if="form.ruleType === 'DIRECT_REFERRAL_TASK'" class="fields"><div class="field"><label>有效直属人数</label><input v-model.number="fields.requiredCompletedDirectReferrals" type="number" min="1" /></div><div class="field"><label>新会员订单门槛（分）</label><input v-model.number="fields.minimumReferralOrderAmountFen" type="number" min="1" /></div><div class="field"><label>新会员所需等级</label><select v-model="fields.requiredReferralLevel"><option>SUPER_MEMBER</option><option>EXPERIENCE_OFFICER</option></select></div></section>
        <section v-if="form.ruleType === 'DIRECT_REFERRAL_POINTS'" class="fields"><div class="field"><label>资格人数</label><input v-model.number="fields.requiredCompletedDirectReferrals" type="number" min="1" /></div><div class="field"><label>从第几人开始计分</label><input v-model.number="fields.pointsStartOrdinal" type="number" min="2" /></div><div class="field"><label>A 池可用积分</label><input v-model.number="fields.availableAPoints" type="number" min="0" /></div><div class="field"><label>B 池冻结积分</label><input v-model.number="fields.frozenBPoints" type="number" min="0" /></div></section>
        <section v-if="form.ruleType === 'FROZEN_POINTS_RELEASE'" class="fields"><div class="field"><label>复购订单门槛（分）</label><input v-model.number="fields.minimumCompletedOrderAmountFen" type="number" min="1" /></div><div class="field"><label>每单释放积分</label><input v-model.number="fields.releasePointsPerOrder" type="number" min="1" /></div><div class="field readonly"><label>释放方式</label><b>固定积分 · FIFO</b></div></section>
        <section v-if="form.ruleType === 'INACTIVITY_DOWNGRADE'" class="fields"><div class="field"><label>连续无业绩（月）</label><input v-model.number="fields.inactiveMonths" type="number" min="1" max="60" /></div><div class="field"><label>原等级</label><select v-model="fields.sourceLevel"><option>DIVIDEND_MEMBER</option><option>SUPER_MEMBER</option></select></div><div class="field"><label>降级目标</label><select v-model="fields.targetLevel"><option>SUPER_MEMBER</option><option>EXPERIENCE_OFFICER</option><option>BASIC</option></select></div></section>
        <section v-if="form.ruleType === 'ORDER_TIMER'" class="fields"><div class="field"><label>自动收货天数</label><input v-model.number="fields.autoReceiveDaysAfterShipment" type="number" min="1" max="365" /></div><div class="field"><label>售后期限</label><input v-model.number="fields.afterSaleDaysAfterCompletion" type="number" min="1" max="365" /></div><div class="field"><label>凭证保留天数</label><input v-model.number="fields.proofRetentionDays" type="number" min="1" max="3650" /></div><div class="field"><label>最大凭证数</label><input v-model.number="fields.maxProofFiles" type="number" min="1" max="20" /></div><div class="field"><label>单张上限（MB）</label><input v-model.number="fields.maxProofSizeMb" type="number" min="1" max="20" /></div></section>
        <button type="button" class="advanced" @click="advanced = !advanced">{{ advanced ? '使用业务表单重新生成 JSON' : '高级：查看或编辑 JSON' }}</button>
        <div v-if="advanced" class="field"><label>参数 JSON</label><textarea v-model="form.parametersJson" rows="12" required /></div>
        <pre v-else>{{ form.parametersJson }}</pre>
        <div class="modal-actions"><button type="button" class="secondary" @click="publishing = false">取消</button><button class="primary" :disabled="busy">{{ busy ? '发布中…' : '校验并发布' }}</button></div>
      </form>
    </div>

    <div v-if="compare" class="modal-mask" @click.self="compare = undefined">
      <section class="modal compare-modal card">
        <div class="modal-title"><div><h2>{{ compare.ruleCode }} v{{ compare.version }}</h2><p>选择同编码的其他版本进行参数对照。</p></div><button class="secondary" @click="compare = undefined">关闭</button></div>
        <select v-model="compareTarget"><option :value="undefined">不对比</option><option v-for="rule in rules.filter(item => item.ruleCode === compare?.ruleCode && item.id !== compare?.id)" :key="rule.id" :value="rule">v{{ rule.version }} · {{ rule.status }}</option></select>
        <div class="compare-grid"><div><b>当前版本</b><pre>{{ JSON.stringify(JSON.parse(compare.parametersJson), null, 2) }}</pre></div><div v-if="compareTarget"><b>对比版本 v{{ compareTarget.version }}</b><pre>{{ JSON.stringify(JSON.parse(compareTarget.parametersJson), null, 2) }}</pre></div></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.boundary-note{padding:13px 15px;margin-bottom:14px;color:#835c21;background:#fff0d3;border-radius:10px;font-size:13px}.success{padding:10px 12px;color:#276b55;background:#e3f0ea;border-radius:9px}.summary{max-width:380px;overflow:hidden;text-overflow:ellipsis}.actions,.modal-title{display:flex;gap:8px}
.rule-modal{width:min(900px,100%);max-height:94vh;overflow:auto}.modal-title{justify-content:space-between}.modal-title h2,.modal-title p{margin:0}.modal-title p{color:var(--muted);margin-top:5px}.grid,.fields{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:18px}.fields{padding:16px;background:#f6f8f6;border-radius:12px}.readonly b{padding:10px;background:white;border-radius:8px}
pre{padding:14px;overflow:auto;color:#405249;background:#f3f6f4;border-radius:10px;font:12px/1.6 ui-monospace,monospace}.advanced{margin:14px 0 8px;padding:0;border:0;color:var(--green);background:transparent;font-weight:700}.compare-modal{width:min(900px,100%);max-height:92vh;overflow:auto}.compare-modal>select{margin:16px 0;padding:8px;border:1px solid var(--line);border-radius:8px}.compare-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:700px){.grid,.fields,.compare-grid{grid-template-columns:1fr}}
</style>
