<script setup lang="ts">
import AdminIcon, { type AdminIconName } from './AdminIcon.vue'

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

const toneIcon: Record<'danger' | 'warning' | 'info' | 'success', AdminIconName> = {
  danger: 'danger',
  warning: 'warning',
  info: 'info',
  success: 'success'
}
</script>

<template>
  <div class="inline-alert" :class="`inline-alert--${tone}`" :role="tone === 'danger' ? 'alert' : 'status'">
    <span class="inline-alert__icon" aria-hidden="true"><AdminIcon :name="toneIcon[tone]" :size="18" weight="duotone" /></span>
    <div>
      <strong v-if="title">{{ title }}</strong>
      <p v-if="message"><slot>{{ message }}</slot></p>
      <slot v-else />
    </div>
    <button v-if="retryable" type="button" class="secondary compact" @click="$emit('retry')">{{ retryLabel }}</button>
  </div>
</template>
