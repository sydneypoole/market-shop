const authApi = require('../../api/auth')
const systemApi = require('../../api/system')
const { getToken } = require('../../utils/request')

Page({
  data: {
    nickname: '',
    publicId: ''
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadProfile()
  },

  loadProfile() {
    authApi
      .me()
      .then((data) => {
        this.setData({
          nickname: (data && data.nickname) || '拾光会员',
          publicId: (data && data.publicId) || ''
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

  goAllOrders() {
    wx.navigateTo({ url: '/pages/order/list' })
  },

  goOrderTab(e) {
    const tab = e.currentTarget.dataset.tab
    wx.navigateTo({ url: '/pages/order/list?tab=' + tab })
  },

  goAddress() {
    wx.navigateTo({ url: '/pages/address/list' })
  },

  onContact() {
    wx.showToast({ title: '暂未开放', icon: 'none' })
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
