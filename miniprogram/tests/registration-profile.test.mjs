import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

const navigationUtils = await loadCommonJs('utils/navigation.js')

async function loadRegister(requireMap, wx) {
  let definition
  await loadCommonJs('pages/register/register.js', {
    globals: {
      wx,
      Page(value) { definition = value }
    },
    requireMap
  })
  return definition
}

function mountPage(definition) {
  const instance = {
    data: plain(definition.data || {}),
    setData(patch) { Object.assign(this.data, plain(patch)) }
  }
  for (const [name, handler] of Object.entries(definition)) {
    if (typeof handler === 'function') {
      instance[name] = handler.bind(instance)
    }
  }
  return instance
}

function flushPromises() {
  return new Promise((resolvePromise) => setImmediate(resolvePromise))
}

function createFixture(options = {}) {
  let storedToken = options.token || ''
  let privacyMode = 'allow'
  let profileResponse = () => Promise.resolve({
    userId: 7,
    nickname: '杉杉',
    phoneMasked: '138****8000',
    phoneVerifiedAt: '2026-08-12T12:00:00Z',
    avatarUrl: ''
  })
  let avatarResponse = () => Promise.resolve({
    userId: 7,
    nickname: '杉杉',
    phoneMasked: '138****8000',
    phoneVerifiedAt: '2026-08-12T12:00:00Z',
    avatarUrl: '/api/v1/member-avatars/7'
  })
  let meResponse = () => Promise.resolve(options.me || {})
  const profileCalls = []
  const avatarCalls = []
  const privacyCalls = []
  const privacyContracts = []
  const relaunches = []
  const switchTabs = []
  const toasts = []
  const memberApi = {
    me() { return typeof meResponse === 'function' ? meResponse() : meResponse },
    updateWeChatProfile(nickname, phoneCode) {
      profileCalls.push({ nickname, phoneCode })
      return typeof profileResponse === 'function' ? profileResponse() : profileResponse
    },
    uploadAvatar(filePath) {
      avatarCalls.push(filePath)
      return typeof avatarResponse === 'function' ? avatarResponse() : avatarResponse
    }
  }
  const wx = {
    getWindowInfo() { return { statusBarHeight: 24, windowWidth: 390 } },
    getMenuButtonBoundingClientRect() { return { top: 30, left: 292, height: 32 } },
    requirePrivacyAuthorize(callbacks) {
      privacyCalls.push('require')
      if (privacyMode === 'allow') callbacks.success()
      else callbacks.fail({ errMsg: 'requirePrivacyAuthorize:fail user deny' })
    },
    openPrivacyContract(callbacks) {
      privacyContracts.push('open')
      if (callbacks && callbacks.success) callbacks.success()
    },
    reLaunch(value) { relaunches.push(plain(value)) },
    switchTab(value) { switchTabs.push(plain(value)) },
    redirectTo() {},
    showToast(value) { toasts.push(plain(value)) },
    login() { throw new Error('profile tests must not replay wx.login') }
  }
  const requireMap = {
    '../../api/auth': {
      registerWithInvite() { throw new Error('profile tests must not replay an invitation') },
      claimSponsor() { throw new Error('profile tests must not replay a sponsor claim') }
    },
    '../../api/member': memberApi,
    '../../utils/navigation': navigationUtils,
    '../../utils/request': {
      getToken: () => storedToken,
      setToken(token) { storedToken = token }
    }
  }
  return {
    wx,
    requireMap,
    profileCalls,
    avatarCalls,
    privacyCalls,
    privacyContracts,
    relaunches,
    switchTabs,
    toasts,
    setPrivacyMode(value) { privacyMode = value },
    setProfileResponse(value) { profileResponse = value },
    setAvatarResponse(value) { avatarResponse = value },
    setMeResponse(value) { meResponse = value }
  }
}

function authorizeAndFill(page, phoneCode = 'PHONE-CODE-ONE') {
  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/member-avatar.png' } })
  page.onNicknameInput({ detail: { value: '杉杉' } })
  page.onGetPhoneNumber({ detail: { code: phoneCode, errMsg: 'getPhoneNumber:ok' } })
}

