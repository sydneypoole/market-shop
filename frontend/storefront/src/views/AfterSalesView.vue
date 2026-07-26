<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, dateTime, statusText } from '../api'
import PaginationBar from '../components/PaginationBar.vue'
import type { AfterSale } from '../types'

const mine = ref<AfterSale[]>([])
const superior = ref<AfterSale[]>([])
const tab = ref<'mine' | 'superior'>('mine')
const query = ref('')
const status = ref('')
const page = ref(1)
const pageSize = 8
const loading = ref(true)
const error = ref('')

const source = computed(() => tab.value === 'mine' ? mine.value : superior.value)
const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return source.value.filter(row =>
    (!status.value || row.status === status.value)
    && (!keyword
      || row.afterSaleNo.toLowerCase().includes(keyword)
      || String(row.orderId).includes(keyword)
      || row.reason.toLowerCase().includes(keyword))
  )
})
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const availableStatuses = computed(() => Array.from(new Set(source.value.map(row => row.status))))

watch([tab, query, status], () => { page.value = 1 })

async function load() {
  loading.value = true
  error.value = ''
  const results = await Promise.allSettled([
    api<AfterSale[]>('/after-sales'),
    api<AfterSale[]>('/after-sales/superior')
  ])
  if (results[0].status === 'fulfilled') mine.value = results[0].value
  else error.value = results[0].reason instanceof Error ? results[0].reason.message : '售后单加载失败'
  if (results[1].status === 'fulfilled') superior.value = results[1].value
  else if (!error.value) error.value = results[1].reason instanceof Error ? results[1].reason.message : '退款任务加载失败'
  loading.value = false
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="section-head">
      <div><span class="eyebrow">After Sales</span><h1>售后中心</h1><p>查看处理进度、退货地址、回寄物流与各阶段图片凭证。</p></div>
      <button class="secondary" type="button" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>
    <div class="tabs">
      <button :class="{ active: tab === 'mine' }" @click="tab = 'mine'">我的售后 <b>{{ mine.length }}</b></button>
      <button :class="{ active: tab === 'superior' }" @click="tab = 'superior'">待我退款 <b>{{ superior.filter(row => row.status === 'PENDING_OFFLINE_REFUND').length }}</b></button>
    </div>
    <section class="filters card">
      <div class="field"><label for="sale-query">搜索</label><input id="sale-query" v-model="query" placeholder="售后单号、订单 ID 或原因" /></div>
      <div class="field"><label for="sale-status">状态</label><select id="sale-status" v-model="status"><option value="">全部状态</option><option v-for="value in availableStatuses" :key="value" :value="value">{{ statusText[value] || value }}</option></select></div>
      <button v-if="query || status" class="secondary" type="button" @click="query = ''; status = ''">清除筛选</button>
    </section>
    <p v-if="error" class="error">{{ error }}</p>

    <section v-if="loading" class="list" aria-busy="true">
      <div v-for="index in 3" :key="index" class="card sale skeleton"></div>
    </section>
    <section v-else-if="paged.length" class="list">
      <article v-for="row in paged" :key="row.id" class="card sale">
        <header>
          <div><small>{{ row.afterSaleNo }}</small><h3>订单 #{{ row.orderId }} · {{ row.type === 'RETURN_REFUND' ? '退货退款' : '仅退款' }}</h3></div>
          <span>{{ statusText[row.status] || row.status }}</span>
        </header>
        <p>{{ row.reason }}</p>
        <p v-if="row.adminReason" class="muted">处理意见：{{ row.adminReason }}</p>
        <dl>
          <div><dt>申请时间</dt><dd>{{ dateTime(row.createdAt) }}</dd></div>
          <div v-if="row.returnTrackingNo"><dt>回寄物流</dt><dd>{{ row.returnCarrier }} · {{ row.returnTrackingNo }}</dd></div>
        </dl>
        <footer>
          <RouterLink class="primary detail-link" :to="`/after-sales/${row.id}`">
            {{ tab === 'superior' && row.status === 'PENDING_OFFLINE_REFUND' ? '处理退款 / 查看详情' : '查看进度与凭证' }}
          </RouterLink>
        </footer>
      </article>
    </section>
    <div v-else class="empty card">{{ source.length ? '没有符合筛选条件的售后单。' : '暂无售后记录。' }}</div>
    <PaginationBar :page="page" :page-size="pageSize" :total="filtered.length" @change="page = $event" />
  </div>
</template>

<style scoped>
.tabs { display:flex; gap:8px; margin-bottom:14px; }
.tabs button { border:0; border-radius:99px; padding:10px 16px; background:#eee8df; color:var(--muted); }
.tabs .active { color:white; background:var(--ink); }
.tabs b { margin-left:5px; }
.filters { display:grid; grid-template-columns:1fr 240px auto; align-items:end; gap:12px; padding:16px; margin-bottom:18px; }
.list { display:grid; gap:13px; }
.sale { min-height:210px; padding:20px; }
.sale header { display:flex; justify-content:space-between; gap:15px; }
.sale h3 { margin:5px 0; }
.sale header span { flex:none; color:#96631f; }
.sale dl { display:flex; gap:24px; padding-top:12px; border-top:1px solid var(--line); }
.sale dl div { display:grid; gap:4px; }
.sale dt { color:var(--muted); font-size:12px; }
.sale dd { margin:0; }
.sale footer { display:flex; justify-content:flex-end; margin-top:16px; }
.detail-link { display:inline-flex; align-items:center; justify-content:center; }
.skeleton { background:linear-gradient(100deg,#eee8de 20%,#faf7f1 40%,#eee8de 60%); background-size:200% 100%; animation:shine 1.2s infinite; }
@keyframes shine { to { background-position:-200% 0; } }
@media (max-width:700px) {
  .filters { grid-template-columns:1fr; }
  .sale header { align-items:start; flex-direction:column; }
  .sale dl { flex-direction:column; gap:10px; }
  .detail-link { width:100%; }
}
</style>
