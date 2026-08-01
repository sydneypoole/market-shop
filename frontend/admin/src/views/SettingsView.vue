<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi, adminErrorMessage, dateTime, isConflictError } from '../api'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import { parseOrderTimerParameters } from '../rules/rule-parameters'
import { can } from '../session'
import { notifyError, notifySuccess } from '../toast'

type Settings = {
  afterSaleReturnReceiver: string
  afterSaleReturnPhone: string
  afterSaleReturnAddress: string
  lowInventoryThreshold: number
}
type Rule = { id: number; ruleCode: string; version: number; ruleType: string; parametersJson: string; status: string; effectiveFrom: string }
type LoadState = 'unloaded' | 'loading' | 'loaded' | 'error'
type Timers = {
  autoReceiveDaysAfterShipment: number
  afterSaleDaysAfterCompletion: number
  proofRetentionDays: number
  maxProofFiles: number
  maxProofSizeMb: number
}

const operations = reactive<Settings>({
  afterSaleReturnReceiver: '', afterSaleReturnPhone: '', afterSaleReturnAddress: '', lowInventoryThreshold: 0
})
const operationState = ref<LoadState>('unloaded')
const operationError = ref('')
const operationReason = ref('')
const operationSubmitting = ref(false)

const timers = reactive<Timers>({
  autoReceiveDaysAfterShipment: 0, afterSaleDaysAfterCompletion: 0,
  proofRetentionDays: 0, maxProofFiles: 0, maxProofSizeMb: 0
})
const timerBaseline = ref<Timers>()
const currentTimerRule = ref<Rule>()
const timerState = ref<LoadState>('unloaded')
const timerError = ref('')
const timerReason = ref('')
const timerConfirmOpen = ref(false)
const timerSubmitting = ref(false)
const timerActionError = ref('')

function isRule(value: unknown): value is Rule {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const candidate = value as Record<string, unknown>
  return typeof candidate.id === 'number'
    && typeof candidate.ruleCode === 'string'
    && typeof candidate.version === 'number'
    && typeof candidate.ruleType === 'string'
    && typeof candidate.parametersJson === 'string'
    && typeof candidate.status === 'string'
    && (typeof candidate.effectiveFrom === 'string' || candidate.effectiveFrom === null)
}

const timerDiff = computed(() => {
  const baseline = timerBaseline.value
  if (!baseline) return []
  const labels: Record<keyof Timers, string> = {
    autoReceiveDaysAfterShipment: '发货后自动收货天数',
    afterSaleDaysAfterCompletion: '订单完成后售后期限',
    proofRetentionDays: '凭证保留期限',
    maxProofFiles: '单据最大凭证数',
    maxProofSizeMb: '单张凭证上限（MB）'
  }
  return (Object.keys(labels) as Array<keyof Timers>).flatMap(key =>
    baseline[key] === timers[key] ? [] : [{ key, label: labels[key], before: baseline[key], after: timers[key] }]
  )
})

async function loadOperations() {
  operationState.value = 'loading'
  operationError.value = ''
  try {
    Object.assign(operations, await adminApi<Settings>('/settings'))
    operationState.value = 'loaded'
  } catch (cause) {
    operationState.value = 'error'
    operationError.value = adminErrorMessage(cause)
  }
}

function parseTimers(rule: Rule): Timers {
  const parsed = parseOrderTimerParameters(rule.parametersJson)
  if (!parsed.ok) throw new Error(parsed.error)
  const source = parsed.value
  return {
    autoReceiveDaysAfterShipment: source.autoReceiveDaysAfterShipment,
    afterSaleDaysAfterCompletion: source.afterSaleDaysAfterCompletion,
    proofRetentionDays: source.proofRetentionDays,
    maxProofFiles: source.maxProofFiles,
    // Keep sub-megabyte values lossless when reading an existing rule.  The
    // input remains expressed in MB, while the payload is rounded back to an
    // integer byte count before the shared semantic validator runs.
    maxProofSizeMb: source.maxProofSizeBytes / 1024 / 1024
  }
}

async function loadTimers() {
  if (!can('rule:publish')) return
  timerState.value = 'loading'
  timerError.value = ''
  currentTimerRule.value = undefined
  timerBaseline.value = undefined
  try {
    const current = await adminApi<Rule>('/settings/order-timers')
    if (!isRule(current)) throw new Error('服务端返回的当前策略版本格式无效')
    if (current.ruleCode !== 'ORDER_TIMERS' || current.ruleType !== 'ORDER_TIMER' || current.status !== 'ACTIVE') {
      throw new Error('当前订单与凭证策略类型或状态无效')
    }
    const parsed = parseTimers(current)
    currentTimerRule.value = current
    timerBaseline.value = { ...parsed }
    Object.assign(timers, parsed)
    timerState.value = 'loaded'
  } catch (cause) {
    timerState.value = 'error'
    timerError.value = adminErrorMessage(cause, '订单与凭证策略加载失败')
  }
}

