const authApi = require('../../api/auth')
const memberApi = require('../../api/member')
const { getToken, setToken } = require('../../utils/request')
const { DEFAULT_NAVIGATION_METRICS, getNavigationMetrics } = require('../../utils/navigation')
const {
  normalizeNickname,
  nicknameValidationError,
  nicknameInitial,
  isLocalAvatarPath
} = require('../../utils/member-profile')

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
    progressText: '',
    privacyAgreed: false,
    privacyAuthorized: false,
    privacyRequesting: false,
    privacyError: '',
    nickname: '',
    nicknameError: '',
    avatarTempPath: '',
    avatarError: '',
    avatarFallback: '会',
    accountCreated: false,
    nicknameSaved: false,
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
    if (getToken() && !this.data.accountCreated) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },

  goHome() {
    if (!this.data.loading && !this.data.privacyRequesting && !this.data.accountCreated) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },

  goLogin() {
    if (!this.data.loading && !this.data.privacyRequesting && !this.data.accountCreated) {
      wx.redirectTo({ url: '/pages/login/login' })
    }
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

  onPrivacyAgreementChange(e) {
    if (this.data.loading || this.data.privacyRequesting || this.data.accountCreated) {
      return
    }
    const values = (e && e.detail && e.detail.value) || []
    const agreed = values.indexOf('accepted') >= 0
    if (!agreed) {
      this.setData({
        privacyAgreed: false,
        privacyAuthorized: false,
        privacyError: '',
        nickname: '',
        nicknameError: '',
        avatarTempPath: '',
        avatarError: '',
        avatarFallback: '会',
        formError: ''
      })
      return
    }
    this.setData({ privacyAgreed: true, privacyError: '' })
    this.requestPrivacyAuthorize()
  },

  requestPrivacyAuthorize() {
    if (this.data.loading || this.data.privacyRequesting || this.data.accountCreated) {
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
        this.setData({
          privacyRequesting: false,
          privacyAgreed: false,
          privacyAuthorized: false,
          privacyError: '请先同意隐私保护指引，再选择微信头像和昵称'
        })
      }
    })
  },

  openPrivacyContract() {
    if (this.data.loading || this.data.privacyRequesting || this.data.accountCreated) {
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
    if (this.data.loading || !this.data.privacyAuthorized || this.data.nicknameSaved) {
      return
    }
    const nickname = (e && e.detail && e.detail.value) || ''
    this.setData({
      nickname: nickname,
      nicknameError: '',
      avatarFallback: nicknameInitial(nickname),
      formError: ''
    })
  },

  onChooseAvatar(e) {
    if (this.data.loading || !this.data.privacyAuthorized) {
      return
    }
    const avatarTempPath = e && e.detail && e.detail.avatarUrl
    if (!isLocalAvatarPath(avatarTempPath)) {
      this.setData({ avatarError: '请重新选择微信头像', formError: '' })
      return
    }
    this.setData({
      avatarTempPath: String(avatarTempPath),
      avatarError: '',
      formError: ''
    })
  },

  onAvatarPreviewError() {
    if (this.data.loading) {
      return
    }
    this.setData({
      avatarTempPath: '',
      avatarError: '头像预览失败，请重新选择'
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

  validateProfile() {
    const privacyError = this.data.privacyAuthorized
      ? ''
      : '请先阅读并同意用户隐私保护指引'
    const nickname = normalizeNickname(this.data.nickname)
    const nicknameError = nicknameValidationError(nickname)
    const avatarError = isLocalAvatarPath(this.data.avatarTempPath)
      ? ''
      : '请选择微信头像'
    this.setData({
      privacyError: privacyError,
      nickname: nickname,
      nicknameError: nicknameError,
      avatarError: avatarError,
      avatarFallback: nicknameInitial(nickname),
      formError: ''
    })
    return !privacyError && !nicknameError && !avatarError
  },

  onRegister() {
    if (this.data.loading || this.data.privacyRequesting) return
    if (!this.validateCredential() || !this.validateProfile()) return

    if (this.data.accountCreated) {
      this.setData({
        loading: true,
        progressText: this.data.nicknameSaved ? '正在上传微信头像' : '正在保存微信昵称',
        formError: ''
      })
      if (this.data.nicknameSaved) {
        this.uploadAvatarPhase()
      } else {
        this.saveNicknamePhase()
      }
      return
    }

    const credentialMode = this.data.credentialMode
    const inviteCode = credentialMode === 'invite' ? this.data.inviteCode.trim() : ''
    const sponsorClaimSecret = credentialMode === 'claim'
      ? this.data.sponsorClaimSecret.trim()
      : ''
    this.setData({
      loading: true,
      progressText: credentialMode === 'claim' ? '正在认领账号' : '正在创建会员账号',
      fieldError: '',
      formError: ''
    })

    // Every click obtains a fresh one-time login code. It stays in this callback
    // and is never stored or replayed after any failure.
    wx.login({
      success: (loginRes) => {
        const code = loginRes && loginRes.code
        if (!code) {
          this.setData({
            loading: false,
            progressText: '',
            formError: '获取微信登录凭证失败，请重新点击注册'
          })
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
              progressText: '',
              fieldError: fieldCodes.has(errorCode) ? message : '',
              formError: fieldCodes.has(errorCode) ? '' : message
            })
          })
      },
      fail: () => {
        this.setData({
          loading: false,
          progressText: '',
          formError: credentialMode === 'claim'
            ? '微信登录不可用，已保留认领密钥，请稍后重试'
            : '微信登录不可用，已保留邀请码，请稍后重试'
        })
      }
    })
  },

  handleRegistered(data, credentialMode) {
    if (!data || !data.token) {
      this.setData({ loading: false, progressText: '', formError: '注册失败，未返回会话' })
      return
    }
    setToken(data.token)
    this._registeredCredentialMode = credentialMode
    this.setData({
      accountCreated: true,
      nicknameSaved: false,
      loading: true,
      progressText: '正在保存微信昵称',
      formError: '',
      fieldError: ''
    })
    this.saveNicknamePhase()
  },

  saveNicknamePhase() {
    const nickname = normalizeNickname(this.data.nickname)
    memberApi
      .updateNickname(nickname)
      .then(() => {
        this.setData({
          nickname: nickname,
          nicknameSaved: true,
          progressText: '正在上传微信头像',
          formError: ''
        })
        this.uploadAvatarPhase()
      })
      .catch((err) => {
        this.setData({
          loading: false,
          progressText: '',
          formError: (err && err.message) || '账号已创建，微信昵称保存失败，请重试'
        })
      })
  },

  uploadAvatarPhase() {
    memberApi
      .uploadAvatar(this.data.avatarTempPath)
      .then(() => {
        this.finishRegistration()
      })
      .catch((err) => {
        this.setData({
          loading: false,
          progressText: '',
          avatarError: (err && err.message) || '账号与昵称已保存，微信头像上传失败，请重试',
          formError: '账号与昵称已保存，本次只需重试头像上传'
        })
      })
  },

  finishRegistration() {
    const credentialMode = this._registeredCredentialMode || this.data.credentialMode
    this.setData({
      loading: false,
      progressText: '',
      inviteCode: '',
      sponsorClaimSecret: '',
      avatarTempPath: '',
      avatarError: '',
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
