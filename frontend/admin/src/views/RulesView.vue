<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { adminApi, adminErrorMessage, dateTime, isConflictError } from '../api'
import BaseDialog from '../components/admin/BaseDialog.vue'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import DetailDrawer from '../components/admin/DetailDrawer.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import { memberLevelLabel, ruleParameterLabel, ruleParameterValue, ruleStatusLabel, ruleTypeLabel, ruleTypeOptions } from '../localization'
import { notifyError, notifySuccess } from '../toast'

type Rule = { id: number; ruleCode: string; version: number; ruleType: string; parametersJson: string; status: string; effectiveFrom: string }
type RuleType = 'SELF_ORDER_TASK' | 'DIRECT_REFERRAL_TASK' | 'DIRECT_REFERRAL_POINTS' | 'FROZEN_POINTS_RELEASE' | 'INACTIVITY_DOWNGRADE'
type RulePayload = { ruleCode: string; ruleType: RuleType; effectiveFrom: string; parametersJson: string; reason: string }
type LoadState = 'unloaded' | 'loading' | 'loaded' | 'error'

const rules = ref<Rule[]>([])
const loadState = ref<LoadState>('unloaded')
const loadError = ref('')
const publishOpen = ref(false)
const advanced = ref(false)
const validateBusy = ref(false)
const validationError = ref('')
const validatedPayload = ref<RulePayload>()
const validatedSignature = ref('')
const publishConfirmOpen = ref(false)
const publishSubmitting = ref(false)
const publishError = ref('')
const publishReason = ref('')
const compare = ref<Rule>()
const compareTarget = ref<Rule>()
const cancelling = ref<Rule>()
const cancelReason = ref('')
const cancelError = ref('')
const cancelSubmitting = ref(false)

const form = reactive({
  ruleCode: 'DIRECT_REFERRAL_POINTS',
  ruleType: 'DIRECT_REFERRAL_POINTS' as RuleType,
  effectiveFrom: '',
  parametersJson: ''
})
const fields = reactive({
  minimumCompletedOrderAmountFen: 199800, targetLevel: 'SUPER_MEMBER',
  requiredCompletedDirectReferrals: 5, minimumReferralOrderAmountFen: 199800,
  requiredReferralLevel: 'SUPER_MEMBER', pointsStartOrdinal: 6, availableAPoints: 160,
  frozenBPoints: 160, releasePointsPerOrder: 160, inactiveMonths: 5,
  sourceLevel: 'DIVIDEND_MEMBER'
})

const codes: Record<RuleType, string> = {
  SELF_ORDER_TASK: 'SUPER_MEMBER_UPGRADE',
  DIRECT_REFERRAL_TASK: 'DIVIDEND_MEMBER_QUALIFICATION',
  DIRECT_REFERRAL_POINTS: 'DIRECT_REFERRAL_POINTS',
  FROZEN_POINTS_RELEASE: 'REPURCHASE_RELEASE',
  INACTIVITY_DOWNGRADE: 'DIVIDEND_INACTIVITY_DOWNGRADE'
}

const publishableRuleTypeOptions = ruleTypeOptions.filter(option => option.value !== 'ORDER_TIMER')

const parameters = computed<Record<string, unknown>>(() => {
  switch (form.ruleType) {
    case 'SELF_ORDER_TASK': return { minimumCompletedOrderAmountFen: fields.minimumCompletedOrderAmountFen, eligibleSalesScenes: ['UPGRADE'], targetLevel: fields.targetLevel }
    case 'DIRECT_REFERRAL_TASK': return { requiredCompletedDirectReferrals: fields.requiredCompletedDirectReferrals, minimumReferralOrderAmountFen: fields.minimumReferralOrderAmountFen, requiredReferralLevel: fields.requiredReferralLevel, targetLevel: 'DIVIDEND_MEMBER' }
    case 'DIRECT_REFERRAL_POINTS': return { qualificationCount: fields.requiredCompletedDirectReferrals, pointsStartOrdinal: fields.pointsStartOrdinal, totalPoints: fields.availableAPoints + fields.frozenBPoints, availableAPoints: fields.availableAPoints, frozenBPoints: fields.frozenBPoints, maxRewardDepth: 1 }
    case 'FROZEN_POINTS_RELEASE': return { eligibleSalesScenes: ['REPURCHASE'], minimumCompletedOrderAmountFen: fields.minimumCompletedOrderAmountFen, releaseMode: 'FIXED', releasePointsPerOrder: fields.releasePointsPerOrder, batchOrder: 'FIFO' }
    case 'INACTIVITY_DOWNGRADE': return { inactiveMonths: fields.inactiveMonths, sourceLevel: fields.sourceLevel, targetLevel: fields.targetLevel }
  }
})