async function saveOperations() {
  if (operationSubmitting.value || operationState.value !== 'loaded') return
  operationSubmitting.value = true
  operationError.value = ''
  try {
    const saved = await adminApi<Settings>('/settings', {
      method: 'PUT', body: JSON.stringify({ ...operations, reason: operationReason.value.trim() })
    })
    Object.assign(operations, saved)
    operationReason.value = ''
    notifySuccess('运营配置已保存', '服务端返回值已回读，修改已写入审计日志。')
  } catch (cause) { operationError.value = adminErrorMessage(cause) }
  finally { operationSubmitting.value = false }
}

function requestTimerPublish() {
  timerActionError.value = ''
  timerReason.value = ''
  timerConfirmOpen.value = true
}

async function publishTimers() {
  if (timerSubmitting.value || timerState.value !== 'loaded' || !currentTimerRule.value || !timerDiff.value.length) return
  timerSubmitting.value = true
  timerActionError.value = ''
  const payload = {
    ruleCode: 'ORDER_TIMERS', ruleType: 'ORDER_TIMER', effectiveFrom: new Date().toISOString(),
    parametersJson: JSON.stringify({
      autoReceiveDaysAfterShipment: timers.autoReceiveDaysAfterShipment,
      afterSaleDaysAfterCompletion: timers.afterSaleDaysAfterCompletion,
      proofRetentionDays: timers.proofRetentionDays,
      maxProofFiles: timers.maxProofFiles,
      maxProofSizeBytes: Math.round(timers.maxProofSizeMb * 1024 * 1024),
      changeReason: timerReason.value.trim()
    })
  }
  let committed = false
  try {
    const parsed = parseOrderTimerParameters(payload.parametersJson)
    if (!parsed.ok) throw new Error(parsed.error)
    await adminApi('/settings/order-timers/validate', { method: 'POST', body: JSON.stringify(payload) })
    const published = await adminApi<Rule>('/settings/order-timers', { method: 'POST', body: JSON.stringify(payload) })
    committed = true
    await loadTimers()
    if (timerState.value !== 'loaded' || !currentTimerRule.value || currentTimerRule.value.version < published.version) {
      throw new Error('版本已提交，但服务端最新版本回读失败，请重试读取当前策略')
    }
    timerConfirmOpen.value = false
    timerReason.value = ''
    notifySuccess('订单与凭证策略已发布', `当前服务端版本：第 ${currentTimerRule.value?.version} 版。`)
  } catch (cause) {
    const message = adminErrorMessage(cause)
    if (committed) {
      timerConfirmOpen.value = false
      timerReason.value = ''
      timerError.value = message
      timerState.value = 'error'
      notifyError('策略版本已提交，但回读失败', '发布入口已锁定，请重试读取当前版本，不要重复发布。')
    } else {
      timerActionError.value = message
      if (isConflictError(cause)) await loadTimers()
    }
  } finally { timerSubmitting.value = false }
}

onMounted(() => { void Promise.all([loadOperations(), loadTimers()]) })
</script>

