<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, dateTime, fileSize } from '../api'

type Asset = { id:number; originalFilename:string; mediaType:string; sizeBytes:number; url:string; createdAt:string }
const props = defineProps<{ modelValue?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const assets = ref<Asset[]>([])
const busy = ref(false)
const error = ref('')

async function load() {
  try { assets.value = await adminApi<Asset[]>('/catalog/assets') }
  catch (e) { error.value = (e as Error).message }
}

async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    const asset = await adminApi<Asset>('/catalog/assets', { method: 'POST', body: form })
    emit('update:modelValue', asset.url)
    await load()
  } catch (e) { error.value = (e as Error).message }
  finally {
    busy.value = false
    ;(event.target as HTMLInputElement).value = ''
  }
}

async function remove(asset: Asset) {
  const reason = prompt('删除素材的原因') || ''
  if (!reason) return
  await adminApi(`/catalog/assets/${asset.id}`, {
    method: 'DELETE',
    body: JSON.stringify({ reason })
  })
  if (props.modelValue === asset.url) emit('update:modelValue', '')
  await load()
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
    <div class="asset-grid">
      <button v-for="asset in assets" :key="asset.id" type="button" class="asset" :class="{ active: modelValue === asset.url }" @click="emit('update:modelValue', asset.url)">
        <img :src="asset.url" :alt="asset.originalFilename" />
        <span>{{ asset.originalFilename }}<small>{{ fileSize(asset.sizeBytes) }} · {{ dateTime(asset.createdAt) }}</small></span>
        <i @click.stop="remove(asset)">删除</i>
      </button>
      <p v-if="!assets.length" class="empty">暂无素材，可直接上传第一张图片。</p>
    </div>
  </section>
</template>

<style scoped>
.asset-picker{margin-top:14px;padding:14px;border:1px solid var(--line);border-radius:12px}.asset-head{display:flex;justify-content:space-between;align-items:center;gap:12px}
.asset-head b,.asset-head small{display:block}.asset-head small{margin-top:4px;color:var(--muted)}.upload{display:grid;place-items:center}.upload input{display:none}
.asset-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;max-height:320px;overflow:auto;margin-top:12px}
.asset{position:relative;padding:8px;text-align:left;border:1px solid var(--line);border-radius:10px;background:white}.asset.active{border:2px solid var(--green)}
.asset img{width:100%;height:100px;object-fit:cover;border-radius:7px;background:#eef1ef}.asset span,.asset small{display:block;overflow:hidden;text-overflow:ellipsis}
.asset span{margin-top:6px;font-size:12px}.asset small{color:var(--muted);margin-top:3px}.asset i{position:absolute;top:13px;right:13px;padding:3px 6px;color:#a33;background:#fff;border-radius:6px;font-size:11px;font-style:normal}
.empty{grid-column:1/-1;color:var(--muted)}
</style>
