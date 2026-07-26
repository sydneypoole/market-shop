<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, money } from '../api'
import { useShopStore } from '../stores/shop'

type Content = { id: number; type: string; title: string; summary: string; targetUrl?: string }
const shop = useShopStore()
const contents = ref<Content[]>([])
const error = ref('')

onMounted(async () => {
  const results = await Promise.allSettled([
    shop.loadProducts(),
    api<Content[]>('/content').then(data => (contents.value = data))
  ])
  const failed = results.find(result => result.status === 'rejected')
  if (failed?.status === 'rejected') {
    error.value = failed.reason instanceof Error ? failed.reason.message : '商城内容加载失败'
  }
})
</script>

<template>
  <div class="page home">
    <p v-if="error" class="error">{{ error }}</p>
    <section class="hero card">
      <div class="hero-copy">
        <span class="eyebrow">Member Select · 2026</span>
        <h1>好商品，<br /><em>让信任慢慢发生。</em></h1>
        <p>提交订单后由直属上级确认线下收款，再经平台审核发货。没有在线支付，也没有积分提现。</p>
        <div class="hero-actions">
          <a class="primary" href="#products">开始选购</a>
          <RouterLink class="secondary" to="/rules">先看规则</RouterLink>
        </div>
        <div class="hero-metrics">
          <span><b>1 层</b>奖励关系上限</span>
          <span><b>全程</b>订单留痕</span>
          <span><b>0</b>在线支付入口</span>
        </div>
      </div>
      <div class="hero-art" aria-hidden="true">
        <div class="sun"></div>
        <div class="package package-a">拾光</div>
        <div class="package package-b">SELECT</div>
        <div class="leaf">⌇</div>
      </div>
    </section>

    <section v-if="contents.length" class="announcement">
      <b>商城公告</b>
      <span>{{ contents[0]?.summary }}</span>
      <RouterLink to="/rules">查看详情 →</RouterLink>
    </section>

    <section id="products">
      <div class="section-head">
        <div>
          <span class="eyebrow">Curated Collections</span>
          <h2>本期精选组合</h2>
          <p>升级任务与复购任务清晰分区，金额均以后端当前规则为准。</p>
        </div>
      </div>
      <div v-if="shop.loadingProducts" class="empty card" aria-busy="true">正在加载精选商品…</div>
      <div v-else class="product-grid">
        <RouterLink
          v-for="(product, index) in shop.products"
          :key="product.productId"
          class="product-card card"
          :to="`/products/${product.productId}`"
        >
          <div class="product-cover" :class="`cover-${index % 3}`">
            <span>{{ product.salesScene === 'UPGRADE' ? '成长任务' : '精选复购' }}</span>
            <b>{{ String(index + 1).padStart(2, '0') }}</b>
          </div>
          <div class="product-body">
            <span class="chip" :class="{ green: product.salesScene === 'REPURCHASE' }">
              {{ product.salesScene === 'UPGRADE' ? '升级专区' : '复购专区' }}
            </span>
            <h3>{{ product.name }}</h3>
            <p>{{ product.subtitle }}</p>
            <div class="product-meta">
              <span class="price">{{ money(product.priceFen) }}</span>
              <span class="muted">库存 {{ product.inventory }}</span>
            </div>
          </div>
        </RouterLink>
      </div>
      <div v-if="!shop.loadingProducts && !shop.products.length" class="empty card">暂时没有在售商品。</div>
    </section>

    <section class="process card">
      <div>
        <span class="eyebrow">Offline Settlement</span>
        <h2>五步完成一次安心订单</h2>
      </div>
      <ol>
        <li><b>01</b><span>用户提交订单<small>不发起在线支付</small></span></li>
        <li><b>02</b><span>直属上级确认<small>核对线下收款情况</small></span></li>
        <li><b>03</b><span>平台后台审核<small>审核订单与可选凭证</small></span></li>
        <li><b>04</b><span>仓库发货<small>录入承运商和运单</small></span></li>
        <li><b>05</b><span>确认收货<small>完成后才计算任务</small></span></li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.hero { min-height: 500px; display: grid; grid-template-columns: 1.08fr .92fr; overflow: hidden; background: #fffaf1; }
.hero-copy { padding: 64px 34px 48px 60px; position: relative; z-index: 2; }
.hero h1 { font-family: "Songti SC", serif; font-size: clamp(44px, 6vw, 76px); line-height: 1.05; margin: 20px 0; letter-spacing: -.05em; }
.hero h1 em { color: var(--coral); font-style: normal; }
.hero-copy > p { color: var(--muted); max-width: 560px; line-height: 1.8; }
.hero-actions { display: flex; gap: 12px; margin-top: 28px; }
.hero-actions a { display: inline-flex; align-items: center; }
.hero-metrics { display: flex; gap: 28px; margin-top: 48px; color: var(--muted); font-size: 12px; }
.hero-metrics span, .hero-metrics b { display: block; }
.hero-metrics b { color: var(--ink); font-size: 18px; margin-bottom: 3px; }
.hero-art { position: relative; min-height: 420px; background: #ebc77e; overflow: hidden; }
.sun { width: 280px; height: 280px; border-radius: 50%; position: absolute; right: -50px; top: -70px; background: #f45d48; }
.package { position: absolute; display: grid; place-items: center; color: #fff; font-family: serif; letter-spacing: .12em; box-shadow: 0 28px 44px rgba(63, 40, 20, .2); }
.package-a { width: 210px; height: 280px; left: 18%; top: 18%; background: #2b5146; border-radius: 20px 20px 8px 8px; transform: rotate(-7deg); font-size: 34px; }
.package-b { width: 160px; height: 210px; right: 10%; bottom: 9%; background: #f8efe0; border: 5px solid #fff; color: #bc4435; transform: rotate(10deg); }
.leaf { position: absolute; font-size: 190px; color: rgba(255,255,255,.55); left: -10px; bottom: -60px; transform: rotate(25deg); }
.announcement { display: grid; grid-template-columns: auto 1fr auto; gap: 16px; margin: 18px 0 10px; padding: 13px 18px; border-radius: 14px; background: #2d4f44; color: white; font-size: 14px; }
.announcement span { opacity: .78; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.product-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; }
.product-card { overflow: hidden; transition: transform .2s, box-shadow .2s; }
.product-card:hover { transform: translateY(-4px); box-shadow: 0 18px 42px rgba(73, 51, 34, .12); }
.product-cover { height: 218px; padding: 18px; display: flex; align-items: flex-end; justify-content: space-between; color: white; }
.product-cover b { font-family: serif; font-size: 70px; opacity: .35; line-height: .8; }
.cover-0 { background: linear-gradient(145deg, #f18e58, #bb3f32); }
.cover-1 { background: linear-gradient(145deg, #557869, #203f37); }
.cover-2 { background: linear-gradient(145deg, #d5a64a, #846221); }
.product-body { padding: 20px; }
.product-body h3 { margin: 12px 0 7px; }
.product-body p { color: var(--muted); min-height: 44px; margin: 0 0 18px; font-size: 14px; line-height: 1.6; }
.product-meta { display: flex; align-items: center; justify-content: space-between; }
.process { margin-top: 56px; padding: 32px; }
.process h2 { font-family: serif; margin: 8px 0 24px; }
.process ol { display: grid; grid-template-columns: repeat(5, 1fr); padding: 0; margin: 0; list-style: none; }
.process li { display: flex; gap: 10px; padding: 14px; border-left: 1px solid var(--line); }
.process li:first-child { border: 0; }
.process li > b { color: var(--coral); font-family: serif; }
.process li span, .process li small { display: block; }
.process li small { color: var(--muted); margin-top: 5px; line-height: 1.4; }
@media (max-width: 800px) {
  .hero { grid-template-columns: 1fr; min-height: 0; }
  .hero-copy { padding: 38px 24px 32px; }
  .hero h1 { font-size: 46px; }
  .hero-art { min-height: 310px; }
  .hero-metrics { gap: 16px; justify-content: space-between; }
  .product-grid { grid-template-columns: 1fr; }
  .product-cover { height: 180px; }
  .announcement { grid-template-columns: auto 1fr; }
  .announcement a { display: none; }
  .process { padding: 24px 18px; }
  .process ol { grid-template-columns: 1fr; }
  .process li, .process li:first-child { border: 0; border-top: 1px solid var(--line); }
}
</style>
