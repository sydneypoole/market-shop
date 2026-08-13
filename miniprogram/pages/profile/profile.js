const authApi = require('../../api/auth')
const memberApi = require('../../api/member')
const notifyApi = require('../../api/notify')
const { getToken, setToken } = require('../../utils/request')
const { nicknameInitial, resolveOwnedAvatarUrl } = require('../../utils/member-profile')

Page({
  data: {
    loading: true,
    error: '',
    nickname: '',
    avatarUrl: '',
    avatarFailed: false,
    avatarFallback: '会',
    phoneMasked: '',
    publicId: '',
    levelName: '',
    unreadCount: 0,
    loggingOut: false
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadProfile()
    this.loadUnreadCount()
  },

  loadProfile() {
    this.setData({ loading: true, error: '' })
    return Promise.all([authApi.me(), memberApi.me()])
      .then((results) => {
        const data = results[0]
        const membership = results[1]
        const nickname = (membership && membership.nickname) || (data && data.nickname) || '宏杉会员'
        const avatarUrl = resolveOwnedAvatarUrl(membership && membership.avatarUrl)
        this.setData({
          nickname: nickname,
          avatarUrl: avatarUrl,
          avatarFailed: false,
          avatarFallback: nicknameInitial(nickname),
          phoneMasked: (membership && membership.phoneMasked) || '',
          publicId: (data && data.publicId) || '',
          levelName: (membership && membership.levelName) || '会员',
          loading: false,
          error: ''
        })
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          this.setData({ loading: false })
          return
        }
        this.setData({
          loading: false,
          error: (err && err.message) || '加载用户信息失败'
        })
      })
  },

  onAvatarError() {
    this.setData({ avatarFailed: true })
  },

  loadUnreadCount() {
    notifyApi
      .unreadCount()
      .then((count) => {
        this.setData({ unreadCount: Number(count) || 0 })
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({ title: (err && err.message) || '消息数量加载失败', icon: 'none' })
      })
  },

  goMemberCenter() {
    wx.navigateTo({ url: '/pages/member/center' })
  },

  goProfileEdit() {
    wx.navigateTo({ url: '/pages/profile/edit' })
  },

  goNotify() {
    wx.navigateTo({ url: '/pages/notify/list' })
  },

  goAftersaleList() {
    wx.navigateTo({ url: '/pages/aftersale/list' })
  },

  goRules() {
    wx.navigateTo({ url: '/pages/rules/rules' })
  },

  goAllOrders() {
    wx.navigateTo({ url: '/pages/order/list' })
  },

  goSuperiorOrders() {
    wx.navigateTo({ url: '/pages/order/superior' })
  },

  goOrderTab(e) {
    const tab = e.currentTarget.dataset.tab
    wx.navigateTo({ url: '/pages/order/list?tab=' + tab })
  },

  goAddress() {
    wx.navigateTo({ url: '/pages/address/list' })
  },

  onLogout() {
    if (this.data.loggingOut) {
      return
    }
    wx.showModal({
      title: '退出登录',
      content: '确认退出当前账号？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.setData({ loggingOut: true })
        authApi
          .logout()
          .then(() => {
            setToken('')
            this.setData({ loggingOut: false })
            wx.reLaunch({ url: '/pages/login/login' })
          })
          .catch((err) => {
            this.setData({ loggingOut: false })
            if (err && err.code === 'NOT_LOGGED_IN') {
              return
            }
            wx.showToast({ title: (err && err.message) || '退出失败，请重试', icon: 'none' })
          })
      }
    })
  }
})
