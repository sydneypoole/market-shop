import assert from 'node:assert/strict'
import test from 'node:test'

import { loadCommonJs, plain } from './helpers.mjs'

function recorder() {
  const calls = []
  const transport = {
    request(path, options) {
      const call = { transport: 'request', path }
      if (options !== undefined) {
        call.options = plain(options)
      }
      calls.push(call)
      return calls.at(-1)
    },
    uploadFile(path, filePath, formData) {
      const call = { transport: 'uploadFile', path, filePath }
      if (formData !== undefined) {
        call.formData = plain(formData)
      }
      calls.push(call)
      return calls.at(-1)
    }
  }
  return { calls, transport }
}

async function loadApi(relativePath) {
  const capture = recorder()
  const api = await loadCommonJs(`api/${relativePath}.js`, {
    requireMap: { '../utils/request': capture.transport }
  })
  return { api, calls: capture.calls }
}

test('authentication and public catalog wrappers keep the miniprogram HTTP contract', async () => {
  const auth = await loadApi('auth')
  auth.api.login('wx-code', 'INVITE-2026', 'SponsorClaimSecret2026StrongFixture')
  auth.api.me()
  auth.api.logout()
  assert.deepEqual(auth.calls, [
    {
      transport: 'request',
      path: '/auth/wechat/miniprogram/login',
      options: {
        method: 'POST',
        data: {
          code: 'wx-code',
          inviteCode: 'INVITE-2026',
          sponsorClaimSecret: 'SponsorClaimSecret2026StrongFixture'
        },
        auth: false
      }
    },
    { transport: 'request', path: '/auth/me' },
    { transport: 'request', path: '/auth/logout', options: { method: 'POST' } }
  ])

  const catalog = await loadApi('catalog')
  catalog.api.products()
  catalog.api.product(7)
  catalog.api.categories()
  catalog.api.contents()
  catalog.api.content(9)
  assert.deepEqual(catalog.calls, [
    { transport: 'request', path: '/catalog/products', options: { auth: false } },
    { transport: 'request', path: '/catalog/products/7', options: { auth: false } },
    { transport: 'request', path: '/catalog/categories', options: { auth: false } },
    { transport: 'request', path: '/content', options: { auth: false } },
    { transport: 'request', path: '/content/9', options: { auth: false } }
  ])
})

test('cart, address and order wrappers preserve methods and request bodies', async () => {
  const cart = await loadApi('cart')
  cart.api.list()
  cart.api.setItem(12, 4, false)
  assert.deepEqual(cart.calls, [
    { transport: 'request', path: '/cart' },
    {
      transport: 'request',
      path: '/cart/items/12',
      options: { method: 'PUT', data: { quantity: 4, selected: false } }
    }
  ])

  const address = await loadApi('address')
  const addressBody = { recipientName: '收货人', version: 2 }
  address.api.list()
  address.api.create(addressBody)
  address.api.update(3, addressBody)
  address.api.remove(3, 2)
  assert.deepEqual(address.calls, [
    { transport: 'request', path: '/addresses' },
    { transport: 'request', path: '/addresses', options: { method: 'POST', data: addressBody } },
    { transport: 'request', path: '/addresses/3', options: { method: 'PUT', data: addressBody } },
    { transport: 'request', path: '/addresses/3?version=2', options: { method: 'DELETE' } }
  ])

  const order = await loadApi('order')
  const submitBody = {
    clientRequestId: 'mp-order-idempotency-key',
    source: 'MINIPROGRAM',
    buyerNote: '工作日发货',
    address: { recipientName: '收货人' },
    items: [{ skuId: 12, quantity: 2 }]
  }
  order.api.submit(submitBody)
  order.api.list()
  order.api.detail(8)
  order.api.cancel(8, '信息有误')
  order.api.receive(8)
  order.api.uploadProof(8, 'wxfile://payment-proof')
  order.api.proofs(8)
  order.api.proofDownload(18)
  order.api.superiorOrders()
  order.api.superiorDecision(8, true, '线下款已核对')
  assert.deepEqual(order.calls, [
    { transport: 'request', path: '/orders', options: { method: 'POST', data: submitBody } },
    { transport: 'request', path: '/orders' },
    { transport: 'request', path: '/orders/8' },
    {
      transport: 'request',
      path: '/orders/8/cancel',
      options: { method: 'POST', data: { reason: '信息有误' } }
    },
    { transport: 'request', path: '/orders/8/receive', options: { method: 'POST' } },
    {
      transport: 'uploadFile',
      path: '/orders/8/proofs',
      filePath: 'wxfile://payment-proof'
    },
    { transport: 'request', path: '/orders/8/proofs' },
    { transport: 'request', path: '/order-proofs/18/download' },
    { transport: 'request', path: '/superior/orders' },
    {
      transport: 'request',
      path: '/superior/orders/8/decision',
      options: { method: 'POST', data: { approve: true, reason: '线下款已核对' } }
    }
  ])
})

