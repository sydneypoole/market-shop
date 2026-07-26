<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, money } from '../api'
import ProductMedia from '../components/ProductMedia.vue'
import { useShopStore } from '../stores/shop'
import heroImage from '../assets/storefront-hero.webp'

type Content = {
  id: number
  type: string
  title: string
  summary: string
  coverUrl?: string
  targetUrl?: string
}

const shop = useShopStore()
const contents = ref<Content[]>([])
const error = ref('')
const scene = ref<'ALL' | 'UPGRADE' | 'REPURCHASE'>('ALL')

const visibleProducts = computed(() =>
  scene.value === 'ALL'
    ? shop.products
    : shop.products.filter(product => product.salesScene === scene.value)
)
const upgradeCount = computed(() => shop.products.filter(product => product.salesScene === 'UPGRADE').length)
const repurchaseCount = computed(() => shop.products.filter(product => product.salesScene === 'REPURCHASE').length)

async function load() {
  error.value = ''
  const results = await Promise.allSettled([
    shop.loadProducts(),
    api<Content[]>('/content').then(data => {
      contents.value = data
    })
  ])
  const failed = results.find(result => result.status === 'rejected')
  if (failed?.status === 'rejected') {
    error.value = failed.reason instanceof Error ? failed.reason.message : '商城内容加载失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="page home">
    <section class="hero" aria-labelledby="hero-title">
      <img :src="heroImage" alt="" fetchpriority="high" />
      <div class="hero-wash" aria-hidden="true"></div>
      <div class="hero-copy">
        <span class="edition">SHIGUANG / CURATED GOODS / 2026</span>
        <h1 id="hero-title">认真挑选，<br /><em>让日常值得期待。</em></h1>
        <p>从一件好物开始，体验清晰、克制而安心的会员购物旅程。</p>
        <div class="hero-actions">
          <a class="primary hero-primary" href="#products">探索本期精选</a>
          <RouterLink class="hero-link" to="/rules">了解会员礼遇 <span>↗</span></RouterLink>
        </div>
      </div>
      <div class="hero-note">
        <span>01</span>
        <p><b>线下确认</b>订单提交后不跳转支付，由直属上级核对收款。</p>
      </div>
    </section>

    <section class="service-strip" aria-label="商城服务说明">
      <div><i aria-hidden="true">✦</i><span><b>精品选物</b><small>后台商品实时上新</small></span></div>
      <div><i aria-hidden="true">◎</i><span><b>安心履约</b><small>确认、审核、发货全程留痕</small></span></div>
      <div><i aria-hidden="true">↺</i><span><b>售后可查</b><small>进度与凭证集中管理</small></span></div>
      <div><i aria-hidden="true">◇</i><span><b>规则透明</b><small>等级与任务读取当前配置</small></span></div>
    </section>

    <p v-if="error" class="error home-error" role="alert">
      {{ error }}
      <button type="button" @click="load">重新加载</button>
    </p>

    <RouterLink v-if="contents.length" class="announcement" to="/rules">
      <span class="announcement-label">NEW</span>
      <b>{{ contents[0]?.title || '商城公告' }}</b>
      <p>{{ contents[0]?.summary }}</p>
      <span class="announcement-arrow">阅读全文 →</span>
    </RouterLink>

    <section class="collection-intro">
      <div>
        <span class="eyebrow">Curated collections</span>
        <h2>不是更多，<br />而是更值得。</h2>
      </div>
      <div class="collection-copy">
        <p>围绕成长任务与品质复购，呈现当前正在销售的商品。价格、库存和任务资格都以后端实时数据为准。</p>
        <div class="collection-stats">
          <span><b>{{ shop.products.length }}</b>全部在售</span>
          <span><b>{{ upgradeCount }}</b>成长精选</span>
          <span><b>{{ repurchaseCount }}</b>日常复购</span>
        </div>
      </div>
    </section>

    <section id="products" class="products-section">
      <div class="product-toolbar">
        <div class="scene-tabs" role="tablist" aria-label="商品分类">
          <button :class="{ active: scene === 'ALL' }" role="tab" :aria-selected="scene === 'ALL'" @click="scene = 'ALL'">
            全部商品
          </button>
          <button :class="{ active: scene === 'UPGRADE' }" role="tab" :aria-selected="scene === 'UPGRADE'" @click="scene = 'UPGRADE'">
            成长精选
          </button>
          <button :class="{ active: scene === 'REPURCHASE' }" role="tab" :aria-selected="scene === 'REPURCHASE'" @click="scene = 'REPURCHASE'">
            日常复购
          </button>
        </div>
        <span>{{ visibleProducts.length }} 件好物</span>
      </div>

      <div v-if="shop.loadingProducts" class="product-loading" aria-busy="true" aria-label="正在加载精选商品">
        <div v-for="index in 4" :key="index"><i></i><b></b><span></span></div>
      </div>
      <div v-else-if="visibleProducts.length" class="product-grid">
        <RouterLink
          v-for="(product, index) in visibleProducts"
          :key="product.productId"
          class="product-card"
          :class="{ featured: index === 0 && scene === 'ALL' }"
          :to="`/products/${product.productId}`"
        >
          <div class="product-visual">
            <ProductMedia
              :src="product.coverUrl"
              :alt="product.name"
              :scene="product.salesScene"
              :ratio="index === 0 && scene === 'ALL' ? 'landscape' : 'portrait'"
              :eager="index < 2"
            />
            <span class="scene-badge">{{ product.salesScene === 'UPGRADE' ? '会员成长' : '品质复购' }}</span>
            <span class="view-product" aria-hidden="true">↗</span>
          </div>
          <div class="product-copy">
            <div>
              <h3>{{ product.name }}</h3>
              <p>{{ product.subtitle || '精选品质好物，为日常带来恰到好处的愉悦。' }}</p>
            </div>
            <div class="product-meta">
              <span class="price">{{ money(product.priceFen) }}</span>
              <small>{{ product.skuName }} · 库存 {{ product.inventory }}</small>
            </div>
          </div>
        </RouterLink>
      </div>
      <div v-else class="empty card">
        <h2>这一辑还在准备中</h2>
        <p>运营人员发布商品后会立即出现在这里。</p>
      </div>
    </section>

    <section class="member-editorial">
      <div class="member-number" aria-hidden="true">01</div>
      <div class="member-copy">
        <span class="eyebrow">Membership, reimagined</span>
        <h2>每一次成长，<br />都有规则可循。</h2>
        <p>会员等级、升级任务和演示积分均读取后台当前生效版本；系统不提供提现、转账或现金兑换。</p>
        <RouterLink class="editorial-link" to="/membership">进入会员中心 <span>→</span></RouterLink>
      </div>
      <div class="member-seal">
        <span>MEMBER</span>
        <b>拾光</b>
        <small>ONE-LEVEL RELATION</small>
      </div>
    </section>

    <section class="process">
      <header>
        <span class="eyebrow">A calm order journey</span>
        <h2>买得清楚，也等得安心。</h2>
        <p>这里没有在线付款按钮。订单从提交到收货，每一步都有明确的处理人和状态记录。</p>
      </header>
      <ol>
        <li><span>01</span><div><b>提交订单</b><small>确认商品与收货信息</small></div></li>
        <li><span>02</span><div><b>上级确认</b><small>核对线下收款事实</small></div></li>
        <li><span>03</span><div><b>平台审核</b><small>复核订单与可选凭证</small></div></li>
        <li><span>04</span><div><b>仓库发货</b><small>录入承运商和物流单号</small></div></li>
        <li><span>05</span><div><b>确认收货</b><small>完成后再计算会员任务</small></div></li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.home { padding-top: 24px; }
.hero {
  min-height: min(760px, calc(100vh - 112px));
  position: relative;
  overflow: hidden;
  border-radius: 30px;
  background: #e8dfd0;
  box-shadow: var(--shadow-lg);
  isolation: isolate;
}
.hero > img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: center; }
.hero-wash { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(247, 239, 225, .98) 0%, rgba(247, 239, 225, .88) 34%, rgba(247, 239, 225, .12) 64%); }
.hero-copy { position: relative; z-index: 2; width: min(620px, 53%); padding: clamp(80px, 10vw, 146px) 0 70px clamp(38px, 6vw, 86px); }
.edition { color: var(--green); font-size: 9px; font-weight: 850; letter-spacing: .27em; }
.hero h1 { margin: 28px 0 24px; font: 650 clamp(54px, 7.1vw, 96px)/.98 var(--font-display); letter-spacing: -.075em; }
.hero h1 em { color: var(--coral); font-style: normal; }
.hero-copy > p { max-width: 450px; margin: 0; color: #5f655f; font: 500 17px/1.9 var(--font-display); }
.hero-actions { display: flex; align-items: center; gap: 28px; margin-top: 36px; }
.hero-primary { display: inline-flex; align-items: center; min-height: 54px; padding: 0 26px; }
.hero-link { padding: 12px 0; border-bottom: 1px solid #9ca59d; font-size: 13px; font-weight: 740; }
.hero-link span { margin-left: 14px; }
.hero-note { position: absolute; z-index: 3; right: 28px; bottom: 28px; width: 270px; display: grid; grid-template-columns: auto 1fr; gap: 14px; padding: 18px; color: white; border: 1px solid rgba(255,255,255,.18); border-radius: 18px; background: rgba(20, 50, 39, .88); backdrop-filter: blur(14px); }
.hero-note > span { color: #eab99e; font: 650 28px/1 var(--font-display); }
.hero-note p { margin: 0; font-size: 11px; line-height: 1.65; }
.hero-note b { display: block; margin-bottom: 3px; font-size: 12px; }

.service-strip { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; margin: 18px 0 0; overflow: hidden; border: 1px solid var(--line); border-radius: 18px; background: var(--line); }
.service-strip > div { display: flex; align-items: center; gap: 13px; min-height: 82px; padding: 15px 20px; background: rgba(255, 254, 250, .88); }
.service-strip i { color: var(--coral); font: normal 22px var(--font-display); }
.service-strip span, .service-strip b, .service-strip small { display: block; }
.service-strip b { font-size: 12px; }
.service-strip small { color: var(--muted); margin-top: 4px; font-size: 10px; }
.home-error { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 18px 0; }
.home-error button { padding: 0; color: inherit; border: 0; border-bottom: 1px solid currentColor; background: none; }
.announcement { display: grid; grid-template-columns: auto auto 1fr auto; align-items: center; gap: 18px; margin: 18px 0 0; padding: 15px 18px; border: 1px solid var(--line); border-radius: 14px; background: rgba(255, 254, 250, .75); }
.announcement-label { padding: 5px 8px; color: white; border-radius: 6px; background: var(--coral); font-size: 9px; font-weight: 800; letter-spacing: .14em; }
.announcement b { font-size: 12px; }
.announcement p { margin: 0; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.announcement-arrow { color: var(--green); font-size: 11px; font-weight: 750; }

.collection-intro { display: grid; grid-template-columns: 1fr 1fr; gap: 90px; align-items: end; padding: 120px 3% 64px; }
.collection-intro h2 { margin: 12px 0 0; font: 650 clamp(46px, 6vw, 78px)/1.05 var(--font-display); letter-spacing: -.07em; }
.collection-copy > p { max-width: 560px; margin: 0; color: var(--muted); line-height: 1.9; }
.collection-stats { display: flex; gap: 44px; margin-top: 34px; }
.collection-stats span { color: var(--muted); font-size: 10px; letter-spacing: .08em; }
.collection-stats b { display: block; margin-bottom: 4px; color: var(--ink); font: 650 28px var(--font-display); letter-spacing: -.04em; }
.product-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; border-bottom: 1px solid var(--line-strong); }
.scene-tabs { display: flex; gap: 28px; }
.scene-tabs button { position: relative; padding: 14px 0 16px; color: var(--muted); border: 0; background: none; font-size: 13px; font-weight: 680; }
.scene-tabs button::after { content: ""; position: absolute; left: 0; right: 100%; bottom: -1px; height: 2px; background: var(--green); transition: right .25s; }
.scene-tabs button.active { color: var(--ink); }
.scene-tabs button.active::after { right: 0; }
.product-toolbar > span { color: var(--muted); font-size: 11px; }
.product-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 42px 18px; }
.product-card { min-width: 0; }
.product-card.featured { grid-column: span 2; }
.product-visual { position: relative; overflow: hidden; border-radius: 4px 22px 4px 22px; background: #e9e4da; }
.scene-badge { position: absolute; left: 14px; top: 14px; padding: 7px 10px; color: var(--ink); border-radius: 99px; background: rgba(255,255,250,.9); backdrop-filter: blur(10px); font-size: 9px; font-weight: 800; letter-spacing: .1em; }
.view-product { position: absolute; right: 14px; bottom: 14px; display: grid; place-items: center; width: 42px; height: 42px; color: white; border-radius: 50%; background: rgba(18,58,46,.88); font-size: 18px; transition: transform .25s; }
.product-card:hover .view-product { transform: rotate(45deg); }
.product-copy { display: flex; justify-content: space-between; gap: 18px; padding: 17px 4px 0; }
.product-copy h3 { margin: 0 0 7px; font: 650 20px/1.35 var(--font-display); letter-spacing: -.03em; }
.product-copy p { max-width: 380px; margin: 0; color: var(--muted); font-size: 11px; line-height: 1.6; }
.product-meta { flex: none; text-align: right; }
.product-meta .price { display: block; font-size: 18px; }
.product-meta small { display: block; margin-top: 7px; color: var(--muted); font-size: 9px; }
.product-loading { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; }
.product-loading > div { display: grid; gap: 12px; }
.product-loading i { display: block; aspect-ratio: 4/5; border-radius: 4px 22px; background: linear-gradient(100deg,#e8e6df 20%,#f6f5ef 40%,#e8e6df 60%); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
.product-loading b, .product-loading span { display: block; height: 12px; width: 70%; border-radius: 20px; background: #e3e1d9; }
.product-loading span { width: 42%; }

.member-editorial { position: relative; min-height: 500px; margin-top: 140px; display: grid; grid-template-columns: .7fr 1.4fr 1fr; align-items: center; gap: 34px; padding: 54px 7%; overflow: hidden; color: white; border-radius: 30px; background: var(--green-dark); }
.member-editorial::after { content: ""; position: absolute; width: 420px; height: 420px; right: -120px; top: -150px; border: 1px solid rgba(255,255,255,.16); border-radius: 50%; box-shadow: 0 0 0 60px rgba(255,255,255,.025), 0 0 0 120px rgba(255,255,255,.02); }
.member-number { align-self: start; color: #d9a17e; font: 600 96px/1 var(--font-display); opacity: .7; }
.member-copy { position: relative; z-index: 2; }
.member-copy .eyebrow { color: #e7aa86; }
.member-copy h2 { margin: 18px 0 22px; font: 620 clamp(42px, 5vw, 68px)/1.08 var(--font-display); letter-spacing: -.07em; }
.member-copy p { max-width: 580px; color: rgba(255,255,255,.66); line-height: 1.85; }
.editorial-link { display: inline-flex; align-items: center; gap: 40px; margin-top: 24px; padding-bottom: 10px; border-bottom: 1px solid rgba(255,255,255,.4); font-size: 12px; font-weight: 700; }
.member-seal { position: relative; z-index: 2; aspect-ratio: 1; display: grid; place-items: center; align-content: center; gap: 7px; color: #1f493b; border-radius: 50%; background: #ece5d1; box-shadow: inset 0 0 0 12px #ece5d1, inset 0 0 0 13px rgba(29,81,63,.28); transform: rotate(5deg); }
.member-seal span, .member-seal small { font-size: 8px; font-weight: 800; letter-spacing: .23em; }
.member-seal b { font: 650 clamp(44px, 6vw, 72px)/1 var(--font-display); letter-spacing: -.08em; }

.process { margin-top: 120px; display: grid; grid-template-columns: .85fr 1.15fr; gap: 90px; padding: 70px 3% 40px; border-top: 1px solid var(--line-strong); }
.process header h2 { margin: 16px 0 20px; font: 650 clamp(42px, 5vw, 68px)/1.1 var(--font-display); letter-spacing: -.06em; }
.process header p { max-width: 460px; color: var(--muted); line-height: 1.85; }
.process ol { margin: 0; padding: 0; list-style: none; }
.process li { display: grid; grid-template-columns: 48px 1fr; gap: 18px; padding: 20px 0; border-bottom: 1px solid var(--line); }
.process li > span { color: var(--coral); font: 650 18px var(--font-display); }
.process li b, .process li small { display: block; }
.process li b { font: 650 18px var(--font-display); }
.process li small { margin-top: 6px; color: var(--muted); }
@keyframes shimmer { to { background-position: -200% 0; } }

@media (max-width: 900px) {
  .service-strip { grid-template-columns: 1fr 1fr; }
  .collection-intro { gap: 45px; }
  .product-grid { grid-template-columns: 1fr 1fr; }
  .product-card.featured { grid-column: span 2; }
  .member-editorial { grid-template-columns: .4fr 1.2fr .8fr; }
  .process { gap: 46px; }
}

@media (max-width: 700px) {
  .home { padding-top: 12px; }
  .hero { min-height: 680px; border-radius: 22px; }
  .hero > img { object-position: 66% center; }
  .hero-wash { background: linear-gradient(180deg, rgba(245,236,221,.96) 0%, rgba(245,236,221,.78) 46%, rgba(23,57,47,.06) 72%); }
  .hero-copy { width: 100%; padding: 52px 24px 0; }
  .edition { font-size: 8px; }
  .hero h1 { margin-top: 22px; font-size: clamp(48px, 15vw, 68px); }
  .hero-copy > p { max-width: 310px; font-size: 14px; }
  .hero-actions { align-items: flex-start; flex-direction: column; gap: 12px; margin-top: 26px; }
  .hero-primary { min-height: 50px; }
  .hero-note { left: 14px; right: 14px; bottom: 14px; width: auto; }
  .service-strip { grid-template-columns: 1fr 1fr; }
  .service-strip > div { min-height: 74px; padding: 12px; }
  .service-strip i { display: none; }
  .announcement { grid-template-columns: auto 1fr auto; gap: 10px; }
  .announcement b { display: none; }
  .announcement-arrow { font-size: 0; }
  .announcement-arrow::after { content: "→"; font-size: 14px; }
  .collection-intro { grid-template-columns: 1fr; gap: 28px; padding: 82px 4px 42px; }
  .collection-intro h2 { font-size: 52px; }
  .collection-stats { gap: 24px; justify-content: space-between; }
  .collection-stats b { font-size: 24px; }
  .product-toolbar { align-items: flex-end; }
  .scene-tabs { gap: 18px; overflow: auto; }
  .scene-tabs button { white-space: nowrap; font-size: 12px; }
  .product-toolbar > span { display: none; }
  .product-grid, .product-loading { grid-template-columns: 1fr 1fr; gap: 28px 10px; }
  .product-card.featured { grid-column: span 2; }
  .product-copy { display: block; padding-top: 12px; }
  .product-copy h3 { font-size: 16px; }
  .product-copy p { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
  .product-meta { margin-top: 12px; text-align: left; }
  .product-meta small { display: none; }
  .product-meta .price { font-size: 17px; }
  .scene-badge { left: 8px; top: 8px; padding: 6px 8px; font-size: 8px; }
  .view-product { right: 8px; bottom: 8px; width: 34px; height: 34px; }
  .member-editorial { min-height: 640px; margin-top: 90px; grid-template-columns: 1fr; align-content: start; padding: 40px 26px; }
  .member-number { font-size: 62px; }
  .member-copy h2 { font-size: 48px; }
  .member-seal { width: 200px; justify-self: end; margin-top: 8px; }
  .process { margin-top: 80px; grid-template-columns: 1fr; gap: 32px; padding: 52px 4px 20px; }
  .process header h2 { font-size: 46px; }
}
</style>
