<script setup lang="ts">
type Tokens = {
  primary: string
  accent: string
  canvas: string
  surface: string
  ink: string
  muted: string
  radius: string
}

type Section = {
  id: string
  type: string
  enabled: boolean
  settings: Record<string, unknown>
}

const props = defineProps<{
  preset: string
  tokens: Tokens
  sections: Section[]
  device: 'desktop' | 'mobile'
}>()

function text(section: Section, key: string, fallback: string) {
  const value = section.settings[key]
  return typeof value === 'string' ? value : fallback
}

function items(section: Section) {
  const value = section.settings.items
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : ['精选商品', '透明规格', '完整履约', '售后可查']
}
</script>

<template>
  <div class="preview-stage" :class="`device-${device}`">
    <div
      class="preview-page"
      :class="`preview-${preset.toLowerCase()}`"
      :style="{
        '--p-primary': tokens.primary,
        '--p-accent': tokens.accent,
        '--p-canvas': tokens.canvas,
        '--p-surface': tokens.surface,
        '--p-ink': tokens.ink,
        '--p-muted': tokens.muted,
        '--p-radius': tokens.radius
      }"
    >
      <header><b>拾光优选</b><nav>精选　商品　会员　订单</nav><i>购物袋 0</i></header>
      <template v-for="section in sections.filter(item => item.enabled)" :key="section.id">
        <div v-if="section.type === 'ANNOUNCEMENT'" class="p-announcement">商城公告　精心挑选每一件日常好物 →</div>
        <section v-else-if="section.type === 'HERO'" class="p-hero">
          <div><small>{{ text(section, 'eyebrow', 'CURATED FOR YOU') }}</small><h1>{{ text(section, 'title', '认真挑选，让日常值得期待。') }}</h1><p>{{ text(section, 'description', '从一件真正好用的物品开始。') }}</p><button>{{ text(section, 'primaryLabel', '浏览精选') }}</button></div>
          <aside><span>{{ preset === 'VIBRANT' ? 'GO' : preset === 'MINIMAL' ? '01' : '拾' }}</span></aside>
        </section>
        <section v-else-if="section.type === 'CATEGORY_NAV'" class="p-categories">
          <h2>{{ text(section, 'title', '按分类探索') }}</h2><div><span>全部商品</span><span>成长精选</span><span>品质复购</span></div>
        </section>
        <section v-else-if="section.type === 'QUICK_LINKS'" class="p-links"><h2>{{ text(section, 'title', '快捷入口') }}</h2><div><span>会员中心 ↗</span><span>规则说明 ↗</span></div></section>
        <section v-else-if="section.type === 'PRODUCT_COLLECTION'" class="p-products">
          <small>{{ text(section, 'eyebrow', 'THE COLLECTION') }}</small><h2>{{ text(section, 'title', '本期精选') }}</h2>
          <div><article v-for="index in 4" :key="index"><i><b>{{ index }}</b></i><strong>精选商品 {{ index }}</strong><span>¥298.00</span></article></div>
        </section>
        <section v-else-if="section.type === 'CONTENT_STORY'" class="p-story"><i>拾</i><div><small>OUR STORY</small><h2>认真选择的理由</h2><p>展示后台发布的品牌故事与帮助内容。</p></div></section>
        <section v-else-if="section.type === 'SERVICE_BENEFITS'" class="p-benefits"><span v-for="item in items(section)" :key="item">{{ item }}</span></section>
      </template>
      <footer>拾光优选 · 商城模板实时预览</footer>
    </div>
  </div>
</template>

