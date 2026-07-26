<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ page: number; size: number; total: number }>()
const emit = defineEmits<{ change: [page: number] }>()
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
</script>

<template>
  <div class="pagination" aria-label="分页">
    <span>共 {{ total }} 条 · 第 {{ page }}/{{ pages }} 页</span>
    <div>
      <button class="secondary" :disabled="page <= 1" @click="emit('change', page - 1)">上一页</button>
      <button class="secondary" :disabled="page >= pages" @click="emit('change', page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.pagination{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:12px;color:var(--muted);font-size:13px}
.pagination div{display:flex;gap:8px}.pagination button:disabled{opacity:.45;cursor:not-allowed}
</style>