test('incremental add-to-cart reads the authoritative quantity before updating it', async () => {
  const calls = []
  const cart = await loadCommonJs('api/cart.js', {
    requireMap: {
      '../utils/request': {
        request(path, options) {
          calls.push({ path, options: plain(options) })
          if (path === '/cart') {
            return Promise.resolve([{ skuId: 12, quantity: 2, inventory: 8 }])
          }
          return Promise.resolve(null)
        }
      }
    }
  })

  assert.equal(await cart.incrementItem(12, 3), 5)
  assert.deepEqual(calls, [
    { path: '/cart', options: undefined },
    {
      path: '/cart/items/12',
      options: { method: 'PUT', data: { quantity: 5, selected: true } }
    }
  ])
})

test('after-sale wrappers always send refund JSON and stage-specific proofType', async () => {
  const { api, calls } = await loadApi('aftersale')
  const applyBody = { orderId: 8, type: 'RETURN_REFUND', reason: '商品破损' }
  const shipmentBody = { carrier: '顺丰', trackingNo: 'SF10086' }
  api.apply(applyBody)
  api.list()
  api.superiorList()
  api.detail(21)
  api.returnShipment(21, shipmentBody)
  api.confirmRefund(21)
  api.cancel(21, '协商取消')
  api.superiorConfirmOfflineRefund(21, '线下退款流水已核对')
  api.uploadProof(21, 'wxfile://return-proof', 'RETURN')
  api.proofs(21)
  api.proofDownload(31)

  assert.deepEqual(calls, [
    { transport: 'request', path: '/after-sales', options: { method: 'POST', data: applyBody } },
    { transport: 'request', path: '/after-sales' },
    { transport: 'request', path: '/after-sales/superior' },
    { transport: 'request', path: '/after-sales/21' },
    {
      transport: 'request',
      path: '/after-sales/21/return-shipment',
      options: { method: 'POST', data: shipmentBody }
    },
    { transport: 'request', path: '/after-sales/21/confirm-refund', options: { method: 'POST' } },
    {
      transport: 'request',
      path: '/after-sales/21/cancel',
      options: { method: 'POST', data: { reason: '协商取消' } }
    },
    {
      transport: 'request',
      path: '/after-sales/superior/21/confirm-offline-refund',
      options: { method: 'POST', data: { reason: '线下退款流水已核对' } }
    },
    {
      transport: 'uploadFile',
      path: '/after-sales/21/proofs',
      filePath: 'wxfile://return-proof',
      formData: { proofType: 'RETURN' }
    },
    { transport: 'request', path: '/after-sales/21/proofs' },
    { transport: 'request', path: '/after-sale-proofs/31/download' }
  ])

  calls.length = 0
  api.superiorConfirmOfflineRefund(21)
  assert.deepEqual(calls[0].options.data, { reason: '' })
})

test('member, notification, rule and capability wrappers retain their routes', async () => {
  const member = await loadApi('member')
  member.api.me()
  member.api.invitation()
  member.api.createInvitation()
  member.api.revokeInvitation()
  member.api.regenerateInvitation(30)
  member.api.directMembers()
  member.api.ledger()
  assert.deepEqual(member.calls, [
    { transport: 'request', path: '/membership/me' },
    { transport: 'request', path: '/membership/invitation' },
    { transport: 'request', path: '/membership/invitation', options: { method: 'POST' } },
    { transport: 'request', path: '/membership/invitation/revoke', options: { method: 'POST' } },
    {
      transport: 'request',
      path: '/membership/invitation/regenerate?validityDays=30',
      options: { method: 'POST' }
    },
    { transport: 'request', path: '/membership/direct-members' },
    { transport: 'request', path: '/membership/ledger' }
  ])

  const notify = await loadApi('notify')
  notify.api.list(2, 50)
  notify.api.unreadCount()
  notify.api.markRead(6)
  assert.deepEqual(notify.calls, [
    { transport: 'request', path: '/notifications?page=2&size=50' },
    { transport: 'request', path: '/notifications/unread-count' },
    { transport: 'request', path: '/notifications/6/read', options: { method: 'POST' } }
  ])

  const rules = await loadApi('rules')
  rules.api.active()
  assert.deepEqual(rules.calls, [{ transport: 'request', path: '/rules/active' }])

  const system = await loadApi('system')
  system.api.about()
  system.api.capabilities()
  assert.deepEqual(system.calls, [
    { transport: 'request', path: '/system/about', options: { auth: false } },
    { transport: 'request', path: '/system/capabilities', options: { auth: false } }
  ])
})
