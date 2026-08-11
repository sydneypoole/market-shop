import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

async function loadComponent(relativePath, requireMap = {}) {
  let definition
  await loadCommonJs(relativePath, {
    globals: {
      Component(value) {
        definition = value
      }
    },
    requireMap
  })
  return definition
}

async function loadPage(relativePath, requireMap = {}, globals = {}) {
  let definition
  await loadCommonJs(relativePath, {
    globals: {
      ...globals,
      Page(value) {
        definition = value
      }
    },
    requireMap
  })
  return definition
}

function mount(definition, data) {
  const events = []
  const instance = {
    data: { ...data },
    setData(patch) {
      Object.assign(this.data, plain(patch))
    },
    triggerEvent(name, detail) {
      events.push({ name, detail: plain(detail) })
    }
  }
  for (const [name, handler] of Object.entries(definition.methods || {})) {
    instance[name] = handler.bind(instance)
  }
  return { instance, events }
}

function mountPage(definition) {
  const instance = {
    data: plain(definition.data || {}),
    setData(patch) {
      Object.assign(this.data, plain(patch))
    }
  }
  for (const [name, handler] of Object.entries(definition)) {
    if (typeof handler === 'function') {
      instance[name] = handler.bind(instance)
    }
  }
  return instance
}

test('stepper adapts FirstUI object detail back to the existing scalar change event', async () => {
  const definition = await loadComponent('components/stepper/stepper.js')
  const { instance, events } = mount(definition, { value: 2, min: 0, max: 8 })

  instance.onFirstUiChange({ detail: { value: 4, index: 99, params: 'ignored' } })
  instance.onFirstUiChange({ detail: {} })

  assert.deepEqual(events, [{ name: 'change', detail: 4 }])

  const unavailable = mount(definition, { value: 1, min: 1, max: 0 })
  unavailable.instance.onFirstUiChange({ detail: { value: 0 } })
  assert.deepEqual(unavailable.events, [], 'an unavailable SKU must not emit an implicit zero/delete')
})

test('SKU wrapper adapts FirstUI tag index and preserves the confirm payload', async () => {
  const definition = await loadComponent('components/sku-sheet/sku-sheet.js', {
    '../../utils/format': {
      fenToYuan(value) {
        return (Number(value) / 100).toFixed(2)
      },
      resolveMediaUrl(value) {
        return value || ''
      }
    }
  })
  const { instance, events } = mount(definition, {
    skuOptions: [
      { skuId: 7, priceFen: 1_990, inventory: 6, label: '规格 A' },
      { skuId: 8, priceFen: 2_990, inventory: 0, label: '规格 B' }
    ],
    selectedSkuId: 0,
    quantity: 1,
    inventory: 0,
    maxQuantity: 1
  })

  instance.onFirstUiSelectSku({ detail: { index: 7 } })
  instance.onConfirm({ detail: { index: 123 } })

  assert.equal(instance.data.selectedSkuId, 7)
  assert.equal(instance.data.inventory, 6)
  assert.equal(instance.data.maxQuantity, 6)
  assert.deepEqual(events, [{ name: 'confirm', detail: { skuId: 7, quantity: 1 } }])

  const pending = mount(definition, {
    selectedSkuId: 7,
    quantity: 1,
    inventory: 6,
    pending: true
  })
  pending.instance.onConfirm()
  assert.deepEqual(pending.events, [], 'a pending cart mutation must disable duplicate SKU confirmation')
})

test('empty and goods-card wrappers preserve their public event names', async () => {
  const emptyDefinition = await loadComponent('components/empty/empty.js')
  const empty = mount(emptyDefinition, {})
  empty.instance.onAction({ detail: { index: 5 } })
  assert.deepEqual(empty.events, [{ name: 'action', detail: undefined }])

  const cardDefinition = await loadComponent('components/goods-card/goods-card.js', {
    '../../utils/format': {
      fenToYuan(value) {
        return (Number(value) / 100).toFixed(2)
      },
      resolveMediaUrl(value) {
        return value || ''
      }
    }
  })
  const card = mount(cardDefinition, {})
  card.instance.onTap({ detail: { index: 3 } })
  assert.deepEqual(card.events, [{ name: 'tap', detail: undefined }])
})

