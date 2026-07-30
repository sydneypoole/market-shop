<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import type { StorefrontContent } from '../types'
import { sanitizeRichText } from '../utils/sanitize'

const route = useRoute()
const router = useRouter()
const content = ref<StorefrontContent>()
const loading = ref(true)
const error = ref('')
const safeBody = computed(() => sanitizeRichText(content.value?.bodyHtml || ''))

onMounted(async () => {
  try {
    content.value = await api<StorefrontContent>(`/content/${route.params.id}`)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '内容加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <article class="page content-page">
    <div v-if="loading" class="content-loading" aria-busy="true">正在准备内容…</div>
    <template v-else-if="content">
      <nav aria-label="面包屑"><RouterLink to="/">首页</RouterLink><span>/</span><b>{{ content.title }}</b></nav>
      <header>
        <span>STORE JOURNAL · {{ content.type }}</span>
        <h1>{{ content.title }}</h1>
        <p>{{ content.summary }}</p>
      </header>
      <img v-if="content.coverUrl" class="content-cover" :src="content.coverUrl" :alt="content.title" />
      <div v-if="safeBody" class="content-body" v-html="safeBody"></div>
      <div v-else class="content-empty">这篇内容暂时没有更多正文。</div>
    </template>
    <div v-else class="empty card">
      <p>{{ error || '内容不存在或尚未发布。' }}</p>
      <button class="secondary" type="button" @click="router.replace('/')">返回商城</button>
    </div>
  </article>
</template>

<style scoped>
.content-page{max-width:1080px}.content-page nav{display:flex;gap:10px;align-items:center;color:var(--muted);font-size:10px}.content-page nav b{max-width:70%;overflow:hidden;color:var(--ink);text-overflow:ellipsis;white-space:nowrap}
.content-page header{padding:90px 4% 64px;text-align:center}.content-page header>span{color:var(--coral);font-size:9px;font-weight:850;letter-spacing:.22em}.content-page h1{max-width:850px;margin:24px auto;font:650 clamp(50px,8vw,92px)/1.02 var(--font-display);letter-spacing:-.07em}.content-page header p{max-width:660px;margin:auto;color:var(--muted);font-size:16px;line-height:1.8}
.content-cover{width:100%;max-height:680px;object-fit:cover;border-radius:28px}.content-body{max-width:760px;margin:70px auto;color:var(--ink-soft);font-size:16px;line-height:1.9}.content-body :deep(h2),.content-body :deep(h3){margin:1.6em 0 .6em;color:var(--ink);font-family:var(--font-display)}.content-body :deep(img){max-width:100%;height:auto;border-radius:16px}.content-body :deep(a){color:var(--green);text-decoration:underline}.content-body :deep(blockquote){margin:1.5em 0;padding-left:18px;border-left:3px solid var(--coral);color:var(--muted)}.content-body :deep(.ql-ui){display:none}.content-empty,.content-loading{min-height:45vh;display:grid;place-items:center;color:var(--muted)}
@media(max-width:700px){.content-page header{padding:60px 0 40px}.content-page h1{font-size:50px}.content-page header p{font-size:14px}.content-cover{border-radius:18px}.content-body{margin:45px 2px;font-size:15px}}
</style>
