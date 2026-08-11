const authApi = require('../../api/auth')
const { getToken, setToken } = require('../../utils/request')
const { DEFAULT_NAVIGATION_METRICS, getNavigationMetrics } = require('../../utils/navigation')

function loginErrorMessage(err) {
  if (err && err.code === 'INVITE_CODE_REQUIRED') {
    return '当前微信尚未注册，请先完成注册'
  }
  return (err && err.message) || '登录失败，请稍后重试'
}

Page({
  data: {
    loading: false,
    error: '',
    navigation: DEFAULT_NAVIGATION_METRICS
  },

  onLoad() {
    this.setData({ navigation: getNavigationMetrics(wx) })
  },

  onShow() {
    if (getToken()) {
      wx.reLaunch({ url: '/pages/index/index' })
    }
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  goRegister() {
    if (this.data.loading) {
      return
    }
    wx.redirectTo({ url: '/pages/register/register' })
  },

  onLogin() {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true, error: '' })

    wx.login({
      success: (loginRes) => {
        const code = loginRes && loginRes.code
        if (!code) {
          this.setData({ loading: false, error: '获取微信登录凭证失败，请重试' })
          return
        }

        authApi
          .login(code)
          .then((data) => {
            if (!data || !data.token) {
              throw { code: 'LOGIN_FAILED', message: '登录失败，未返回会话' }
            }
            setToken(data.token)
            this.setData({ loading: false, error: '' })
            wx.reLaunch({ url: '/pages/index/index' })
          })
          .catch((err) => {
            this.setData({ loading: false, error: loginErrorMessage(err) })
          })
      },
      fail: () => {
        this.setData({ loading: false, error: '微信登录不可用，请稍后重试' })
      }
    })
  }
})
