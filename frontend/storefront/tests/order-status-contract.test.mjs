import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { orderStatusLabel, statusText } from '../src/order-status.ts'

const source = path => readFile(new URL(path, import.meta.url), 'utf8')

test('pending superior status is canonical across domain, policies, KPI and both web clients', async () => {
  const legacy = ['PENDING', 'SUPERIOR', 'CONFIRMATION'].join('_')
  const paths = [
    '../../../backend/shop-domain/src/main/java/com/marketshop/domain/trade/OrderStatus.java',
    '../../../backend/shop-application/src/main/java/com/marketshop/application/proof/OrderProofApplicationService.java',
    '../../../backend/shop-infrastructure/src/main/java/com/marketshop/infrastructure/commerce/MyBatisOrderOperationsAdapter.java',
    '../src/order-status.ts',
    '../src/views/OrdersView.vue',
    '../src/views/OrderDetailView.vue',
    '../../admin/src/localization.ts'
  ]
  const layers = await Promise.all(paths.map(source))

  for (const [index, content] of layers.entries()) {
    assert.ok(content.includes('PENDING_SUPERIOR'), `${paths[index]} must use the canonical status`)
    assert.ok(!content.includes(legacy), `${paths[index]} still contains the legacy status`)
  }
})

test('unknown order statuses remain read-only and use safe localized fallbacks', async () => {
  const [statusModule, orders, detail, adminLocalization] = await Promise.all([
    source('../src/order-status.ts'),
    source('../src/views/OrdersView.vue'),
    source('../src/views/OrderDetailView.vue'),
    source('../../admin/src/localization.ts')
  ])

  assert.match(statusModule, /orderStatusLabel.+未知订单状态/)
  assert.match(orders, /orderStatusLabel\(order\.status\)/)
  assert.match(detail, /orderStatusLabel\(detail\.order\.status\)/)
  assert.match(adminLocalization, /orderStatusLabel.+未知订单状态/)
  assert.doesNotMatch(orders, /status\.startsWith\(|status\.includes\('PENDING'/)
  assert.equal(orderStatusLabel('PENDING_SUPERIOR'), '待上级确认')
  assert.equal(orderStatusLabel('FUTURE_ORDER_STATE'), '未知订单状态')
  assert.equal(Object.hasOwn(statusText, 'FUTURE_ORDER_STATE'), false)
})
