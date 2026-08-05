const notifyApi = require('../../api/notify')
const { dateTime } = require('../../utils/format')
const { getToken } = require('../../utils/request')

const PAGE_SIZE = 20

function mapItem(row) {
  const read = row.status === 'READ' || !!row.readAt
  return {
    id: row.id,
    title: row.title || '',
    content: row.content || '',
    businessType: row.businessType || '',
    businessId: row.businessId,
    unread: !read,
    expanded: false,
    createdText: dateTime(row.createdAt)
  }
}

Page({
  data: {
    loading: true,
    loadingMore: false,
    items: [],
    page: 1,
    total: 0
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadFirst()
  },

  loadFirst() {
    this.setData({ loading: true })
    notifyApi
      .list(1, PAGE_SIZE)
      .then((res) => {
        const data = res || {}
        this.setData({
          items: (data.items || []).map(mapItem),
          total: data.total || 0,
          page: 1,
          loading: false
        })
      })
      .catch((err) => {
        this.setData({ loading: false, items: [], total: 0 })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({ title: (err && err.message) || '加载消息失败', icon: 'none' })
      })
  },

  loadMore() {
    if (this.data.loadingMore) {
      return
    }
    const nextPage = this.data.page + 1
    this.setData({ loadingMore: true })
    notifyApi
      .list(nextPage, PAGE_SIZE)
      .then((res) => {
        const data = res || {}
        this.setData({
          items: this.data.items.concat((data.items || []).map(mapItem)),
          total: data.total || this.data.total,
          page: nextPage,
          loadingMore: false
        })
      })
      .catch(() => {
        this.setData({ loadingMore: false })
        wx.showToast({ title: '加载失败，请稍后重试', icon: 'none' })
      })
  },

  onReachBottom() {
    if (this.data.loading || this.data.items.length >= this.data.total) {
      return
    }
    this.loadMore()
  },

  onTapItem(e) {
    const id = e.currentTarget.dataset.id
    const index = this.data.items.findIndex(function (item) {
      return item.id === id
    })
    if (index < 0) {
      return
    }
    const item = this.data.items[index]
    if (item.unread) {
      notifyApi.markRead(id).catch(function () {})
      this.setData({ ['items[' + index + '].unread']: false })
    }
    if (item.businessType === 'ORDER' && item.businessId) {
      wx.navigateTo({ url: '/pages/order/detail?id=' + item.businessId })
      return
    }
    if (item.businessType === 'AFTERSALE' && item.businessId) {
      wx.navigateTo({ url: '/pages/aftersale/detail?id=' + item.businessId })
      return
    }
    this.setData({ ['items[' + index + '].expanded']: !item.expanded })
  }
})
