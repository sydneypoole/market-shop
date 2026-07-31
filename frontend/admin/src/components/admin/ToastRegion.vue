<script setup lang="ts">
import { useToastRegion } from '../../toast'

const { messages, dismissToast } = useToastRegion()
</script>

<template>
  <Teleport to="body">
    <section class="toast-region" aria-label="操作通知" aria-live="polite" aria-relevant="additions removals">
      <article v-for="message in messages" :key="message.id" class="toast" :class="`toast--${message.tone}`" role="status">
        <span aria-hidden="true">{{ message.tone === 'success' ? '✓' : message.tone === 'danger' ? '!' : 'i' }}</span>
        <div><b>{{ message.title }}</b><p v-if="message.message">{{ message.message }}</p></div>
        <button type="button" aria-label="关闭通知" @click="dismissToast(message.id)">×</button>
      </article>
    </section>
  </Teleport>
</template>
