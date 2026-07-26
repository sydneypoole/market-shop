<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  page: number
  pageSize: number
  total: number
}>()

const emit = defineEmits<{
  change: [page: number]
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
</script>

<template>
  <nav v-if="total > pageSize" class="pagination" aria-label="分页">
    <button
      class="secondary"
      type="button"
      :disabled="page <= 1"
      @click="emit('change', page - 1)"
    >
      上一页
    </button>
    <span>第 {{ page }} / {{ pageCount }} 页 · 共 {{ total }} 条</span>
    <button
      class="secondary"
      type="button"
      :disabled="page >= pageCount"
      @click="emit('change', page + 1)"
    >
      下一页
    </button>
  </nav>
</template>

<style scoped>
.pagination { display: flex; align-items: center; justify-content: center; gap: 14px; margin-top: 20px; }
.pagination span { color: var(--muted); font-size: 13px; }
@media (max-width: 520px) {
  .pagination { justify-content: space-between; gap: 8px; }
  .pagination .secondary { padding: 0 12px; }
}
</style>
