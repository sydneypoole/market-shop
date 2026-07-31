<script setup lang="ts">
withDefaults(defineProps<{
  tone?: 'danger' | 'warning' | 'info' | 'success'
  title?: string
  message?: string
  retryLabel?: string
  retryable?: boolean
}>(), {
  tone: 'danger',
  title: '',
  message: '',
  retryLabel: '重试',
  retryable: false
})

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="inline-alert" :class="`inline-alert--${tone}`" :role="tone === 'danger' ? 'alert' : 'status'">
    <span class="inline-alert__icon" aria-hidden="true">{{ tone === 'success' ? '✓' : tone === 'warning' ? '!' : tone === 'info' ? 'i' : '×' }}</span>
    <div>
      <strong v-if="title">{{ title }}</strong>
      <p v-if="message"><slot>{{ message }}</slot></p>
      <slot v-else />
    </div>
    <button v-if="retryable" type="button" class="secondary compact" @click="$emit('retry')">{{ retryLabel }}</button>
  </div>
</template>
