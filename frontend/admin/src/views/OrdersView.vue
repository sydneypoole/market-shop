<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi, dateTime, fileSize, money, queryString } from '../api'
import {
  mediaTypeLabel,
  orderStatusLabel,
  orderStatusOptions,
  salesSceneLabel
} from '../localization'
import { can } from '../session'
import PaginationBar from '../components/PaginationBar.vue'

type Order = {
  id:number; orderNo:string; buyerUserId:number; superiorUserId:number
  totalAmountFen:number; status:string; reason?:string; createdAt:string
}
type Item = {
  skuId:number; productName:string; skuName:string; coverUrl?:string
  salesScene:string; unitPriceFen:number; quantity:number; subtotalFen:number
}
type Detail = {
  order:Order; addressJson:string; items:Item[]
  shipment?:{carrierCode:string;carrierName:string;trackingNo:string;shippedAt:string}
  superiorConfirmedAt?:string; adminReviewedAt?:string; autoReceiveAt?:string; completedAt?:string
}
type Note = { id:number;adminId:number;note:string;createdAt:string }
type Proof = {
  proofId:number;orderId:number;mediaType:string;sizeBytes:number
  uploadedBy:number;retainUntil:string;createdAt:string
}

const orders = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const error = ref('')
const selected = ref<number[]>([])
const shipOrder = ref<Order>()
const detail = ref<Detail>()
const detailNotes = ref<Note[]>([])
const detailProofs = ref<Proof[]>([])
const detailBusy = ref(false)
const busyAction = ref('')
const filters = reactive({ orderNo:'', buyerUserId:'', superiorUserId:'', status:'', from:'', to:'' })
const shipment = reactive({ carrierCode:'SF', carrierName:'顺丰速运', trackingNo:'' })

const address = computed<Record<string, string>>(() => {
  try { return detail.value ? JSON.parse(detail.value.addressJson) as Record<string, string> : {} }
  catch { return {} }
})

function params(includePage = true) {
  return queryString({
    orderNo: filters.orderNo,
    buyerUserId: filters.buyerUserId,
    superiorUserId: filters.superiorUserId,
    status: filters.status,
    from: filters.from ? new Date(filters.from).toISOString() : '',
    to: filters.to ? new Date(filters.to).toISOString() : '',
    page: includePage ? page.value : undefined,
    size: includePage ? size : undefined
  })
}

async function load(targetPage = 1) {
  page.value = targetPage
  error.value = ''
  try {
    const result = await adminApi<{items:Order[];total:number;page:number;size:number}>(`/orders/search?${params()}`)
    orders.value = result.items
    total.value = result.total
    selected.value = selected.value.filter(id => result.items.some(row => row.id === id))
  } catch (e) { error.value = (e as Error).message }
}

async function openDetail(row: Order) {
  detailBusy.value = true
  error.value = ''
  try {
    const [value, notes, proofs] = await Promise.all([
      adminApi<Detail>(`/orders/${row.id}`),
      adminApi<Note[]>(`/orders/${row.id}/notes`),
      adminApi<Proof[]>(`/orders/${row.id}/proofs`)
    ])
    detail.value = value
    detailNotes.value = notes
    detailProofs.value = proofs
  } catch (e) { error.value = (e as Error).message }
  finally { detailBusy.value = false }
}

async function mutate(key: string, action: () => Promise<void>) {
  busyAction.value = key
  error.value = ''
  try { await action() }
  catch (e) { error.value = (e as Error).message }
  finally { busyAction.value = '' }
}

async function review(row: Order, approve: boolean) {
  const reason = approve ? (prompt('审核备注（可选）') || '') : (prompt('请输入拒绝原因') || '')
  if (!approve && !reason) return
  await mutate(`review-${row.id}`, async () => {
    await adminApi(`/orders/${row.id}/review`, { method:'POST', body:JSON.stringify({ approve, reason }) })
    await load(page.value)
  })
}

