import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

const navigationUtils = await loadCommonJs('utils/navigation.js')
const memberProfileUtils = await loadCommonJs('utils/member-profile.js', {
  requireMap: {
    './format': {
      resolveMediaUrl(value) {
        return 'https://shop.example.test' + value
      }
    }
  }
})

async function loadEditPage(fixture) {
  let definition
  await loadCommonJs('pages/profile/edit.js', {
    globals: {
      wx: fixture.wx,
      Page(value) { definition = value }
    },
    requireMap: {
      '../../api/member': fixture.memberApi,
      '../../utils/request': {
        getToken: () => fixture.getToken(),
        isConflict(error) {
          return Number(error && (error.statusCode || error.status)) === 409 ||
            /CONFLICT$/.test(String((error && error.code) || ''))
        }
      },
      '../../utils/navigation': navigationUtils,
      '../../utils/member-profile': memberProfileUtils
    }
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
  let token = Object.hasOwn(options, 'token') ? options.token : 'MEMBER-TOKEN'
  let privacyMode = 'allow'
  let meResponse = () => Promise.resolve({
    userId: 7,
    nickname: '杉杉',
    avatarUrl: '/api/v1/member-avatars/7',
    phoneMasked: '138****8000',
    phoneVerifiedAt: '2026-08-12T12:00:00Z'
  })
  let nicknameResponse = (nickname) => Promise.resolve({
    userId: 7,
    nickname,
    avatarUrl: '/api/v1/member-avatars/7',
    phoneMasked: '138****8000',
    phoneVerifiedAt: '2026-08-12T12:00:00Z'
  })
  let avatarResponse = () => Promise.resolve({
    userId: 7,
    nickname: '新昵称',
    avatarUrl: '/api/v1/member-avatars/7',
    phoneMasked: '138****8000',
    phoneVerifiedAt: '2026-08-12T12:00:00Z'
  })
  const meCalls = []
  const nicknameCalls = []
  const avatarCalls = []
  const events = []
  const privacyCalls = []
  const privacyContracts = []
  const relaunches = []
  const switchTabs = []

  const memberApi = {
    me() {
      meCalls.push('me')
      events.push('me')
      return typeof meResponse === 'function' ? meResponse() : meResponse
    },
    updateNickname(nickname) {
      nicknameCalls.push({ nickname })
      events.push('nickname')
      return typeof nicknameResponse === 'function'
        ? nicknameResponse(nickname)
        : nicknameResponse
    },
    uploadAvatar(filePath) {
      avatarCalls.push(filePath)
      events.push('avatar')
      return typeof avatarResponse === 'function' ? avatarResponse(filePath) : avatarResponse
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
    login() { throw new Error('profile confirmation must not call wx.login') },
    getPhoneNumber() { throw new Error('profile confirmation must not request a phone number') },
    getUserProfile() { throw new Error('deprecated profile APIs must not be called') },
    getUserInfo() { throw new Error('deprecated profile APIs must not be called') },
    setStorageSync() { throw new Error('temporary profile state must not enter storage') }
  }

  return {
    wx,
    memberApi,
    meCalls,
    nicknameCalls,
    avatarCalls,
    events,
    privacyCalls,
    privacyContracts,
    relaunches,
    switchTabs,
    getToken: () => token,
    setToken(value) { token = value },
    setPrivacyMode(value) { privacyMode = value },
    setMeResponse(value) { meResponse = value },
    setNicknameResponse(value) { nicknameResponse = value },
    setAvatarResponse(value) { avatarResponse = value }
  }
}

async function loadMountedPage(fixture) {
  const definition = await loadEditPage(fixture)
  const page = mountPage(definition)
  page.onLoad()
  await flushPromises()
  return page
}

function authorize(page) {
  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  assert.equal(page.data.privacyAuthorized, true)
}

test('profile confirmation is an independent current-WeChat-capability page with a nickname-only API', async () => {
  const [appSource, markup, script, memberApi, loginScript, profileMarkup, profileScript] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/edit.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/edit.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'api/member.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.js'), 'utf8')
  ])
  const appConfig = JSON.parse(appSource)

  assert.equal(appConfig.pages.length, 26)
  assert.ok(appConfig.pages.includes('pages/profile/edit'))
  assert.match(markup, /open-type="chooseAvatar"/)
  assert.match(markup, /bindchooseavatar="onChooseAvatar"/)
  assert.match(markup, /type="nickname"/)
  assert.match(markup, /《用户隐私保护指引》/)
  assert.match(script, /wx\.requirePrivacyAuthorize\s*\(/)
  assert.match(script, /wx\.openPrivacyContract\s*\(/)
  assert.doesNotMatch(markup, /getPhoneNumber|getphonenumber/)
  assert.doesNotMatch(script, /wx\.login|getPhoneNumber|getUserProfile|getUserInfo/)
  assert.doesNotMatch(script, /setStorageSync|setStorage|JSON\.stringify|console\.(?:log|info|warn|error)/)
  assert.match(memberApi, /request\('\/membership\/nickname'/)
  assert.match(memberApi, /data:\s*\{\s*nickname:\s*nickname\s*\}/)
  assert.doesNotMatch(memberApi.match(/function updateNickname[\s\S]*?\n\}/)?.[0] || '', /phone|avatar/)
  assert.match(loginScript, /setToken\(data\.token\)[\s\S]*?reLaunch\(\{ url: '\/pages\/profile\/edit' \}\)/)
  assert.match(loginScript, /onShow\(\)[\s\S]*?getToken\(\)[\s\S]*?reLaunch\(\{ url: '\/pages\/index\/index' \}\)/)
  assert.match(profileMarkup, /bindtap="goProfileEdit"/)
  assert.match(profileMarkup, />更新头像与昵称</)
  assert.match(profileScript, /wx\.navigateTo\(\{ url: '\/pages\/profile\/edit' \}\)/)
})

test('authoritative profile load has token, retry, stable-avatar and nickname-initial behavior', async () => {
  const missing = createFixture({ token: '' })
  const missingDefinition = await loadEditPage(missing)
  const missingPage = mountPage(missingDefinition)
  missingPage.onLoad()
  assert.deepEqual(missing.relaunches, [{ url: '/pages/login/login' }])
  assert.deepEqual(missing.meCalls, [])

  const fixture = createFixture()
  fixture.setMeResponse(() => Promise.reject({ code: 'NETWORK_ERROR', message: '资料网络失败' }))
  const page = await loadMountedPage(fixture)
  assert.equal(page.data.loaded, false)
  assert.equal(page.data.loadError, '资料网络失败')

  fixture.setMeResponse(() => Promise.resolve({
    nickname: '林木',
    avatarUrl: '/api/v1/member-avatars/9'
  }))
  await page.loadProfile()
  assert.equal(page.data.loaded, true)
  assert.equal(page.data.authoritativeNickname, '林木')
  assert.equal(page.data.nickname, '林木')
  assert.equal(page.data.avatarFallback, '林')
  assert.equal(page.data.avatarUrl, 'https://shop.example.test/api/v1/member-avatars/9')

  fixture.setMeResponse(() => Promise.resolve({
    nickname: '杉杉',
    avatarUrl: 'https://untrusted.example.test/avatar.png'
  }))
  await page.loadProfile()
  assert.equal(page.data.avatarUrl, '')
  assert.equal(page.data.avatarFallback, '杉')

  fixture.setMeResponse(() => Promise.reject({ code: 'NOT_LOGGED_IN', message: '请先登录' }))
  await page.loadProfile()
  assert.equal(page.data.loading, false)
  assert.deepEqual(fixture.switchTabs, [])
})

test('skip, explicit home and unchanged confirmation all switchTab without writes', async () => {
  const unchangedFixture = createFixture()
  const unchanged = await loadMountedPage(unchangedFixture)
  unchanged.onSave()
  assert.deepEqual(unchangedFixture.nicknameCalls, [])
  assert.deepEqual(unchangedFixture.avatarCalls, [])
  assert.deepEqual(unchangedFixture.switchTabs, [{ url: '/pages/index/index' }])

  const skipFixture = createFixture()
  const skipped = await loadMountedPage(skipFixture)
  skipped.onSkip()
  assert.deepEqual(skipFixture.switchTabs, [{ url: '/pages/index/index' }])
  assert.deepEqual(skipFixture.nicknameCalls, [])

  const homeFixture = createFixture()
  const home = await loadMountedPage(homeFixture)
  home.goHome()
  assert.deepEqual(homeFixture.switchTabs, [{ url: '/pages/index/index' }])
  assert.deepEqual(homeFixture.avatarCalls, [])
})

test('privacy refusal stays visible and still permits the explicit skip path', async () => {
  const fixture = createFixture()
  fixture.setPrivacyMode('deny')
  const page = await loadMountedPage(fixture)

  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  assert.equal(page.data.privacyAgreed, false)
  assert.equal(page.data.privacyAuthorized, false)
  assert.match(page.data.privacyError, /尚未同意/)
  assert.equal(fixture.privacyCalls.length, 1)

  page.openPrivacyContract()
  assert.equal(fixture.privacyContracts.length, 1)
  page.onSkip()
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
  assert.deepEqual(fixture.nicknameCalls, [])
  assert.deepEqual(fixture.avatarCalls, [])
})

test('nickname-only and avatar-only saves are separate, guarded and end with switchTab', async () => {
  const nicknameFixture = createFixture()
  let settleNickname
  nicknameFixture.setNicknameResponse(() => new Promise((resolvePromise) => {
    settleNickname = resolvePromise
  }))
  const nicknamePage = await loadMountedPage(nicknameFixture)
  authorize(nicknamePage)
  nicknamePage.onNicknameInput({ detail: { value: '  新昵称  ' } })
  nicknamePage.onSave()
  nicknamePage.onSave()
  assert.deepEqual(nicknameFixture.nicknameCalls, [{ nickname: '新昵称' }])
  assert.deepEqual(nicknameFixture.avatarCalls, [])
  settleNickname({ nickname: '新昵称', avatarUrl: '/api/v1/member-avatars/7' })
  await flushPromises()
  assert.deepEqual(nicknameFixture.switchTabs, [{ url: '/pages/index/index' }])

  const avatarFixture = createFixture()
  const avatarPage = await loadMountedPage(avatarFixture)
  authorize(avatarPage)
  avatarPage.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/new-avatar.png' } })
  avatarPage.onSave()
  avatarPage.onSave()
  await flushPromises()
  assert.deepEqual(avatarFixture.nicknameCalls, [])
  assert.deepEqual(avatarFixture.avatarCalls, ['wxfile://tmp/new-avatar.png'])
  assert.deepEqual(avatarFixture.switchTabs, [{ url: '/pages/index/index' }])
  assert.equal(avatarPage.data.avatarTempPath, '')
})

test('nickname succeeds before avatar and an avatar retry never repeats nickname or persists the temp path', async () => {
  const fixture = createFixture()
  fixture.setAvatarResponse(() => Promise.reject({
    code: 'AVATAR_STORAGE_FAILED',
    message: '头像存储暂时失败'
  }))
  const page = await loadMountedPage(fixture)
  authorize(page)
  page.onNicknameInput({ detail: { value: '新昵称' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/retry-avatar.png' } })
  page.onSave()
  page.onSave()
  await flushPromises()
  await flushPromises()

  assert.deepEqual(fixture.events.slice(-2), ['nickname', 'avatar'])
  assert.deepEqual(fixture.nicknameCalls, [{ nickname: '新昵称' }])
  assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/retry-avatar.png'])
  assert.equal(page.data.nicknameSaved, true)
  assert.equal(page.data.authoritativeNickname, '新昵称')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/retry-avatar.png')
  assert.match(page.data.saveError, /只需重试头像/)
  assert.deepEqual(fixture.switchTabs, [])

  fixture.setAvatarResponse(() => Promise.resolve({
    nickname: '新昵称',
    avatarUrl: '/api/v1/member-avatars/7'
  }))
  page.onSave()
  await flushPromises()

  assert.equal(fixture.nicknameCalls.length, 1)
  assert.deepEqual(fixture.avatarCalls, [
    'wxfile://tmp/retry-avatar.png',
    'wxfile://tmp/retry-avatar.png'
  ])
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
  assert.equal(page.data.avatarTempPath, '')
  assert.equal(JSON.stringify(fixture.nicknameCalls).includes('wxfile://'), false)
  assert.equal(JSON.stringify(fixture.switchTabs).includes('wxfile://'), false)
})

test('nickname conflict refreshes authority while preserving the in-memory draft and selected avatar', async () => {
  const fixture = createFixture()
  let read = 0
  fixture.setMeResponse(() => {
    read += 1
    return Promise.resolve(read === 1
      ? { nickname: '旧昵称', avatarUrl: '/api/v1/member-avatars/7' }
      : { nickname: '其他设备昵称', avatarUrl: '/api/v1/member-avatars/7' })
  })
  fixture.setNicknameResponse(() => Promise.reject({
    statusCode: 409,
    code: 'MEMBER_PROFILE_CONFLICT',
    message: '会员资料已变更'
  }))
  const page = await loadMountedPage(fixture)
  authorize(page)
  page.onNicknameInput({ detail: { value: '我的草稿昵称' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/conflict-avatar.png' } })
  page.onSave()
  await flushPromises()
  await flushPromises()

  assert.equal(fixture.meCalls.length, 2)
  assert.equal(page.data.authoritativeNickname, '其他设备昵称')
  assert.equal(page.data.nickname, '我的草稿昵称')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/conflict-avatar.png')
  assert.match(page.data.saveError, /其他操作中更新/)
  assert.deepEqual(fixture.avatarCalls, [])
})

test('avatar conflict refreshes authority and retry uploads only the preserved avatar', async () => {
  const fixture = createFixture()
  let read = 0
  fixture.setMeResponse(() => {
    read += 1
    return Promise.resolve(read === 1
      ? { nickname: '旧昵称', avatarUrl: '/api/v1/member-avatars/7' }
      : { nickname: '其他设备昵称', avatarUrl: '/api/v1/member-avatars/7' })
  })
  fixture.setAvatarResponse(() => Promise.reject({
    statusCode: 409,
    code: 'MEMBER_PROFILE_CONFLICT',
    message: '会员资料已变更'
  }))
  const page = await loadMountedPage(fixture)
  authorize(page)
  page.onNicknameInput({ detail: { value: '我的新昵称' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/avatar-conflict.png' } })
  page.onSave()
  await flushPromises()
  await flushPromises()
  await flushPromises()

  assert.deepEqual(fixture.events.slice(-3), ['nickname', 'avatar', 'me'])
  assert.equal(fixture.nicknameCalls.length, 1)
  assert.equal(fixture.avatarCalls.length, 1)
  assert.equal(fixture.meCalls.length, 2)
  assert.equal(page.data.authoritativeNickname, '其他设备昵称')
  assert.equal(page.data.nickname, '其他设备昵称')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/avatar-conflict.png')
  assert.match(page.data.saveError, /未重复保存昵称/)
  assert.deepEqual(fixture.switchTabs, [])

  fixture.setAvatarResponse(() => Promise.resolve({
    nickname: '其他设备昵称',
    avatarUrl: '/api/v1/member-avatars/7'
  }))
  page.onSave()
  await flushPromises()

  assert.equal(fixture.nicknameCalls.length, 1)
  assert.deepEqual(fixture.avatarCalls, [
    'wxfile://tmp/avatar-conflict.png',
    'wxfile://tmp/avatar-conflict.png'
  ])
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
})

test('failed conflict refresh remains retryable without losing the selected avatar', async () => {
  const fixture = createFixture()
  let read = 0
  fixture.setMeResponse(() => {
    read += 1
    if (read === 1) {
      return Promise.resolve({ nickname: '旧昵称', avatarUrl: '/api/v1/member-avatars/7' })
    }
    if (read === 2) {
      return Promise.reject({ code: 'NETWORK_ERROR', message: '刷新网络失败' })
    }
    return Promise.resolve({ nickname: '最新权威昵称', avatarUrl: '/api/v1/member-avatars/7' })
  })
  fixture.setAvatarResponse(() => Promise.reject({
    statusCode: 409,
    code: 'MEMBER_PROFILE_CONFLICT',
    message: '会员资料已变更'
  }))
  const page = await loadMountedPage(fixture)
  authorize(page)
  page.onNicknameInput({ detail: { value: '我的新昵称' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/preserved-after-refresh-error.png' } })
  page.onSave()
  await flushPromises()
  await flushPromises()
  await flushPromises()

  assert.equal(page.data.loaded, false)
  assert.equal(page.data.loadError, '刷新网络失败')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/preserved-after-refresh-error.png')

  await page.loadProfile()
  assert.equal(page.data.loaded, true)
  assert.equal(page.data.loadError, '')
  assert.equal(page.data.nickname, '最新权威昵称')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/preserved-after-refresh-error.png')

  fixture.setAvatarResponse(() => Promise.resolve({
    nickname: '最新权威昵称',
    avatarUrl: '/api/v1/member-avatars/7'
  }))
  page.onSave()
  await flushPromises()

  assert.equal(fixture.nicknameCalls.length, 1)
  assert.equal(fixture.avatarCalls.length, 2)
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
})

test('conflict refresh loading blocks edits, privacy changes and navigation until authority returns', async () => {
  const fixture = createFixture()
  let resolveRefresh
  let read = 0
  fixture.setMeResponse(() => {
    read += 1
    if (read === 1) {
      return Promise.resolve({ nickname: '旧昵称', avatarUrl: '/api/v1/member-avatars/7' })
    }
    return new Promise((resolvePromise) => {
      resolveRefresh = resolvePromise
    })
  })
  fixture.setNicknameResponse(() => Promise.reject({
    statusCode: 409,
    code: 'MEMBER_PROFILE_CONFLICT',
    message: '会员资料已变更'
  }))
  const page = await loadMountedPage(fixture)
  authorize(page)
  page.onNicknameInput({ detail: { value: '保留的草稿' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/loading-guard.png' } })
  page.onSave()
  await flushPromises()
  await flushPromises()

  assert.equal(page.data.loading, true)
  page.onNicknameInput({ detail: { value: '不应覆盖' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/must-not-replace.png' } })
  page.onPrivacyAgreementChange({ detail: { value: [] } })
  page.openPrivacyContract()
  page.onSkip()
  page.goHome()
  assert.equal(page.data.nickname, '保留的草稿')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/loading-guard.png')
  assert.equal(page.data.privacyAuthorized, true)
  assert.deepEqual(fixture.privacyContracts, [])
  assert.deepEqual(fixture.switchTabs, [])

  resolveRefresh({ nickname: '其他设备昵称', avatarUrl: '/api/v1/member-avatars/7' })
  await flushPromises()
  assert.equal(page.data.loading, false)
  assert.equal(page.data.authoritativeNickname, '其他设备昵称')
  assert.equal(page.data.nickname, '保留的草稿')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/loading-guard.png')
})
