const orderApi = require('../../api/order')
const { fenToYuan, dateTime } = require('../../utils/format')
const { statusText, statusTone } = require('../../utils/order-status')
const { getToken } = require('../../utils/request')

function mapOrder(row) {
  return {
    id: row.id,
    orderNo: row.orderNo,
    statusText: statusText(row.status),
    tone: statusTone(row.status),
    amountText: fenToYuan(row.totalAmountFen),
    createdText: dateTime(row.createdAt)
  }
}

Page({
  data: {
    loading: true,
    error: '',
    orders: []
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadOrders()
  },

  loadOrders() {
    this.setData({ loading: true, error: '' })
    orderApi
      .superiorOrders()
      .then((rows) => {
        this.setData({ orders: (rows || []).map(mapOrder), loading: false })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          orders: [],
          error: (err && err.message) || '加载订单失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!id) {
      return
    }
    wx.navigateTo({ url: '/pages/order/detail?id=' + id })
  }
})
