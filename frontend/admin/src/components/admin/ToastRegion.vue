<script setup lang="ts">
import { useToastRegion } from '../../toast'
import AdminIcon from './AdminIcon.vue'

const { messages, dismissToast } = useToastRegion()
</script>

<template>
  <Teleport to="body">
    <section class="toast-region" aria-label="操作通知" aria-live="polite" aria-relevant="additions removals">
      <article v-for="message in messages" :key="message.id" class="toast" :class="`toast--${message.tone}`" role="status">
        <AdminIcon :name="message.tone === 'success' ? 'success' : message.tone === 'danger' ? 'danger' : 'info'" :size="20" weight="duotone" />
        <div><b>{{ message.title }}</b><p v-if="message.message">{{ message.message }}</p></div>
        <button type="button" class="toast__close" aria-label="关闭通知" @click="dismissToast(message.id)"><AdminIcon name="close" :size="17" weight="bold" /></button>
      </article>
    </section>
  </Teleport>
</template>
