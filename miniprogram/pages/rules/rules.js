const rulesApi = require('../../api/rules')
const { dateTime } = require('../../utils/format')
const { getToken } = require('../../utils/request')

const TYPE_TEXT = {
  ORDER_TIMERS: '订单时效'
}

function stringifyValue(value) {
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

function parseParams(parametersJson) {
  try {
    const obj = JSON.parse(parametersJson || '{}')
    if (!obj || typeof obj !== 'object') {
      return []
    }
    return Object.keys(obj).map(function (key) {
      return { key: key, value: stringifyValue(obj[key]) }
    })
  } catch (e) {
    return []
  }
}

function mapRule(row) {
  return {
    id: row.id,
    title: TYPE_TEXT[row.ruleType] || row.ruleType || row.ruleCode || '',
    version: row.version,
    effectiveText: dateTime(row.effectiveFrom),
    params: parseParams(row.parametersJson)
  }
}

Page({
  data: {
    loading: true,
    rules: []
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadRules()
  },

  loadRules() {
    this.setData({ loading: true })
    rulesApi
      .active()
      .then((rows) => {
        this.setData({ rules: (rows || []).map(mapRule), loading: false })
      })
      .catch((err) => {
        this.setData({ loading: false, rules: [] })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({ title: (err && err.message) || '加载规则失败', icon: 'none' })
      })
  }
})
