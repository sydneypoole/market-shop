<script setup lang="ts">
import AdminIcon from './AdminIcon.vue'
import InlineAlert from './InlineAlert.vue'

withDefaults(defineProps<{
  loading?: boolean
  error?: string
  empty?: boolean
  emptyTitle?: string
  emptyText?: string
  label?: string
}>(), {
  loading: false,
  error: '',
  empty: false,
  emptyTitle: '暂无数据',
  emptyText: '调整筛选条件后再试。',
  label: '数据列表'
})

defineEmits<{ retry: [] }>()
</script>

<template>
  <section class="table-frame card" :aria-label="label" :aria-busy="loading">
    <div v-if="loading" class="state-panel" role="status">
      <span class="state-skeleton state-skeleton--icon" aria-hidden="true"></span>
      <div class="state-skeleton-copy" aria-hidden="true"><span></span><span></span></div>
      <span class="sr-only">正在加载，正在读取最新服务端数据</span>
    </div>
    <InlineAlert v-else-if="error" title="数据加载失败" :message="error" retryable @retry="$emit('retry')" />
    <div v-else-if="empty" class="state-panel state-panel--empty">
      <span class="state-empty-icon" aria-hidden="true"><AdminIcon name="empty" :size="30" weight="duotone" /></span>
      <div><b>{{ emptyTitle }}</b><p>{{ emptyText }}</p></div>
      <slot name="empty-action" />
    </div>
    <div v-else class="table-frame__scroll" tabindex="0" :aria-label="`${label}内容`"><slot /></div>
    <div v-if="$slots.footer && !loading && !error" class="table-frame__footer"><slot name="footer" /></div>
  </section>
</template>
