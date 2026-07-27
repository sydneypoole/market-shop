<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../api'
import { can } from '../session'

type Settings = {
  afterSaleReturnReceiver:string
  afterSaleReturnPhone:string
  afterSaleReturnAddress:string
  lowInventoryThreshold:number
}
type Rule = { id:number; ruleCode:string; version:number; ruleType:string; parametersJson:string; status:string; effectiveFrom:string }

const operations = reactive<Settings>({
  afterSaleReturnReceiver: '',
  afterSaleReturnPhone: '',
  afterSaleReturnAddress: '',
  lowInventoryThreshold: 10
})
const timers = reactive({
  autoReceiveDaysAfterShipment: 7,
  afterSaleDaysAfterCompletion: 7,
  proofRetentionDays: 180,
  maxProofFiles: 3,
  maxProofSizeMb: 8
})
const operationReason = ref('')
const timerReason = ref('')
const error = ref('')
const success = ref('')
const busy = ref('')

async function load() {
  error.value = ''
  try {
    const settings = await adminApi<Settings>('/settings')
    Object.assign(operations, settings)
    if (!can('rule:publish')) return
    const rules = await adminApi<Rule[]>('/rules')
    const current = rules
      .filter(rule => rule.ruleCode === 'ORDER_TIMERS' && rule.status === 'ACTIVE')
      .sort((a, b) => b.version - a.version)[0]
    if (current) {
      const value = JSON.parse(current.parametersJson) as Record<string, number>
      timers.autoReceiveDaysAfterShipment = value.autoReceiveDaysAfterShipment ?? 7
      timers.afterSaleDaysAfterCompletion = value.afterSaleDaysAfterCompletion ?? 7
      timers.proofRetentionDays = value.proofRetentionDays ?? 180
      timers.maxProofFiles = value.maxProofFiles ?? 3
      timers.maxProofSizeMb = Math.round((value.maxProofSizeBytes ?? 8 * 1024 * 1024) / 1024 / 1024)
    }
  } catch (e) { error.value = (e as Error).message }
}

async function saveOperations() {
  busy.value = 'operations'
  success.value = ''
  error.value = ''
  try {
    const saved = await adminApi<Settings>('/settings', {
      method: 'PUT',
      body: JSON.stringify({ ...operations, reason: operationReason.value })
    })
    Object.assign(operations, saved)
    operationReason.value = ''
    success.value = '运营配置已保存并写入审计日志。'
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = '' }
}

async function saveTimers() {
  busy.value = 'timers'
  success.value = ''
  error.value = ''
  try {
    if (!timerReason.value.trim()) throw new Error('请填写规则修改原因')
    const payload = {
      ruleCode: 'ORDER_TIMERS',
      ruleType: 'ORDER_TIMER',
      effectiveFrom: new Date().toISOString(),
      parametersJson: JSON.stringify({
        autoReceiveDaysAfterShipment: timers.autoReceiveDaysAfterShipment,
        afterSaleDaysAfterCompletion: timers.afterSaleDaysAfterCompletion,
        proofRetentionDays: timers.proofRetentionDays,
        maxProofFiles: timers.maxProofFiles,
        maxProofSizeBytes: timers.maxProofSizeMb * 1024 * 1024,
        changeReason: timerReason.value.trim()
      })
    }
    await adminApi('/rules/validate', { method: 'POST', body: JSON.stringify(payload) })
    await adminApi('/rules', { method: 'POST', body: JSON.stringify(payload) })
    timerReason.value = ''
    success.value = '订单时限与凭证策略已发布为新的规则版本。'
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = '' }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title"><div><h1>系统配置</h1><p>集中管理履约时限、售后地址、凭证策略和库存预警；所有修改均保留版本或审计记录。</p></div></div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="success" class="success">{{ success }}</p>
    <div class="settings-grid">
      <form class="card settings-card" @submit.prevent="saveOperations">
        <div class="section-head"><div><h2>运营基础配置</h2><p>售后审核通过后将向用户展示这里维护的退货信息。</p></div><span class="tag">全局</span></div>
        <div class="grid">
          <div class="field"><label>退货收件人</label><input v-model="operations.afterSaleReturnReceiver" maxlength="80" required /></div>
          <div class="field"><label>联系电话</label><input v-model="operations.afterSaleReturnPhone" maxlength="40" required /></div>
        </div>
        <div class="field"><label>完整退货地址</label><textarea v-model="operations.afterSaleReturnAddress" maxlength="500" rows="4" required /></div>
        <div class="field"><label>低库存预警阈值</label><input v-model.number="operations.lowInventoryThreshold" type="number" min="0" max="100000" required /></div>
        <div class="field"><label>修改原因</label><input v-model="operationReason" maxlength="500" required placeholder="例如：仓库地址调整" /></div>
        <button class="primary" :disabled="Boolean(busy)">{{ busy === 'operations' ? '保存中…' : '保存运营配置' }}</button>
      </form>

      <form v-if="can('rule:publish')" class="card settings-card" @submit.prevent="saveTimers">
        <div class="section-head"><div><h2>订单与凭证策略</h2><p>保存时发布不可变的订单时限策略新版本，不追溯既有订单。</p></div><span class="tag green">版本化</span></div>
        <div class="grid">
          <div class="field"><label>发货后自动收货（天）</label><input v-model.number="timers.autoReceiveDaysAfterShipment" type="number" min="1" max="30" required /></div>
          <div class="field"><label>完成后售后期限（天）</label><input v-model.number="timers.afterSaleDaysAfterCompletion" type="number" min="1" max="90" required /></div>
          <div class="field"><label>凭证保留期限（天）</label><input v-model.number="timers.proofRetentionDays" type="number" min="1" max="3650" required /></div>
          <div class="field"><label>单据最大凭证数</label><input v-model.number="timers.maxProofFiles" type="number" min="1" max="20" required /></div>
          <div class="field"><label>单张凭证上限（MB）</label><input v-model.number="timers.maxProofSizeMb" type="number" min="1" max="20" required /></div>
        </div>
        <div class="field"><label>版本发布原因</label><input v-model="timerReason" maxlength="500" required placeholder="例如：调整自动收货周期" /></div>
        <button class="primary" :disabled="Boolean(busy)">{{ busy === 'timers' ? '发布中…' : '发布策略新版本' }}</button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.settings-card{padding:24px}.section-head{display:flex;justify-content:space-between;gap:16px;margin-bottom:20px}
.section-head h2{margin:0;font-family:serif}.section-head p{margin:5px 0 0;color:var(--muted);font-size:13px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:13px}
.field{margin-top:13px}.settings-card>.primary{margin-top:18px}.success{padding:10px 12px;color:#276b55;background:#e3f0ea;border-radius:9px}
@media(max-width:900px){.settings-grid{grid-template-columns:1fr}}@media(max-width:560px){.grid{grid-template-columns:1fr}}
</style>
