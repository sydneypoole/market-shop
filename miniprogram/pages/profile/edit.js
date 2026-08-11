const memberApi = require('../../api/member')
const { getToken, isConflict } = require('../../utils/request')
const { DEFAULT_NAVIGATION_METRICS, getNavigationMetrics } = require('../../utils/navigation')
const {
  nicknameInitial,
  resolveOwnedAvatarUrl,
  isLocalAvatarPath
} = require('../../utils/member-profile')

function normalizedNickname(value) {
  return String(value || '').trim()
}

function hasInvalidNicknameContent(value) {
  return /[\u0000-\u001f\u007f-\u009f]/.test(value)
}

function authoritativeProfile(profile) {
  const nickname = normalizedNickname(profile && profile.nickname)
  return {
    nickname: nickname,
    avatarUrl: resolveOwnedAvatarUrl(profile && profile.avatarUrl),
    avatarFallback: nicknameInitial(nickname)
  }
}

Page({
  data: {
    navigation: DEFAULT_NAVIGATION_METRICS,
    loading: true,
    loaded: false,
    loadError: '',
    saving: false,
    saveError: '',
    nicknameSaved: false,
    privacyAgreed: false,
    privacyAuthorized: false,
    privacyRequesting: false,
    privacyError: '',
    authoritativeNickname: '',
    nickname: '',
    nicknameError: '',
    avatarUrl: '',
    avatarFallback: '会',
    avatarFailed: false,
    avatarTempPath: '',
    avatarError: ''
  },

  onLoad() {
    this._leaving = false
    this.setData({ navigation: getNavigationMetrics(wx) })
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadProfile()
  },

  loadProfile() {
    if (this.data.saving || this.data.privacyRequesting) {
      return Promise.resolve()
    }
    if (this._pendingConflict) {
      return this.refreshConflictProfile()
    }
    this.setData({ loading: true, loadError: '', saveError: '' })
    return memberApi
      .me()
      .then((profile) => {
        const current = authoritativeProfile(profile)
        this.setData({
          loading: false,
          loaded: true,
          loadError: '',
          authoritativeNickname: current.nickname,
          nickname: current.nickname,
          nicknameError: '',
          nicknameSaved: false,
          avatarUrl: current.avatarUrl,
          avatarFallback: current.avatarFallback,
          avatarFailed: false,
          avatarTempPath: '',
          avatarError: ''
        })
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          this.setData({ loading: false })
          return
        }
        this.setData({
          loading: false,
          loaded: false,
          loadError: (err && err.message) || '会员资料加载失败，请重试'
        })
      })
  },

  onPrivacyAgreementChange(e) {
    if (this.data.loading || this.data.saving || this.data.privacyRequesting) {
      return
    }
    const values = (e && e.detail && e.detail.value) || []
    const agreed = values.indexOf('accepted') >= 0
    if (!agreed) {
      this.setData({
        privacyAgreed: false,
        privacyAuthorized: false,
        privacyError: '',
        nickname: this.data.authoritativeNickname,
        nicknameError: '',
        avatarTempPath: '',
        avatarError: '',
        saveError: '',
        nicknameSaved: false
      })
      return
    }
    this.setData({ privacyAgreed: true, privacyError: '' })
    this.requestPrivacyAuthorize()
  },

  requestPrivacyAuthorize() {
    if (this.data.loading || this.data.saving || this.data.privacyRequesting) {
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
          privacyError: '你尚未同意隐私保护指引，可暂不更新并进入商城'
        })
      }
    })
  },

  openPrivacyContract() {
    if (this.data.loading || this.data.saving || this.data.privacyRequesting) {
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
    if (this.data.loading || this.data.saving || !this.data.privacyAuthorized) {
      return
    }
    this.setData({
      nickname: (e && e.detail && e.detail.value) || '',
      nicknameError: '',
      saveError: '',
      nicknameSaved: false
    })
  },

  onChooseAvatar(e) {
    if (this.data.loading || this.data.saving || !this.data.privacyAgreed || !this.data.privacyAuthorized) {
      return
    }
    const avatarTempPath = e && e.detail && e.detail.avatarUrl
    if (!isLocalAvatarPath(avatarTempPath)) {
      this.setData({ avatarError: '请重新选择微信头像', saveError: '' })
      return
    }
    this.setData({
      avatarTempPath: String(avatarTempPath),
      avatarError: '',
      saveError: ''
    })
  },

  onAvatarError() {
    this.setData({ avatarFailed: true })
  },

  onAvatarPreviewError() {
    if (this.data.loading || this.data.saving) {
      return
    }
    this.setData({
      avatarTempPath: '',
      avatarError: '头像预览失败，请重新选择'
    })
  },

  validateNickname(nickname) {
    if (!nickname) {
      this.setData({ nicknameError: '请选择或输入微信昵称' })
      return false
    }
    if (Array.from(nickname).length > 32 || hasInvalidNicknameContent(nickname)) {
      this.setData({ nicknameError: '昵称不能超过 32 个字符，且不能包含控制字符' })
      return false
    }
    return true
  },

  onSave() {
    if (this.data.loading || !this.data.loaded || this.data.saving || this.data.privacyRequesting) {
      return
    }
    const nickname = normalizedNickname(this.data.nickname)
    const nicknameChanged = nickname !== this.data.authoritativeNickname
    const avatarChanged = !!this.data.avatarTempPath

    if (!nicknameChanged && !avatarChanged) {
      this.finishToHome()
      return
    }
    if (!this.data.privacyAgreed || !this.data.privacyAuthorized) {
      this.setData({ privacyError: '请先阅读并同意用户隐私保护指引' })
      return
    }
    if (nicknameChanged && !this.validateNickname(nickname)) {
      return
    }

    this.setData({
      saving: true,
      saveError: '',
      nicknameError: '',
      avatarError: '',
      nickname: nickname
    })
    if (!nicknameChanged) {
      this.uploadAvatarPhase()
      return
    }

    memberApi
      .updateNickname(nickname)
      .then((profile) => {
        const savedNickname = normalizedNickname(profile && profile.nickname) || nickname
        this.setData({
          authoritativeNickname: savedNickname,
          nickname: savedNickname,
          avatarFallback: nicknameInitial(savedNickname),
          nicknameSaved: true,
          saveError: ''
        })
        this.uploadAvatarPhase()
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          this.setData({ saving: false })
          return
        }
        if (isConflict(err)) {
          this.refreshAfterConflict(nickname)
          return
        }
        this.setData({
          saving: false,
          saveError: (err && err.message) || '昵称保存失败，请重试'
        })
      })
  },

  uploadAvatarPhase() {
    const avatarTempPath = this.data.avatarTempPath
    if (!avatarTempPath) {
      this.setData({ saving: false })
      this.finishToHome()
      return
    }
    memberApi
      .uploadAvatar(avatarTempPath)
      .then((profile) => {
        const saved = authoritativeProfile(profile)
        this.setData({
          saving: false,
          authoritativeNickname: saved.nickname || this.data.authoritativeNickname,
          nickname: saved.nickname || this.data.nickname,
          avatarUrl: saved.avatarUrl,
          avatarFallback: saved.nickname
            ? nicknameInitial(saved.nickname)
            : this.data.avatarFallback,
          avatarFailed: false,
          avatarTempPath: '',
          avatarError: '',
          saveError: ''
        })
        this.finishToHome()
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          this._pendingConflict = null
          this.setData({ saving: false })
          return
        }
        if (isConflict(err)) {
          this.refreshAfterAvatarConflict()
          return
        }
        this.setData({
          saving: false,
          avatarError: (err && err.message) || '头像上传失败，请重试',
          saveError: this.data.nicknameSaved
            ? '昵称已保存，本次只需重试头像上传'
            : ''
        })
      })
  },

  refreshAfterConflict(draftNickname) {
    this._pendingConflict = {
      phase: 'nickname',
      draftNickname: draftNickname,
      nicknameSaved: false
    }
    return this.refreshConflictProfile()
  },

  refreshAfterAvatarConflict() {
    this._pendingConflict = {
      phase: 'avatar',
      draftNickname: '',
      nicknameSaved: this.data.nicknameSaved
    }
    return this.refreshConflictProfile()
  },

  refreshConflictProfile() {
    const conflict = this._pendingConflict
    if (!conflict) {
      return this.loadProfile()
    }
    this.setData({ loading: true, saving: false, saveError: '资料已变更，正在刷新服务端状态' })
    return memberApi
      .me()
      .then((profile) => {
        const current = authoritativeProfile(profile)
        const avatarConflict = conflict.phase === 'avatar'
        this._pendingConflict = null
        this.setData({
          loading: false,
          loaded: true,
          loadError: '',
          authoritativeNickname: current.nickname,
          nickname: avatarConflict ? current.nickname : conflict.draftNickname,
          avatarUrl: current.avatarUrl,
          avatarFallback: current.avatarFallback,
          avatarFailed: false,
          nicknameSaved: avatarConflict ? conflict.nicknameSaved : false,
          avatarError: avatarConflict ? '头像未保存，请确认最新资料后重试上传' : '',
          saveError: avatarConflict
            ? '资料已在其他操作中更新，已刷新且未重复保存昵称'
            : '资料已在其他操作中更新，请确认后重试'
        })
      })
      .catch((err) => {
        if (err && err.code === 'NOT_LOGGED_IN') {
          this._pendingConflict = null
          this.setData({ loading: false })
          return
        }
        this.setData({
          loading: false,
          loaded: false,
          loadError: (err && err.message) || '服务端资料刷新失败，请重试'
        })
      })
  },

  onSkip() {
    if (this.data.loading || this.data.saving || this.data.privacyRequesting) {
      return
    }
    this.finishToHome()
  },

  goHome() {
    if (this.data.loading || this.data.saving || this.data.privacyRequesting) {
      return
    }
    this.finishToHome()
  },

  finishToHome() {
    if (this._leaving) {
      return
    }
    this._leaving = true
    this._pendingConflict = null
    this.setData({ avatarTempPath: '' })
    wx.switchTab({ url: '/pages/index/index' })
  }
})