async function ship() {
  if (!shipOrder.value) return
  const current = shipOrder.value
  await mutate(`ship-${current.id}`, async () => {
    await adminApi(`/orders/${current.id}/ship`, { method:'POST', body:JSON.stringify(shipment) })
    shipOrder.value = undefined
    shipment.trackingNo = ''
    await load(page.value)
  })
}

async function batchShip() {
  const carrierCode = prompt('承运商编码', 'SF') || ''
  const carrierName = prompt('承运商名称', '顺丰速运') || ''
  const prefix = prompt('物流单号前缀（将自动追加订单编号）', '批量') || ''
  if (!carrierCode || !carrierName || !prefix) return
  await mutate('batch', async () => {
    const result = await adminApi<Array<{orderId:number;success:boolean;message:string}>>('/orders/batch-ship', {
      method:'POST',
      body:JSON.stringify({ items:selected.value.map(orderId => ({
        orderId, carrierCode, carrierName, trackingNo:`${prefix}-${orderId}`
      })) })
    })
    alert(result.map(row => `#${row.orderId} ${row.message}`).join('\n'))
    selected.value = []
    await load(page.value)
  })
}

async function addNote(row: Order) {
  const value = prompt('订单内部备注') || ''
  if (!value) return
  await mutate(`note-${row.id}`, async () => {
    await adminApi(`/orders/${row.id}/notes`, { method:'POST', body:JSON.stringify({ note:value }) })
    if (detail.value?.order.id === row.id) detailNotes.value = await adminApi<Note[]>(`/orders/${row.id}/notes`)
  })
}

async function openProof(proof: Proof) {
  const result = await adminApi<{signedUrl:string;expiresAt:string}>(`/order-proofs/${proof.proofId}/download`)
  window.open(result.signedUrl, '_blank', 'noopener,noreferrer')
}

async function deleteProof(proof: Proof) {
  const reason = prompt('删除凭证的原因') || ''
  if (!reason) return
  await mutate(`proof-${proof.proofId}`, async () => {
    await adminApi(`/order-proofs/${proof.proofId}`, {
      method:'DELETE',
      body:JSON.stringify({ reason })
    })
    detailProofs.value = detailProofs.value.filter(row => row.proofId !== proof.proofId)
  })
}