test('order and after-sale statuses stay semantic and unknown states expose no mutation', async () => {
  const [orders, aftersales, orderList, orderDetail, orderDetailScript, aftersaleList, aftersaleDetail, aftersaleDetailScript] = await Promise.all([
    loadCommonJs('utils/order-status.js'),
    loadCommonJs('utils/aftersale-status.js'),
    readFile(resolve(miniprogramRoot, 'pages/order/list.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/order/detail.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/order/detail.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/aftersale/list.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/aftersale/detail.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/aftersale/detail.js'), 'utf8')
  ])

  assert.equal(orders.statusTone('COMPLETED'), 'green')
  assert.equal(orders.statusTone('SUPERIOR_REJECTED'), 'danger')
  assert.equal(orders.statusTone('ADMIN_REJECTED'), 'danger')
  assert.equal(orders.statusTone('CANCELLED'), 'danger')
  assert.equal(orders.statusTone('UNKNOWN_STATUS'), 'muted')
  assert.equal(orders.statusText('UNKNOWN_STATUS'), '未知订单状态')
  assert.deepEqual(plain(orders.resolveOrderActions({ canReceive: true }, 'UNKNOWN_STATUS')), {
    canReceive: false,
    canUploadProof: false,
    canCancel: false,
    canSuperiorDecide: false
  })
  assert.deepEqual(plain(orders.resolveOrderActions({
    canReceive: 'true',
    canUploadProof: 1,
    canCancel: 'true',
    canSuperiorDecide: 1
  }, 'PENDING_SUPERIOR')), {
    canReceive: false,
    canUploadProof: false,
    canCancel: false,
    canSuperiorDecide: false
  })
  assert.equal(
    orders.resolveOrderActions({ canReceive: true }, 'SHIPPED').canReceive,
    true
  )

  assert.equal(aftersales.aftersaleStatusTone('COMPLETED'), 'green')
  assert.equal(aftersales.aftersaleStatusTone('REJECTED'), 'danger')
  assert.equal(aftersales.aftersaleStatusTone('CANCELLED'), 'danger')
  assert.equal(aftersales.aftersaleStatusTone('UNKNOWN_STATUS'), 'muted')
  assert.equal(aftersales.aftersaleStatusText('UNKNOWN_STATUS'), '未知售后状态')
  assert.deepEqual(plain(aftersales.resolveAftersaleActions({
    status: 'UNKNOWN_STATUS',
    type: 'RETURN_REFUND'
  }, true, false)), {
    canUploadProof: false,
    canReturnShip: false,
    canConfirmRefund: false,
    canConfirmOffline: false,
    canCancel: false
  })
  assert.equal(aftersales.resolveAftersaleActions({
    status: 'AWAITING_RETURN',
    type: 'RETURN_REFUND'
  }, true, false).canReturnShip, true)

  for (const source of [orderList, aftersaleList]) {
    assert.match(source, /item\.tone === 'green'/)
    assert.match(source, /item\.tone === 'danger'/)
    assert.match(source, /: '#6F656B'/, '未知或 muted 状态应使用符合 AA 对比度的中性灰')
  }
  assert.match(orderDetail, /order\.status === 'SHIPPED' \|\| order\.status === 'COMPLETED'/)
  assert.match(orderDetail, /order\.status === 'SUPERIOR_REJECTED' \|\| order\.status === 'ADMIN_REJECTED' \|\| order\.status === 'CANCELLED'/)
  assert.match(aftersaleDetail, /view\.status === 'COMPLETED'/)
  assert.match(aftersaleDetail, /view\.status === 'REJECTED' \|\| view\.status === 'CANCELLED'/)
  assert.match(orderDetailScript, /resolveOrderActions\(detail\.actorCapabilities, order\.status\)/)
  assert.match(aftersaleDetailScript, /resolveAftersaleActions\(view, isApplicant, isSuperior\)/)
})

