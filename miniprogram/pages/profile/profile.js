const authApi = require('../../api/auth')
const memberApi = require('../../api/member')
const systemApi = require('../../api/system')
const notifyApi = require('../../api/notify')
const { getToken, setToken } = require('../../utils/request')

Page({
  data: {
    nickname: '',
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
    Promise.all([authApi.me(), memberApi.me()])
      .then((results) => {
        const data = results[0]
        const membership = results[1]
        this.setData({
          nickname: (data && data.nickname) || '拾光会员',
          publicId: (data && data.publicId) || '',
          levelName: (membership && membership.levelName) || '会员'
        })
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({
          title: (err && err.message) || '加载用户信息失败',
          icon: 'none'
        })
      })
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
  },

  onAbout() {
    systemApi
      .about()
      .then((data) => {
        const name = (data && data.name) || '拾光优选'
        const lines = [
          name,
          '在线支付：' + ((data && data.onlinePaymentEnabled) ? '已开启' : '未开启'),
          '积分不可兑现金：' + ((data && data.pointsCashEquivalent) ? '否' : '是'),
          '奖励深度：' + ((data && data.rewardDepth) != null ? data.rewardDepth : 1) + ' 层'
        ]
        wx.showModal({
          title: '关于拾光优选',
          content: lines.join('\n'),
          showCancel: false,
          confirmText: '知道了'
        })
      })
      .catch((err) => {
        wx.showToast({
          title: (err && err.message) || '获取关于信息失败',
          icon: 'none'
        })
      })
  }
})
