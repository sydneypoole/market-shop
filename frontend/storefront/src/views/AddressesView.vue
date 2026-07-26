<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'

type Address = {
  id: number
  recipientName: string
  phone: string
  province: string
  city: string
  district: string
  detailAddress: string
  postalCode?: string
  defaultAddress: boolean
  version: number
}

const rows = ref<Address[]>([])
const editing = ref<Address>()
const deleting = ref<Address>()
const showForm = ref(false)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const query = ref('')
const visibleRows = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return rows.value.filter(row => !keyword || [
    row.recipientName, row.phone, row.province, row.city, row.district, row.detailAddress
  ].some(value => value.toLowerCase().includes(keyword)))
})
const form = reactive({
  recipientName: '', phone: '', province: '', city: '', district: '', detailAddress: '',
  postalCode: '', defaultAddress: false, version: 0
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api<Address[]>('/addresses')
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

function open(row?: Address) {
  editing.value = row
  Object.assign(form, row || {
    recipientName: '', phone: '', province: '', city: '', district: '', detailAddress: '',
    postalCode: '', defaultAddress: !rows.value.length, version: 0
  })
  showForm.value = true
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const path = editing.value ? `/addresses/${editing.value.id}` : '/addresses'
    await api(path, { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(form) })
    showForm.value = false
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!deleting.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api(`/addresses/${deleting.value.id}?version=${deleting.value.version}`, { method: 'DELETE' })
    deleting.value = undefined
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="section-head"><div><span class="eyebrow">Address Book</span><h1>收货地址</h1><p>订单提交时会保存地址快照，之后修改地址不会影响历史订单。</p></div><button class="primary" type="button" :disabled="busy" @click="open()">新增地址</button></div>
    <div class="field search"><label for="address-query">搜索地址</label><input id="address-query" v-model="query" placeholder="姓名、电话或地址关键词" /></div>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="loading" class="empty card" aria-busy="true">正在加载地址簿…</div>
    <section v-else class="address-grid">
      <article v-for="row in visibleRows" :key="row.id" class="card address-card">
        <header><b>{{ row.recipientName }}</b><span>{{ row.phone }}</span><small v-if="row.defaultAddress">默认</small></header>
        <p>{{ row.province }} {{ row.city }} {{ row.district }} {{ row.detailAddress }}</p>
        <footer><button class="secondary" type="button" :disabled="busy" @click="open(row)">编辑</button><button class="danger" type="button" :disabled="busy" @click="deleting = row">删除</button></footer>
      </article>
      <div v-if="!visibleRows.length" class="empty card">{{ rows.length ? '没有符合搜索条件的地址。' : '还没有收货地址。' }}</div>
    </section>
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <form class="modal card address-modal" @submit.prevent="save">
        <h2>{{ editing ? '编辑地址' : '新增地址' }}</h2>
        <div class="grid-2">
          <div class="field"><label>收货人</label><input v-model="form.recipientName" required /></div>
          <div class="field"><label>联系电话</label><input v-model="form.phone" required inputmode="tel" /></div>
          <div class="field"><label>省份</label><input v-model="form.province" required /></div>
          <div class="field"><label>城市</label><input v-model="form.city" required /></div>
          <div class="field"><label>区县</label><input v-model="form.district" required /></div>
          <div class="field"><label>邮编</label><input v-model="form.postalCode" /></div>
        </div>
        <div class="field detail"><label>详细地址</label><textarea v-model="form.detailAddress" required /></div>
        <label class="check"><input v-model="form.defaultAddress" type="checkbox" /> 设为默认地址</label>
        <div class="modal-actions"><button type="button" class="secondary" :disabled="busy" @click="showForm = false">取消</button><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></div>
      </form>
    </div>
    <div v-if="deleting" class="modal-mask" @click.self="deleting = undefined">
      <form class="modal card delete-modal" @submit.prevent="remove">
        <h2>删除收货地址</h2><p>确定删除“{{ deleting.recipientName }} · {{ deleting.detailAddress }}”吗？历史订单中的地址快照不会受影响。</p>
        <div class="modal-actions"><button type="button" class="secondary" :disabled="busy" @click="deleting = undefined">返回</button><button class="danger" :disabled="busy">{{ busy ? '删除中…' : '确认删除' }}</button></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.search{max-width:460px;margin-bottom:16px}.address-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:14px}.address-card{padding:22px}.address-card header{display:flex;gap:12px;align-items:center}.address-card header span,.address-card p,.delete-modal p{color:var(--muted)}.address-card header small{color:white;background:var(--green);padding:3px 8px;border-radius:99px}.address-card p,.delete-modal p{line-height:1.7}.address-card footer{display:flex;justify-content:flex-end;gap:8px}.address-modal{width:min(660px,calc(100vw - 28px));padding:26px}.delete-modal{width:min(480px,calc(100vw - 28px));padding:24px}.detail{margin-top:16px}.check{display:block;margin:16px 0}.modal-actions{display:flex;justify-content:flex-end;gap:8px}@media(max-width:700px){.address-grid{grid-template-columns:1fr}}
</style>
