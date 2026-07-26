<script setup lang="ts">
import { ref } from 'vue'
import { api, dateTime, fileSize } from '../api'
import type { AfterSaleProof, Proof, SignedDownload } from '../types'

const props = defineProps<{
  proofs: Array<Proof | AfterSaleProof>
  kind: 'order' | 'after-sale'
  loading?: boolean
}>()

const previewUrl = ref('')
const previewExpiresAt = ref('')
const previewingId = ref<number>()
const error = ref('')

function proofId(proof: Proof | AfterSaleProof) {
  return 'proofId' in proof ? proof.proofId : proof.id
}

async function preview(proof: Proof | AfterSaleProof) {
  const id = proofId(proof)
  previewingId.value = id
  error.value = ''
  try {
    const endpoint = props.kind === 'order'
      ? `/order-proofs/${id}/download`
      : `/after-sale-proofs/${id}/download`
    const result = await api<SignedDownload>(endpoint)
    previewUrl.value = result.signedUrl
    previewExpiresAt.value = result.expiresAt
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    previewingId.value = undefined
  }
}

function closePreview() {
  previewUrl.value = ''
  previewExpiresAt.value = ''
}
</script>

<template>
  <section class="proof-section">
    <div class="subhead">
      <div><h2>图片凭证</h2><p>图片只通过短时授权地址查看，不会生成永久公开链接。</p></div>
      <span>{{ proofs.length }} 张</span>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="loading" class="proof-grid" aria-busy="true">
      <div v-for="index in 2" :key="index" class="proof-card skeleton"></div>
    </div>
    <div v-else-if="proofs.length" class="proof-grid">
      <article v-for="proof in proofs" :key="proofId(proof)" class="proof-card">
        <span class="proof-icon">凭</span>
        <div>
          <b>{{ proof.mediaType.replace('image/', '').toUpperCase() }} 图片</b>
          <small>{{ fileSize(proof.sizeBytes) }} · {{ dateTime(proof.createdAt) }}</small>
          <small>保留至 {{ dateTime(proof.retainUntil) }}</small>
        </div>
        <button
          class="secondary"
          type="button"
          :disabled="previewingId === proofId(proof)"
          @click="preview(proof)"
        >
          {{ previewingId === proofId(proof) ? '获取中…' : '安全查看' }}
        </button>
      </article>
    </div>
    <div v-else class="empty compact">暂无图片凭证。</div>

    <div v-if="previewUrl" class="modal-mask" @click.self="closePreview">
      <section class="modal preview card" role="dialog" aria-modal="true" aria-label="凭证预览">
        <header>
          <div><b>凭证安全预览</b><small>授权将于 {{ dateTime(previewExpiresAt) }} 失效</small></div>
          <button class="secondary" type="button" @click="closePreview">关闭</button>
        </header>
        <img :src="previewUrl" alt="交易或售后图片凭证" />
      </section>
    </div>
  </section>
</template>

<style scoped>
.proof-section { margin-top: 24px; }
.subhead { display: flex; justify-content: space-between; align-items: end; gap: 16px; margin-bottom: 12px; }
.subhead h2, .subhead p { margin: 0; }
.subhead h2 { font-family: serif; }
.subhead p, .subhead > span { color: var(--muted); font-size: 13px; margin-top: 5px; }
.proof-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.proof-card { min-height: 92px; display: grid; grid-template-columns: 44px 1fr auto; align-items: center; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 14px; background: #fff; }
.proof-icon { display: grid; place-items: center; width: 44px; height: 44px; color: white; background: var(--green); border-radius: 12px; font-weight: 800; }
.proof-card b, .proof-card small { display: block; }
.proof-card small { color: var(--muted); margin-top: 3px; }
.compact { padding: 24px; border: 1px dashed var(--line); border-radius: 14px; }
.skeleton { background: linear-gradient(100deg, #eee8de 20%, #faf7f1 40%, #eee8de 60%); background-size: 200% 100%; animation: shine 1.2s infinite; }
.preview { width: min(760px, 100%); padding: 18px; }
.preview header { display: flex; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.preview header small { display: block; color: var(--muted); margin-top: 4px; }
.preview img { display: block; max-width: 100%; max-height: calc(100vh - 160px); margin: auto; object-fit: contain; border-radius: 12px; }
@keyframes shine { to { background-position: -200% 0; } }
@media (max-width: 700px) {
  .proof-grid { grid-template-columns: 1fr; }
  .proof-card { grid-template-columns: 40px 1fr; }
  .proof-card button { grid-column: 2; }
}
</style>
