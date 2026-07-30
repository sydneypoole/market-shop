<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { money } from '../api'
import type { Category, Product, StorefrontContent, StorefrontTemplate } from '../types'
import {
  parseTemplate,
  settingNumber,
  settingStrings,
  settingText,
  type TemplateSection
} from '../utils/template'
import ProductMedia from './ProductMedia.vue'

const props = defineProps<{
  template?: StorefrontTemplate
  products: Product[]
  contents: StorefrontContent[]
  categories: Category[]
  loading?: boolean
}>()

const query = ref('')
const selectedScene = ref<'ALL' | 'UPGRADE' | 'REPURCHASE'>('ALL')
const selectedCategory = ref<number>()
const visibleLimit = ref(8)
const parsed = computed(() => parseTemplate(props.template))
const sections = computed(() => parsed.value.sections.filter(section => section.enabled))
const announcements = computed(() => props.contents.filter(content => content.type === 'ANNOUNCEMENT'))
const quickEntries = computed(() => props.contents.filter(content => content.type === 'QUICK_ENTRY'))
const banner = computed(() => props.contents.find(content => content.type === 'BANNER'))

const filteredProducts = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  return props.products.filter(product => {
    const matchesQuery = !normalized
      || `${product.name} ${product.subtitle} ${product.categoryName}`.toLowerCase().includes(normalized)
    const matchesScene = selectedScene.value === 'ALL' || product.salesScene === selectedScene.value
    const matchesCategory = !selectedCategory.value || product.categoryId === selectedCategory.value
    return matchesQuery && matchesScene && matchesCategory
  })
})

watch([query, selectedScene, selectedCategory], () => {
  visibleLimit.value = 8
})

function productsFor(section: TemplateSection) {
  const configuredScene = settingText(section, 'scene', 'ALL')
  const limit = Math.max(1, Math.min(24, settingNumber(section, 'limit', 8)))
  const source = configuredScene === 'ALL'
    ? filteredProducts.value
    : filteredProducts.value.filter(product => product.salesScene === configuredScene)
  return source.slice(0, Math.max(limit, visibleLimit.value))
}

function contentFor(section: TemplateSection) {
  const type = settingText(section, 'contentType', 'HELP')
  return props.contents.find(content => content.type === type)
}

function safeLink(value: string | undefined, fallback = '/') {
  if (!value) return fallback
  if (value.startsWith('/') && !value.startsWith('//')) {
    return value === '/invitation' ? '/membership' : value
  }
  if (value.startsWith('#') || value.startsWith('https://')) return value
  return fallback
}

function contentLink(content: StorefrontContent) {
  return safeLink(content.targetUrl, `/content/${content.id}`)
}

function sectionContentLink(section: TemplateSection) {
  const content = contentFor(section)
  return content ? contentLink(content) : '/'
}

function productPrice(product: Product) {
  return product.minPriceFen === product.maxPriceFen
    ? money(product.minPriceFen)
    : `${money(product.minPriceFen)} 起`
}
</script>

