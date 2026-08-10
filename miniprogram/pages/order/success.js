const { fenToYuan } = require('../../utils/format')

function safeDecode(value) {
  try {
    return decodeURIComponent(String(value))
  } catch (e) {
    return ''
  }
}

Page({
  data: {
    valid: false,
    error: '',
    orderNo: '',
    orderId: '',
    totalText: '0.00'
  },

  onLoad(query) {
    const q = query || {}
    const orderNo = q.orderNo ? safeDecode(q.orderNo).trim() : ''
    const orderId = q.id != null && q.id !== '' ? safeDecode(q.id).trim() : ''
    const totalText = q.total != null && q.total !== '' ? safeDecode(q.total).trim() : ''
    const idNumber = Number(orderId)
    const totalFen = Number(totalText)
    const valid = !!orderNo && Number.isInteger(idNumber) && idNumber > 0 && Number.isInteger(totalFen) && totalFen > 0
    if (!valid) {
      this.setData({
        valid: false,
        error: '订单提交信息无效',
        orderNo: '',
        orderId: '',
        totalText: '0.00'
      })
      wx.setNavigationBarTitle({ title: '订单信息无效' })
      return
    }
    this.setData({
      valid: true,
      error: '',
      orderNo: orderNo,
      orderId: orderId,
      totalText: fenToYuan(totalFen)
    })
  },

  goOrderDetail() {
    const id = this.data.orderId
    if (!this.data.valid || !id) {
      wx.showToast({ title: '订单信息缺失', icon: 'none' })
      return
    }
    wx.redirectTo({ url: '/pages/order/detail?id=' + encodeURIComponent(String(id)) })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  }
})
