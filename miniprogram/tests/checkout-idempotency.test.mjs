import assert from 'node:assert/strict'
import test from 'node:test'

import { loadCommonJs, plain } from './helpers.mjs'

function flushPromises() {
  return new Promise((resolve) => setImmediate(resolve))
}

test('checkout retries reuse one clientRequestId and clear it only after success', async () => {
  let pageDefinition
  const submissions = []
  const redirects = []
  let attempt = 0

  await loadCommonJs('pages/order/confirm.js', {
    globals: {
      Page(definition) {
        pageDefinition = definition
      },
      wx: {
        showToast() {},
        redirectTo(options) {
          redirects.push(plain(options))
        }
      }
    },
    requireMap: {
      '../../api/cart': {
        setItem() {
          return Promise.resolve(null)
        }
      },
      '../../api/address': {},
      '../../api/catalog': {},
      '../../api/order': {
        submit(payload) {
          submissions.push(plain(payload))
          attempt += 1
          if (attempt === 1) {
            return Promise.reject({ code: 'NETWORK_ERROR', message: '网络异常' })
          }
          return Promise.resolve({ id: 8, orderNo: 'MS8', totalAmountFen: 2_980 })
        }
      },
      '../../utils/format': {
        fenToYuan(value) {
          return (Number(value) / 100).toFixed(2)
        },
        resolveMediaUrl(value) {
          return value || ''
        }
      },
      '../../utils/request': {
        getToken() {
          return 'member-token'
        },
        isConflict(error) {
          return Number(error && error.statusCode) === 409
        }
      },
      '../../utils/client-request': {
        makeClientRequestId() {
          return 'generated-only-if-missing'
        }
      }
    }
  })

  const page = Object.assign({}, pageDefinition, {
    data: {
      ...pageDefinition.data,
      loading: false,
      submitting: false,
      fromCart: false,
      remark: '  工作日配送  ',
      goodsAmountFen: 2_980,
      address: {
        recipientName: '张三',
        phone: '13800138000',
        province: '广东省',
        city: '深圳市',
        district: '南山区',
        detailAddress: '科技园 1 号'
      },
      goods: [{ skuId: 1, quantity: 1 }]
    },
    _clientRequestId: 'checkout-stable-id',
    setData(patch) {
      Object.assign(this.data, patch)
    }
  })

  page.onSubmit()
  await flushPromises()
  assert.equal(page.data.submitting, false)
  assert.equal(page._clientRequestId, 'checkout-stable-id')

  page.onSubmit()
  await flushPromises()
  await flushPromises()

  assert.deepEqual(submissions.map((payload) => payload.clientRequestId), [
    'checkout-stable-id',
    'checkout-stable-id'
  ])
  assert.equal(submissions[1].source, 'MINIPROGRAM')
  assert.equal(submissions[1].buyerNote, '工作日配送')
  assert.equal(page._clientRequestId, '')
  assert.equal(redirects.length, 1)
})
