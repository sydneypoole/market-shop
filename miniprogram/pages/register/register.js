const authApi = require('../../api/auth')
const { getToken, setToken } = require('../../utils/request')
const { DEFAULT_NAVIGATION_METRICS, getNavigationMetrics } = require('../../utils/navigation')

const INVITE_FIELD_ERRORS = new Set([
  'INVITE_CODE_REQUIRED',
  'INVITE_CODE_INVALID',
  'INVITE_CODE_EXPIRED',
  'INVITE_CODE_EXHAUSTED'
])

const CLAIM_FIELD_ERRORS = new Set([
  'SPONSOR_CLAIM_SECRET_INVALID',
  'SPONSOR_CLAIM_PROVIDER_INVALID',
  'SPONSOR_CLAIM_IDENTITY_CONFLICT',
  'SPONSOR_CLAIM_CONFLICT'
])

function safeDecode(value) {
  try {
    return decodeURIComponent(String(value))
  } catch (e) {
    return ''
  }
}

Page({
  data: {
    inviteCode: '',
    sponsorClaimSecret: '',
    credentialMode: 'invite',
    fieldError: '',
    formError: '',
    loading: false,
    navigation: DEFAULT_NAVIGATION_METRICS
  },

  onLoad(query) {
    const claimMode = query && query.mode === 'sponsor'
    let inviteCode = ''
    if (!claimMode && query) {
      if (query.inviteCode) {
        inviteCode = safeDecode(query.inviteCode).trim()
      } else if (query.scene) {
        inviteCode = safeDecode(query.scene).trim()
      }
    }
    this.setData({
      navigation: getNavigationMetrics(wx),
      credentialMode: claimMode ? 'claim' : 'invite',
      inviteCode: inviteCode.slice(0, 64)
    })
  },

  onShow() {
    if (getToken()) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },

  goHome() {
    if (!this.data.loading) wx.switchTab({ url: '/pages/index/index' })
  },

  goLogin() {
    if (!this.data.loading) wx.redirectTo({ url: '/pages/login/login' })
  },

  onInviteInput(e) {
    this.setData({
      inviteCode: ((e && e.detail && e.detail.value) || '').trim(),
      fieldError: '',
      formError: ''
    })
  },

  onSponsorClaimInput(e) {
    this.setData({
      sponsorClaimSecret: ((e && e.detail && e.detail.value) || '').trim(),
      fieldError: '',
      formError: ''
    })
  },

  validateCredential() {
    const missing = this.data.credentialMode === 'claim'
      ? !this.data.sponsorClaimSecret
      : !this.data.inviteCode
    if (missing) {
      this.setData({
        fieldError: this.data.credentialMode === 'claim'
          ? '请输入一次性发起人认领密钥'
          : '请输入邀请码',
        formError: ''
      })
      return false
    }
    return true
  },

  onRegister() {
    if (this.data.loading || !this.validateCredential()) return

    const credentialMode = this.data.credentialMode
    const inviteCode = credentialMode === 'invite' ? this.data.inviteCode.trim() : ''
    const sponsorClaimSecret = credentialMode === 'claim'
      ? this.data.sponsorClaimSecret.trim()
      : ''
    this.setData({ loading: true, fieldError: '', formError: '' })

    // Every click obtains a fresh one-time login code. It stays in this callback
    // and is never stored or replayed after any failure.
    wx.login({
      success: (loginRes) => {
        const code = loginRes && loginRes.code
        if (!code) {
          this.setData({ loading: false, formError: '获取微信登录凭证失败，请重新点击注册' })
          return
        }
        const request = credentialMode === 'claim'
          ? authApi.claimSponsor(code, sponsorClaimSecret)
          : authApi.registerWithInvite(code, inviteCode)
        request
          .then((data) => this.handleRegistered(data, credentialMode))
          .catch((err) => {
            const errorCode = err && err.code
            const message = (err && err.message) || '注册失败，请重新点击注册'
            const fieldCodes = credentialMode === 'claim' ? CLAIM_FIELD_ERRORS : INVITE_FIELD_ERRORS
            this.setData({
              loading: false,
              fieldError: fieldCodes.has(errorCode) ? message : '',
              formError: fieldCodes.has(errorCode) ? '' : message
            })
          })
      },
      fail: () => {
        this.setData({
          loading: false,
          formError: credentialMode === 'claim'
            ? '微信登录不可用，已保留认领密钥，请稍后重试'
            : '微信登录不可用，已保留邀请码，请稍后重试'
        })
      }
    })
  },

  handleRegistered(data, credentialMode) {
    if (!data || !data.token) {
      this.setData({ loading: false, formError: '注册失败，未返回会话' })
      return
    }
    setToken(data.token)
    this.setData({
      loading: false,
      inviteCode: '',
      sponsorClaimSecret: '',
      formError: '',
      fieldError: ''
    })
    wx.showToast({
      title: credentialMode === 'claim' ? '发起人账号认领成功' : '会员注册成功',
      icon: 'success'
    })
    wx.switchTab({ url: '/pages/index/index' })
  }
})