<template>
  <div class="storefront-canvas" :class="`preset-${parsed.preset.toLowerCase()}`">
    <template v-for="section in sections" :key="section.id">
      <section v-if="section.type === 'ANNOUNCEMENT' && announcements.length" class="template-announcements" aria-label="商城公告">
        <span>商城公告</span>
        <div class="announcement-track">
          <a
            v-for="notice in announcements.slice(0, settingNumber(section, 'limit', 3))"
            :key="notice.id"
            :href="contentLink(notice)"
          >
            <b>{{ notice.title }}</b><small>{{ notice.summary }}</small>
          </a>
        </div>
      </section>

      <section v-else-if="section.type === 'HERO'" class="template-hero" :class="{ 'has-cover': banner?.coverUrl }">
        <div class="hero-art" aria-hidden="true">
          <img v-if="banner?.coverUrl" :src="banner.coverUrl" alt="" fetchpriority="high" />
          <span class="orb orb-one"></span>
          <span class="orb orb-two"></span>
          <span class="hero-monogram">{{ parsed.preset === 'VIBRANT' ? 'GO' : parsed.preset === 'MINIMAL' ? '01' : '拾' }}</span>
        </div>
        <div class="hero-content">
          <span class="section-kicker">{{ settingText(section, 'eyebrow', 'CURATED FOR YOU') }}</span>
          <h1>{{ banner?.title || settingText(section, 'title', '认真挑选，让日常值得期待。') }}</h1>
          <p>{{ banner?.summary || settingText(section, 'description', '从一件真正好用的物品开始。') }}</p>
          <div class="hero-actions">
            <a class="template-primary" :href="safeLink(settingText(section, 'primaryLink', '#products'))">
              {{ settingText(section, 'primaryLabel', '浏览精选') }}
            </a>
            <a v-if="banner" class="text-link" :href="contentLink(banner)">查看本期专题 <span>↗</span></a>
          </div>
        </div>
        <div class="hero-index" aria-hidden="true">
          <span>{{ parsed.name }}</span><b>{{ String(props.products.length).padStart(2, '0') }}</b>
        </div>
      </section>

      <section v-else-if="section.type === 'CATEGORY_NAV' && categories.length" class="template-categories">
        <header>
          <span class="section-kicker">DISCOVER</span>
          <h2>{{ settingText(section, 'title', '按分类探索') }}</h2>
        </header>
        <div class="category-row">
          <button :class="{ active: !selectedCategory }" type="button" @click="selectedCategory = undefined">
            <b>全部</b><small>{{ products.length }} 件商品</small>
          </button>
          <button
            v-for="(category, index) in categories"
            :key="category.id"
            :class="{ active: selectedCategory === category.id }"
            type="button"
            @click="selectedCategory = category.id"
          >
            <i>{{ String(index + 1).padStart(2, '0') }}</i>
            <b>{{ category.name }}</b>
            <small>{{ category.productCount }} 件商品</small>
          </button>
        </div>
      </section>

      <section v-else-if="section.type === 'QUICK_LINKS'" class="template-quick-links">
        <header><span class="section-kicker">SHORTCUTS</span><h2>{{ settingText(section, 'title', '快捷入口') }}</h2></header>
        <div>
          <a v-for="entry in quickEntries" :key="entry.id" :href="contentLink(entry)">
            <span>{{ entry.title }}</span><small>{{ entry.summary }}</small><b>↗</b>
          </a>
          <RouterLink v-if="!quickEntries.length" to="/membership"><span>会员中心</span><small>查看等级、邀请与任务进度</small><b>↗</b></RouterLink>
          <RouterLink v-if="!quickEntries.length" to="/rules"><span>规则说明</span><small>查看当前生效的会员规则</small><b>↗</b></RouterLink>
        </div>
      </section>

      <section v-else-if="section.type === 'PRODUCT_COLLECTION'" id="products" class="template-products">
        <header class="products-heading">
          <div>
            <span class="section-kicker">{{ settingText(section, 'eyebrow', 'THE COLLECTION') }}</span>
            <h2>{{ settingText(section, 'title', '本期精选') }}</h2>
          </div>
          <p>{{ settingText(section, 'description', '价格、规格与库存均以后端实时数据为准。') }}</p>
        </header>

        <div class="product-filters">
          <label>
            <span class="sr-only">搜索商品</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m16 16 5 5" /></svg>
            <input v-model="query" type="search" placeholder="搜索商品或分类" />
          </label>
          <div role="tablist" aria-label="销售场景">
            <button type="button" :class="{ active: selectedScene === 'ALL' }" @click="selectedScene = 'ALL'">全部</button>
            <button type="button" :class="{ active: selectedScene === 'UPGRADE' }" @click="selectedScene = 'UPGRADE'">成长精选</button>
            <button type="button" :class="{ active: selectedScene === 'REPURCHASE' }" @click="selectedScene = 'REPURCHASE'">品质复购</button>
          </div>
          <span>{{ filteredProducts.length }} 件</span>
        </div>

        <div v-if="loading" class="template-product-grid product-skeleton" aria-busy="true">
          <div v-for="index in 4" :key="index"><i></i><b></b><span></span></div>
        </div>
        <div
          v-else-if="productsFor(section).length"
          class="template-product-grid"
          :style="{ '--product-columns': String(Math.max(2, Math.min(4, settingNumber(section, 'columns', 4)))) }"
        >
          <RouterLink
            v-for="(product, index) in productsFor(section)"
            :key="product.productId"
            class="template-product-card"
            :class="{ 'card-featured': parsed.preset === 'EDITORIAL' && index === 0 }"
            :to="`/products/${product.productId}`"
          >
            <div class="product-image">
              <ProductMedia
                :src="product.coverUrl"
                :alt="product.name"
                :scene="product.salesScene"
                :ratio="parsed.preset === 'MINIMAL' ? 'portrait' : index === 0 && parsed.preset === 'EDITORIAL' ? 'landscape' : 'portrait'"
                :eager="index < 2"
              />
              <span>{{ product.salesScene === 'UPGRADE' ? '成长精选' : '品质复购' }}</span>
              <i aria-hidden="true">↗</i>
            </div>
            <div class="product-info">
              <div><h3>{{ product.name }}</h3><p>{{ product.subtitle || `${product.categoryName}精选商品` }}</p></div>
              <div class="product-price"><b>{{ productPrice(product) }}</b><small>{{ product.skuCount }} 种规格 · 库存 {{ product.inventory }}</small></div>
            </div>
          </RouterLink>
        </div>
        <div v-else class="template-empty">
          <b>暂时没有匹配的商品</b><p>请调整关键词、分类或销售场景。</p>
          <button type="button" @click="query = ''; selectedScene = 'ALL'; selectedCategory = undefined">清除筛选</button>
        </div>
        <button
          v-if="filteredProducts.length > productsFor(section).length"
          class="load-more"
          type="button"
          @click="visibleLimit += 8"
        >
          查看更多商品
        </button>
      </section>

      <section v-else-if="section.type === 'CONTENT_STORY' && contentFor(section)" class="template-story">
        <div class="story-image">
          <img v-if="contentFor(section)?.coverUrl" :src="contentFor(section)?.coverUrl" alt="" loading="lazy" />
          <span v-else aria-hidden="true">{{ parsed.preset === 'MINIMAL' ? 'S' : '拾' }}</span>
        </div>
        <div class="story-copy">
          <span class="section-kicker">OUR POINT OF VIEW</span>
          <h2>{{ contentFor(section)?.title }}</h2>
          <p>{{ contentFor(section)?.summary }}</p>
          <a :href="sectionContentLink(section)">阅读完整内容 <span>→</span></a>
        </div>
      </section>

      <section v-else-if="section.type === 'SERVICE_BENEFITS'" class="template-benefits" aria-label="商城服务">
        <div v-for="(item, index) in settingStrings(section, 'items', ['精选商品', '透明规格', '完整履约', '售后可查'])" :key="item">
          <span>{{ String(index + 1).padStart(2, '0') }}</span><b>{{ item }}</b>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.storefront-canvas{--preset-primary:var(--theme-primary,var(--green));--preset-accent:var(--theme-accent,var(--coral));--preset-canvas:var(--theme-canvas,var(--canvas));--preset-surface:var(--theme-surface,var(--paper));--preset-ink:var(--theme-ink,var(--ink));--preset-muted:var(--theme-muted,var(--muted));width:min(100%,1440px);margin:0 auto;padding:24px 30px 112px;color:var(--preset-ink)}
