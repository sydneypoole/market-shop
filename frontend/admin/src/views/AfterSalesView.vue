<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, dateTime, fileSize } from '../api'
import {
  afterSaleStatusLabel,
  afterSaleStatusOptions,
  afterSaleTypeLabel,
  mediaTypeLabel,
  proofTypeLabel
} from '../localization'
import PaginationBar from '../components/PaginationBar.vue'

type AfterSale = {
  id:number; afterSaleNo:string; orderId:number; applicantUserId:number; superiorUserId:number
  type:string; status:string; reason:string; adminReason?:string; returnAddressJson?:string
  returnCarrier?:string; returnTrackingNo?:string; createdAt:string; completedAt?:string
}
type Proof = {
  id:number;afterSaleId:number;proofType:string;mediaType:string;sizeBytes:number
  uploadedByUserId?:number;retainUntil:string;createdAt:string
}
type Settings = { afterSaleReturnReceiver:string;afterSaleReturnPhone:string;afterSaleReturnAddress:string }

const rows = ref<AfterSale[]>([])
const status = ref('')
const keyword = ref('')
const page = ref(1)
const size = 20
const detail = ref<AfterSale>()
const proofs = ref<Proof[]>([])
const settings = ref<Settings>()
const error = ref('')
const busy = ref('')

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return rows.value
  return rows.value.filter(row =>
    row.afterSaleNo.toLowerCase().includes(value)
    || String(row.orderId).includes(value)
    || String(row.applicantUserId).includes(value)
  )
})
const visible = computed(() => filtered.value.slice((page.value - 1) * size, page.value * size))

async function load() {
  error.value = ''
  try {
    const [result, operationSettings] = await Promise.all([
      adminApi<AfterSale[]>(`/after-sales${status.value ? `?status=${status.value}` : ''}`),
      adminApi<Settings>('/settings')
    ])
    rows.value = result
    settings.value = operationSettings
    page.value = 1
  } catch (e) { error.value = (e as Error).message }
}

async function open(row: AfterSale) {
  detail.value = row
  try { proofs.value = await adminApi<Proof[]>(`/after-sales/${row.id}/proofs`) }
  catch (e) { error.value = (e as Error).message }
}

async function run(key: string, action: () => Promise<void>) {
  busy.value = key
  error.value = ''
  try {
    await action()
    await load()
    detail.value = undefined
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = '' }
}

async function review(row: AfterSale, approve: boolean) {
  const reason = prompt(approve ? '审核备注（可选）' : '拒绝原因') || ''
  if (!approve && !reason) return
  let returnAddressJson: string | null = null
  if (approve && row.type === 'RETURN_REFUND') {
    if (!settings.value) {
      error.value = '未加载到退货配置，请先在系统配置中完善退货地址'
      return
    }
    const configured = settings.value
    const accepted = confirm(`确认使用以下退货信息？\n${configured.afterSaleReturnReceiver} ${configured.afterSaleReturnPhone}\n${configured.afterSaleReturnAddress}`)
    if (!accepted) return
    returnAddressJson = JSON.stringify({
      receiver: configured.afterSaleReturnReceiver,
      phone: configured.afterSaleReturnPhone,
      address: configured.afterSaleReturnAddress
    })
  }
  await run(`review-${row.id}`, async () => {
    await adminApi(`/after-sales/${row.id}/review`, {
      method:'POST',
      body:JSON.stringify({ approve, reason, returnAddressJson })
    })
  })
}

async function confirmReturn(row: AfterSale) {
  const reason = prompt('填写收货确认备注') || '退货已验收入库'
  await run(`return-${row.id}`, async () => {
    await adminApi(`/after-sales/${row.id}/confirm-return-received`, {
      method:'POST',
      body:JSON.stringify({ reason })
    })
  })
}

async function openProof(proof: Proof) {
  const value = await adminApi<{signedUrl:string;expiresAt:string}>(`/after-sale-proofs/${proof.id}/download`)
  window.open(value.signedUrl, '_blank', 'noopener,noreferrer')
}

