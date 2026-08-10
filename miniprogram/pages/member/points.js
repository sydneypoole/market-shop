const memberApi = require('../../api/member')
const { dateTime } = require('../../utils/format')
const { getToken } = require('../../utils/request')

const ENTRY_TYPE = {
  FREEZE: '冻结',
  RELEASE: '释放',
  EARN: '获得',
  CONSUME: '扣减',
  REVERSE: '冲正',
  DIRECT_REFERRAL_AWARD: '直推奖励',
  FROZEN_POINTS_RELEASED: '冻结释放',
  REVERSAL: '冲正'
}

function signedDelta(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n === 0) {
    return null
  }
  return (n > 0 ? '+' : '-') + Math.abs(n)
}

Page({
  data: {
    loading: true,
    error: '',
    entries: []
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },

  load() {
    this.setData({ loading: true, error: '' })
    memberApi
      .ledger()
      .then((list) => {
        const entries = (list || []).map(function (e) {
          const available = signedDelta(e.availableDelta)
          const frozen = signedDelta(e.frozenDelta)
          return {
            id: e.id,
            typeText: ENTRY_TYPE[e.entryType] || '其他积分变动',
            availableText: available,
            availableTone: available ? (e.availableDelta > 0 ? 'positive' : 'negative') : '',
            frozenText: frozen,
            occurredText: dateTime(e.occurredAt),
            sourceOrderId: e.sourceOrderId || 0
          }
        })
        this.setData({ loading: false, entries: entries })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          entries: [],
          error: (err && err.message) || '积分流水加载失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  goOrder(e) {
    const id = Number(e.currentTarget.dataset.id)
    if (!id) {
      return
    }
    wx.navigateTo({ url: '/pages/order/detail?id=' + id })
  }
})
