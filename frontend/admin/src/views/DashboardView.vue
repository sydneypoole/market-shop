<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, adminErrorMessage, money } from '../api'
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
  <div>
    <PageHeader title="业务概览" description="线下订单、会员、履约和售后的实时工作队列。">
      <template #actions><StatusTag tone="success" label="服务端实时数据" /></template>
    </PageHeader>

    <InlineAlert v-if="pageError" title="工作台加载失败" :message="pageError" retryable @retry="load" />
    <section v-else-if="pageLoading" class="dashboard-loading card" aria-busy="true" role="status">
      <span class="state-spinner" aria-hidden="true"></span>正在读取运营指标…
    </section>
    <template v-else-if="data">
      <section class="metrics" aria-label="核心运营指标">
        <article class="card"><span>会员总数</span><b>{{ data.memberCount }}</b><small>商城会员账号</small></article>
        <article class="card"><span>今日订单</span><b>{{ data.todayOrderCount }}</b><small>含全部状态</small></article>
        <article class="card"><span>今日完成订单额</span><b>{{ money(data.todayCompletedAmountFen) }}</b><small>线下订单统计，不代表在线收款</small></article>
        <article class="card"><span>在售商品</span><b>{{ data.onSaleProductCount }}</b><small>低库存 {{ data.lowInventorySkuCount }} 个</small></article>
      </section>
      <section class="dashboard-grid">
        <article class="card task-card">
          <h2>工作队列</h2>
          <RouterLink v-if="can('order:review')" :to="{ path: '/orders', query: { status: 'PENDING_ADMIN_REVIEW' } }">
            <span class="queue-icon coral" aria-hidden="true">审</span><div><b>等待后台审核</b><small>进入详情核对订单、凭证和时间线</small></div><strong>{{ data.pendingReviewCount }} →</strong>
          </RouterLink>
          <RouterLink v-if="can('order:ship')" :to="{ path: '/orders', query: { status: 'PENDING_SHIPMENT' } }">
            <span class="queue-icon green" aria-hidden="true">发</span><div><b>等待仓库发货</b><small>进入已应用“待发货”筛选</small></div><strong>{{ data.pendingShipCount }} →</strong>
          </RouterLink>
          <RouterLink v-if="can('aftersale:review')" :to="{ path: '/after-sales', query: { status: 'PENDING_ADMIN_REVIEW' } }">
            <span class="queue-icon gold" aria-hidden="true">售</span><div><b>进行中售后</b><small>在完整凭证与退货上下文中处理</small></div><strong>{{ data.activeAfterSaleCount }} →</strong>
          </RouterLink>
          <p v-if="!can('order:review') && !can('order:ship') && !can('aftersale:review')" class="empty-copy">当前岗位没有待处理队列。</p>
        </article>
        <article class="card boundary">
          <span>合规边界</span><h2>积分不是现金。</h2>
          <p>积分禁止提现、转账或兑换现金。奖励关系深度固定为一层；订单首次完成后才生成任务证据。</p>
          <RouterLink v-if="can('rule:publish')" to="/rules">查看规则版本 →</RouterLink>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dashboard-loading{min-height:240px;display:flex;align-items:center;justify-content:center;gap:12px;color:var(--color-text-muted)}
.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.metrics article{padding:20px}.metrics span,.metrics b,.metrics small{display:block}.metrics span{color:var(--color-text-muted)}.metrics b{margin:9px 0;font:750 28px serif}.metrics small{color:#89948e;line-height:1.5}
.dashboard-grid{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(280px,.6fr);gap:16px;margin-top:16px}.task-card,.boundary{padding:24px}.task-card h2{margin-top:0;font-family:serif}.task-card a{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:12px;align-items:center;padding:15px 0;border-top:1px solid var(--color-border)}.task-card a:focus-visible{border-radius:8px}.task-card small{display:block;margin-top:4px;color:var(--color-text-muted)}.queue-icon{display:grid;place-items:center;width:40px;height:40px;border-radius:11px}.coral{background:#fbe6e1}.green{background:#e3efea}.gold{background:#fff0cf}.boundary{color:#fff;background:#31594e}.boundary>span,.boundary a{color:#f2b88f}.boundary p{color:#ffffffb8;line-height:1.8}.empty-copy{color:var(--color-text-muted)}
@media(max-width:1000px){.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.dashboard-grid{grid-template-columns:1fr}}@media(max-width:520px){.metrics{grid-template-columns:1fr}.task-card,.boundary{padding:18px}}
</style>
