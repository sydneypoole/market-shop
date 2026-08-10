import assert from 'node:assert/strict'
import test from 'node:test'

import { loadCommonJs, plain } from './helpers.mjs'

function wechatEnvironment(envVersion, extConfig = {}) {
  return {
    getAccountInfoSync() {
      return { miniProgram: { envVersion } }
    },
    getExtConfigSync() {
      return extConfig
    }
  }
}

test('API origin follows envVersion, supports extConfig and rejects insecure release origins', async () => {
  const develop = await loadCommonJs('utils/config.js', {
    globals: { wx: wechatEnvironment('develop') }
  })
  assert.equal(develop.getEnvVersion(), 'develop')
  const developBaseUrl = develop.getBaseUrl()
  assert.equal(developBaseUrl, develop.BASE_URLS.develop)
  assert.match(developBaseUrl, /^https?:\/\/[^/?#\s@]+$/i)
  if (/^http:\/\//i.test(developBaseUrl)) {
    assert.match(developBaseUrl, /^http:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?$/i)
  }

  const trial = await loadCommonJs('utils/config.js', {
    globals: { wx: wechatEnvironment('trial') }
  })
  assert.equal(trial.getBaseUrl(), 'https://shop.cllbmz.kdns.fr')

  const release = await loadCommonJs('utils/config.js', {
    globals: {
      wx: wechatEnvironment('release', { apiBaseUrl: 'https://api.example.test///' })
    }
  })
  assert.equal(release.getBaseUrl(), 'https://api.example.test')

  const localOverride = await loadCommonJs('utils/config.js', {
    globals: {
      wx: wechatEnvironment('develop', { apiBaseUrl: 'http://127.0.0.1:18080/' })
    }
  })
  assert.equal(localOverride.getBaseUrl(), 'http://127.0.0.1:18080')

  const insecureRelease = await loadCommonJs('utils/config.js', {
    globals: {
      wx: wechatEnvironment('release', { apiBaseUrl: 'http://api.example.test' })
    }
  })
  assert.throws(() => insecureRelease.getBaseUrl(), /HTTPS/i)

  const originWithPath = await loadCommonJs('utils/config.js', {
    globals: {
      wx: wechatEnvironment('release', { apiBaseUrl: 'https://api.example.test/api/v1' })
    }
  })
  assert.throws(() => originWithPath.getBaseUrl(), /配置无效/)

  const originWithQuery = await loadCommonJs('utils/config.js', {
    globals: {
      wx: wechatEnvironment('release', { apiBaseUrl: 'https://api.example.test?tenant=market' })
    }
  })
  assert.throws(() => originWithQuery.getBaseUrl(), /配置无效/)

  const originWithCredentials = await loadCommonJs('utils/config.js', {
    globals: {
      wx: wechatEnvironment('release', { apiBaseUrl: 'https://user:secret@api.example.test' })
    }
  })
  assert.throws(() => originWithCredentials.getBaseUrl(), /配置无效/)
})

test('relative media URLs use the current API origin while absolute and local URLs are preserved', async () => {
  const format = await loadCommonJs('utils/format.js', {
    requireMap: {
      './config': {
        getBaseUrl: () => 'https://api.example.test'
      }
    }
  })

  assert.equal(
    format.resolveMediaUrl('/api/v1/storage/private/proof?token=SIGNED'),
    'https://api.example.test/api/v1/storage/private/proof?token=SIGNED'
  )
  assert.equal(format.resolveMediaUrl('api/v1/catalog/assets/7'), 'https://api.example.test/api/v1/catalog/assets/7')
  assert.equal(format.resolveMediaUrl('https://cdn.example.test/image.png'), 'https://cdn.example.test/image.png')
  assert.equal(format.resolveMediaUrl('//cdn.example.test/image.png'), 'https://cdn.example.test/image.png')
  assert.equal(format.resolveMediaUrl('wxfile://local-proof'), 'wxfile://local-proof')
  assert.equal(format.resolveMediaUrl('data:image/png;base64,AA=='), 'data:image/png;base64,AA==')
  assert.equal(format.resolveMediaUrl(''), '')
  assert.equal(
    format.resolveRichTextMedia('<p><img src="/api/v1/catalog/assets/7"><img src="https://cdn.example.test/8.png"></p>'),
    '<p><img src="https://api.example.test/api/v1/catalog/assets/7"><img src="https://cdn.example.test/8.png"></p>'
  )
})

function requestHarness(initialToken = '') {
  let token = initialToken
  const requests = []
  const uploads = []
  const removed = []
  const launches = []
  const app = { globalData: { token: initialToken } }
  const wx = {
    getStorageSync(key) {
      return key === 'market-shop-user-token' ? token : ''
    },
    setStorageSync(key, value) {
      if (key === 'market-shop-user-token') {
        token = value
      }
    },
    removeStorageSync(key) {
      removed.push(key)
      if (key === 'market-shop-user-token') {
        token = ''
      }
    },
    reLaunch(options) {
      launches.push(plain(options))
    },
    request(options) {
      requests.push(options)
    },
    uploadFile(options) {
      uploads.push(options)
    }
  }
  return {
    wx,
    app,
    requests,
    uploads,
    removed,
    launches,
    token: () => token
  }
}

async function loadRequest(harness, baseUrl = 'https://api.example.test') {
  return loadCommonJs('utils/request.js', {
    globals: {
      wx: harness.wx,
      getApp: () => harness.app
    },
    requireMap: {
      './config': { getBaseUrl: () => baseUrl }
    }
  })
}

test('protected requests and uploads send the member token Header without cookies', async () => {
  const harness = requestHarness('member-token-value')
  const client = await loadRequest(harness)

  const responsePromise = client.request('/membership/me')
  assert.equal(harness.requests.length, 1)
  assert.equal(harness.requests[0].url, 'https://api.example.test/api/v1/membership/me')
  assert.equal(harness.requests[0].method, 'GET')
  assert.deepEqual(plain(harness.requests[0].header), {
    'market-shop-user-token': 'member-token-value'
  })
  assert.equal(harness.requests[0].header.Cookie, undefined)
  harness.requests[0].success({
    statusCode: 200,
    data: { success: true, code: 'OK', data: { publicId: 'MEMBER-1' } }
  })
  assert.deepEqual(plain(await responsePromise), { publicId: 'MEMBER-1' })

  const publicPromise = client.request('/catalog/products', { auth: false })
  assert.equal(harness.requests[1].header['market-shop-user-token'], undefined)
  harness.requests[1].success({ statusCode: 200, data: { success: true, data: [] } })
  assert.deepEqual(plain(await publicPromise), [])

  const uploadPromise = client.uploadFile(
    '/after-sales/7/proofs',
    'wxfile://proof',
    { proofType: 'RETURN' }
  )
  assert.equal(harness.uploads[0].url, 'https://api.example.test/api/v1/after-sales/7/proofs')
  assert.equal(harness.uploads[0].header['market-shop-user-token'], 'member-token-value')
  assert.deepEqual(plain(harness.uploads[0].formData), { proofType: 'RETURN' })
  harness.uploads[0].success({
    statusCode: 200,
    data: JSON.stringify({ success: true, data: { id: 71 } })
  })
  assert.deepEqual(plain(await uploadPromise), { id: 71 })
})

test('401 clears the token and reLaunches login while preserving HTTP error metadata', async () => {
  const harness = requestHarness('expired-token')
  const client = await loadRequest(harness)
  const rejected = client.request('/orders').catch((error) => error)

  harness.requests[0].success({
    statusCode: 401,
    data: {
      success: false,
      code: 'NOT_LOGGED_IN',
      message: '登录已失效',
      data: { reason: 'expired' }
    }
  })
  const error = plain(await rejected)

  assert.equal(harness.token(), '')
  assert.equal(harness.app.globalData.token, '')
  assert.deepEqual(harness.removed, ['market-shop-user-token'])
  assert.deepEqual(harness.launches, [{ url: '/pages/login/login' }])
  assert.equal(error.code, 'NOT_LOGGED_IN')
  assert.equal(error.message, '登录已失效')
  assert.equal(error.status, 401)
  assert.equal(error.statusCode, 401)
  assert.deepEqual(error.data, { reason: 'expired' })
})

test('409 keeps the stable server code, status and authoritative response data', async () => {
  const harness = requestHarness('member-token')
  const client = await loadRequest(harness)
  const rejected = client.request('/orders/8/receive', { method: 'POST' }).catch((error) => error)

  harness.requests[0].success({
    statusCode: 409,
    data: {
      success: false,
      code: 'ORDER_STATE_CONFLICT',
      message: '订单状态已经变化',
      data: { orderId: 8, status: 'COMPLETED' }
    }
  })
  const error = plain(await rejected)

  assert.equal(error.code, 'ORDER_STATE_CONFLICT')
  assert.equal(error.status, 409)
  assert.equal(error.statusCode, 409)
  assert.deepEqual(error.data, { orderId: 8, status: 'COMPLETED' })
  assert.equal(harness.launches.length, 0)
})

test('native request failures use stable Chinese user-facing messages', async () => {
  const harness = requestHarness('member-token')
  const client = await loadRequest(harness)

  const requestFailure = client.request('/orders').catch((error) => error)
  harness.requests[0].fail({ errMsg: 'request:fail timeout' })
  assert.deepEqual(plain(await requestFailure), {
    code: 'NETWORK_ERROR',
    message: '网络异常，请稍后重试',
    status: 0,
    statusCode: 0,
    data: null,
    requestId: ''
  })

  const uploadFailure = client.uploadFile('/orders/7/proofs', 'wxfile://proof').catch((error) => error)
  harness.uploads[0].fail({ errMsg: 'uploadFile:fail socket error' })
  assert.equal((await uploadFailure).message, '网络异常，请稍后重试')
})