function returnAddress(row: AfterSale) {
  if (!row.returnAddressJson) return undefined
  try { return JSON.parse(row.returnAddressJson) as {receiver:string;phone:string;address:string} }
  catch { return undefined }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title"><div><h1>售后处理</h1><p>查看完整售后资料、用户凭证、退货信息和处理进度。</p></div></div>
    <div class="toolbar">
      <input v-model="keyword" placeholder="售后单号 / 订单编号 / 用户编号" @input="page = 1" />
      <select v-model="status" @change="load">
        <option value="">全部状态</option>
        <option v-for="option in afterSaleStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
      </select>
      <button class="secondary" @click="load">刷新</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card table-wrap">
      <table>
        <thead><tr><th>售后单号</th><th>订单 / 用户</th><th>类型</th><th>申请原因</th><th>状态</th><th>回寄物流</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in visible" :key="row.id">
            <td><button class="link-button" @click="open(row)">{{ row.afterSaleNo }}</button></td>
            <td>#{{ row.orderId }} / #{{ row.applicantUserId }}</td>
            <td>{{ afterSaleTypeLabel(row.type) }}</td>
            <td class="reason">{{ row.reason }}</td>
            <td><span class="tag">{{ afterSaleStatusLabel(row.status) }}</span></td>
            <td>{{ row.returnCarrier ? `${row.returnCarrier} ${row.returnTrackingNo}` : '—' }}</td>
            <td class="actions">
              <template v-if="row.status === 'PENDING_ADMIN_REVIEW'">
                <button class="danger" :disabled="Boolean(busy)" @click="review(row, false)">拒绝</button>
                <button class="primary" :disabled="Boolean(busy)" @click="review(row, true)">通过</button>
              </template>
              <button v-if="row.status === 'RETURN_SHIPPED'" class="primary" :disabled="Boolean(busy)" @click="confirmReturn(row)">确认收货</button>
              <button class="secondary" @click="open(row)">详情</button>
            </td>
          </tr>
          <tr v-if="!visible.length"><td colspan="7" class="empty">暂无符合条件的售后记录。</td></tr>
        </tbody>
      </table>
    </div>
    <PaginationBar :page="page" :size="size" :total="filtered.length" @change="value => page = value" />

    <div v-if="detail" class="modal-mask" @click.self="detail = undefined">
      <section class="modal detail card">
        <div class="detail-head"><div><span>售后详情</span><h2>{{ detail.afterSaleNo }}</h2></div><button class="secondary" @click="detail = undefined">关闭</button></div>
        <div class="summary">
          <div><small>状态</small><b>{{ afterSaleStatusLabel(detail.status) }}</b></div>
          <div><small>订单 / 买家 / 上级</small><b>#{{ detail.orderId }} / #{{ detail.applicantUserId }} / #{{ detail.superiorUserId }}</b></div>
          <div><small>类型</small><b>{{ afterSaleTypeLabel(detail.type) }}</b></div>
        </div>
        <div class="columns">
          <section>
            <h3>申请信息</h3>
            <dl><dt>申请原因</dt><dd>{{ detail.reason }}</dd><dt>后台意见</dt><dd>{{ detail.adminReason || '—' }}</dd><dt>申请时间</dt><dd>{{ dateTime(detail.createdAt) }}</dd><dt>完成时间</dt><dd>{{ dateTime(detail.completedAt) }}</dd></dl>
            <h3>退货信息</h3>
            <dl v-if="returnAddress(detail)"><dt>收件人</dt><dd>{{ returnAddress(detail)?.receiver }} · {{ returnAddress(detail)?.phone }}</dd><dt>地址</dt><dd>{{ returnAddress(detail)?.address }}</dd></dl>
            <p v-else class="muted">当前流程没有退货地址。</p>
            <dl><dt>回寄物流</dt><dd>{{ detail.returnCarrier ? `${detail.returnCarrier} ${detail.returnTrackingNo}` : '—' }}</dd></dl>
          </section>
          <section>
            <h3>处理进度</h3>
            <ol class="timeline">
              <li class="done"><b>提交售后申请</b><span>{{ dateTime(detail.createdAt) }}</span></li>
              <li :class="{done: detail.status !== 'PENDING_ADMIN_REVIEW'}"><b>后台审核</b><span>{{ detail.adminReason || '等待处理' }}</span></li>
              <li :class="{done: ['RETURN_SHIPPED','PENDING_OFFLINE_REFUND','PENDING_BUYER_REFUND_CONFIRMATION','COMPLETED'].includes(detail.status)}"><b>退货验收（如需要）</b><span>{{ detail.returnTrackingNo || '—' }}</span></li>
              <li :class="{done: ['PENDING_BUYER_REFUND_CONFIRMATION','COMPLETED'].includes(detail.status)}"><b>直属上级线下退款</b><span>系统只记录确认事实</span></li>
              <li :class="{done: detail.status === 'COMPLETED'}"><b>买家确认到账</b><span>{{ dateTime(detail.completedAt) }}</span></li>
            </ol>
          </section>
        </div>
        <h3>售后凭证</h3>
        <div class="proofs">
          <div v-for="proof in proofs" :key="proof.id">
            <span><b>{{ proofTypeLabel(proof.proofType) }}</b><small>{{ mediaTypeLabel(proof.mediaType) }} · {{ fileSize(proof.sizeBytes) }} · 上传人 #{{ proof.uploadedByUserId }}</small></span>
            <button class="secondary" @click="openProof(proof)">查看凭证</button>
          </div>
          <p v-if="!proofs.length" class="muted">未上传售后凭证。</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.actions{display:flex;gap:7px}.reason{max-width:220px;overflow:hidden;text-overflow:ellipsis}.empty{text-align:center;padding:40px;color:var(--muted)}.link-button{padding:0;border:0;color:var(--green);font-weight:800;background:transparent}
.detail{width:min(980px,100%);max-height:92vh;overflow:auto}.detail-head{display:flex;justify-content:space-between}.detail-head span{color:var(--coral);font-size:11px;letter-spacing:.13em}.detail-head h2{margin:4px 0}
.summary{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:18px 0}.summary div{padding:13px;background:#f5f7f5;border-radius:10px}.summary small,.summary b{display:block}.summary small,.muted{color:var(--muted)}.summary b{margin-top:5px}
.columns{display:grid;grid-template-columns:1fr 1fr;gap:24px}.detail h3{font-family:serif;margin:22px 0 10px}dl{display:grid;grid-template-columns:90px 1fr;gap:8px;margin:0;padding:13px;background:#f7f8f7;border-radius:10px}dt{color:var(--muted)}dd{margin:0}
.timeline{list-style:none;margin:0;padding:0}.timeline li{padding:10px 10px 10px 28px;position:relative;border-left:2px solid var(--line)}.timeline li:before{content:'';position:absolute;left:-7px;top:15px;width:10px;height:10px;border-radius:50%;background:#ccd3cf}.timeline li.done{border-color:var(--green)}.timeline li.done:before{background:var(--green)}.timeline b,.timeline span{display:block}.timeline span{color:var(--muted);font-size:12px;margin-top:3px}
.proofs>div{display:flex;align-items:center;gap:12px;padding:10px;border:1px solid var(--line);border-radius:9px;margin-bottom:8px}.proofs span{flex:1}.proofs small{display:block;color:var(--muted);margin-top:4px}
@media(max-width:720px){.summary,.columns{grid-template-columns:1fr}}
</style>
