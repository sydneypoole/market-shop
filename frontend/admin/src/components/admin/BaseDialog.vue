<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  title: string
  description?: string
  width?: string
  placement?: 'center' | 'right'
  submitting?: boolean
  dirty?: boolean
  persistent?: boolean
  showDefaultFooter?: boolean
  closeLabel?: string
}>(), {
  description: '',
  width: 'min(560px, calc(100vw - 32px))',
  placement: 'center',
  submitting: false,
  dirty: false,
  persistent: false,
  showDefaultFooter: true,
  closeLabel: '关闭'
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  closeBlocked: []
  afterClose: []
}>()

const panel = ref<HTMLElement>()
const titleId = `dialog-title-${useId()}`
const descriptionId = `dialog-description-${useId()}`
let previousFocus: HTMLElement | null = null
let scrollLocked = false

const focusableSelector = [
  'button:not([disabled])', '[href]', 'input:not([disabled])', 'select:not([disabled])',
  'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])'
].join(',')

function lockScroll() {
  if (scrollLocked || typeof document === 'undefined') return
  scrollLocked = true
  const count = Number(document.body.dataset.adminOverlayCount || '0')
  if (count === 0) document.body.dataset.adminPreviousOverflow = document.body.style.overflow
  document.body.dataset.adminOverlayCount = String(count + 1)
  document.body.style.overflow = 'hidden'
}

function unlockScroll() {
  if (!scrollLocked || typeof document === 'undefined') return
  scrollLocked = false
  const count = Math.max(0, Number(document.body.dataset.adminOverlayCount || '1') - 1)
  document.body.dataset.adminOverlayCount = String(count)
  if (count === 0) {
    document.body.style.overflow = document.body.dataset.adminPreviousOverflow || ''
    delete document.body.dataset.adminPreviousOverflow
    delete document.body.dataset.adminOverlayCount
  }
}

function requestClose() {
  if (props.submitting || props.persistent) return
  if (props.dirty) {
    emit('closeBlocked')
    return
  }
  emit('update:modelValue', false)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !panel.value) return
  const focusable = Array.from(panel.value.querySelectorAll<HTMLElement>(focusableSelector))
  if (!focusable.length) {
    event.preventDefault()
    panel.value.focus()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (!first || !last) return
  if (!panel.value.contains(document.activeElement)) {
    event.preventDefault()
    first.focus()
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(() => props.modelValue, async (open, wasOpen) => {
  if (open) {
    previousFocus = typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    lockScroll()
    await nextTick()
    const target = panel.value?.querySelector<HTMLElement>('[autofocus]')
      ?? panel.value?.querySelector<HTMLElement>(focusableSelector)
      ?? panel.value
    target?.focus()
  } else if (wasOpen) {
    unlockScroll()
    previousFocus?.focus()
    previousFocus = null
    emit('afterClose')
  }
}, { immediate: true })

onBeforeUnmount(() => {
  unlockScroll()
  previousFocus?.focus()
})
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="admin-overlay" :class="`admin-overlay--${placement}`" @mousedown.self="requestClose">
      <section
        ref="panel"
        class="admin-dialog"
        :class="`admin-dialog--${placement}`"
        :style="{ '--dialog-width': width }"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="description ? descriptionId : undefined"
        tabindex="-1"
        @keydown="handleKeydown"
      >
        <header class="admin-dialog__header">
          <div>
            <h2 :id="titleId">{{ title }}</h2>
            <p v-if="description" :id="descriptionId">{{ description }}</p>
          </div>
          <button v-if="!persistent" type="button" class="icon-button" :aria-label="closeLabel" :disabled="submitting" @click="requestClose">×</button>
        </header>
        <div class="admin-dialog__body"><slot /></div>
        <footer v-if="$slots.footer || showDefaultFooter" class="admin-dialog__footer">
          <slot name="footer" :close="requestClose">
            <button type="button" class="secondary" :disabled="submitting" @click="requestClose">{{ closeLabel }}</button>
          </slot>
        </footer>
      </section>
    </div>
  </Teleport>
</template>