test('registration profile uses current WeChat capabilities and never persists sensitive transient values', async () => {
  const [appSource, markup, script, memberApi] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'api/member.js'), 'utf8')
  ])

  assert.equal(JSON.parse(appSource).__usePrivacyCheck__, true)
  assert.match(markup, /open-type="chooseAvatar"/)
  assert.match(markup, /bindchooseavatar="onChooseAvatar"/)
  assert.match(markup, /type="nickname"/)
  assert.match(markup, /open-type="getPhoneNumber"/)
  assert.match(markup, /bindgetphonenumber="onGetPhoneNumber"/)
  assert.match(markup, /bindchange="onPrivacyAgreementChange"/)
  assert.match(markup, /《用户隐私保护指引》/)
  assert.match(script, /wx\.requirePrivacyAuthorize\s*\(/)
  assert.match(script, /wx\.openPrivacyContract\s*\(/)
  assert.doesNotMatch(script, /getUserProfile|getUserInfo/)
  assert.doesNotMatch(script, /setStorageSync|console\.(?:log|info|warn|error)/)
  assert.match(memberApi, /request\('\/membership\/wechat-profile'/)
  assert.match(memberApi, /data:\s*\{\s*nickname:\s*nickname,\s*phoneCode:\s*phoneCode\s*\}/)
  assert.match(memberApi, /uploadFile\('\/membership\/avatar',\s*filePath\)/)
  assert.doesNotMatch(memberApi, /avatarTempPath|phoneMasked\s*:/)
})

test('privacy refusal stays visible while accepted avatar, nickname and phone events complete the staged flow once', async () => {
  const fixture = createFixture()
  const definition = await loadRegister(fixture.requireMap, fixture.wx)
  const page = mountPage(definition)
  page.onLoad({})
  page.setData({ stage: 'profile' })

  fixture.setPrivacyMode('deny')
  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  assert.equal(page.data.privacyAuthorized, false)
  assert.equal(page.data.privacyAgreed, false)
  assert.match(page.data.privacyError, /尚未同意/)

  fixture.setPrivacyMode('allow')
  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  assert.equal(page.data.privacyAuthorized, true)
  assert.equal(page.data.privacyAgreed, true)
  assert.equal(fixture.privacyCalls.length, 2)

  page.openPrivacyContract()
  assert.equal(fixture.privacyContracts.length, 1)

  page.onChooseAvatar({ detail: { avatarUrl: 'https://cdn.invalid.test/avatar.png' } })
  assert.equal(page.data.avatarTempPath, '')
  assert.match(page.data.avatarError, /重新选择/)
  page.onGetPhoneNumber({ detail: { errMsg: 'getPhoneNumber:fail user deny' } })
  assert.equal(page.data.phoneAuthorized, false)

  authorizeAndFill(page)
  page.onSubmitProfile()
  page.onSubmitProfile()
  await flushPromises()
  await flushPromises()

  assert.deepEqual(fixture.profileCalls, [{ nickname: '杉杉', phoneCode: 'PHONE-CODE-ONE' }])
  assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/member-avatar.png'])
  assert.equal(page.data.stage, 'complete')
  assert.equal(page.data.avatarTempPath, '', 'temporary avatar path must be cleared after permanent upload')
  assert.equal(page._phoneCode, '', 'dynamic phone code must be cleared before the request settles')
  assert.equal(fixture.toasts.at(-1).title, '会员资料已完善')

  page.goHome()
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
})

test('profile failure requires a fresh phone code and avatar retry never replays profile or registration credentials', async () => {
  const fixture = createFixture()
  fixture.setProfileResponse(() => Promise.reject({
    code: 'WECHAT_PHONE_CODE_EXPIRED',
    message: '手机号凭证已过期'
  }))
  const definition = await loadRegister(fixture.requireMap, fixture.wx)
  const page = mountPage(definition)
  page.onLoad({})
  page.setData({ stage: 'profile' })
  authorizeAndFill(page, 'PHONE-CODE-OLD')

  page.onSubmitProfile()
  await flushPromises()
  assert.deepEqual(fixture.profileCalls, [{ nickname: '杉杉', phoneCode: 'PHONE-CODE-OLD' }])
  assert.equal(page._phoneCode, '')
  assert.equal(page.data.profileSaved, false)

  page.onSubmitProfile()
  assert.equal(fixture.profileCalls.length, 1, 'retry without a newly authorized phone code must be blocked')
  assert.match(page.data.phoneError, /重新授权|先授权/)

  fixture.setProfileResponse(() => Promise.resolve({
    userId: 7,
    nickname: '杉杉',
    phoneMasked: '138****8000',
    phoneVerifiedAt: '2026-08-12T12:00:00Z',
    avatarUrl: ''
  }))
  fixture.setAvatarResponse(() => Promise.reject({ code: 'AVATAR_STORAGE_FAILED', message: '头像存储暂时失败' }))
  page.onGetPhoneNumber({ detail: { code: 'PHONE-CODE-FRESH' } })
  page.onSubmitProfile()
  await flushPromises()
  await flushPromises()
  assert.deepEqual(fixture.profileCalls.at(-1), { nickname: '杉杉', phoneCode: 'PHONE-CODE-FRESH' })
  assert.equal(page.data.profileSaved, true)
  assert.equal(fixture.avatarCalls.length, 1)

  fixture.setAvatarResponse(() => Promise.resolve({
    userId: 7,
    nickname: '杉杉',
    phoneMasked: '138****8000',
    avatarUrl: '/api/v1/member-avatars/7'
  }))
  page.onSubmitProfile()
  await flushPromises()
  assert.equal(fixture.profileCalls.length, 2, 'avatar retry must not replay the consumed phone-code profile request')
  assert.equal(fixture.avatarCalls.length, 2)
  assert.equal(page.data.stage, 'complete')
})

test('reload resumes from authoritative profile state and uploads only the unfinished avatar phase', async () => {
  const fixture = createFixture({
    token: 'MEMBER-TOKEN',
    me: {
      userId: 7,
      nickname: '杉杉',
      avatarUrl: '',
      phoneMasked: '138****8000',
      phoneVerifiedAt: '2026-08-12T12:00:00Z'
    }
  })
  const definition = await loadRegister(fixture.requireMap, fixture.wx)
  const page = mountPage(definition)
  page.onLoad({ inviteCode: 'MUST-NOT-REPLAY' })
  page.onShow()
  await flushPromises()

  assert.equal(page.data.stage, 'profile')
  assert.equal(page.data.profileSaved, true)
  assert.equal(page.data.nickname, '杉杉')
  assert.equal(page.data.phoneMasked, '138****8000')
  assert.equal(page.data.inviteCode, '')

  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  page.onChooseAvatar({ detail: { avatarUrl: 'http://tmp/resumed-avatar.jpg' } })
  page.onSubmitProfile()
  await flushPromises()

  assert.deepEqual(fixture.profileCalls, [], 'resume must trust the verified profile and never replay phone/invite data')
  assert.deepEqual(fixture.avatarCalls, ['http://tmp/resumed-avatar.jpg'])
  assert.equal(page.data.stage, 'complete')

  const completeFixture = createFixture({
    token: 'MEMBER-TOKEN',
    me: {
      userId: 8,
      nickname: '林木',
      avatarUrl: '/api/v1/member-avatars/8',
      phoneMasked: '139****9000',
      phoneVerifiedAt: '2026-08-12T12:00:00Z'
    }
  })
  const completeDefinition = await loadRegister(completeFixture.requireMap, completeFixture.wx)
  const completePage = mountPage(completeDefinition)
  completePage.onLoad({})
  completePage.onShow()
  await flushPromises()
  assert.deepEqual(completeFixture.relaunches, [{ url: '/pages/index/index' }])
})
