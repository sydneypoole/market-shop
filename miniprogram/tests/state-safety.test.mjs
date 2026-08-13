import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

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

function mountPage(definition) {
  const instance = {
    ...definition,
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

function formatStub() {
  return {
    fenToYuan(value) {
      return (Number(value) / 100).toFixed(2)
    },
    resolveMediaUrl(value) {
      return value || ''
    },
    resolveRichTextMedia(value) {
      return value || ''
    }
  }
}

test('home omits the default announcement but still renders server announcements', async () => {
  let contents = []
  const definition = await loadPage('pages/index/index.js', {
    '../../api/catalog': {
      categories: () => Promise.resolve([]),
      products: () => Promise.resolve([]),
      contents: () => Promise.resolve(contents)
    },
    '../../utils/format': formatStub()
  }, { wx: {} })

  const emptyPage = mountPage(definition)
  assert.equal(emptyPage.data.announcement, '')
  emptyPage.loadHome()
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  assert.equal(emptyPage.data.announcement, '')

  contents = [{ id: 7, type: 'ANNOUNCEMENT', title: '今日营业时间调整' }]
  const announcedPage = mountPage(definition)
  announcedPage.loadHome()
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  assert.equal(announcedPage.data.announcement, '今日营业时间调整')
})

test('out-of-stock cart rows are visible but fail closed for quantity and checkout', async () => {
  const updates = []
  const toasts = []
  const modals = []
  let navigateCount = 0
  const rows = [{
    id: 1,
    skuId: 7,
    productName: '商品',
    skuName: '规格',
    priceFen: 2_980,
    quantity: 2,
    selected: true,
    inventory: 0
  }]
  const cartApi = {
    list: () => Promise.resolve(rows),
    setItem(skuId, quantity, selected) {
      updates.push({ skuId, quantity, selected })
      return Promise.resolve()
    }
  }
  const definition = await loadPage('pages/cart/cart.js', {
    '../../api/cart': cartApi,
    '../../utils/format': formatStub(),
    '../../utils/request': { isConflict: () => false }
  }, {
    wx: {
      showToast(options) { toasts.push(plain(options)) },
      showModal(options) { modals.push(options) },
      navigateTo() { navigateCount += 1 }
    }
  })
  const page = mountPage(definition)

  await page.loadCart()
  assert.equal(page.data.items[0].available, false)
  assert.equal(page.data.items[0].maxQuantity, 0)
  assert.equal(page.data.checkoutCount, 0)
  assert.equal(page.data.hasUnavailableSelected, true)
  assert.equal(page.data.totalText, '0.00')

  page.onCheckout()
  assert.equal(navigateCount, 0)
  assert.match(toasts[0].title, /库存不足/)

  page.onRemoveUnavailable({ currentTarget: { dataset: { skuid: 7 } } })
  assert.equal(updates.length, 0, '移除库存异常商品前必须确认')
  assert.equal(modals.length, 1)
  modals[0].success({ confirm: true })
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  assert.deepEqual(updates, [{ skuId: 7, quantity: 0, selected: false }])
})

test('checkout rejects selected or direct goods whose inventory cannot satisfy quantity', async () => {
  const definition = await loadPage('pages/order/confirm.js', {
    '../../api/cart': {
      list: () => Promise.resolve([{ skuId: 7, quantity: 1, selected: true, inventory: 0 }])
    },
    '../../api/address': {},
    '../../api/catalog': {},
    '../../api/order': {},
    '../../utils/format': formatStub(),
    '../../utils/request': { getToken: () => 'TOKEN', isConflict: () => false },
    '../../utils/client-request': { makeClientRequestId: () => 'REQUEST-ID' }
  }, { wx: {} })
  const page = mountPage(definition)

  await assert.rejects(
    page.loadCartGoods(),
    (error) => error && error.code === 'INVENTORY_UNAVAILABLE'
  )
  assert.throws(
    () => page.snapshotFromHit({
      base: { name: '商品' },
      sku: { skuId: 7, priceFen: 2_980, inventory: 0 }
    }, 1),
    (error) => error && error.code === 'INVENTORY_UNAVAILABLE'
  )
})

test('goods detail hides purchase actions when every SKU is unavailable and localizes attribute keys', async () => {
  const definition = await loadPage('pages/goods/detail.js', {
    '../../api/catalog': {
      product: () => Promise.resolve({
        product: { name: '商品', coverUrl: '' },
        skus: [{
          skuId: 7,
          skuName: '缺货规格',
          inventory: 0,
          priceFen: 2_980,
          attributesJson: JSON.stringify({ package: '体验装' })
        }]
      })
    },
    '../../api/cart': {},
    '../../utils/format': formatStub()
  }, { wx: {} })
  const page = mountPage(definition)

  await page.loadDetail('7')
  assert.equal(page.data.hasAvailableSku, false)
  assert.equal(page.data.selectedSkuId, 0)
  assert.deepEqual(page.data.attrRows, [{ key: '包装规格', value: '体验装' }])

  page.onLoad({})
  assert.equal(page.data.error, '商品参数无效')
  assert.equal(page.data.retryable, false)
})

test('invalid content, after-sale and success routes render errors rather than no-op retries or false success', async () => {
  let contentCalls = 0
  const contentDefinition = await loadPage('pages/content/detail.js', {
    '../../api/catalog': {
      content() {
        contentCalls += 1
        return Promise.resolve(null)
      }
    },
    '../../utils/format': formatStub()
  }, { wx: {} })
  const contentPage = mountPage(contentDefinition)
  contentPage.onLoad({})
  contentPage.loadContent()
  assert.equal(contentCalls, 0)
  assert.equal(contentPage.data.error, '内容参数无效')
  assert.equal(contentPage.data.retryable, false)

  let orderCalls = 0
  const applyDefinition = await loadPage('pages/aftersale/apply.js', {
    '../../api/aftersale': {},
    '../../api/auth': { me: () => Promise.resolve({ userId: 99 }) },
    '../../api/order': {
      detail() {
        orderCalls += 1
        return Promise.resolve({
          order: {
            orderNo: 'MS-1',
            buyerUserId: 10,
            status: 'SHIPPED',
            totalAmountFen: 2_980
          }
        })
      }
    },
    '../../utils/format': formatStub(),
    '../../utils/request': { getToken: () => 'TOKEN', isConflict: () => false },
    '../../utils/client-request': { makeClientRequestId: () => 'REQUEST-ID' }
  }, { wx: {} })
  const applyPage = mountPage(applyDefinition)
  applyPage.onLoad({ orderId: '-1' })
  assert.equal(orderCalls, 0)
  assert.equal(applyPage.data.orderLoadError, '订单参数无效')
  assert.equal(applyPage.data.orderRetryable, false)

  const unauthorizedApply = mountPage(applyDefinition)
  unauthorizedApply.setData({ orderId: 1, orderLoadError: '' })
  await unauthorizedApply.loadOrder()
  assert.equal(unauthorizedApply.data.orderNo, '')
  assert.equal(unauthorizedApply.data.orderLoadError, '仅订单购买人可申请售后')

  const titles = []
  const redirects = []
  const switchTabs = []
  const toasts = []
  const successDefinition = await loadPage('pages/order/success.js', {
    '../../utils/format': formatStub()
  }, {
    wx: {
      setNavigationBarTitle(options) { titles.push(plain(options)) },
      redirectTo(options) { redirects.push(plain(options)) },
      switchTab(options) { switchTabs.push(plain(options)) },
      showToast(options) { toasts.push(plain(options)) }
    }
  })
  const invalidSuccess = mountPage(successDefinition)
  assert.doesNotThrow(() => invalidSuccess.onLoad({ orderNo: '%', id: '7', total: '2980' }))
  assert.equal(invalidSuccess.data.valid, false)
  assert.equal(invalidSuccess.data.error, '订单提交信息无效')
  assert.deepEqual(titles, [{ title: '订单信息无效' }])
  invalidSuccess.goOrderDetail()
  assert.deepEqual(toasts, [{ title: '订单信息缺失', icon: 'none' }])
  assert.deepEqual(redirects, [])

  const validSuccess = mountPage(successDefinition)
  validSuccess.onLoad({ orderNo: 'MS-7', id: '7', total: '2980' })
  assert.equal(validSuccess.data.valid, true)
  assert.equal(validSuccess.data.totalText, '29.80')
  validSuccess.goOrderDetail()
  validSuccess.goHome()
  assert.deepEqual(redirects, [{ url: '/pages/order/detail?id=7' }])
  assert.deepEqual(switchTabs, [{ url: '/pages/index/index' }])
})

test('order success page presents one truthful receipt, a capability-safe flow and accessible actions', async () => {
  const [markup, style] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'pages/order/success.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/order/success.wxss'), 'utf8')
  ])

  assert.match(markup, /<block wx:if="\{\{valid\}\}">/)
  assert.match(markup, /当前等待直属上级确认线下收款/)
  assert.match(markup, /最新状态、凭证入口及可执行操作请以订单详情为准/)
  assert.doesNotMatch(markup, /上传付款凭证/)
  assert.match(markup, /<fui-icon[^>]+disabled[^>]+aria-hidden="true"/)
  assert.doesNotMatch(markup, /<brand-shell[\s\S]*?safe-bottom/)
  assert.match(style, /\.page--success\s*\{[\s\S]*?display:\s*flex;[\s\S]*?flex-direction:\s*column;/)
  assert.match(style, /\.actions\s*\{[\s\S]*?margin-top:\s*auto;/)
  assert.match(style, /\.action-btn\s*\{[\s\S]*?min-height:\s*88rpx;/)
  assert.match(style, /@media\s*\(prefers-reduced-motion:\s*reduce\)/)
})

test('neutral labels, pending bindings and primary-action dimensions stay explicit', async () => {
  const files = await Promise.all([
    'pages/index/index.wxml',
    'pages/goods/list.wxml',
    'pages/goods/detail.wxml',
    'pages/goods/detail.wxss',
    'pages/search/search.wxss',
    'components/empty/empty.wxml',
    'components/sku-sheet/sku-sheet.wxml',
    'pages/aftersale/apply.wxml',
    'pages/order/success.wxml',
    'pages/order/list.wxss',
    'pages/order/detail.js',
    'pages/aftersale/detail.js'
  ].map((filename) => readFile(resolve(miniprogramRoot, filename), 'utf8')))
  const [
    index,
    goodsList,
    goodsDetail,
    goodsDetailStyle,
    searchStyle,
    empty,
    skuSheet,
    aftersaleApply,
    orderSuccess,
    orderListStyle,
    orderDetailScript,
    aftersaleDetailScript
  ] = files

  assert.doesNotMatch(index, /今日推荐/)
  assert.doesNotMatch(goodsList, />新品<|新品顺序/)
  assert.match(goodsDetail, /pending="\{\{adding\}\}"/)
  assert.match(goodsDetail, /disabled="\{\{adding \|\| !hasAvailableSku\}\}"/)
  assert.match(skuSheet, /disabled="\{\{pending \|\| !selectedSkuId \|\| inventory <= 0\}\}"/)
  assert.match(goodsDetailStyle, /\.action-button\s*\{[\s\S]*?min-height:\s*88rpx;/)
  assert.match(searchStyle, /\.search-submit\s*\{[\s\S]*?min-height:\s*88rpx;/)
  assert.match(empty, /height="88rpx"/)
  assert.match(aftersaleApply, /<block wx:if="\{\{orderNo && !orderLoadError\}\}">/)
  assert.match(orderSuccess, /<block wx:if="\{\{valid\}\}">/)
  assert.match(orderListStyle, /\.action\s*\{[\s\S]*?min-height:\s*88rpx;/)
  assert.match(orderDetailScript, /canApplyAftersale:\s*isBuyer &&/)
  for (const source of [orderDetailScript, aftersaleDetailScript]) {
    assert.match(source, /_proofLoadGeneration/)
    assert.match(source, /_detailLoadGeneration/)
  }
})

test('newer signed-proof requests win over stale preview completions', async () => {
  const pending = new Map()
  function deferred(proofId) {
    let resolvePromise
    const promise = new Promise((resolve) => { resolvePromise = resolve })
    pending.set(proofId, resolvePromise)
    return promise
  }
  const definition = await loadPage('pages/order/detail.js', {
    '../../api/order': {
      proofDownload(proofId) {
        return deferred(proofId)
      }
    },
    '../../api/auth': {},
    '../../api/system': {},
    '../../utils/format': {
      ...formatStub(),
      dateTime: () => ''
    },
    '../../utils/order-status': {
      statusText: () => '',
      resolveOrderActions: () => ({})
    },
    '../../utils/request': { getToken: () => 'TOKEN', isConflict: () => false },
    '../../utils/proof': { resolveProofLimits: () => ({}), aftersaleProofType: () => 'APPLICATION' }
  }, { wx: {} })
  const page = mountPage(definition)

  const older = page.loadProofPreviews([{ proofId: 1 }])
  const newer = page.loadProofPreviews([{ proofId: 2 }])
  pending.get(2)({ signedUrl: '/new' })
  await newer
  pending.get(1)({ signedUrl: '/old' })
  await older

  assert.deepEqual(page.data.proofPreviews, [{ proofId: 2, url: '/new', failed: false }])
  assert.equal(page.data.proofCount, 1)
  assert.equal(page.data.proofLoading, false)
})