const currentRule = computed(() => rules.value
  .filter(rule => rule.ruleCode === form.ruleCode && rule.status === 'ACTIVE' && !isFutureRule(rule))
  .sort((a, b) => b.version - a.version)[0])

const draftSignature = computed(() => JSON.stringify({
  ruleCode: form.ruleCode, ruleType: form.ruleType, effectiveFrom: form.effectiveFrom,
  parametersJson: form.parametersJson
}))

const diffRows = computed(() => {
  if (!validatedPayload.value) return []
  const before = parseParameters(currentRule.value?.parametersJson)
  const after = parseParameters(validatedPayload.value.parametersJson)
  const keys = new Set([...Object.keys(before), ...Object.keys(after)])
  return [...keys].flatMap(key => {
    const oldValue = JSON.stringify(before[key])
    const newValue = JSON.stringify(after[key])
    return oldValue === newValue ? [] : [{ key, label: ruleParameterLabel(key), before: ruleParameterValue(key, before[key]), after: ruleParameterValue(key, after[key]) }]
  })
})

function parseParameters(value?: string): Record<string, unknown> {
  if (!value) return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
  } catch { return {} }
}

function assignNumber(source: Record<string, unknown>, key: keyof typeof fields, sourceKey: string = key) {
  const value = source[sourceKey]
  if (typeof value === 'number') fields[key] = value as never
}

function hydrateFromCurrent() {
  const source = parseParameters(currentRule.value?.parametersJson)
  assignNumber(source, 'minimumCompletedOrderAmountFen')
  assignNumber(source, 'requiredCompletedDirectReferrals', 'requiredCompletedDirectReferrals' in source ? 'requiredCompletedDirectReferrals' : 'qualificationCount')
  assignNumber(source, 'minimumReferralOrderAmountFen')
  assignNumber(source, 'pointsStartOrdinal')
  assignNumber(source, 'availableAPoints')
  assignNumber(source, 'frozenBPoints')
  assignNumber(source, 'releasePointsPerOrder')
  assignNumber(source, 'inactiveMonths')
  if (typeof source.targetLevel === 'string') fields.targetLevel = source.targetLevel
  if (typeof source.requiredReferralLevel === 'string') fields.requiredReferralLevel = source.requiredReferralLevel
  if (typeof source.sourceLevel === 'string') fields.sourceLevel = source.sourceLevel
  form.parametersJson = JSON.stringify(Object.keys(source).length ? source : parameters.value, null, 2)
}

async function load() {
  loadState.value = 'loading'
  loadError.value = ''
  try {
    rules.value = await adminApi<Rule[]>('/rules')
    loadState.value = 'loaded'
  } catch (cause) {
    rules.value = []
    loadState.value = 'error'
    loadError.value = adminErrorMessage(cause)
  }
}

function selectRuleType(type: RuleType) {
  form.ruleType = type
  form.ruleCode = codes[type]
  form.effectiveFrom = ''
  advanced.value = false
  validatedPayload.value = undefined
  validatedSignature.value = ''
  hydrateFromCurrent()
}

function openPublisher() {
  if (loadState.value !== 'loaded') return
  publishReason.value = ''
  validationError.value = ''
  publishError.value = ''
  selectRuleType(form.ruleType)
  publishOpen.value = true
}

function closePublisher() {
  publishOpen.value = false
  validatedPayload.value = undefined
  validatedSignature.value = ''
  validationError.value = ''
}

