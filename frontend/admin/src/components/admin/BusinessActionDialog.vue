<script setup lang="ts">
import { computed, watch } from 'vue'
import BaseDialog from './BaseDialog.vue'
import InlineAlert from './InlineAlert.vue'
import AdminIcon from './AdminIcon.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  title: string
  target: string
  impact: string
  currentState?: string
  nextState?: string
  reason?: string
  password?: string
  reasonLabel?: string
  passwordLabel?: string
  requiresReason?: boolean
  requiresPassword?: boolean
  confirmLabel?: string
  danger?: boolean
  submitting?: boolean
  submitDisabled?: boolean
  error?: string
}>(), {
  currentState: '',
  nextState: '',
  reason: '',
  password: '',
  reasonLabel: '操作原因',
  passwordLabel: '当前管理员密码',
  requiresReason: true,
  requiresPassword: false,
  confirmLabel: '确认提交',
  danger: false,
  submitting: false,
  submitDisabled: false,
  error: ''
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:reason': [value: string]
  'update:password': [value: string]
  submit: []
}>()

const canSubmit = computed(() =>
  !props.submitting
  && !props.submitDisabled
  && (!props.requiresReason || props.reason.trim().length > 0)
  && (!props.requiresPassword || props.password.length > 0)
)

function clearSecrets() {
  emit('update:password', '')
}

function updateOpen(value: boolean) {
  if (!value) clearSecrets()
  emit('update:modelValue', value)
}

watch(() => props.modelValue, open => {
  if (!open) clearSecrets()
})

watch(() => props.target, (target, previous) => {
  if (props.modelValue && previous !== undefined && target !== previous) clearSecrets()
})
</script>

<template>
  <BaseDialog
    :model-value="modelValue"
    :title="title"
    :description="`请核对对象和影响后再${confirmLabel}`"
    :submitting="submitting"
    :dirty="false"
    @update:model-value="updateOpen"
  >
    <div class="business-summary">
      <div><small>操作对象</small><b>{{ target }}</b></div>
      <div v-if="currentState || nextState" class="business-summary__transition">
        <span><small>当前状态</small><b>{{ currentState || '暂无状态' }}</b></span>
        <AdminIcon name="arrow-right" :size="18" />
        <span><small>目标状态</small><b>{{ nextState || '暂无状态' }}</b></span>
      </div>
      <div class="business-summary__impact"><small>影响说明</small><p>{{ impact }}</p></div>
    </div>
    <slot />
    <label v-if="requiresReason" class="field">
      <span>{{ reasonLabel }}</span>
      <textarea
        :value="reason"
        rows="3"
        maxlength="500"
        required
        :disabled="submitting"
        @input="$emit('update:reason', ($event.target as HTMLTextAreaElement).value)"
      ></textarea>
    </label>
    <label v-if="requiresPassword" class="field">
      <span>{{ passwordLabel }}</span>
      <input
        :value="password"
        type="password"
        autocomplete="current-password"
        required
        :disabled="submitting"
        @input="$emit('update:password', ($event.target as HTMLInputElement).value)"
      />
    </label>
    <InlineAlert v-if="error" title="操作未完成" :message="error" />
    <template #footer="{ close }">
      <button type="button" class="secondary" autofocus :disabled="submitting" @click="close">取消</button>
      <button type="button" :class="danger ? 'danger solid' : 'primary'" :disabled="!canSubmit" @click="$emit('submit')">
        {{ submitting ? '提交中…' : confirmLabel }}
      </button>
    </template>
  </BaseDialog>
</template>
