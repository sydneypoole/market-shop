<script setup lang="ts">
import BaseDialog from './BaseDialog.vue'

withDefaults(defineProps<{
  modelValue: boolean
  title: string
  description?: string
  width?: string
  submitting?: boolean
  dirty?: boolean
}>(), {
  description: '',
  width: 'min(760px, 100vw)',
  submitting: false,
  dirty: false
})

defineEmits<{
  'update:modelValue': [value: boolean]
  closeBlocked: []
  afterClose: []
}>()
</script>

<template>
  <BaseDialog
    :model-value="modelValue"
    :title="title"
    :description="description"
    :width="width"
    placement="right"
    :submitting="submitting"
    :dirty="dirty"
    :show-default-footer="!$slots.footer"
    @update:model-value="$emit('update:modelValue', $event)"
    @close-blocked="$emit('closeBlocked')"
    @after-close="$emit('afterClose')"
  >
    <slot />
    <template v-if="$slots.footer" #footer="{ close }"><slot name="footer" :close="close" /></template>
  </BaseDialog>
</template>