<template>
  <div>
    <PageHeader title="系统配置" description="维护退货与库存预警配置；订单时限策略仅在成功读取当前版本后允许编辑和发布。" />
    <div class="settings-grid">
      <form class="card settings-card" @submit.prevent="saveOperations">
        <div class="section-head"><div><h2>运营基础配置</h2><p>售后审核通过后向用户展示这里维护的退货信息。</p></div><StatusTag tone="info" label="全局配置" /></div>
        <div v-if="operationState === 'loading'" class="section-loading" role="status"><span class="state-spinner"></span>加载配置…</div>
        <template v-else>
          <InlineAlert v-if="operationError" title="运营配置加载或保存失败" :message="operationError" retryable @retry="loadOperations" />
          <fieldset :disabled="operationState !== 'loaded' || operationSubmitting"><div class="grid"><label class="field"><span>退货收件人</span><input v-model="operations.afterSaleReturnReceiver" maxlength="80" required /></label><label class="field"><span>联系电话</span><input v-model="operations.afterSaleReturnPhone" maxlength="40" required /></label></div><label class="field"><span>完整退货地址</span><textarea v-model="operations.afterSaleReturnAddress" maxlength="500" rows="4" required /></label><label class="field"><span>低库存预警阈值</span><input v-model.number="operations.lowInventoryThreshold" type="number" min="0" max="100000" required /></label><label class="field"><span>修改原因</span><input v-model="operationReason" maxlength="500" required placeholder="例如：仓库地址调整" /></label></fieldset>
          <button class="primary" :disabled="operationState !== 'loaded' || operationSubmitting || !operationReason.trim()">{{ operationSubmitting ? '保存中…' : '保存运营配置' }}</button>
        </template>
      </form>

      <section v-if="can('rule:publish')" class="card settings-card">
        <div class="section-head"><div><h2>订单与凭证策略</h2><p>发布不可变新版本，不追溯既有订单。</p></div><StatusTag :tone="timerState === 'loaded' ? 'success' : timerState === 'error' ? 'danger' : 'warning'" :label="currentTimerRule ? `第 ${currentTimerRule.version} 版` : '版本未就绪'" /></div>
        <div v-if="timerState === 'loading' || timerState === 'unloaded'" class="section-loading" role="status"><span class="state-spinner"></span>读取当前策略版本…</div>
        <template v-else-if="timerState === 'error'"><InlineAlert title="当前策略版本加载失败" :message="`${timerError}；策略编辑与发布已锁定，避免用默认值覆盖线上版本。`" retryable @retry="loadTimers" /></template>
        <template v-else>
          <p class="version-meta">当前版本自 {{ dateTime(currentTimerRule?.effectiveFrom) }} 生效。修改字段后将先显示差异，再确认发布。</p>
          <fieldset :disabled="timerSubmitting"><div class="grid"><label class="field"><span>发货后自动收货（天）</span><input v-model.number="timers.autoReceiveDaysAfterShipment" type="number" min="1" max="365" required /></label><label class="field"><span>完成后售后期限（天）</span><input v-model.number="timers.afterSaleDaysAfterCompletion" type="number" min="1" max="365" required /></label><label class="field"><span>凭证保留期限（天）</span><input v-model.number="timers.proofRetentionDays" type="number" min="1" max="3650" required /></label><label class="field"><span>单据最大凭证数</span><input v-model.number="timers.maxProofFiles" type="number" min="1" max="20" required /></label><label class="field"><span>单张凭证上限（MB）</span><input v-model.number="timers.maxProofSizeMb" type="number" min="1" max="20" required /></label></div></fieldset>
          <div v-if="timerDiff.length" class="diff-list"><p v-for="row in timerDiff" :key="row.key"><b>{{ row.label }}</b><span>{{ row.before }} → {{ row.after }}</span></p></div><p v-else class="no-diff">当前草稿与服务端第 {{ currentTimerRule?.version }} 版一致。</p>
          <button class="primary" type="button" :disabled="!timerDiff.length || timerSubmitting" @click="requestTimerPublish">查看差异并发布</button>
        </template>
      </section>
    </div>

    <BusinessActionDialog v-model="timerConfirmOpen" title="发布订单与凭证策略新版本" target="ORDER_TIMERS" :impact="`将基于服务端第 ${currentTimerRule?.version} 版创建新版本；既有订单仍使用原快照。`" :current-state="`第 ${currentTimerRule?.version} 版`" next-state="新版本" v-model:reason="timerReason" reason-label="版本发布原因" confirm-label="校验并发布" :submitting="timerSubmitting" :submit-disabled="timerState !== 'loaded' || !timerDiff.length" :error="timerActionError" @submit="publishTimers"><div class="confirm-diff"><p v-for="row in timerDiff" :key="row.key"><b>{{ row.label }}</b><span>{{ row.before }} → {{ row.after }}</span></p></div></BusinessActionDialog>
  </div>
</template>

<style scoped>
.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.settings-card{padding:24px}.section-head{display:flex;justify-content:space-between;gap:16px;margin-bottom:20px}.section-head h2{margin:0;font-family:serif}.section-head p{margin:5px 0 0;color:var(--color-text-muted);font-size:13px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:13px}.field{margin-top:13px}fieldset{margin:0;padding:0;border:0}.settings-card>.primary{margin-top:18px}.section-loading{min-height:180px;display:flex;align-items:center;justify-content:center;gap:10px;color:var(--color-text-muted)}.version-meta,.no-diff{color:var(--color-text-muted);line-height:1.6}.diff-list,.confirm-diff{margin:16px 0;padding:12px;border:1px solid var(--color-border);border-radius:10px;background:var(--color-surface-subtle)}.diff-list p,.confirm-diff p{display:grid;grid-template-columns:1fr auto;gap:10px;margin:0;padding:8px 0;border-bottom:1px solid var(--color-border)}.diff-list p:last-child,.confirm-diff p:last-child{border:0}.diff-list span,.confirm-diff span{color:var(--color-text-muted)}
@media(max-width:900px){.settings-grid{grid-template-columns:1fr}}@media(max-width:560px){.grid{grid-template-columns:1fr}.settings-card{padding:18px}}
</style>