async function validateDraft() {
  if (validateBusy.value || !publishReason.value.trim()) return
  validateBusy.value = true
  validationError.value = ''
  try {
    JSON.parse(form.parametersJson)
    const payload: RulePayload = {
      ruleCode: form.ruleCode,
      ruleType: form.ruleType,
      effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : new Date().toISOString(),
      parametersJson: form.parametersJson,
      reason: publishReason.value.trim()
    }
    await adminApi('/rules/validate', { method: 'POST', body: JSON.stringify(payload) })
    validatedPayload.value = payload
    validatedSignature.value = draftSignature.value
    notifySuccess('规则草稿校验通过', '请核对字段差异后进入发布确认。')
  } catch (cause) {
    validationError.value = cause instanceof SyntaxError ? '原始规则参数格式无效' : adminErrorMessage(cause)
    validatedPayload.value = undefined
  } finally { validateBusy.value = false }
}

async function publishValidated() {
  const payload = validatedPayload.value
  if (!payload || validatedSignature.value !== draftSignature.value || publishSubmitting.value) return
  publishSubmitting.value = true
  publishError.value = ''
  let committed = false
  try {
    const published = await adminApi<Rule>('/rules', { method: 'POST', body: JSON.stringify({ ...payload, reason: publishReason.value.trim() }) })
    committed = true
    await load()
    if (loadState.value !== 'loaded' || !rules.value.some(rule => rule.id === published.id)) {
      throw new Error('版本已提交，但服务端最新版本回读失败，请返回列表重试读取')
    }
    publishConfirmOpen.value = false
    closePublisher()
    notifySuccess('规则新版本已发布', '列表已重新读取服务端权威版本。')
  } catch (cause) {
    const message = adminErrorMessage(cause)
    if (committed) {
      publishConfirmOpen.value = false
      closePublisher()
      loadError.value = message
      loadState.value = 'error'
      notifyError('规则版本已提交，但回读失败', '发布入口已锁定，请重试读取列表，不要重复发布。')
    } else {
      publishError.value = message
      if (isConflictError(cause)) await load()
    }
  } finally { publishSubmitting.value = false }
}

function openCancel(rule: Rule) { cancelling.value = rule; cancelReason.value = ''; cancelError.value = '' }
async function cancelRule() {
  const rule = cancelling.value
  if (!rule || cancelSubmitting.value) return
  cancelSubmitting.value = true
  cancelError.value = ''
  try {
    await adminApi(`/rules/${rule.id}`, { method: 'DELETE', body: JSON.stringify({ reason: cancelReason.value.trim() }) })
    cancelling.value = undefined
    await load()
    notifySuccess('未来规则版本已取消')
  } catch (cause) { cancelError.value = adminErrorMessage(cause) }
  finally { cancelSubmitting.value = false }
}

function summary(rule: Rule) {
  const value = parseParameters(rule.parametersJson)
  return Object.entries(value).slice(0, 3).map(([key, item]) => `${ruleParameterLabel(key)}：${ruleParameterValue(key, item)}`).join(' · ') || '规则参数暂时无法解析'
}
function parameterDetails(parametersJson: string) {
  const value = parseParameters(parametersJson)
  return Object.entries(value).map(([key, item]) => `${ruleParameterLabel(key)}：${ruleParameterValue(key, item)}`).join('\n') || '规则参数暂时无法解析'
}
function isFutureRule(rule: Rule) { return new Date(rule.effectiveFrom).getTime() > Date.now() }
function displayRuleStatus(rule: Rule) { return rule.status === 'ACTIVE' && isFutureRule(rule) ? '待生效' : ruleStatusLabel(rule.status) }
function openCompare(rule: Rule) { compare.value = rule; compareTarget.value = rules.value.find(item => item.ruleCode === rule.ruleCode && item.id !== rule.id) }

