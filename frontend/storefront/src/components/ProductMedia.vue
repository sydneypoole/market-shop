<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  src?: string
  alt: string
  scene?: string
  eager?: boolean
  ratio?: 'square' | 'portrait' | 'landscape'
}>(), {
  src: '',
  scene: 'SELECT',
  eager: false,
  ratio: 'square'
})

const failed = ref(false)
const visible = computed(() => Boolean(props.src) && !failed.value)

watch(() => props.src, () => {
  failed.value = false
})
</script>

<template>
  <div class="product-media" :class="[`ratio-${ratio}`, { 'has-image': visible }]">
    <img
      v-if="visible"
      :src="src"
      :alt="alt"
      :loading="eager ? 'eager' : 'lazy'"
      :fetchpriority="eager ? 'high' : 'auto'"
      decoding="async"
      @error="failed = true"
    />
    <div v-else class="media-fallback" :class="{ repurchase: scene === 'REPURCHASE' }" role="img" :aria-label="`${alt}，暂无商品图片`">
      <span>{{ scene === 'UPGRADE' ? 'MEMBER EDIT' : 'DAILY SELECT' }}</span>
      <b>拾光<br />优选</b>
      <i aria-hidden="true"></i>
    </div>
  </div>
</template>

<style scoped>
.product-media {
  position: relative;
  min-width: 0;
  overflow: hidden;
  isolation: isolate;
  background: #e9e4da;
}
.ratio-square { aspect-ratio: 1; }
.ratio-portrait { aspect-ratio: 4 / 5; }
.ratio-landscape { aspect-ratio: 16 / 10; }
img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  transition: transform .7s cubic-bezier(.2, .7, .2, 1);
}
.product-media:hover img { transform: scale(1.035); }
.media-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: clamp(18px, 4vw, 34px);
  color: #fffaf0;
  background:
    radial-gradient(circle at 75% 18%, rgba(255, 207, 151, .42), transparent 24%),
    linear-gradient(145deg, #d46d52 0%, #9f4034 100%);
}
.media-fallback.repurchase {
  background:
    radial-gradient(circle at 72% 20%, rgba(199, 219, 185, .34), transparent 25%),
    linear-gradient(145deg, #416b59 0%, #17392f 100%);
}
.media-fallback::before {
  content: "";
  position: absolute;
  width: 58%;
  aspect-ratio: 1;
  right: -16%;
  bottom: -20%;
  border: 1px solid rgba(255, 255, 255, .32);
  border-radius: 50%;
  box-shadow: 0 0 0 28px rgba(255, 255, 255, .05), 0 0 0 64px rgba(255, 255, 255, .035);
}
.media-fallback span {
  position: relative;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: .24em;
  opacity: .76;
}
.media-fallback b {
  position: relative;
  font: 700 clamp(34px, 7vw, 64px) / .98 var(--font-display);
  letter-spacing: -.08em;
}
.media-fallback i {
  position: absolute;
  width: 30%;
  height: 54%;
  right: 16%;
  top: 25%;
  border-radius: 50% 10% 50% 10%;
  background: rgba(255, 255, 255, .1);
  transform: rotate(24deg);
}
@media (prefers-reduced-motion: reduce) {
  img { transition: none; }
  .product-media:hover img { transform: none; }
}
</style>
