<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { navigationBreadcrumbs } from '../../admin-navigation'

const props = withDefaults(defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>(), {
  description: '',
  eyebrow: ''
})

const route = useRoute()
const breadcrumbs = computed(() => navigationBreadcrumbs(route.path))
const headingId = computed(() => `page-${props.title.replace(/\s+/g, '-').toLowerCase()}`)
</script>

<template>
  <header class="page-header" :aria-labelledby="headingId">
    <div class="page-header__copy">
      <nav v-if="breadcrumbs.length" class="breadcrumbs" aria-label="面包屑">
        <template v-for="(crumb, index) in breadcrumbs" :key="crumb.label">
          <span v-if="index" aria-hidden="true">/</span>
          <RouterLink v-if="crumb.path && index < breadcrumbs.length - 1" :to="crumb.path">{{ crumb.label }}</RouterLink>
          <span v-else :aria-current="index === breadcrumbs.length - 1 ? 'page' : undefined">{{ crumb.label }}</span>
        </template>
      </nav>
      <span v-if="eyebrow" class="page-header__eyebrow">{{ eyebrow }}</span>
      <h1 :id="headingId">{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions"><slot name="actions" /></div>
  </header>
</template>