watch(parameters, value => { if (!advanced.value) form.parametersJson = JSON.stringify(value, null, 2) }, { immediate: true })
watch(draftSignature, value => { if (validatedSignature.value && validatedSignature.value !== value) validatedPayload.value = undefined })
onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="动态规则版本" description="编辑草稿、服务端校验、核对字段差异并二次确认后，才发布不可变新版本。"><template #actions><button class="primary" type="button" :disabled="loadState !== 'loaded'" @click="openPublisher">发布新版本</button></template></PageHeader>
    <div class="boundary-note"><b>硬安全边界不可配置：</b>在线支付关闭、积分不可提现/转账/兑现金、奖励深度固定为 1 层。</div>
    <InlineAlert v-if="loadState === 'error'" title="当前规则版本加载失败" :message="`${loadError}；编辑与发布已锁定，避免用未知默认值覆盖线上规则。`" retryable @retry="load" />
    <TableFrame :loading="loadState === 'loading' || loadState === 'unloaded'" :error="loadState === 'error' ? loadError : ''" :empty="loadState === 'loaded' && !rules.length" empty-title="暂无规则版本" label="规则版本列表" @retry="load"><table class="responsive-table"><thead><tr><th>规则编码</th><th>版本</th><th>类型</th><th>状态</th><th>生效时间</th><th>业务摘要</th><th>操作</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id"><td data-label="规则编码"><b>{{ rule.ruleCode }}</b></td><td data-label="版本">第 {{ rule.version }} 版</td><td data-label="类型">{{ ruleTypeLabel(rule.ruleType) }}</td><td data-label="状态"><StatusTag :tone="rule.status === 'ACTIVE' ? (isFutureRule(rule) ? 'warning' : 'success') : 'neutral'" :label="displayRuleStatus(rule)" /></td><td data-label="生效时间">{{ dateTime(rule.effectiveFrom) }}</td><td class="summary" data-label="业务摘要">{{ summary(rule) }}</td><td class="actions" data-label="操作"><button class="primary" type="button" @click="openCompare(rule)">详情 / 对比</button><button v-if="isFutureRule(rule) && rule.status === 'ACTIVE'" class="danger" type="button" @click="openCancel(rule)">取消版本</button></td></tr></tbody></table></TableFrame>

    <BaseDialog :model-value="publishOpen" title="编辑规则新版本草稿" description="先从当前服务端版本复制参数；修改后必须重新校验并核对差异。订单与凭证时限统一在“系统配置”维护。" width="min(940px, calc(100vw - 32px))" :submitting="validateBusy" @update:model-value="value => { if (!value) closePublisher() }"><form id="rule-draft-form" class="rule-form" @submit.prevent="validateDraft"><div class="grid"><label class="field"><span>规则类型</span><select :value="form.ruleType" @change="selectRuleType(($event.target as HTMLSelectElement).value as RuleType)"><option v-for="option in publishableRuleTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>规则编码</span><input v-model="form.ruleCode" required /></label><label class="field"><span>生效时间（留空立即生效）</span><input v-model="form.effectiveFrom" type="datetime-local" /></label></div><InlineAlert v-if="!currentRule" tone="warning" title="该规则没有当前生效版本" message="将以安全表单默认值创建首个版本；请重点核对差异。" />
      <section v-if="form.ruleType === 'SELF_ORDER_TASK'" class="fields"><label class="field"><span>完成订单金额门槛（分）</span><input v-model.number="fields.minimumCompletedOrderAmountFen" type="number" min="1" /></label><label class="field"><span>升级目标等级</span><select v-model="fields.targetLevel"><option value="EXPERIENCE_OFFICER">{{ memberLevelLabel('EXPERIENCE_OFFICER') }}</option><option value="SUPER_MEMBER">{{ memberLevelLabel('SUPER_MEMBER') }}</option></select></label></section>
      <section v-if="form.ruleType === 'DIRECT_REFERRAL_TASK'" class="fields"><label class="field"><span>有效直属人数</span><input v-model.number="fields.requiredCompletedDirectReferrals" type="number" min="1" /></label><label class="field"><span>新会员订单门槛（分）</span><input v-model.number="fields.minimumReferralOrderAmountFen" type="number" min="1" /></label><label class="field"><span>新会员所需等级</span><select v-model="fields.requiredReferralLevel"><option value="SUPER_MEMBER">{{ memberLevelLabel('SUPER_MEMBER') }}</option><option value="EXPERIENCE_OFFICER">{{ memberLevelLabel('EXPERIENCE_OFFICER') }}</option></select></label></section>
      <section v-if="form.ruleType === 'DIRECT_REFERRAL_POINTS'" class="fields"><label class="field"><span>资格人数</span><input v-model.number="fields.requiredCompletedDirectReferrals" type="number" min="1" /></label><label class="field"><span>从第几人开始计分</span><input v-model.number="fields.pointsStartOrdinal" type="number" min="2" /></label><label class="field"><span>A 池可用积分</span><input v-model.number="fields.availableAPoints" type="number" min="0" /></label><label class="field"><span>B 池冻结积分</span><input v-model.number="fields.frozenBPoints" type="number" min="0" /></label></section>
      <section v-if="form.ruleType === 'FROZEN_POINTS_RELEASE'" class="fields"><label class="field"><span>复购订单门槛（分）</span><input v-model.number="fields.minimumCompletedOrderAmountFen" type="number" min="1" /></label><label class="field"><span>每单释放积分</span><input v-model.number="fields.releasePointsPerOrder" type="number" min="1" /></label><div class="field readonly"><span>释放方式</span><b>固定积分 · 先进先出</b></div></section>
      <section v-if="form.ruleType === 'INACTIVITY_DOWNGRADE'" class="fields"><label class="field"><span>连续无业绩（月）</span><input v-model.number="fields.inactiveMonths" type="number" min="1" max="60" /></label><label class="field"><span>原等级</span><select v-model="fields.sourceLevel"><option value="DIVIDEND_MEMBER">{{ memberLevelLabel('DIVIDEND_MEMBER') }}</option><option value="SUPER_MEMBER">{{ memberLevelLabel('SUPER_MEMBER') }}</option></select></label><label class="field"><span>降级目标</span><select v-model="fields.targetLevel"><option value="SUPER_MEMBER">{{ memberLevelLabel('SUPER_MEMBER') }}</option><option value="EXPERIENCE_OFFICER">{{ memberLevelLabel('EXPERIENCE_OFFICER') }}</option><option value="BASIC">{{ memberLevelLabel('BASIC') }}</option></select></label></section>
      <button type="button" class="advanced" @click="advanced = !advanced">{{ advanced ? '使用业务表单重新生成原始参数' : '高级：查看或编辑原始参数' }}</button><label v-if="advanced" class="field"><span>原始参数（结构化数据）</span><textarea v-model="form.parametersJson" rows="12" required /></label><pre v-else>{{ parameterDetails(form.parametersJson) }}</pre><label class="field"><span>版本发布原因</span><textarea v-model="publishReason" rows="3" maxlength="500" required placeholder="说明为什么需要调整此规则" /></label><InlineAlert v-if="validationError" title="规则草稿校验失败" :message="validationError" /><section v-if="validatedPayload" class="diff-panel"><h3>校验通过 · 字段差异</h3><div v-if="diffRows.length"><p v-for="row in diffRows" :key="row.key"><b>{{ row.label }}</b><span>{{ row.before }} → {{ row.after }}</span></p></div><p v-else>参数与当前版本一致；仍会创建新的不可变版本。</p></section></form><template #footer="{ close }"><button class="secondary" type="button" :disabled="validateBusy" @click="close">取消</button><button v-if="!validatedPayload" class="primary" form="rule-draft-form" :disabled="validateBusy || !publishReason.trim()">{{ validateBusy ? '校验中…' : '校验草稿' }}</button><button v-else class="primary" type="button" :disabled="validatedSignature !== draftSignature" @click="publishConfirmOpen = true">进入发布确认</button></template></BaseDialog>

    <BusinessActionDialog :model-value="publishConfirmOpen" title="确认发布规则新版本" :target="`${form.ruleCode} · ${ruleTypeLabel(form.ruleType)}`" :impact="`将创建不可变新版本，生效时间为${validatedPayload ? dateTime(validatedPayload.effectiveFrom) : '—'}；历史订单继续使用原规则快照。`" :current-state="currentRule ? `第 ${currentRule.version} 版` : '暂无生效版本'" next-state="新版本" v-model:reason="publishReason" reason-label="版本发布原因" confirm-label="确认发布新版本" :submitting="publishSubmitting" :error="publishError" @update:model-value="value => publishConfirmOpen = value" @submit="publishValidated"><div class="confirm-diff"><p v-for="row in diffRows" :key="row.key"><b>{{ row.label }}</b><span>{{ row.before }} → {{ row.after }}</span></p><p v-if="!diffRows.length">参数无字段变化。</p></div></BusinessActionDialog>

    <DetailDrawer :model-value="Boolean(compare)" :title="compare ? `${compare.ruleCode} · 第 ${compare.version} 版` : '规则详情'" description="选择同编码版本进行参数对照" width="min(900px, 100vw)" @update:model-value="value => { if (!value) compare = undefined }"><select v-if="compare" v-model="compareTarget" class="compare-select"><option :value="undefined">不对比</option><option v-for="rule in rules.filter(item => item.ruleCode === compare?.ruleCode && item.id !== compare?.id)" :key="rule.id" :value="rule">第 {{ rule.version }} 版 · {{ displayRuleStatus(rule) }}</option></select><div v-if="compare" class="compare-grid"><section><b>当前查看版本</b><pre>{{ parameterDetails(compare.parametersJson) }}</pre></section><section v-if="compareTarget"><b>对比版本 · 第 {{ compareTarget.version }} 版</b><pre>{{ parameterDetails(compareTarget.parametersJson) }}</pre></section></div></DetailDrawer>

    <BusinessActionDialog :model-value="Boolean(cancelling)" title="取消未来规则版本" :target="cancelling ? `${cancelling.ruleCode} · 第 ${cancelling.version} 版` : '当前规则版本'" impact="仅可取消尚未到生效时间的版本；已生效版本和历史快照不会被改写。" current-state="待生效" next-state="已取消" v-model:reason="cancelReason" danger confirm-label="确认取消版本" :submitting="cancelSubmitting" :error="cancelError" @update:model-value="value => { if (!value) cancelling = undefined }" @submit="cancelRule" />
  </div>
