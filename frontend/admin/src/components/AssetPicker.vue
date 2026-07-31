<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, adminErrorMessage, dateTime, fileSize } from '../api'
import BusinessActionDialog from './admin/BusinessActionDialog.vue'
import { notifySuccess } from '../toast'

type Asset = { id:number; originalFilename:string; mediaType:string; sizeBytes:number; url:string; createdAt:string }
const props = defineProps<{ modelValue?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const assets = ref<Asset[]>([])
const busy = ref(false)
const error = ref('')
const loading = ref(true)
const deleting = ref<Asset>()
const deleteReason = ref('')
const deleteError = ref('')
const deleteSubmitting = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try { assets.value = await adminApi<Asset[]>('/catalog/assets') }
  catch (cause) { error.value = adminErrorMessage(cause) }
  finally { loading.value = false }
}

async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (file.size === 0) {
    error.value = '图片文件不能为空'
    ;(event.target as HTMLInputElement).value = ''
    return
  }
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    error.value = '仅支持 JPG、PNG 或 WebP 图片'
    ;(event.target as HTMLInputElement).value = ''
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    error.value = '图片大小不可超过 10 MB'
    ;(event.target as HTMLInputElement).value = ''
    return
  }
  busy.value = true
  error.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    const asset = await adminApi<Asset>('/catalog/assets', { method: 'POST', body: form })
    emit('update:modelValue', asset.url)
    await load()
    notifySuccess('素材已上传')
  } catch (cause) { error.value = adminErrorMessage(cause) }
  finally {
    busy.value = false
    ;(event.target as HTMLInputElement).value = ''
  }
}

function openDelete(asset: Asset) {
  deleting.value = asset
  deleteReason.value = ''
  deleteError.value = ''
}

async function remove() {
  const asset = deleting.value
  if (!asset || deleteSubmitting.value) return
  deleteSubmitting.value = true
  deleteError.value = ''
  try {
    await adminApi(`/catalog/assets/${asset.id}`, {
      method: 'DELETE',
      body: JSON.stringify({ reason: deleteReason.value.trim() })
    })
    if (props.modelValue === asset.url) emit('update:modelValue', '')
    deleting.value = undefined
    notifySuccess('素材已删除')
    await load()
  } catch (cause) { deleteError.value = adminErrorMessage(cause) }
  finally { deleteSubmitting.value = false }
}

onMounted(load)
</script>

<template>
  <section class="asset-picker">
    <div class="asset-head">
      <div><b>素材库</b><small>JPG / PNG / WebP，上传后自动移除图片元数据</small></div>
      <label class="primary upload">{{ busy ? '上传中…' : '上传图片' }}<input type="file" accept="image/jpeg,image/png,image/webp" :disabled="busy" @change="upload" /></label>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="loading" class="asset-loading" role="status"><span class="state-spinner"></span>正在加载素材库…</div>
    <div v-else class="asset-grid">
      <article v-for="asset in assets" :key="asset.id" class="asset" :class="{ active: modelValue === asset.url }">
        <button type="button" class="asset-select" @click="emit('update:modelValue', asset.url)">
          <img :src="asset.url" :alt="asset.originalFilename" />
          <span>{{ asset.originalFilename }}<small>{{ fileSize(asset.sizeBytes) }} · {{ dateTime(asset.createdAt) }}</small></span>
        </button>
        <button type="button" class="asset-delete" :aria-label="`删除素材 ${asset.originalFilename}`" @click="openDelete(asset)">删除</button>
      </article>
      <p v-if="!assets.length" class="empty">暂无素材，可直接上传第一张图片。</p>
    </div>
    <BusinessActionDialog
      :model-value="Boolean(deleting)"
      title="删除共享素材"
      :target="deleting?.originalFilename || '当前素材'"
      impact="素材是商品与内容共用资源；删除前请确认线上对象不再引用该图片。删除后当前选择会立即清空。"
      v-model:reason="deleteReason"
      danger
      confirm-label="确认删除素材"
      :submitting="deleteSubmitting"
      :error="deleteError"
      @update:model-value="value => { if (!value) deleting = undefined }"
      @submit="remove"
    />
  </section>
</template>

<style scoped>
.asset-picker{margin-top:14px;padding:14px;border:1px solid var(--line);border-radius:12px}.asset-head{display:flex;justify-content:space-between;align-items:center;gap:12px}
.asset-head b,.asset-head small{display:block}.asset-head small{margin-top:4px;color:var(--muted)}.upload{display:grid;place-items:center}.upload input{display:none}
.asset-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;max-height:320px;overflow:auto;margin-top:12px}
.asset{position:relative;padding:8px;text-align:left;border:1px solid var(--line);border-radius:10px;background:white}.asset.active{border:2px solid var(--green)}.asset-select{width:100%;padding:0;text-align:left;border:0;background:transparent}
.asset img{width:100%;height:100px;object-fit:cover;border-radius:7px;background:#eef1ef}.asset span,.asset small{display:block;overflow:hidden;text-overflow:ellipsis}
.asset span{margin-top:6px;font-size:12px}.asset small{color:var(--muted);margin-top:3px}.asset-delete{position:absolute;top:13px;right:13px;padding:3px 6px;color:#a33;border:0;background:#fff;border-radius:6px;font-size:11px}
.empty{grid-column:1/-1;color:var(--muted)}
.asset-loading{min-height:150px;display:flex;align-items:center;justify-content:center;gap:10px;color:var(--color-text-muted)}
</style>
