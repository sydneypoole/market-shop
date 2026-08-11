<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  nickname?: string | null
  avatarUrl?: string | null
  size?: 'small' | 'large'
}>(), {
  nickname: '',
  avatarUrl: '',
  size: 'small'
})

const broken = ref(false)
const displayName = computed(() => props.nickname?.trim() || '微信会员')
const initial = computed(() => Array.from(displayName.value)[0] || '会')
const safeAvatarUrl = computed(() => {
  const value = props.avatarUrl?.trim() || ''
  return /^\/api\/v1\/member-avatars\/\d+$/.test(value) ? value : ''
})
const imageVisible = computed(() => Boolean(safeAvatarUrl.value) && !broken.value)

watch(() => props.avatarUrl, () => {
  broken.value = false
})
</script>

<template>
  <span class="member-avatar" :class="`member-avatar--${size}`">
    <img
      v-if="imageVisible"
      :src="safeAvatarUrl"
      :alt="`${displayName}的头像`"
      loading="lazy"
      decoding="async"
      referrerpolicy="no-referrer"
      @error="broken = true"
    />
    <span v-else class="member-avatar__fallback" role="img" :aria-label="`${displayName}的头像占位`">
      {{ initial }}
    </span>
  </span>
</template>

<style scoped>
.member-avatar{display:inline-grid;place-items:center;flex:0 0 auto;overflow:hidden;border:1px solid color-mix(in srgb,var(--color-brand) 18%,var(--color-border));border-radius:50%;background:color-mix(in srgb,var(--color-brand) 8%,var(--color-surface));color:var(--color-brand);font-weight:700;line-height:1}.member-avatar--small{width:42px;height:42px;font-size:15px}.member-avatar--large{width:72px;height:72px;font-size:24px}.member-avatar img,.member-avatar__fallback{width:100%;height:100%}.member-avatar img{display:block;object-fit:cover}.member-avatar__fallback{display:grid;place-items:center}
</style>