<style scoped>
.preview-stage{width:100%;min-height:620px;padding:16px;overflow:auto;border-radius:14px;background:#dfe3e0}.preview-page{--scale:1;width:min(1180px,100%);min-width:720px;min-height:640px;margin:auto;color:var(--p-ink);background:var(--p-canvas);border-radius:10px;box-shadow:0 16px 45px #1a2a2230;overflow:hidden;transform-origin:top center}.device-mobile .preview-page{width:390px;min-width:390px}.preview-page>header{height:54px;display:flex;align-items:center;justify-content:space-between;padding:0 28px;border-bottom:1px solid #0002;background:var(--p-surface);font-size:10px}.preview-page>header b{font-family:serif;font-size:14px}.preview-page>header i{font-style:normal}.p-announcement{margin:10px 18px 0;padding:8px 12px;border:1px solid #0002;border-radius:8px;background:var(--p-surface);font-size:8px}.p-hero{min-height:350px;display:grid;grid-template-columns:1fr 1fr;margin:10px 18px 0;overflow:hidden;border-radius:var(--p-radius);background:color-mix(in srgb,var(--p-primary) 10%,var(--p-surface))}.p-hero>div{align-self:center;padding:42px}.p-hero small,.p-products>small,.p-story small{color:var(--p-accent);font-size:7px;letter-spacing:.18em}.p-hero h1{max-width:430px;margin:13px 0;font:650 48px/.95 serif;letter-spacing:-.06em}.p-hero p{color:var(--p-muted);font-size:9px}.p-hero button{margin-top:14px;padding:9px 14px;color:white;border:0;border-radius:99px;background:var(--p-primary);font-size:8px}.p-hero aside{display:grid;place-items:center;background:linear-gradient(145deg,var(--p-accent),var(--p-primary))}.p-hero aside span{color:white;font:700 120px serif;opacity:.8}.p-categories,.p-links,.p-products{margin:48px 28px 0}.p-categories h2,.p-links h2,.p-products h2{margin:6px 0 18px;font:650 32px serif}.p-categories>div,.p-links>div{display:flex;gap:8px}.p-categories span,.p-links span{flex:1;min-height:56px;padding:12px;border:1px solid #0002;border-radius:8px;background:var(--p-surface);font-size:9px}.p-products>div{display:grid;grid-template-columns:repeat(4,1fr);gap:9px}.p-products article{display:grid;gap:5px;font-size:8px}.p-products article i{aspect-ratio:4/5;display:grid;place-items:center;border-radius:8px;background:color-mix(in srgb,var(--p-primary) 12%,var(--p-surface));font-style:normal}.p-products article i b{color:var(--p-primary);font:500 40px serif;opacity:.3}.p-products article span{color:var(--p-accent);font-weight:700}.p-story{min-height:220px;display:grid;grid-template-columns:1fr 1fr;margin:55px 18px 0;overflow:hidden;border-radius:var(--p-radius);color:white;background:var(--p-primary)}.p-story>i{display:grid;place-items:center;background:var(--p-accent);font:normal 100px serif;opacity:.75}.p-story>div{align-self:center;padding:28px}.p-story h2{margin:9px 0;font:650 28px serif}.p-story p{color:#ffffffa0;font-size:8px}.p-benefits{display:grid;grid-template-columns:repeat(4,1fr);margin:10px 18px 0;border:1px solid #0002;background:var(--p-surface)}.p-benefits span{padding:16px;border-right:1px solid #0002;font-size:8px}.preview-page>footer{margin-top:48px;padding:24px;color:#ffffff90;background:var(--p-primary);font-size:8px}
.preview-vibrant .p-hero{border:2px solid var(--p-ink);border-radius:2px;box-shadow:6px 6px 0 var(--p-ink)}.preview-vibrant .p-hero h1{font-family:sans-serif;font-weight:900;text-transform:uppercase}.preview-vibrant .p-hero button{border-radius:0}.preview-vibrant .p-products article i,.preview-vibrant .p-categories span,.preview-vibrant .p-links span{border:2px solid var(--p-ink);border-radius:2px;box-shadow:3px 3px 0 var(--p-ink)}
.preview-minimal .p-hero{border-radius:0;background:white}.preview-minimal .p-hero h1{font-family:sans-serif;font-weight:400}.preview-minimal .p-hero button{border-radius:0}.preview-minimal .p-categories,.preview-minimal .p-products{margin-top:72px}.preview-minimal .p-products article i{border-radius:0;background:#eee}.preview-minimal .p-story{border-radius:0;background:#181818}
.device-mobile .preview-page>header{padding:0 14px}.device-mobile .preview-page>header nav{display:none}.device-mobile .p-hero{min-height:480px;display:block;position:relative}.device-mobile .p-hero>div{position:relative;z-index:2;padding:30px 20px}.device-mobile .p-hero h1{font-size:43px}.device-mobile .p-hero aside{position:absolute;inset:52% 0 0}.device-mobile .p-hero aside span{font-size:90px}.device-mobile .p-categories>div{overflow:hidden}.device-mobile .p-categories span{min-width:120px}.device-mobile .p-products>div{grid-template-columns:1fr 1fr}.device-mobile .p-story{grid-template-columns:1fr}.device-mobile .p-story>i{min-height:160px}.device-mobile .p-benefits{grid-template-columns:1fr 1fr}
</style>
