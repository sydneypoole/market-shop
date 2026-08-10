const aftersaleApi = require('../../api/aftersale')
const orderApi = require('../../api/order')
const authApi = require('../../api/auth')
const { fenToYuan } = require('../../utils/format')
const { getToken, isConflict } = require('../../utils/request')
const { makeClientRequestId } = require('../../utils/client-request')

const TYPES = [
  { key: 'REFUND_ONLY', label: '仅退款', desc: '未收到货，或已与商家协商无需退货' },
  { key: 'RETURN_REFUND', label: '退货退款', desc: '已收到货，寄回商品后退款' }
]

Page({
  data: {
    orderId: 0,
    orderNo: '',
    totalText: '',
    types: TYPES,
    type: 'REFUND_ONLY',
    reason: '',
    description: '',
    submitting: false,
    orderLoading: false,
    orderLoadError: '',
    orderRetryable: false
  },

  _clientRequestId: '',

  onLoad(query) {
    const orderId = Number(query && query.orderId)
    const validOrderId = Number.isInteger(orderId) && orderId > 0
    this.setData({
      orderId: validOrderId ? orderId : 0,
      orderLoadError: validOrderId ? '' : '订单参数无效',
      orderRetryable: false
    })
    this._clientRequestId = makeClientRequestId('aftersale')
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!validOrderId) {
      return
    }
    this.loadOrder()
  },

  loadOrder() {
    const orderId = this.data.orderId
    if (!orderId || (this.data.orderLoadError && !this.data.orderRetryable)) {
      return
    }
    this.setData({ orderLoading: true, orderLoadError: '', orderRetryable: false })
    return Promise.all([orderApi.detail(orderId), authApi.me()])
      .then((results) => {
        const d = results[0]
        const me = results[1]
        const order = d && d.order
        if (!order) {
          this.setData({ orderLoading: false, orderLoadError: '订单不存在或已失效', orderRetryable: false })
          return
        }
        const actorId = Number(me && me.userId)
        const buyerUserId = Number(order.buyerUserId)
        if (!Number.isInteger(actorId) || actorId <= 0 || actorId !== buyerUserId) {
          this.setData({
            orderLoading: false,
            orderLoadError: '仅订单购买人可申请售后',
            orderRetryable: false,
            orderNo: ''
          })
          return
        }
        if (['SHIPPED', 'COMPLETED'].indexOf(order.status) < 0) {
          this.setData({
            orderLoading: false,
            orderLoadError: '当前订单状态暂不支持申请售后',
            orderRetryable: false,
            orderNo: ''
          })
          return
        }
        this.setData({
          orderNo: order.orderNo || '',
          totalText: fenToYuan(order.totalAmountFen),
          orderLoading: false,
          orderLoadError: '',
          orderRetryable: false
        })
      })
      .catch((err) => {
        this.setData({
          orderLoading: false,
          orderLoadError: (err && err.message) || '订单信息加载失败',
          orderRetryable: Number(err && (err.statusCode || err.status)) !== 404
        })
      })
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
    if (!this.data.orderId || !this.data.orderNo || this.data.orderLoadError) {
      wx.showToast({ title: '订单信息缺失', icon: 'none' })
      return
    }
    const reason = (this.data.reason || '').trim()
    if (!reason) {
      wx.showToast({ title: '请填写申请原因', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    const clientRequestId = this._clientRequestId || makeClientRequestId('aftersale')
    this._clientRequestId = clientRequestId
    aftersaleApi
      .apply({
        orderId: this.data.orderId,
        clientRequestId: clientRequestId,
        type: this.data.type,
        reason: reason,
        description: (this.data.description || '').trim()
      })
      .then((view) => {
        this._clientRequestId = ''
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
        if (isConflict(err)) {
          wx.showToast({ title: '订单或售后状态已变化，正在刷新', icon: 'none' })
          this.loadOrder()
          return
        }
        wx.showToast({ title: (err && err.message) || '提交失败', icon: 'none' })
      })
  }
})
