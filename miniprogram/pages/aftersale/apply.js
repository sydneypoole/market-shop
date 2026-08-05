const aftersaleApi = require('../../api/aftersale')
const orderApi = require('../../api/order')
const { fenToYuan } = require('../../utils/format')
const { getToken } = require('../../utils/request')

const TYPES = [
  { key: 'REFUND_ONLY', label: '仅退款', desc: '未收到货，或已与商家协商无需退货' },
  { key: 'RETURN_REFUND', label: '退货退款', desc: '已收到货，寄回商品后退款' }
]

function makeClientRequestId() {
  return String(Date.now()) + '-' + Math.random().toString(36).slice(2, 10)
}

Page({
  data: {
    orderId: 0,
    orderNo: '',
    totalText: '',
    types: TYPES,
    type: 'REFUND_ONLY',
    reason: '',
    description: '',
    submitting: false
  },

  onLoad(query) {
    const orderId = Number(query && query.orderId)
    this.setData({ orderId: orderId })
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!orderId) {
      return
    }
    orderApi
      .detail(orderId)
      .then((d) => {
        const order = d && d.order
        if (!order) {
          return
        }
        this.setData({
          orderNo: order.orderNo || '',
          totalText: fenToYuan(order.totalAmountFen)
        })
      })
      .catch(() => {})
  },

  onSelectType(e) {
    const key = e.currentTarget.dataset.key
    if (key) {
      this.setData({ type: key })
    }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (field) {
      this.setData({ [field]: e.detail.value })
    }
  },

  onSubmit() {
    if (this.data.submitting) {
      return
    }
    if (!this.data.orderId) {
      wx.showToast({ title: '订单信息缺失', icon: 'none' })
      return
    }
    const reason = (this.data.reason || '').trim()
    if (!reason) {
      wx.showToast({ title: '请填写申请原因', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    aftersaleApi
      .apply({
        orderId: this.data.orderId,
        clientRequestId: makeClientRequestId(),
        type: this.data.type,
        reason: reason,
        description: (this.data.description || '').trim()
      })
      .then((view) => {
        const id = view && view.id
        wx.showToast({ title: '已提交', icon: 'none' })
        if (id) {
          wx.redirectTo({ url: '/pages/aftersale/detail?id=' + id })
        } else {
          wx.redirectTo({ url: '/pages/aftersale/list' })
        }
      })
      .catch((err) => {
        this.setData({ submitting: false })
        wx.showToast({ title: (err && err.message) || '提交失败', icon: 'none' })
      })
  }
})
