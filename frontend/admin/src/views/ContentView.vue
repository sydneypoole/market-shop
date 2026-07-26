<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../api'
import AssetPicker from '../components/AssetPicker.vue'

type Content = {id:number;contentType:string;title:string;summary?:string;coverUrl?:string;targetUrl?:string;bodyHtml?:string;status:string;sortOrder:number}
const rows = ref<Content[]>([])
const editing = ref<Content>()
const show = ref(false)
const preview = ref(false)
const error = ref('')
const busy = ref(false)
const form = reactive({contentType:'BANNER',title:'',summary:'',coverUrl:'',targetUrl:'',bodyHtml:'',status:'DRAFT',sortOrder:0})

async function load() {
  try { rows.value = await adminApi<Content[]>('/catalog/contents') }
  catch (e) { error.value = (e as Error).message }
}
function open(row?: Content) {
  editing.value = row
  Object.assign(form, row || {contentType:'BANNER',title:'',summary:'',coverUrl:'',targetUrl:'',bodyHtml:'',status:'DRAFT',sortOrder:0})
  preview.value = false
  show.value = true
}
async function save() {
  busy.value = true
  try {
    await adminApi(`/catalog/contents${editing.value ? `/${editing.value.id}` : ''}`, {
      method:editing.value ? 'PUT' : 'POST',
      body:JSON.stringify(form)
    })
    show.value = false
    await load()
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function remove(row: Content) {
  if (!confirm('确定下线并删除该内容吗？')) return
  await adminApi(`/catalog/contents/${row.id}`, {method:'DELETE'})
  await load()
}
onMounted(load)
</script>

<template>
  <div>
    <div class="page-title"><div><h1>内容运营</h1><p>维护首页横幅、公告和帮助内容，并可从 RustFS 素材库选择封面。</p></div><button class="primary" @click="open()">新增内容</button></div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card table-wrap"><table><thead><tr><th>排序</th><th>封面</th><th>类型</th><th>标题</th><th>摘要</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in rows" :key="row.id"><td>{{ row.sortOrder }}</td><td><img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.title" /></td><td>{{ row.contentType }}</td><td><b>{{ row.title }}</b></td><td>{{ row.summary || '—' }}</td><td><span class="tag" :class="{green:row.status === 'PUBLISHED'}">{{ row.status }}</span></td><td class="actions"><button class="secondary" @click="open(row)">编辑</button><button class="danger" @click="remove(row)">删除</button></td></tr>
    </tbody></table></div>
    <div v-if="show" class="modal-mask" @click.self="show = false">
      <form class="modal content-modal card" @submit.prevent="save">
        <div class="modal-title"><div><h2>{{ editing ? '编辑内容' : '新增内容' }}</h2><p>发布状态会实时影响商城展示。</p></div><button type="button" class="secondary" @click="show = false">关闭</button></div>
        <div class="row"><div class="field"><label>类型</label><select v-model="form.contentType"><option>BANNER</option><option>ANNOUNCEMENT</option><option>QUICK_ENTRY</option><option>HELP</option></select></div><div class="field"><label>状态</label><select v-model="form.status"><option>DRAFT</option><option>PUBLISHED</option><option>OFFLINE</option></select></div></div>
        <div class="field"><label>标题</label><input v-model="form.title" required /></div>
        <div class="field"><label>摘要</label><textarea v-model="form.summary" /></div>
        <AssetPicker v-model="form.coverUrl" />
        <div class="field"><label>封面 URL</label><input v-model="form.coverUrl" /></div>
        <div class="field"><label>跳转 URL</label><input v-model="form.targetUrl" placeholder="/rules 或 https://..." /></div>
        <div class="body-head"><label>正文 HTML</label><button type="button" class="secondary" @click="preview = !preview">{{ preview ? '继续编辑' : '安全预览' }}</button></div>
        <textarea v-if="!preview" v-model="form.bodyHtml" rows="9" />
        <iframe v-else sandbox="" class="preview" :srcdoc="form.bodyHtml || '<p>暂无正文</p>'" title="内容预览"></iframe>
        <div class="field"><label>排序</label><input v-model.number="form.sortOrder" type="number" /></div>
        <div class="modal-actions"><button type="button" class="secondary" @click="show = false">取消</button><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.actions,.modal-title,.body-head{display:flex;gap:8px}.actions img,td img{width:54px;height:40px;object-fit:cover;border-radius:6px}.content-modal{width:min(880px,100%);max-height:94vh;overflow:auto}.modal-title,.body-head{justify-content:space-between;align-items:center}.modal-title h2,.modal-title p{margin:0}.modal-title p{color:var(--muted);margin-top:5px}
.content-modal .field{margin-top:12px}.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}.body-head{margin-top:13px;color:var(--muted);font-size:12px;font-weight:700}.content-modal>textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:9px}.preview{width:100%;height:260px;border:1px solid var(--line);border-radius:9px}
</style>
