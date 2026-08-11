const authApi = require('../../api/auth')
const memberApi = require('../../api/member')
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
  'SPONSOR_CLAIM_IDENTITY_CONFLICT'
])

const PHONE_CODE_ERRORS = new Set([
  'WECHAT_PHONE_CODE_INVALID',
  'WECHAT_PHONE_CODE_EXPIRED',
  'WECHAT_PHONE_EXCHANGE_FAILED'
])

function safeDecode(value) {
  try {
    return decodeURIComponent(String(value))
  } catch (e) {
    return ''
  }
}

function isLocalAvatarPath(value) {
  const path = String(value || '').trim()
  return /^(?:wxfile:\/\/|https?:\/\/tmp\/|\/?tmp\/)/i.test(path)
}

Page({
  data: {
    stage: 'account',
    inviteCode: '',
    sponsorClaimSecret: '',
    credentialMode: 'invite',
    fieldError: '',
    formError: '',
    loading: false,
    navigation: DEFAULT_NAVIGATION_METRICS,
    privacyAgreed: false,
    privacyAuthorized: false,
    privacyRequesting: false,
    privacyError: '',
    nickname: '',
    nicknameError: '',
    phoneAuthorized: false,
    phoneMasked: '',
    phoneError: '',
    avatarTempPath: '',
    avatarUrl: '',
    avatarUploaded: false,
    avatarError: '',
    profileSaved: false,
    profileError: ''
  },

  onLoad(query) {
    this._phoneCode = ''
    this.setData({ navigation: getNavigationMetrics(wx) })
    const inviteCode = query && query.inviteCode ? safeDecode(query.inviteCode).trim() : ''
    if (inviteCode) {
      this.setData({ inviteCode: inviteCode.slice(0, 64) })
    }
  },

  onShow() {
    if (getToken() && this.data.stage === 'account') {
      this.resumeExistingProfile()
    }
  },

  resumeExistingProfile() {
    if (this.data.loading) {
      return
    }
    this.setData({
      stage: 'resuming',
      loading: true,
      inviteCode: '',
      sponsorClaimSecret: '',
      profileError: ''
    })
    memberApi
      .me()
      .then((profile) => {
        const nickname = (profile && profile.nickname) || ''
        const phoneMasked = (profile && profile.phoneMasked) || ''
        const phoneVerified = !!(phoneMasked || (profile && profile.phoneVerifiedAt))
        const avatarUrl = (profile && profile.avatarUrl) || ''
        if (nickname && phoneVerified && avatarUrl) {
          this.setData({ loading: false })
          wx.reLaunch({ url: '/pages/index/index' })
          return
        }
        this.setData({
          stage: 'profile',
          loading: false,
          nickname: nickname,
          phoneMasked: phoneMasked,
          profileSaved: phoneVerified,
          avatarUrl: avatarUrl,
          avatarUploaded: !!avatarUrl,
          profileError: ''
        })
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          this.setData({ loading: false })
          return
        }
        this.setData({
          stage: 'resume-error',
          loading: false,
          profileError: (err && err.message) || '恢复注册资料状态失败，请重试'
        })
      })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  goLogin() {
    if (this.data.loading || this.data.stage !== 'account') {
      return
    }
    wx.redirectTo({ url: '/pages/login/login' })
  },

  onInviteInput(e) {
    this.setData({
      inviteCode: (e.detail.value || '').trim(),
      fieldError: '',
      formError: ''
    })
  },

  onSponsorClaimInput(e) {
    this.setData({
      sponsorClaimSecret: (e.detail.value || '').trim(),
      fieldError: '',
      formError: ''
    })
  },

  toggleCredentialMode() {
    if (this.data.loading) {
      return
    }
    const claimMode = this.data.credentialMode === 'claim'
    this.setData({
      credentialMode: claimMode ? 'invite' : 'claim',
      inviteCode: '',
      sponsorClaimSecret: '',
      fieldError: '',
      formError: ''
    })
  },

  validateCredential() {
    if (this.data.credentialMode === 'claim') {
      if (!this.data.sponsorClaimSecret) {
        this.setData({ fieldError: '请输入一次性发起人认领密钥', formError: '' })
        return false
      }
      return true
    }
    if (!this.data.inviteCode) {
      this.setData({ fieldError: '请输入邀请码', formError: '' })
      return false
    }
    return true
  },

  onRegister() {
    if (this.data.loading || this.data.stage !== 'account' || !this.validateCredential()) {
      return
    }
    const credentialMode = this.data.credentialMode
    const inviteCode = credentialMode === 'invite' ? this.data.inviteCode.trim() : ''
    const sponsorClaimSecret = credentialMode === 'claim' ? this.data.sponsorClaimSecret.trim() : ''
    this.setData({ loading: true, fieldError: '', formError: '' })

    wx.login({
      success: (loginRes) => {
        const code = loginRes && loginRes.code
        if (!code) {
          this.setData({ loading: false, formError: '获取微信登录凭证失败，请重试' })
          return
        }

        const request = credentialMode === 'claim'
          ? authApi.claimSponsor(code, sponsorClaimSecret)
          : authApi.registerWithInvite(code, inviteCode)

        request
          .then((data) => {
            if (!data || !data.token) {
              throw { code: 'REGISTER_FAILED', message: '注册失败，未返回会话' }
            }
            setToken(data.token)
            this.setData({
              stage: 'profile',
              loading: false,
              inviteCode: '',
              sponsorClaimSecret: '',
              fieldError: '',
              formError: ''
            })
            wx.showToast({
              title: credentialMode === 'claim' ? '账号认领成功，请完善资料' : '账号创建成功，请完善资料',
              icon: 'none'
            })
          })
          .catch((err) => {
            const message = (err && err.message) || '注册失败，请稍后重试'
            const fieldCodes = credentialMode === 'claim'
              ? CLAIM_FIELD_ERRORS
              : INVITE_FIELD_ERRORS
            this.setData({
              loading: false,
              fieldError: fieldCodes.has(err && err.code) ? message : '',
              formError: fieldCodes.has(err && err.code) ? '' : message
            })
          })
      },
      fail: () => {
        this.setData({ loading: false, formError: '微信登录不可用，请稍后重试' })
      }
    })
  },

  onPrivacyAgreementChange(e) {
    if (this.data.loading) {
      return
    }
    const values = (e && e.detail && e.detail.value) || []
    const agreed = values.indexOf('accepted') >= 0
    if (!agreed) {
      this._phoneCode = ''
      this.setData({
        privacyAgreed: false,
        privacyAuthorized: false,
        privacyError: '',
        phoneAuthorized: false,
        phoneError: ''
      })
      return
    }
    this.setData({ privacyAgreed: true, privacyError: '' })
    this.requestPrivacyAuthorize()
  },

  requestPrivacyAuthorize() {
    if (this.data.loading || this.data.privacyRequesting) {
      return
    }
    if (typeof wx.requirePrivacyAuthorize !== 'function') {
      this.setData({
        privacyAgreed: false,
        privacyAuthorized: false,
        privacyError: '当前微信版本不支持隐私授权，请升级后重试'
      })
      return
    }
    this.setData({ privacyRequesting: true, privacyError: '' })
    wx.requirePrivacyAuthorize({
      success: () => {
        this.setData({
          privacyRequesting: false,
          privacyAgreed: true,
          privacyAuthorized: true,
          privacyError: ''
        })
      },
      fail: () => {
        this._phoneCode = ''
        this.setData({
          privacyRequesting: false,
          privacyAgreed: false,
          privacyAuthorized: false,
          phoneAuthorized: false,
          privacyError: '你尚未同意隐私保护指引，同意后才能完善会员资料'
        })
      }
    })
  },

  openPrivacyContract() {
    if (this.data.loading || this.data.privacyRequesting) {
      return
    }
    if (typeof wx.openPrivacyContract !== 'function') {
      this.setData({ privacyError: '当前微信版本暂不支持查看隐私保护指引' })
      return
    }
    wx.openPrivacyContract({
      fail: () => {
        this.setData({ privacyError: '隐私保护指引打开失败，请稍后重试' })
      }
    })
  },

  onNicknameInput(e) {
    if (this.data.profileSaved) {
      return
    }
    this.setData({
      nickname: (e && e.detail && e.detail.value) || '',
      nicknameError: '',
      profileError: ''
    })
  },

  onChooseAvatar(e) {
    if (!this.data.privacyAgreed || !this.data.privacyAuthorized || this.data.loading) {
      return
    }
    const avatarTempPath = e && e.detail && e.detail.avatarUrl
    if (!isLocalAvatarPath(avatarTempPath)) {
      this.setData({ avatarError: '请重新选择微信头像', profileError: '' })
      return
    }
    this.setData({
      avatarTempPath: String(avatarTempPath),
      avatarUploaded: false,
      avatarError: '',
      profileError: ''
    })
  },

  onGetPhoneNumber(e) {
    if (!this.data.privacyAgreed || !this.data.privacyAuthorized || this.data.loading || this.data.profileSaved) {
      return
    }
    const code = e && e.detail && e.detail.code
    if (!code) {
      this._phoneCode = ''
      this.setData({
        phoneAuthorized: false,
        phoneError: '未获取到手机号授权，请点击按钮重试',
        profileError: ''
      })
      return
    }
    this._phoneCode = String(code)
    this.setData({ phoneAuthorized: true, phoneError: '', profileError: '' })
  },

  validateProfile() {
    const nickname = this.data.nickname.trim()
    const patch = { nicknameError: '', phoneError: '', avatarError: '', profileError: '' }
    if (!this.data.privacyAgreed || !this.data.privacyAuthorized) {
      patch.privacyError = '请先阅读并同意用户隐私保护指引'
      this.setData(patch)
      return false
    }
    if (!nickname) {
      patch.nicknameError = '请输入微信昵称'
      this.setData(patch)
      return false
    }
    if (Array.from(nickname).length > 32) {
      patch.nicknameError = '昵称不能超过 32 个字符'
      this.setData(patch)
      return false
    }
    if (!this._phoneCode) {
      patch.phoneError = '请先授权并验证微信手机号'
      this.setData(patch)
      return false
    }
    if (!this.data.avatarUploaded && !this.data.avatarTempPath) {
      patch.avatarError = '请先选择会员头像'
      this.setData(patch)
      return false
    }
    this.setData(patch)
    return true
  },

  onSubmitProfile() {
    if (this.data.loading || this.data.stage !== 'profile') {
      return
    }
    if (!this.data.privacyAgreed || !this.data.privacyAuthorized) {
      this.setData({ privacyError: '请先阅读并同意用户隐私保护指引' })
      return
    }
    if (this.data.profileSaved) {
      this.uploadAvatarPhase()
      return
    }
    if (!this.validateProfile()) {
      return
    }

    const nickname = this.data.nickname.trim()
    const phoneCode = this._phoneCode
    this._phoneCode = ''
    this.setData({
      loading: true,
      nickname: nickname,
      phoneAuthorized: false,
      phoneError: '',
      profileError: ''
    })

    memberApi
      .updateWeChatProfile(nickname, phoneCode)
      .then((profile) => {
        this.setData({
          loading: false,
          profileSaved: true,
          nickname: (profile && profile.nickname) || nickname,
          phoneMasked: (profile && profile.phoneMasked) || '',
          profileError: ''
        })
        this.uploadAvatarPhase()
      })
      .catch((err) => {
        const expired = PHONE_CODE_ERRORS.has(err && err.code)
        this.setData({
          loading: false,
          phoneAuthorized: false,
          phoneError: expired ? '手机号授权已失效，请重新授权后重试' : '请重新授权手机号后重试',
          profileError: (err && err.message) || '会员资料保存失败，请重试'
        })
      })
  },

  uploadAvatarPhase() {
    if (this.data.loading || this.data.stage !== 'profile') {
      return
    }
    if (this.data.avatarUploaded) {
      this.setData({ stage: 'complete', avatarError: '', profileError: '' })
      wx.showToast({ title: '会员资料已完善', icon: 'success' })
      return
    }
    const avatarTempPath = this.data.avatarTempPath
    if (!avatarTempPath) {
      this.setData({ avatarError: '请重新选择会员头像' })
      return
    }
    this.setData({ loading: true, avatarError: '', profileError: '' })
    memberApi
      .uploadAvatar(avatarTempPath)
      .then((profile) => {
        this.setData({
          stage: 'complete',
          loading: false,
          avatarTempPath: '',
          avatarUrl: (profile && profile.avatarUrl) || '',
          avatarUploaded: true,
          nickname: (profile && profile.nickname) || this.data.nickname,
          phoneMasked: (profile && profile.phoneMasked) || this.data.phoneMasked,
          avatarError: '',
          profileError: ''
        })
        wx.showToast({ title: '会员资料已完善', icon: 'success' })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          avatarError: (err && err.message) || '头像上传失败，请重试'
        })
      })
  }
})
