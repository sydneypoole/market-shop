<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, dateTime } from '../api'
import PaginationBar from '../components/PaginationBar.vue'

type Notice = {
  id: number
  title: string
  content: string
  businessType: string
  businessId: string
  status: string
  createdAt: string
}
type NoticePage = { items: Notice[]; total: number; page: number; size: number }

const data = ref<NoticePage>({ items: [], total: 0, page: 1, size: 10 })
const page = ref(1)
const pageSize = 10
const status = ref('')
const query = ref('')
const loading = ref(true)
const error = ref('')
const readingId = ref<number>()
const visible = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return data.value.items.filter(row =>
    (!status.value || row.status === status.value)
    && (!keyword || row.title.toLowerCase().includes(keyword) || row.content.toLowerCase().includes(keyword))
  )
})

async function load(targetPage = page.value) {
  loading.value = true
  error.value = ''
  try {
    data.value = await api<NoticePage>(`/notifications?page=${targetPage}&size=${pageSize}`)
    page.value = data.value.page
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

async function read(row: Notice) {
  if (row.status === 'READ' || readingId.value) return
  readingId.value = row.id
  error.value = ''
  try {
    await api(`/notifications/${row.id}/read`, { method: 'POST' })
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    readingId.value = undefined
  }
}

onMounted(() => load(1))
</script>

<template>
  <div class="page">
    <div class="section-head">
      <div><span class="eyebrow">Messages</span><h1>站内通知</h1><p>订单、发货和售后关键节点会在这里提醒。</p></div>
      <button class="secondary" type="button" :disabled="loading" @click="load()">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>
    <section class="filters card">
      <div class="field"><label for="notice-query">筛选当前页</label><input id="notice-query" v-model="query" placeholder="搜索标题或内容" /></div>
      <div class="field"><label for="notice-status">阅读状态</label><select id="notice-status" v-model="status"><option value="">全部</option><option value="UNREAD">未读</option><option value="READ">已读</option></select></div>
    </section>
    <p v-if="error" class="error">{{ error }}</p>
    <section class="notice-list" :aria-busy="loading">
      <div v-if="loading" class="empty card">正在加载通知…</div>
      <button v-for="row in visible" v-else :key="row.id" class="card notice-row" :class="{ unread: row.status === 'UNREAD' }" :disabled="readingId === row.id" @click="read(row)">
        <span></span>
        <div><b>{{ row.title }}</b><p>{{ row.content }}</p><small>{{ dateTime(row.createdAt) }} · {{ row.businessType }}</small></div>
        <em>{{ readingId === row.id ? '处理中…' : row.status === 'UNREAD' ? '点击标为已读' : '已读' }}</em>
      </button>
      <div v-if="!loading && !visible.length" class="empty card">{{ data.items.length ? '当前页没有符合筛选条件的通知。' : '暂时没有通知。' }}</div>
    </section>
    <PaginationBar :page="page" :page-size="pageSize" :total="data.total" @change="load($event)" />
  </div>
</template>

<style scoped>
.filters { display:grid; grid-template-columns:1fr 220px; gap:12px; padding:16px; margin-bottom:18px; }
.notice-list { display:grid; gap:10px; }
.notice-row { width:100%; display:grid; grid-template-columns:8px 1fr auto; gap:12px; text-align:left; padding:18px; border:1px solid var(--line); background:var(--paper); }
.notice-row > span { width:8px; height:8px; border-radius:50%; margin-top:6px; background:#ccc; }
.notice-row.unread > span { background:var(--coral); }
.notice-row p, .notice-row small { color:var(--muted); }
.notice-row p { margin:7px 0; }
.notice-row em { align-self:center; color:var(--muted); font-size:12px; font-style:normal; }
.notice-row:disabled { cursor:wait; opacity:.65; }
@media (max-width:620px) {
  .filters { grid-template-columns:1fr; }
  .notice-row { grid-template-columns:8px 1fr; }
  .notice-row em { grid-column:2; }
}
</style>
