<script setup lang="ts">
import { computed } from 'vue'
import {
  PhArrowLeft,
  PhArrowRight,
  PhCheckCircle,
  PhClipboardText,
  PhFileText,
  PhFunnel,
  PhGauge,
  PhGearSix,
  PhIdentificationBadge,
  PhInfo,
  PhList,
  PhLockKey,
  PhPackage,
  PhReceipt,
  PhShieldCheckered,
  PhShoppingBag,
  PhSignOut,
  PhSpinnerGap,
  PhStorefront,
  PhUsers,
  PhWarning,
  PhWarningCircle,
  PhX,
  PhXCircle
} from '@phosphor-icons/vue'

export type AdminIconName =
  | 'dashboard'
  | 'orders'
  | 'after-sales'
  | 'catalog'
  | 'content'
  | 'members'
  | 'rules'
  | 'accounts'
  | 'audit'
  | 'settings'
  | 'menu'
  | 'close'
  | 'logout'
  | 'success'
  | 'danger'
  | 'warning'
  | 'info'
  | 'loading'
  | 'empty'
  | 'arrow-right'
  | 'arrow-left'
  | 'security'
  | 'store'

const props = withDefaults(defineProps<{
  name: AdminIconName
  size?: number | string
  weight?: 'bold' | 'fill' | 'thin' | 'light' | 'regular' | 'duotone'
  mirrored?: boolean
}>(), {
  size: 20,
  weight: 'regular',
  mirrored: false
})

const icons = {
  dashboard: PhGauge,
  orders: PhReceipt,
  'after-sales': PhClipboardText,
  catalog: PhPackage,
  content: PhFileText,
  members: PhUsers,
  rules: PhFunnel,
  accounts: PhIdentificationBadge,
  audit: PhShieldCheckered,
  settings: PhGearSix,
  menu: PhList,
  close: PhX,
  logout: PhSignOut,
  success: PhCheckCircle,
  danger: PhXCircle,
  warning: PhWarning,
  info: PhInfo,
  loading: PhSpinnerGap,
  empty: PhShoppingBag,
  'arrow-right': PhArrowRight,
  'arrow-left': PhArrowLeft,
  security: PhLockKey,
  store: PhStorefront
} as const

const component = computed(() => icons[props.name] ?? PhWarningCircle)
</script>

<template>
  <component
    :is="component"
    class="admin-icon"
    :size="size"
    :weight="weight"
    :mirrored="mirrored"
    aria-hidden="true"
  />
</template>
