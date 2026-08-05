const addressApi = require('../../api/address')
const { getToken } = require('../../utils/request')

function maskPhone(phone) {
  const s = String(phone || '')
  if (s.length < 7) {
    return s
  }
  return s.slice(0, 3) + '****' + s.slice(-4)
}

function mapAddress(row) {
  const region = [row.province, row.city, row.district].filter(Boolean).join('')
  const postal = row.postalCode ? '(' + row.postalCode + ')' : ''
  return {
    id: row.id,
    recipientName: row.recipientName,
    phone: row.phone,
    phoneMasked: maskPhone(row.phone),
    fullAddress: region + (row.detailAddress || '') + (postal ? ' ' + postal : ''),
    defaultAddress: !!row.defaultAddress,
    version: row.version,
    raw: row
  }
}

Page({
  data: {
    loading: true,
    list: [],
    selectMode: false
  },

  onLoad(query) {
    this.setData({ selectMode: String((query && query.select) || '') === '1' })
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadList()
  },

  noop() {},

  loadList() {
    this.setData({ loading: true })
    addressApi
      .list()
      .then((rows) => {
        this.setData({
          loading: false,
          list: (rows || []).map(mapAddress)
        })
      })
      .catch((err) => {
        this.setData({ loading: false, list: [] })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({ title: (err && err.message) || '加载地址失败', icon: 'none' })
      })
  },

  onCardTap(e) {
    if (!this.data.selectMode) {
      return
    }
    const index = Number(e.currentTarget.dataset.index)
    const item = this.data.list[index]
    if (!item || !item.raw) {
      return
    }
    try {
      const app = getApp()
      if (app && app.globalData) {
        app.globalData.selectedAddress = item.raw
      }
    } catch (err) {
      // ignore
    }
    wx.navigateBack()
  },

  onEdit(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/address/edit?id=' + id })
  },

  onAdd() {
    wx.navigateTo({ url: '/pages/address/edit' })
  }
})
