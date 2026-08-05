const { fenToYuan } = require('../../utils/format')

Page({
  data: {
    orderNo: '',
    orderId: '',
    totalText: '0.00'
  },

  onLoad(query) {
    const q = query || {}
    const orderNo = q.orderNo ? decodeURIComponent(q.orderNo) : ''
    const orderId = q.id != null && q.id !== '' ? decodeURIComponent(String(q.id)) : ''
    let totalFen = 0
    if (q.total != null && q.total !== '') {
      const n = Number(decodeURIComponent(String(q.total)))
      if (Number.isFinite(n)) {
        totalFen = n
      }
    }
    this.setData({
      orderNo: orderNo,
      orderId: orderId,
      totalText: fenToYuan(totalFen)
    })
  },

  goOrderDetail() {
    const id = this.data.orderId
    if (!id) {
      wx.showToast({ title: '订单信息缺失', icon: 'none' })
      return
    }
    wx.redirectTo({ url: '/pages/order/detail?id=' + encodeURIComponent(String(id)) })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  }
})
