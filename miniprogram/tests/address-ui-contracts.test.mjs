import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

async function loadAddressEdit(addressApi) {
  let definition
  const calls = {
    navigationTitles: [],
    toasts: []
  }
  const wx = {
    navigateBack() {},
    reLaunch() {},
    setNavigationBarTitle(options) {
      calls.navigationTitles.push(plain(options))
    },
    showModal() {},
    showToast(options) {
      calls.toasts.push(plain(options))
    }
  }

  await loadCommonJs('pages/address/edit.js', {
    globals: {
      Page(value) {
        definition = value
      },
      wx
    },
    requireMap: {
      '../../api/address': addressApi,
      '../../utils/request': {
        getToken() {
          return 'TOKEN'
        },
        isConflict() {
          return false
        }
      }
    }
  })

  return { definition, calls }
}

function mountPage(definition) {
  const instance = {
    data: plain(definition.data),
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

test('address edit waits for the server version before exposing mutations', async () => {
  let resolveRows
  let updateCalls = 0
  const rows = new Promise((resolveRowsPromise) => {
    resolveRows = resolveRowsPromise
  })
  const { definition, calls } = await loadAddressEdit({
    list() {
      return rows
    },
    update() {
      updateCalls += 1
      return Promise.resolve()
    },
    create() {
      return Promise.resolve()
    },
    remove() {
      return Promise.resolve()
    }
  })
  const page = mountPage(definition)

  page.onLoad({ id: '7' })
  const loading = page.loadAddress()

  assert.equal(page.data.loadingAddress, true)
  assert.equal(page.data.addressLoaded, false)
  assert.equal(page.data.version, 0)

  page.onInput({
    currentTarget: { dataset: { field: 'recipientName' } },
    detail: { value: '不应写入' }
  })
  page.onSave()

  assert.equal(page.data.recipientName, '')
  assert.equal(updateCalls, 0, '未取得 version 时不得调用更新接口')
  assert.equal(calls.toasts.at(-1).title, '正在读取地址，请稍候')

  resolveRows([
    {
      id: 7,
      version: 4,
      recipientName: '林女士',
      phone: '13800138000',
      province: '广东省',
      city: '深圳市',
      district: '南山区',
      detailAddress: '科技园 1 号',
      postalCode: '518000',
      defaultAddress: true
    }
  ])
  await loading

  assert.equal(page.data.loadingAddress, false)
  assert.equal(page.data.addressLoaded, true)
  assert.equal(page.data.loadError, '')
  assert.equal(page.data.version, 4)
  assert.equal(page.data.recipientName, '林女士')
  assert.deepEqual(page.data.regionValue, ['广东省', '深圳市', '南山区'])
})

test('address edit failure keeps the blank form closed and supports retry', async () => {
  let listCalls = 0
  let updateCalls = 0
  const { definition } = await loadAddressEdit({
    list() {
      listCalls += 1
      if (listCalls === 1) {
        return Promise.reject(new Error('网络中断'))
      }
      return Promise.resolve([
        {
          id: 9,
          version: 6,
          recipientName: '陈先生',
          phone: '13900139000',
          province: '湖北省',
          city: '武汉市',
          district: '洪山区',
          detailAddress: '关山大道 2 号'
        }
      ])
    },
    update() {
      updateCalls += 1
      return Promise.resolve()
    },
    create() {
      return Promise.resolve()
    },
    remove() {
      return Promise.resolve()
    }
  })
  const page = mountPage(definition)

  page.onLoad({ id: '9' })
  await page.loadAddress()

  assert.equal(page.data.loadingAddress, false)
  assert.equal(page.data.addressLoaded, false)
  assert.equal(page.data.loadError, '网络中断')
  page.onSave()
  assert.equal(updateCalls, 0)

  await page.loadAddress()
  assert.equal(listCalls, 2)
  assert.equal(page.data.addressLoaded, true)
  assert.equal(page.data.loadError, '')
  assert.equal(page.data.version, 6)
})

test('address and freight presentation retains enforced business guidance', async () => {
  const [edit, list, listScript, confirm, detail] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'pages/address/edit.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/address/list.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/address/list.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/order/confirm.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/order/detail.wxml'), 'utf8')
  ])

  assert.match(edit, /wx:if="\{\{isEdit && !loadingAddress && loadError\}\}"/)
  assert.match(edit, /wx:elif="\{\{!isEdit \|\| addressLoaded\}\}"/)
  assert.match(edit, /buttonText="重新加载"[\s\S]*bind:action="loadAddress"/)
  assert.match(edit, /disabled="\{\{saving \|\| deleting \|\| loadingAddress \|\| \(isEdit && !addressLoaded\)\}\}"/)

  assert.match(list, /disabled="\{\{list\.length >= 20\}\}"/)
  assert.match(list, /最多可保存 20 个收货地址/)
  assert.match(listScript, /this\.data\.list\.length >= 20/)
  for (const source of [confirm, detail]) {
    assert.match(source, />运费<\//)
    assert.match(source, />¥0\.00<\//)
  }
})