.section-kicker{display:block;color:var(--preset-accent);font-size:9px;font-weight:850;letter-spacing:.25em;text-transform:uppercase}
.template-announcements{display:grid;grid-template-columns:auto 1fr;gap:22px;align-items:center;min-height:48px;margin-bottom:12px;padding:0 18px;color:var(--preset-ink);border:1px solid color-mix(in srgb,var(--preset-ink) 14%,transparent);border-radius:calc(var(--theme-radius,20px) * .55);background:var(--preset-surface);overflow:hidden}
.template-announcements>span{font-size:10px;font-weight:850;letter-spacing:.14em}
.announcement-track{display:flex;gap:36px;overflow:auto;scrollbar-width:none}.announcement-track a{display:flex;gap:10px;align-items:center;min-width:max-content;font-size:11px}.announcement-track small{color:var(--preset-muted)}
.template-hero{position:relative;min-height:min(740px,calc(100vh - 120px));display:grid;grid-template-columns:minmax(0,1.02fr) minmax(360px,.98fr);overflow:hidden;border-radius:var(--theme-radius,24px);background:color-mix(in srgb,var(--preset-primary) 10%,var(--preset-canvas));isolation:isolate}
.hero-content{position:relative;z-index:2;align-self:center;padding:clamp(50px,8vw,116px)}
.hero-content h1{max-width:760px;margin:24px 0;font:680 clamp(55px,7vw,102px)/.97 var(--theme-heading,var(--font-display));letter-spacing:-.075em;text-wrap:balance}
.hero-content>p{max-width:520px;margin:0;color:var(--preset-muted);font-size:clamp(14px,1.3vw,18px);line-height:1.8}
.hero-actions{display:flex;align-items:center;gap:28px;margin-top:36px}.template-primary{display:inline-grid;place-items:center;min-height:54px;padding:0 27px;color:white;border-radius:999px;background:var(--preset-primary);font-size:12px;font-weight:800}.text-link{padding:12px 0;border-bottom:1px solid color-mix(in srgb,var(--preset-ink) 30%,transparent);font-size:12px;font-weight:750}.text-link span{margin-left:16px}
.hero-art{position:relative;min-height:100%;overflow:hidden;background:linear-gradient(145deg,color-mix(in srgb,var(--preset-accent) 22%,var(--preset-surface)),color-mix(in srgb,var(--preset-primary) 68%,#ffffff))}
.hero-art>img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.hero-art::after{content:"";position:absolute;inset:0;background:linear-gradient(130deg,transparent 35%,color-mix(in srgb,var(--preset-primary) 36%,transparent))}
.orb{position:absolute;border-radius:50%;filter:blur(1px)}.orb-one{width:55%;aspect-ratio:1;right:-8%;top:3%;background:color-mix(in srgb,var(--preset-accent) 65%,transparent)}.orb-two{width:66%;aspect-ratio:1;left:-15%;bottom:-30%;border:1px solid color-mix(in srgb,#fff 42%,transparent);box-shadow:0 0 0 60px color-mix(in srgb,#fff 6%,transparent)}
.hero-monogram{position:absolute;z-index:2;inset:0;display:grid;place-items:center;color:#fff;font:700 clamp(120px,18vw,280px)/1 var(--theme-heading,var(--font-display));opacity:.9}
.hero-index{position:absolute;z-index:3;right:24px;bottom:22px;display:flex;align-items:end;gap:18px;color:white}.hero-index span{max-width:140px;font-size:9px;letter-spacing:.12em;text-align:right}.hero-index b{font:650 38px var(--theme-heading,var(--font-display))}
.template-categories,.template-quick-links,.template-products,.template-story{margin-top:clamp(80px,11vw,150px)}
.template-categories header,.template-quick-links header{display:flex;align-items:end;justify-content:space-between;margin-bottom:28px}.template-categories h2,.template-quick-links h2{margin:0;font:670 clamp(34px,4vw,55px)/1.05 var(--theme-heading,var(--font-display));letter-spacing:-.055em}
.category-row{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px}.category-row button{min-height:128px;display:grid;align-content:space-between;justify-items:start;padding:18px;color:var(--preset-ink);border:1px solid color-mix(in srgb,var(--preset-ink) 15%,transparent);border-radius:calc(var(--theme-radius,20px) * .75);background:var(--preset-surface);text-align:left;transition:transform .2s,border-color .2s,background .2s}.category-row button:hover,.category-row button.active{transform:translateY(-3px);border-color:var(--preset-primary);background:color-mix(in srgb,var(--preset-primary) 7%,var(--preset-surface))}.category-row i{color:var(--preset-accent);font:normal 12px var(--theme-heading,var(--font-display))}.category-row b{font:650 19px var(--theme-heading,var(--font-display))}.category-row small{color:var(--preset-muted);font-size:10px}
.template-quick-links>div{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.template-quick-links a{position:relative;min-height:140px;display:grid;align-content:end;padding:20px;border-radius:calc(var(--theme-radius,20px) * .75);background:var(--preset-primary);color:white}.template-quick-links a:nth-child(even){background:var(--preset-accent)}.template-quick-links a span{font:650 20px var(--theme-heading,var(--font-display))}.template-quick-links a small{margin-top:7px;opacity:.7}.template-quick-links a b{position:absolute;right:18px;top:15px;font-size:20px}
.products-heading{display:grid;grid-template-columns:1fr 1fr;gap:60px;align-items:end;margin-bottom:42px}.products-heading h2{max-width:760px;margin:14px 0 0;font:670 clamp(48px,6vw,82px)/1.02 var(--theme-heading,var(--font-display));letter-spacing:-.07em}.products-heading>p{max-width:550px;margin:0;color:var(--preset-muted);line-height:1.85}
.product-filters{display:grid;grid-template-columns:minmax(210px,1fr) auto auto;gap:16px;align-items:center;margin-bottom:28px;padding:13px 0;border-top:1px solid color-mix(in srgb,var(--preset-ink) 18%,transparent);border-bottom:1px solid color-mix(in srgb,var(--preset-ink) 18%,transparent)}.product-filters label{display:flex;align-items:center;gap:10px}.product-filters svg{width:18px;fill:none;stroke:currentColor;stroke-width:1.7}.product-filters input{width:100%;min-height:42px;padding:0;border:0;background:transparent;outline:0}.product-filters>div{display:flex;gap:8px}.product-filters button{min-height:36px;padding:0 13px;color:var(--preset-muted);border:0;border-radius:99px;background:transparent;font-size:11px}.product-filters button.active{color:white;background:var(--preset-primary)}.product-filters>span{color:var(--preset-muted);font-size:10px}
.template-product-grid{display:grid;grid-template-columns:repeat(var(--product-columns,4),minmax(0,1fr));gap:46px 14px}.template-product-card{min-width:0}.template-product-card.card-featured{grid-column:span 2}.product-image{position:relative;overflow:hidden;border-radius:calc(var(--theme-radius,20px) * .65);background:color-mix(in srgb,var(--preset-primary) 10%,var(--preset-surface))}.product-image>span{position:absolute;left:12px;top:12px;padding:6px 9px;color:var(--preset-ink);border-radius:99px;background:color-mix(in srgb,var(--preset-surface) 90%,transparent);font-size:8px;font-weight:800;backdrop-filter:blur(12px)}.product-image>i{position:absolute;right:12px;bottom:12px;display:grid;place-items:center;width:38px;height:38px;color:white;border-radius:50%;background:var(--preset-primary);font-style:normal;transition:transform .2s}.template-product-card:hover .product-image>i{transform:rotate(45deg)}
.product-info{display:flex;justify-content:space-between;gap:14px;padding:15px 3px 0}.product-info h3{margin:0;font:650 18px/1.3 var(--theme-heading,var(--font-display));letter-spacing:-.025em}.product-info p{margin:7px 0 0;color:var(--preset-muted);font-size:10px;line-height:1.55}.product-price{flex:none;text-align:right}.product-price b{display:block;color:var(--preset-accent);font-size:17px}.product-price small{display:block;margin-top:7px;color:var(--preset-muted);font-size:8px}
.product-skeleton>div{display:grid;gap:10px}.product-skeleton i{display:block;aspect-ratio:4/5;border-radius:calc(var(--theme-radius,20px)*.65);background:linear-gradient(100deg,#e4e3dd 20%,#f6f5ef 40%,#e4e3dd 60%);background-size:200% 100%;animation:shimmer 1.4s infinite}.product-skeleton b,.product-skeleton span{height:11px;width:70%;border-radius:20px;background:#dfded8}.product-skeleton span{width:40%}
.template-empty{display:grid;justify-items:center;padding:80px 20px;border:1px solid color-mix(in srgb,var(--preset-ink) 14%,transparent);border-radius:var(--theme-radius,20px);text-align:center}.template-empty b{font:650 25px var(--theme-heading,var(--font-display))}.template-empty p{color:var(--preset-muted)}.template-empty button,.load-more{min-height:44px;padding:0 18px;border:1px solid var(--preset-primary);border-radius:99px;background:transparent;color:var(--preset-primary);font-weight:750}.load-more{display:block;margin:36px auto 0}
.template-story{min-height:580px;display:grid;grid-template-columns:1.15fr .85fr;overflow:hidden;border-radius:var(--theme-radius,24px);background:var(--preset-primary);color:white}.story-image{position:relative;min-height:480px;overflow:hidden;background:color-mix(in srgb,var(--preset-accent) 50%,var(--preset-primary))}.story-image img{width:100%;height:100%;object-fit:cover}.story-image span{position:absolute;inset:0;display:grid;place-items:center;font:700 clamp(160px,25vw,390px)/1 var(--theme-heading,var(--font-display));opacity:.28}.story-copy{align-self:center;padding:clamp(40px,7vw,100px)}.story-copy .section-kicker{color:color-mix(in srgb,var(--preset-accent) 70%,white)}.story-copy h2{margin:22px 0;font:650 clamp(45px,5vw,70px)/1.05 var(--theme-heading,var(--font-display));letter-spacing:-.06em}.story-copy p{color:#ffffffa8;line-height:1.8}.story-copy a{display:inline-flex;gap:40px;margin-top:24px;padding-bottom:10px;border-bottom:1px solid #ffffff66;font-size:12px;font-weight:750}
.template-benefits{display:grid;grid-template-columns:repeat(4,1fr);margin-top:18px;overflow:hidden;border:1px solid color-mix(in srgb,var(--preset-ink) 15%,transparent);border-radius:calc(var(--theme-radius,20px) * .6);background:var(--preset-surface)}.template-benefits>div{min-height:94px;display:flex;align-items:center;gap:13px;padding:18px;border-right:1px solid color-mix(in srgb,var(--preset-ink) 12%,transparent)}.template-benefits>div:last-child{border:0}.template-benefits span{color:var(--preset-accent);font:650 18px var(--theme-heading,var(--font-display))}.template-benefits b{font-size:11px}

.preset-vibrant{padding-top:14px}.preset-vibrant .template-hero{min-height:680px;border:2px solid var(--preset-ink);box-shadow:12px 12px 0 var(--preset-ink);background:var(--preset-canvas)}.preset-vibrant .hero-content h1{text-transform:uppercase;font-weight:900;letter-spacing:-.08em}.preset-vibrant .hero-art{border-left:2px solid var(--preset-ink);background:var(--preset-accent)}.preset-vibrant .template-primary{color:var(--preset-canvas);border:2px solid var(--preset-ink);border-radius:0;background:var(--preset-ink);box-shadow:5px 5px 0 var(--preset-accent)}.preset-vibrant .category-row button,.preset-vibrant .product-image,.preset-vibrant .template-quick-links a{border:2px solid var(--preset-ink);border-radius:4px;box-shadow:5px 5px 0 var(--preset-ink)}.preset-vibrant .template-product-grid{gap:34px 16px}.preset-vibrant .product-info h3{font-family:var(--font-display);font-weight:800}.preset-vibrant .product-image>i{border-radius:4px;background:var(--preset-accent)}
.preset-minimal{max-width:1540px}.preset-minimal .template-hero{min-height:760px;border-radius:0;background:var(--preset-surface)}.preset-minimal .hero-content{padding-left:5vw}.preset-minimal .hero-content h1{font-weight:420;letter-spacing:-.06em}.preset-minimal .hero-art{background:#d9d9d5}.preset-minimal .hero-monogram{font-weight:300;opacity:.45}.preset-minimal .template-primary{border-radius:0}.preset-minimal .template-categories,.preset-minimal .template-quick-links,.preset-minimal .template-products,.preset-minimal .template-story{margin-top:180px}.preset-minimal .category-row{gap:0;border-top:1px solid #bbb}.preset-minimal .category-row button{min-height:100px;border:0;border-bottom:1px solid #bbb;border-radius:0;background:transparent}.preset-minimal .template-product-grid{gap:80px 28px}.preset-minimal .product-image{border-radius:0;background:#eee}.preset-minimal .product-image>span{display:none}.preset-minimal .product-image>i{border-radius:0;background:#111}.preset-minimal .product-info{display:block;padding-top:18px}.preset-minimal .product-price{margin-top:15px;text-align:left}.preset-minimal .template-story{border-radius:0;background:#171717}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@keyframes shimmer{to{background-position:-200% 0}}

@media(max-width:980px){
  .template-hero{grid-template-columns:1fr 1fr}.hero-content{padding:50px}.hero-content h1{font-size:65px}.template-product-grid{grid-template-columns:repeat(3,1fr)}.template-product-card.card-featured{grid-column:span 2}.template-quick-links>div{grid-template-columns:1fr 1fr}.template-story{grid-template-columns:1fr 1fr}.template-benefits{grid-template-columns:1fr 1fr}.template-benefits>div:nth-child(2){border-right:0}.template-benefits>div:nth-child(-n+2){border-bottom:1px solid color-mix(in srgb,var(--preset-ink) 12%,transparent)}
}
@media(max-width:700px){
  .storefront-canvas{padding:12px 14px 96px}.template-announcements{grid-template-columns:auto 1fr;margin:0 -2px 10px}.announcement-track{gap:24px}.announcement-track small{display:none}.template-hero{min-height:690px;display:block;border-radius:calc(var(--theme-radius,20px)*.8)}.hero-art{position:absolute;inset:48% 0 0}.hero-art::after{background:linear-gradient(180deg,var(--preset-canvas),transparent 35%)}.hero-content{padding:48px 24px}.hero-content h1{font-size:clamp(48px,14vw,66px)}.hero-content>p{max-width:330px;font-size:14px}.hero-actions{align-items:flex-start;flex-direction:column;gap:10px;margin-top:26px}.hero-index{display:none}.hero-monogram{font-size:150px}.template-categories,.template-quick-links,.template-products,.template-story,.preset-minimal .template-categories,.preset-minimal .template-quick-links,.preset-minimal .template-products,.preset-minimal .template-story{margin-top:86px}.template-categories header,.template-quick-links header{align-items:start;flex-direction:column;gap:8px}.category-row{display:flex;overflow:auto;padding:0 0 8px;scroll-snap-type:x mandatory}.category-row button{min-width:150px;scroll-snap-align:start}.template-quick-links>div{grid-template-columns:1fr}.template-quick-links a{min-height:112px}.products-heading{grid-template-columns:1fr;gap:18px;margin-bottom:26px}.products-heading h2{font-size:49px}.products-heading>p{font-size:12px}.product-filters{grid-template-columns:1fr auto}.product-filters>div{grid-column:1/-1;overflow:auto}.product-filters>span{grid-column:2;grid-row:1}.template-product-grid,.preset-minimal .template-product-grid{grid-template-columns:1fr 1fr!important;gap:32px 10px}.template-product-card.card-featured{grid-column:span 2}.product-info{display:block;padding-top:11px}.product-info h3{font-size:15px}.product-info p{display:-webkit-box;overflow:hidden;-webkit-line-clamp:2;-webkit-box-orient:vertical}.product-price{margin-top:10px;text-align:left}.product-price small{display:none}.product-image>i{width:32px;height:32px}.template-story{grid-template-columns:1fr}.story-image{min-height:340px}.story-copy{padding:42px 24px}.story-copy h2{font-size:46px}.template-benefits{grid-template-columns:1fr 1fr}.template-benefits>div{min-height:80px;padding:14px}.preset-vibrant .template-hero{box-shadow:6px 6px 0 var(--preset-ink)}.preset-vibrant .hero-art{border:0;border-top:2px solid var(--preset-ink)}.preset-minimal .template-hero{min-height:720px}.preset-minimal .hero-content{padding-left:20px}.preset-minimal .category-row{border:0}.preset-minimal .category-row button{border:1px solid #bbb}
}
</style>
