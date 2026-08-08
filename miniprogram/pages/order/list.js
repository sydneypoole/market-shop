const orderApi = require('../../api/order')
const { fenToYuan, dateTime } = require('../../utils/format')
const { statusText, statusTone, resolveOrderActions } = require('../../utils/order-status')
const { getToken, isConflict } = require('../../utils/request')

const TABS = [
  { key: 'ALL', label: '全部', status: null },
  { key: 'PENDING', label: '待确认', status: 'PENDING_SUPERIOR' },
  { key: 'SHIPMENT', label: '待发货', status: 'PENDING_SHIPMENT' },
  { key: 'RECEIVE', label: '待收货', status: 'SHIPPED' },
  { key: 'DONE', label: '已完成', status: 'COMPLETED' }
]

function actionsFromStatus(status) {
  return resolveOrderActions({
    canCancel: status === 'PENDING_SUPERIOR',
    canUploadProof: status === 'PENDING_SUPERIOR',
    canReceive: status === 'SHIPPED',
    canSuperiorDecide: false
  })
}

function mapOrder(row) {
  return {
    id: row.id,
    orderNo: row.orderNo,
    status: row.status,
    statusText: statusText(row.status),
    tone: statusTone(row.status),
    amountText: fenToYuan(row.totalAmountFen),
    createdText: dateTime(row.createdAt),
    actions: actionsFromStatus(row.status)
  }
}

Page({
  data: {
    tabs: TABS,
    activeTab: 0,
    loading: true,
    error: '',
    allOrders: [],
    displayList: [],
    actionPendingId: 0
  },

  onLoad(query) {
    const tab = Number(query && query.tab)
    if (Number.isFinite(tab) && tab >= 0 && tab < TABS.length) {
      this.setData({ activeTab: tab })
    }
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadOrders()
  },

  noop() {},

  applyFilter() {
    const tab = TABS[this.data.activeTab] || TABS[0]
    const list = this.data.allOrders || []
    const displayList = tab.status
      ? list.filter(function (item) {
          return item.status === tab.status
        })
      : list
    this.setData({ displayList: displayList })
  },

  loadOrders() {
    this.setData({ loading: true, error: '' })
    orderApi
      .list()
      .then((rows) => {
        const allOrders = (rows || []).map(mapOrder)
        this.setData({ allOrders: allOrders, loading: false })
        this.applyFilter()
      })
      .catch((err) => {
        this.setData({
          loading: false,
          allOrders: [],
          displayList: [],
          error: (err && err.message) || '加载订单失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  onTabChange(e) {
    const index = Number(e.currentTarget.dataset.index)
    if (index === this.data.activeTab) {
      return
    }
    this.setData({ activeTab: index })
    this.applyFilter()
  },

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!id) {
      return
    }
    wx.navigateTo({ url: '/pages/order/detail?id=' + id })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  onCancel(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.actionPendingId) {
      return
    }
    wx.showModal({
      title: '取消订单',
      editable: true,
      placeholderText: '请填写取消原因',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        const reason = (res.content || '').trim()
        if (!reason) {
          wx.showToast({ title: '请填写取消原因', icon: 'none' })
          return
        }
        this.setData({ actionPendingId: id })
        orderApi
          .cancel(id, reason)
          .then(() => {
            this.setData({ actionPendingId: 0 })
            wx.showToast({ title: '已取消', icon: 'none' })
            this.loadOrders()
          })
          .catch((err) => {
            this.setData({ actionPendingId: 0 })
            wx.showToast({ title: isConflict(err) ? '订单状态已变化，正在刷新' : ((err && err.message) || '取消失败'), icon: 'none' })
            if (isConflict(err)) {
              this.loadOrders()
            }
          })
      }
    })
  },

  onReceive(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.actionPendingId) {
      return
    }
    wx.showModal({
      title: '确认收货',
      content: '确认已收到商品？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.setData({ actionPendingId: id })
        orderApi
          .receive(id)
          .then(() => {
            this.setData({ actionPendingId: 0 })
            wx.showToast({ title: '已确认收货', icon: 'none' })
            this.loadOrders()
          })
          .catch((err) => {
            this.setData({ actionPendingId: 0 })
            wx.showToast({ title: isConflict(err) ? '订单状态已变化，正在刷新' : ((err && err.message) || '操作失败'), icon: 'none' })
            if (isConflict(err)) {
              this.loadOrders()
            }
          })
      }
    })
  },

  onUploadProof(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/order/detail?id=' + id + '&action=upload' })
  }
})
