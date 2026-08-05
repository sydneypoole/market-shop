const authApi = require('../../api/auth')
const { getToken, setToken } = require('../../utils/request')

Page({
  data: {
    inviteCode: '',
    loading: false
  },

  onShow() {
    if (getToken()) {
      wx.reLaunch({ url: '/pages/index/index' })
    }
  },

  onInviteInput(e) {
    this.setData({ inviteCode: (e.detail.value || '').trim() })
  },

  onLogin() {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })
    const inviteCode = this.data.inviteCode

    wx.login({
      success: (loginRes) => {
        const code = loginRes && loginRes.code
        if (!code) {
          this.setData({ loading: false })
          wx.showToast({ title: '获取微信登录凭证失败', icon: 'none' })
          return
        }

        authApi
          .login(code, inviteCode || undefined)
          .then((data) => {
            this.setData({ loading: false })
            if (!data || !data.token) {
              throw { code: 'LOGIN_FAILED', message: '登录失败，未返回会话' }
            }
            setToken(data.token)
            if (data.newlyRegistered) {
              wx.showToast({ title: '欢迎加入拾光优选', icon: 'none' })
            }
            wx.reLaunch({ url: '/pages/index/index' })
          })
          .catch((err) => {
            this.setData({ loading: false })
            const message = (err && err.message) || '登录失败'
            wx.showToast({ title: message, icon: 'none', duration: 2500 })
          })
      },
      fail: () => {
        this.setData({ loading: false })
        wx.showToast({ title: '微信登录不可用', icon: 'none' })
      }
    })
  }
})
