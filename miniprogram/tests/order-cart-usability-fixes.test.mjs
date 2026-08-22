import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

async function loadPage(relativePath, requireMap = {}, wx = {}) {
  let definition
  await loadCommonJs(relativePath, {
    globals: {
      Page(value) {
        definition = value
      },
      wx
    },
    requireMap
  })
  return definition
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

function formatStub() {
  return {
    fenToYuan(value) {
      return (Number(value) / 100).toFixed(2)
    },
    resolveMediaUrl(value) {
      return value || ''
    }
  }
}

function nextTurn() {
  return new Promise((resolvePromise) => setImmediate(resolvePromise))
}

test('successful cart mutations update the local view without reloading the whole cart', async () => {
  let listCalls = 0
  const mutations = []
  const rows = [{
    id: 1,
    skuId: 7,
    productName: '商品',
    skuName: '规格',
    coverUrl: '',
    priceFen: 2_980,
    quantity: 1,
    selected: true,
    inventory: 9
  }]
  const definition = await loadPage('pages/cart/cart.js', {
    '../../api/cart': {
      list() {
        listCalls += 1
        return Promise.resolve(rows)
      },
      setItem(skuId, quantity, selected) {
        mutations.push({ skuId, quantity, selected })
        return Promise.resolve()
      }
    },
    '../../utils/format': formatStub(),
    '../../utils/request': { isConflict: () => false }
  }, {
    showToast() {},
    showModal() {},
    navigateTo() {}
  })
  const page = mountPage(definition)

  await page.loadCart()
  page.updateItem(7, 2, true)
  await nextTurn()

  assert.equal(listCalls, 1)
  assert.deepEqual(mutations, [{ skuId: 7, quantity: 2, selected: true }])
  assert.equal(page.data.items[0].quantity, 2)
  assert.equal(page.data.totalText, '59.60')
  assert.equal(page.data.busy, false)
})

test('failed cart mutations reload server-authoritative state', async () => {
  let listCalls = 0
  const definition = await loadPage('pages/cart/cart.js', {
    '../../api/cart': {
      list() {
        listCalls += 1
        return Promise.resolve([])
      },
      setItem() {
        return Promise.reject({ code: 'CART_CONFLICT', message: '购物车冲突' })
      }
    },
    '../../utils/format': formatStub(),
    '../../utils/request': { isConflict: () => true }
  }, {
    showToast() {},
    showModal() {},
    navigateTo() {}
  })
  const page = mountPage(definition)
  page.setData({
    items: [{ skuId: 7, quantity: 1, selected: true, inventory: 9, available: true }],
    loading: false,
    empty: false
  })

  page.updateItem(7, 2, true)
  await nextTurn()
  await nextTurn()

  assert.equal(listCalls, 1)
  assert.equal(page.data.empty, true)
})

test('cart background refresh preserves the rendered loading state on tab switches', async () => {
  let resolveList
  const definition = await loadPage('pages/cart/cart.js', {
    '../../api/cart': {
      list() {
        return new Promise((resolvePromise) => {
          resolveList = resolvePromise
        })
      },
      setItem() {
        return Promise.resolve()
      }
    },
    '../../utils/format': formatStub(),
    '../../utils/request': { isConflict: () => false }
  }, { showToast() {} })
  const page = mountPage(definition)
  page._loaded = true
  page.setData({ loading: false, empty: false })

  page.onShow()
  assert.equal(page.data.loading, false)
  resolveList([])
  await nextTurn()
})

test('stepper provides separate 88rpx controls and cart no longer blocks content while mutating', async () => {
  const [stepper, styles, cart] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'components/stepper/stepper.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/stepper/stepper.wxss'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/cart/cart.wxml'), 'utf8')
  ])

  assert.match(stepper, /custom="\{\{true\}\}"/)
  assert.match(stepper, /slot="plus" class="stepper__control"/)
  assert.match(styles, /width:\s*88rpx/)
  assert.match(styles, /height:\s*88rpx/)
  assert.match(cart, /loading="\{\{loading\}\}"/)
  assert.doesNotMatch(cart, /loading="\{\{loading \|\| busy\}\}"/)
})

test('new address payload omits update-only and empty optional fields', async () => {
  const definition = await loadPage('pages/address/edit.js', {
    '../../api/address': {},
    '../../utils/request': {
      getToken: () => 'TOKEN',
      isConflict: () => false
    }
  }, {})
  const page = mountPage(definition)
  page.setData({
    recipientName: ' 林女士 ',
    phone: '13800138000',
    province: '广东省',
    city: '深圳市',
    district: '南山区',
    detailAddress: ' 科技园 1 号 ',
    postalCode: ' ',
    defaultAddress: true,
    version: 4,
    isEdit: false
  })

  const created = plain(page.buildBody())
  assert.deepEqual(created, {
    recipientName: '林女士',
    phone: '13800138000',
    province: '广东省',
    city: '深圳市',
    district: '南山区',
    detailAddress: '科技园 1 号',
    defaultAddress: true
  })

  page.setData({ isEdit: true, postalCode: ' 518000 ' })
  assert.equal(page.buildBody().version, 4)
  assert.equal(page.buildBody().postalCode, '518000')
})

test('cancel request always sends a non-blank reason', async () => {
  const calls = []
  const api = await loadCommonJs('api/order.js', {
    requireMap: {
      '../utils/request': {
        request(path, options) {
          calls.push({ path, options: plain(options) })
          return Promise.resolve()
        },
        uploadFile() {}
      }
    }
  })

  await api.cancel(9, '   ')
  assert.deepEqual(calls, [{
    path: '/orders/9/cancel',
    options: {
      method: 'POST',
      data: { reason: '用户主动取消' }
    }
  }])
})

test('invitation copy exposes success and failure feedback', async () => {
  const clipboard = []
  const toasts = []
  const modals = []
  let clipboardOptions
  const definition = await loadPage('pages/member/invite.js', {
    '../../api/member': {},
    '../../utils/format': {
      fenToYuan: () => '0.00',
      dateTime: () => ''
    },
    '../../utils/request': { getToken: () => 'TOKEN' }
  }, {
    setClipboardData(options) {
      clipboard.push(options.data)
      clipboardOptions = options
    },
    showToast(options) {
      toasts.push(plain(options))
    },
    showModal(options) {
      modals.push(plain(options))
    }
  })
  const page = mountPage(definition)
  page.setData({ invitation: { code: ' HS-2026 ' } })

  page.onCopy()
  assert.deepEqual(clipboard, ['HS-2026'])
  clipboardOptions.success()
  assert.equal(toasts.at(-1).title, '已复制')
  clipboardOptions.fail()
  assert.deepEqual(modals.at(-1), {
    title: '复制失败',
    content: '请长按邀请码手动复制',
    showCancel: false
  })

  const template = await readFile(resolve(miniprogramRoot, 'pages/member/invite.wxml'), 'utf8')
  assert.match(template, /class="code-panel"[\s\S]*bindtap="onCopy"/)
})
