const { BASE_URL } = require('./config')

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

  const url = BASE_URL + API_PREFIX + path

  return new Promise(function (resolve, reject) {
    wx.request({
      url: url,
      method: method,
      data: data,
      header: header,
      success: function (res) {
        const status = res.statusCode || 0
        const body = parseEnvelope(res.data)

        if (status === 401 || (body && body.code === 'NOT_LOGGED_IN')) {
          handleUnauthorized()
          reject({ code: 'NOT_LOGGED_IN', message: (body && body.message) || '请先登录' })
          return
        }

        if (!body) {
          reject({ code: 'BAD_RESPONSE', message: '服务响应异常' })
          return
        }

        if (body.success) {
          resolve(body.data)
          return
        }

        reject({ code: body.code || 'REQUEST_FAILED', message: body.message || '请求失败' })
      },
      fail: function (err) {
        reject({
          code: 'NETWORK_ERROR',
          message: (err && err.errMsg) || '网络异常，请稍后重试'
        })
      }
    })
  })
}

function uploadFile(path, filePath) {
  const header = {}
  const token = getToken()
  if (token) {
    header[TOKEN_KEY] = token
  }

  const url = BASE_URL + API_PREFIX + path

  return new Promise(function (resolve, reject) {
    wx.uploadFile({
      url: url,
      filePath: filePath,
      name: 'file',
      header: header,
      success: function (res) {
        const status = res.statusCode || 0
        const body = parseEnvelope(res.data)

        if (status === 401 || (body && body.code === 'NOT_LOGGED_IN')) {
          handleUnauthorized()
          reject({ code: 'NOT_LOGGED_IN', message: (body && body.message) || '请先登录' })
          return
        }

        if (!body) {
          reject({ code: 'BAD_RESPONSE', message: '上传响应异常' })
          return
        }

        if (body.success) {
          resolve(body.data)
          return
        }

        reject({ code: body.code || 'UPLOAD_FAILED', message: body.message || '上传失败' })
      },
      fail: function (err) {
        reject({
          code: 'NETWORK_ERROR',
          message: (err && err.errMsg) || '网络异常，请稍后重试'
        })
      }
    })
  })
}

module.exports = {
  request,
  uploadFile,
  getToken,
  setToken,
  TOKEN_KEY
}