function exportCsv() {
  location.href = `/api/v1/admin/orders/export?${params(false)}`
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="page-title">
      <div><h1>订单审核与发货</h1><p>完整查看订单快照、付款凭证、处理时间线和内部备注。</p></div>
      <div class="head-actions">
        <button class="secondary" @click="exportCsv">导出表格</button>
        <button v-if="can('order:ship')" class="primary" :disabled="!selected.length || Boolean(busyAction)" @click="batchShip">
          批量发货（{{ selected.length }}）
        </button>
      </div>
    </div>
    <div class="toolbar filters">
      <input v-model="filters.orderNo" placeholder="订单号" />
      <input v-model="filters.buyerUserId" type="number" placeholder="买家编号" />
      <input v-model="filters.superiorUserId" type="number" placeholder="上级编号" />
      <select v-model="filters.status">
        <option value="">全部状态</option>
        <option v-for="option in orderStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
      </select>
      <label>从<input v-model="filters.from" type="datetime-local" /></label>
      <label>至<input v-model="filters.to" type="datetime-local" /></label>
      <button class="secondary" @click="load(1)">查询</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card table-wrap">
      <table>
        <thead><tr><th>选择</th><th>订单号</th><th>买家 / 上级</th><th>金额</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in orders" :key="row.id">
            <td><input v-if="row.status === 'PENDING_SHIPMENT' && can('order:ship')" v-model="selected" :value="row.id" type="checkbox" /></td>
            <td><button class="link-button" @click="openDetail(row)">{{ row.orderNo }}</button></td>
            <td>#{{ row.buyerUserId }} / #{{ row.superiorUserId }}</td>
            <td>{{ money(row.totalAmountFen) }}</td>
            <td><span class="tag" :class="{green:row.status === 'COMPLETED'}">{{ orderStatusLabel(row.status) }}</span></td>
            <td>{{ dateTime(row.createdAt) }}</td>
            <td class="actions">
              <template v-if="row.status === 'PENDING_ADMIN_REVIEW' && can('order:review')">
                <button class="danger" :disabled="Boolean(busyAction)" @click="review(row, false)">拒绝</button>
                <button class="primary" :disabled="Boolean(busyAction)" @click="review(row, true)">通过</button>
              </template>
              <button v-if="row.status === 'PENDING_SHIPMENT' && can('order:ship')" class="primary" @click="shipOrder = row">发货</button>
              <button class="secondary" @click="openDetail(row)">详情</button>
              <button class="secondary" @click="addNote(row)">备注</button>
            </td>
          </tr>
          <tr v-if="!orders.length"><td colspan="7" class="empty">暂无符合条件的订单。</td></tr>
        </tbody>
      </table>
    </div>
    <PaginationBar :page="page" :size="size" :total="total" @change="load" />

    <div v-if="detailBusy" class="modal-mask"><div class="card modal loading">正在加载订单详情…</div></div>
    <div v-if="detail" class="modal-mask" @click.self="detail = undefined">
      <section class="modal detail-modal card">
        <div class="detail-head">
          <div><span class="overline">订单详情</span><h2>{{ detail.order.orderNo }}</h2></div>
          <button class="secondary" @click="detail = undefined">关闭</button>
        </div>
        <div class="summary-grid">
          <div><small>订单状态</small><b>{{ orderStatusLabel(detail.order.status) }}</b></div>
          <div><small>线下应收</small><b>{{ money(detail.order.totalAmountFen) }}</b></div>
          <div><small>买家 / 上级</small><b>#{{ detail.order.buyerUserId }} / #{{ detail.order.superiorUserId }}</b></div>
        </div>
        <div class="detail-grid">
          <section>
            <h3>商品明细</h3>
            <div v-for="item in detail.items" :key="item.skuId" class="item">
              <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.productName" />
              <div><b>{{ item.productName }}</b><small>{{ item.skuName }} · {{ salesSceneLabel(item.salesScene) }}</small></div>
              <span>{{ money(item.unitPriceFen) }} × {{ item.quantity }}<b>{{ money(item.subtotalFen) }}</b></span>
            </div>
            <h3>收货地址快照</h3>
            <p class="address">{{ address.recipientName }} · {{ address.phone }}<br />{{ address.province }}{{ address.city }}{{ address.district }}{{ address.detailAddress }} {{ address.postalCode }}</p>
            <h3>物流信息</h3>
            <p v-if="detail.shipment" class="address">{{ detail.shipment.carrierName }}（{{ detail.shipment.carrierCode }}）<br />{{ detail.shipment.trackingNo }} · {{ dateTime(detail.shipment.shippedAt) }}</p>
            <p v-else class="muted">尚未发货。</p>
          </section>
          <section>
            <h3>处理时间线</h3>
            <ol class="timeline">
              <li><b>订单提交</b><span>{{ dateTime(detail.order.createdAt) }}</span></li>
              <li><b>上级确认</b><span>{{ dateTime(detail.superiorConfirmedAt) }}</span></li>
              <li><b>后台审核</b><span>{{ dateTime(detail.adminReviewedAt) }}</span></li>
              <li><b>发货</b><span>{{ dateTime(detail.shipment?.shippedAt) }}</span></li>
              <li><b>自动收货截止</b><span>{{ dateTime(detail.autoReceiveAt) }}</span></li>
              <li><b>完成</b><span>{{ dateTime(detail.completedAt) }}</span></li>
            </ol>
          </section>
        </div>
        <h3>付款凭证</h3>
        <div class="proofs">
          <div v-for="proof in detailProofs" :key="proof.proofId" class="proof">
            <div><b>{{ mediaTypeLabel(proof.mediaType) }}</b><small>{{ fileSize(proof.sizeBytes) }} · 上传人 #{{ proof.uploadedBy }} · 保留至 {{ dateTime(proof.retainUntil) }}</small></div>
            <button class="secondary" @click="openProof(proof)">查看</button>
            <button v-if="can('order:audit')" class="danger" @click="deleteProof(proof)">删除</button>
          </div>
          <p v-if="!detailProofs.length" class="muted">此订单没有上传付款凭证（现金付款可不上传）。</p>
        </div>
        <h3>内部备注</h3>
        <div class="notes">
          <p v-for="note in detailNotes" :key="note.id"><b>#{{ note.adminId }}</b><span>{{ note.note }}</span><small>{{ dateTime(note.createdAt) }}</small></p>
          <p v-if="!detailNotes.length" class="muted">暂无内部备注。</p>
        </div>
      </section>
    </div>

    <div v-if="shipOrder" class="modal-mask" @click.self="shipOrder = undefined">
      <form class="modal card" @submit.prevent="ship">
        <h2>订单发货</h2><p>{{ shipOrder.orderNo }}</p>
        <div class="field"><label>承运商编码</label><input v-model="shipment.carrierCode" required /></div>
        <div class="field"><label>承运商名称</label><input v-model="shipment.carrierName" required /></div>
        <div class="field"><label>物流单号</label><input v-model="shipment.trackingNo" required /></div>
        <div class="modal-actions"><button type="button" class="secondary" @click="shipOrder = undefined">取消</button><button class="primary" :disabled="Boolean(busyAction)">确认发货</button></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.head-actions,.actions{display:flex;gap:7px}.filters{flex-wrap:wrap}.filters label{display:flex;align-items:center;gap:5px;color:var(--muted);font-size:12px}.filters label input{min-width:190px}
.empty{text-align:center;color:var(--muted);padding:35px}.link-button{padding:0;border:0;color:var(--green);background:transparent;font-weight:800}.detail-modal{width:min(1080px,100%);max-height:92vh;overflow:auto}
.detail-head{display:flex;justify-content:space-between;align-items:flex-start}.detail-head h2{margin:4px 0 0}.overline{color:var(--coral);font-size:11px;letter-spacing:.14em}
.summary-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:18px 0}.summary-grid div{padding:14px;background:#f5f7f5;border-radius:10px}.summary-grid small,.summary-grid b{display:block}.summary-grid small,.muted{color:var(--muted)}.summary-grid b{margin-top:5px}
.detail-grid{display:grid;grid-template-columns:1.4fr .6fr;gap:20px}.detail-modal h3{margin:22px 0 10px;font-family:serif}.item{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid var(--line)}
.item img{width:54px;height:54px;object-fit:cover;border-radius:8px}.item small,.item span b{display:block}.item small,.item span{color:var(--muted);font-size:12px}.item span{text-align:right}.address{padding:12px;background:#f5f7f5;border-radius:9px;line-height:1.7}
.timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid var(--line)}.timeline span{color:var(--muted);font-size:12px}
.proof{display:flex;align-items:center;gap:8px;padding:10px;border:1px solid var(--line);border-radius:10px;margin-bottom:8px}.proof>div{flex:1}.proof small{display:block;color:var(--muted);margin-top:4px}
.notes p{display:grid;grid-template-columns:auto 1fr auto;gap:12px;padding:10px 0;border-bottom:1px solid var(--line);margin:0}.notes small{color:var(--muted)}.loading{text-align:center}
@media(max-width:760px){.summary-grid,.detail-grid{grid-template-columns:1fr}.proof{align-items:flex-start;flex-wrap:wrap}.notes p{grid-template-columns:auto 1fr}.notes small{grid-column:2}}
</style>
