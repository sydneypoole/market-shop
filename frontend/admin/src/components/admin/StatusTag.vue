<script setup lang="ts">
withDefaults(defineProps<{
  tone?: 'success' | 'warning' | 'danger' | 'neutral' | 'info'
  label?: string
}>(), {
  tone: 'neutral',
  label: ''
})
</script>

<template>
  <span class="status-tag" :class="`status-tag--${tone}`">
    <span class="status-tag__dot" aria-hidden="true"></span>
    <slot>{{ label }}</slot>
  </span>
</template>
