const BASE_URLS = Object.freeze({
  develop: 'https://shop.cllbmz.kdns.fr',
  trial: 'https://shop.cllbmz.kdns.fr',
  release: 'https://shop.cllbmz.kdns.fr'
})

function runtimeWx() {
  return typeof wx === 'undefined' ? null : wx
}

function getEnvVersion() {
  const api = runtimeWx()
  if (!api || typeof api.getAccountInfoSync !== 'function') {
    return 'develop'
  }
  try {
    const account = api.getAccountInfoSync() || {}
    const version = account.miniProgram && account.miniProgram.envVersion
    return BASE_URLS[version] ? version : 'develop'
  } catch (e) {
    return 'develop'
  }
}

function getExtConfig() {
  const api = runtimeWx()
  if (!api || typeof api.getExtConfigSync !== 'function') {
    return {}
  }
  try {
    return api.getExtConfigSync() || {}
  } catch (e) {
    return {}
  }
}

function normalizeBaseUrl(value) {
  return String(value || '').trim().replace(/\/+$/, '')
}

function configError(code, message) {
  const err = new Error(message)
  err.code = code
  err.status = 0
  err.statusCode = 0
  return err
}

function getBaseUrl() {
  const envVersion = getEnvVersion()
  const extConfig = getExtConfig()
  const overridden = normalizeBaseUrl(extConfig.apiBaseUrl)
  const baseUrl = overridden || BASE_URLS[envVersion]

  if (!/^https?:\/\/[^/?#\s@]+$/i.test(baseUrl)) {
    throw configError('API_BASE_URL_INVALID', '小程序接口地址配置无效')
  }
  if (envVersion !== 'develop' && !/^https:\/\//i.test(baseUrl)) {
    throw configError('API_BASE_URL_HTTPS_REQUIRED', '体验版和正式版接口地址必须使用 HTTPS')
  }
  return baseUrl
}

module.exports = {
  BASE_URLS,
  // 兼容旧引用；运行时请求和媒体地址必须调用 getBaseUrl()，避免缓存 envVersion/extConfig。
  BASE_URL: BASE_URLS.develop,
  getEnvVersion,
  getBaseUrl,
  normalizeBaseUrl
}