</template>

<style scoped>
.boundary-note{padding:13px 15px;margin-bottom:14px;color:var(--color-warning);background:var(--color-warning-bg);border-radius:10px;font-size:13px}.summary{max-width:380px;overflow:hidden;text-overflow:ellipsis}.actions{display:flex;gap:8px}.rule-form{display:grid;gap:14px}.grid,.fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.fields{padding:16px;background:var(--color-surface-subtle);border-radius:12px}.readonly b{padding:10px;background:#fff;border-radius:8px}.rule-form pre,.compare-grid pre{padding:14px;overflow:auto;color:#405249;background:#f3f6f4;border-radius:10px;font:12px/1.6 ui-monospace,monospace}.advanced{justify-self:start;padding:0;border:0;color:var(--color-brand);background:transparent;font-weight:700}.diff-panel{padding:14px;border:1px solid var(--color-success);border-radius:10px;background:var(--color-success-bg)}.diff-panel h3{margin:0 0 8px}.diff-panel p,.confirm-diff p{display:grid;grid-template-columns:minmax(150px,.7fr) minmax(0,1fr);gap:10px;margin:0;padding:8px 0;border-bottom:1px solid #cfe3d9}.diff-panel p:last-child,.confirm-diff p:last-child{border:0}.confirm-diff{margin-bottom:14px}.confirm-diff span{color:var(--color-text-muted)}.compare-select{margin-bottom:16px;padding:9px;border:1px solid var(--color-border);border-radius:8px}.compare-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:700px){.grid,.fields,.compare-grid{grid-template-columns:1fr}.diff-panel p,.confirm-diff p{grid-template-columns:1fr}.actions{flex-wrap:wrap}}
</style>
