<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  busy?: boolean
  appliedSummary?: string | readonly string[]
  submitLabel?: string
  resetLabel?: string
}>(), {
  busy: false,
  appliedSummary: '',
  submitLabel: '查询',
  resetLabel: '重置'
})

defineEmits<{ apply: []; reset: [] }>()
const advancedOpen = ref(false)
</script>

<template>
  <section class="filter-bar card" aria-label="筛选条件">
    <form @submit.prevent="$emit('apply')">
      <div class="filter-bar__fields"><slot /></div>
      <div class="filter-bar__actions">
        <button
          v-if="$slots.advanced"
          type="button"
          class="text-button"
          :aria-expanded="advancedOpen"
          @click="advancedOpen = !advancedOpen"
        >{{ advancedOpen ? '收起高级筛选' : '高级筛选' }}</button>
        <button type="button" class="secondary" :disabled="busy" @click="$emit('reset')">{{ resetLabel }}</button>
        <button class="primary" :disabled="busy">{{ busy ? '查询中…' : submitLabel }}</button>
      </div>
      <div v-if="advancedOpen && $slots.advanced" class="filter-bar__advanced"><slot name="advanced" /></div>
    </form>
    <div v-if="appliedSummary && (typeof appliedSummary === 'string' || appliedSummary.length)" class="filter-bar__applied" aria-live="polite">
      <b>已应用条件</b>
      <span v-if="typeof appliedSummary === 'string'">{{ appliedSummary }}</span>
      <span v-for="item in appliedSummary" v-else :key="item">{{ item }}</span>
    </div>
  </section>
</template>
