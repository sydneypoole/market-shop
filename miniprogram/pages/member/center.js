const memberApi = require('../../api/member')
const { getToken } = require('../../utils/request')

Page({
  data: {
    loading: true,
    error: '',
    info: null
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
      .me()
      .then((info) => {
        this.setData({ loading: false, info: info || null })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          info: null,
          error: (err && err.message) || '会员信息加载失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  goInvite() {
    wx.navigateTo({ url: '/pages/member/invite' })
  },

  goPoints() {
    wx.navigateTo({ url: '/pages/member/points' })
  },

  goMembers() {
    wx.navigateTo({ url: '/pages/member/invite?tab=members' })
  }
})