test('profile loading failures remain visible and retryable instead of becoming fallback account data', async () => {
  let shouldFail = true
  let memberAvatarUrl = '/api/v1/member-avatars/7'
  const definition = await loadPage('pages/profile/profile.js', {
    '../../api/auth': {
      me() {
        return shouldFail
          ? Promise.reject({ message: '账号信息加载失败' })
          : Promise.resolve({ nickname: '林木', publicId: 'HS-7' })
      }
    },
    '../../api/member': {
      me() {
        return Promise.resolve({
          nickname: '杉杉',
          avatarUrl: memberAvatarUrl,
          phoneMasked: '138****8000',
          levelName: '超级会员'
        })
      }
    },
    '../../api/system': {},
    '../../api/notify': {},
    '../../utils/member-profile': {
      nicknameInitial(value) {
        return Array.from(String(value || '').trim())[0] || '会'
      },
      resolveOwnedAvatarUrl(value) {
        return /^\/api\/v1\/member-avatars\/\d+$/.test(String(value || '').trim())
          ? 'https://shop.example.test' + value
          : ''
      }
    },
    '../../utils/request': { getToken: () => 'TOKEN', setToken() {} }
  }, { wx: {} })
  const page = mountPage(definition)

  await page.loadProfile()
  assert.equal(page.data.loading, false)
  assert.equal(page.data.error, '账号信息加载失败')
  assert.equal(page.data.nickname, '', 'failed data must not masquerade as a valid fallback member')

  shouldFail = false
  await page.loadProfile()
  assert.equal(page.data.loading, false)
  assert.equal(page.data.error, '')
  assert.equal(page.data.nickname, '杉杉', 'membership profile must override the stale auth session nickname')
  assert.equal(page.data.publicId, 'HS-7')
  assert.equal(page.data.levelName, '超级会员')
  assert.equal(page.data.avatarUrl, 'https://shop.example.test/api/v1/member-avatars/7')
  assert.equal(page.data.avatarFallback, '杉')
  assert.equal(page.data.phoneMasked, '138****8000')
  page.onAvatarError()
  assert.equal(page.data.avatarFailed, true)

  memberAvatarUrl = 'https://untrusted.example.test/member.png'
  await page.loadProfile()
  assert.equal(page.data.avatarUrl, '', 'member avatars must use the owned stable same-origin endpoint')
  assert.equal(page.data.avatarFallback, '杉')
})

test('dynamic rule values stay authoritative while their presentation remains Chinese', async () => {
  const definition = await loadPage('pages/rules/rules.js', {
    '../../api/rules': {
      active() {
        return Promise.resolve([
          {
            id: 1,
            ruleType: 'SELF_ORDER_TASK',
            version: 3,
            effectiveFrom: '2026-01-01T00:00:00Z',
            parametersJson: JSON.stringify({
              minimumCompletedOrderAmountFen: 29800,
              eligibleSalesScenes: ['UPGRADE'],
              targetLevel: 'EXPERIENCE_OFFICER'
            })
          },
          {
            id: 2,
            ruleType: 'FUTURE_RULE',
            version: 1,
            effectiveFrom: '2026-01-01T00:00:00Z',
            parametersJson: JSON.stringify({
              futureMode: 'FUTURE_ENUM',
              futureList: ['UPGRADE', 'FUTURE_ENUM'],
              futureObject: { limit: 2 },
              futureNull: null
            })
          }
        ])
      }
    },
    '../../utils/format': {
      dateTime: () => '2026-01-01 08:00',
      fenToYuan: (value) => (Number(value) / 100).toFixed(2),
      fileSize: (value) => `${value} B`
    },
    '../../utils/request': { getToken: () => 'TOKEN' }
  }, { wx: {} })
  const page = mountPage(definition)

  await page.loadRules()

  assert.equal(page.data.loading, false)
  assert.equal(page.data.rules[0].title, '自购升级任务')
  assert.deepEqual(page.data.rules[0].params, [
    { key: '最低完成订单金额', value: '¥298.00' },
    { key: '适用销售专区', value: '升级专区' },
    { key: '目标会员等级', value: '体验官' }
  ])
  assert.equal(page.data.rules[1].title, '其他规则')
  assert.deepEqual(page.data.rules[1].params, [
    { key: '其他配置 · futureMode', value: 'FUTURE_ENUM' },
    { key: '其他配置 · futureList', value: '升级专区、FUTURE_ENUM' },
    { key: '其他配置 · futureObject', value: '{"limit":2}' },
    { key: '其他配置 · futureNull', value: '未配置（null）' }
  ])
})
