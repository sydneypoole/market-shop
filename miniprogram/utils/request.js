const { getBaseUrl } = require('./config')

const TOKEN_KEY = 'market-shop-user-token'
const API_PREFIX = '/api/v1'

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || ''
}

function setToken(token) {
  if (token) {
    wx.setStorageSync(TOKEN_KEY, token)
  } else {
    wx.removeStorageSync(TOKEN_KEY)
  }
  try {
    const app = getApp()
    if (app && app.globalData) {
      app.globalData.token = token || ''
    }
  } catch (e) {
    // getApp may be unavailable during early module load
  }
}

function handleUnauthorized() {
  setToken('')
  wx.reLaunch({ url: '/pages/login/login' })
}

function parseEnvelope(raw) {
  if (raw == null || raw === '') {
    return null
  }
  if (typeof raw === 'object') {
    return raw
  }
  try {
    return JSON.parse(raw)
  } catch (e) {
    return null
  }
}

function requestError(code, message, statusCode, body, headers) {
  const status = Number(statusCode) || 0
  return {
    code: code || 'REQUEST_FAILED',
    message: message || '请求失败',
    status: status,
    statusCode: status,
    data: body && Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : null,
    requestId:
      (body && (body.requestId || body.request_id)) ||
      (headers && (headers['x-request-id'] || headers['X-Request-Id'])) ||
      ''
  }
}

function configRequestError(err) {
  return requestError(
    (err && err.code) || 'API_BASE_URL_INVALID',
    (err && err.message) || '小程序接口地址配置无效',
    0,
    null,
    null
  )
}

function buildApiUrl(path) {
  return getBaseUrl() + API_PREFIX + path
}

function handleResponse(res, resolve, reject, fallbackCode, fallbackMessage) {
  const status = res.statusCode || 0
  const body = parseEnvelope(res.data)

  if (status === 401 || (body && body.code === 'NOT_LOGGED_IN')) {
    handleUnauthorized()
    reject(requestError('NOT_LOGGED_IN', (body && body.message) || '请先登录', status, body, res.header))
    return
  }

  if (!body) {
    reject(requestError('BAD_RESPONSE', fallbackMessage, status, null, res.header))
    return
  }

  if (body.success) {
    resolve(body.data)
    return
  }

  reject(requestError(body.code || fallbackCode, body.message || fallbackMessage, status, body, res.header))
}

function request(path, opts) {
  const options = opts || {}
  const method = (options.method || 'GET').toUpperCase()
  const auth = options.auth !== false
  const data = options.data
  const header = Object.assign({}, options.header || {})

  if (auth) {
    const token = getToken()
    if (token) {
      header[TOKEN_KEY] = token
    }
  }

  if (method !== 'GET' && !header['Content-Type']) {
    header['Content-Type'] = 'application/json'
  }

  return new Promise(function (resolve, reject) {
    let url
    try {
      url = buildApiUrl(path)
    } catch (err) {
      reject(configRequestError(err))
      return
    }

    wx.request({
      url: url,
      method: method,
      data: data,
      header: header,
      success: function (res) {
        handleResponse(res, resolve, reject, 'REQUEST_FAILED', '服务响应异常')
      },
      fail: function () {
        reject(requestError('NETWORK_ERROR', '网络异常，请稍后重试', 0, null, null))
      }
    })
  })
}

function uploadFile(path, filePath, formData) {
  const header = {}
  const token = getToken()
  if (token) {
    header[TOKEN_KEY] = token
  }

  return new Promise(function (resolve, reject) {
    let url
    try {
      url = buildApiUrl(path)
    } catch (err) {
      reject(configRequestError(err))
      return
    }

    wx.uploadFile({
      url: url,
      filePath: filePath,
      name: 'file',
      header: header,
      formData: Object.assign({}, formData || {}),
      success: function (res) {
        handleResponse(res, resolve, reject, 'UPLOAD_FAILED', '上传响应异常')
      },
      fail: function () {
        reject(requestError('NETWORK_ERROR', '网络异常，请稍后重试', 0, null, null))
      }
    })
  })
}

function isConflict(err) {
  return !!err && (Number(err.statusCode || err.status) === 409 || /CONFLICT$/.test(String(err.code || '')))
}

module.exports = {
  request,
  uploadFile,
  getToken,
  setToken,
  isConflict,
  TOKEN_KEY,
  API_PREFIX
}
