<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, adminErrorMessage, money } from '../api'
import AdminIcon from '../components/admin/AdminIcon.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import { can } from '../session'

type Dashboard = {
  memberCount: number
  todayOrderCount: number
  todayCompletedAmountFen: number
  pendingSuperiorCount: number
  pendingReviewCount: number
  pendingShipCount: number
  activeAfterSaleCount: number
  onSaleProductCount: number
  lowInventorySkuCount: number
}

const data = ref<Dashboard>()
const pageLoading = ref(true)
const pageError = ref('')

async function load() {
  pageLoading.value = true
  pageError.value = ''
  try {
    data.value = await adminApi<Dashboard>('/dashboard')
  } catch (cause) {
    data.value = undefined
    pageError.value = adminErrorMessage(cause, '工作台加载失败')
  } finally {
    pageLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="dashboard-page">
    <PageHeader title="业务概览" description="线下订单、会员、履约和售后的实时工作队列。">
      <template #actions><StatusTag tone="success" label="服务端实时数据" /></template>
    </PageHeader>

    <InlineAlert v-if="pageError" title="工作台加载失败" :message="pageError" retryable @retry="load" />
    <section v-else-if="pageLoading" class="dashboard-loading card" aria-busy="true" role="status">
      <div v-for="index in 4" :key="index" class="metric-skeleton" aria-hidden="true"><span></span><b></b><small></small></div>
      <span class="sr-only">正在读取运营指标</span>
    </section>
    <template v-else-if="data">
      <section class="metrics" aria-label="核心运营指标">
        <article><span>会员总数</span><b>{{ data.memberCount }}</b><small>商城会员账号</small></article>
        <article><span>今日订单</span><b>{{ data.todayOrderCount }}</b><small>含全部状态</small></article>
        <article><span>今日完成订单额</span><b>{{ money(data.todayCompletedAmountFen) }}</b><small>线下订单统计，不代表在线收款</small></article>
        <article><span>在售商品</span><b>{{ data.onSaleProductCount }}</b><small>低库存 {{ data.lowInventorySkuCount }} 个</small></article>
      </section>
      <section class="dashboard-grid">
        <article class="card task-card">
          <h2>工作队列</h2>
          <RouterLink v-if="can('order:review')" :to="{ path: '/orders', query: { status: 'PENDING_ADMIN_REVIEW' } }">
            <span class="queue-icon" aria-hidden="true"><AdminIcon name="orders" :size="20" /></span><div><b>等待后台审核</b><small>进入详情核对订单、凭证和时间线</small></div><strong>{{ data.pendingReviewCount }}<AdminIcon name="arrow-right" :size="17" /></strong>
          </RouterLink>
          <RouterLink v-if="can('order:ship')" :to="{ path: '/orders', query: { status: 'PENDING_SHIPMENT' } }">
            <span class="queue-icon" aria-hidden="true"><AdminIcon name="catalog" :size="20" /></span><div><b>等待仓库发货</b><small>进入已应用“待发货”筛选</small></div><strong>{{ data.pendingShipCount }}<AdminIcon name="arrow-right" :size="17" /></strong>
          </RouterLink>
          <RouterLink v-if="can('aftersale:review')" :to="{ path: '/after-sales', query: { status: 'PENDING_ADMIN_REVIEW' } }">
            <span class="queue-icon" aria-hidden="true"><AdminIcon name="after-sales" :size="20" /></span><div><b>进行中售后</b><small>在完整凭证与退货上下文中处理</small></div><strong>{{ data.activeAfterSaleCount }}<AdminIcon name="arrow-right" :size="17" /></strong>
          </RouterLink>
          <p v-if="!can('order:review') && !can('order:ship') && !can('aftersale:review')" class="empty-copy">当前岗位没有待处理队列。</p>
        </article>
        <article class="card boundary">
          <span>合规边界</span><h2>积分不是现金。</h2>
          <p>积分禁止提现、转账或兑换现金。奖励关系深度固定为一层；订单首次完成后才生成任务证据。</p>
          <RouterLink v-if="can('rule:publish')" to="/rules">查看规则版本<AdminIcon name="arrow-right" :size="17" /></RouterLink>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page { container-type: inline-size; }
.dashboard-loading { min-height: 180px; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; padding: 0; overflow: hidden; background: var(--color-border); }
.metric-skeleton { display: grid; align-content: center; gap: 12px; min-height: 180px; padding: var(--space-5); background: var(--color-surface); }
.metric-skeleton span, .metric-skeleton b, .metric-skeleton small { display: block; border-radius: var(--radius-sm); background: #ece9ed; animation: skeleton-pulse 1.4s ease-in-out infinite; }
.metric-skeleton span { width: 46%; height: 10px; }.metric-skeleton b { width: 64%; height: 30px; }.metric-skeleton small { width: 74%; height: 9px; }
.metrics { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.metrics article { min-width: 0; padding: var(--space-5); background: var(--color-surface); }
.metrics span, .metrics b, .metrics small { display: block; }.metrics span { color: var(--color-text-muted); font-size: 12px; }.metrics b { margin: 9px 0; font-size: 28px; line-height: 1.15; letter-spacing: -.035em; }.metrics small { color: var(--color-text-muted); font-size: 11px; line-height: 1.5; }
.dashboard-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(280px, .55fr); gap: var(--space-4); margin-top: var(--space-4); }
.task-card, .boundary { padding: var(--space-5); box-shadow: none; }.task-card h2, .boundary h2 { margin: 0 0 var(--space-3); font-size: 18px; }.task-card a { display: grid; grid-template-columns: auto minmax(0,1fr) auto; gap: 12px; align-items: center; min-height: 70px; padding: 12px 0; border-top: 1px solid var(--color-border); transition: color .15s ease, background .15s ease; }.task-card a:hover { color: var(--color-brand); }.task-card a:focus-visible { border-radius: var(--radius-sm); }.task-card a > strong { display: flex; align-items: center; gap: 7px; font-size: 17px; }.task-card small { display: block; margin-top: 4px; color: var(--color-text-muted); font-size: 11px; }.queue-icon { display: grid; place-items: center; width: 38px; height: 38px; color: var(--color-brand); border: 1px solid #decfdb; border-radius: var(--radius-md); background: var(--color-brand-soft); }
.boundary { position: relative; overflow: hidden; color: #f7f3f7; background: #2a222b; border-color: #2a222b; }.boundary::after { content: ''; position: absolute; right: -52px; bottom: -52px; width: 150px; height: 150px; border: 1px solid rgba(208,168,207,.18); border-radius: 50%; }.boundary > span { color: #d0a8cf; font-size: 11px; font-weight: 760; }.boundary h2 { margin-top: 10px; font-size: 24px; }.boundary p { color: rgba(255,255,255,.66); line-height: 1.75; }.boundary a { position: relative; z-index: 1; display: inline-flex; align-items: center; gap: 7px; margin-top: 10px; color: #d8b4d3; font-weight: 720; }.boundary a:hover { color: #fff; }.empty-copy { color: var(--color-text-muted); }
@container (max-width: 900px) { .metrics, .dashboard-loading { grid-template-columns: repeat(2, minmax(0,1fr)); }.dashboard-grid { grid-template-columns: 1fr; } }
@container (max-width: 500px) { .metrics, .dashboard-loading { grid-template-columns: 1fr; }.task-card, .boundary { padding: var(--space-4); } }
@media (prefers-reduced-motion: reduce) { .metric-skeleton span, .metric-skeleton b, .metric-skeleton small { animation: none; } }
</style>
