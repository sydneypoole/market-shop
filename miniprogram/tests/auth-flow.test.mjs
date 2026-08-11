import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

const navigationUtils = await loadCommonJs('utils/navigation.js')

async function loadPage(relativePath, requireMap, wx) {
  let definition
  await loadCommonJs(relativePath, {
    globals: {
      wx,
      Page(value) {
        definition = value
      }
    },
    requireMap
  })
  return definition
}

function mountPage(definition) {
  const instance = {
    ...definition,
    data: plain(definition.data || {}),
    setData(patch) {
      Object.assign(this.data, plain(patch))
    }
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

test('login and registration are separate routes with explicit home exits', async () => {
  const [appSource, loginMarkup, loginScript, registerMarkup, registerScript, authStyle] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'styles/auth-flow.wxss'), 'utf8')
  ])
  const pages = JSON.parse(appSource).pages

  assert.ok(pages.includes('pages/login/login'))
  assert.ok(pages.includes('pages/register/register'))
  assert.doesNotMatch(loginMarkup, /inviteCode|sponsorClaimSecret|credentialMode|邀请码|认领密钥/)
  assert.match(loginScript, /authApi\s*\.login\(code\)/)
  assert.match(registerMarkup, /maxlength="64"/)
  assert.match(registerMarkup, /一次性认领密钥/)
  const credentialInputs = (registerMarkup.match(/<input[\s\S]*?\/>/g) || [])
    .filter((input) => /class="credential-input/.test(input))
  assert.equal(credentialInputs.length, 2)
  for (const input of credentialInputs) {
    assert.match(input, /disabled="\{\{loading\}\}"/)
  }
  assert.match(registerScript, /authApi\.registerWithInvite\(code, inviteCode\)/)
  assert.match(registerScript, /authApi\.claimSponsor\(code, sponsorClaimSecret\)/)
  for (const markup of [loginMarkup, registerMarkup]) {
    assert.match(markup, /bindtap="goHome"/)
    assert.match(markup, />返回首页<\/text>/)
    assert.match(markup, /aria-label="返回商城首页"/)
    assert.match(markup, /navigation\.statusBarHeight/)
    assert.match(markup, /navigation\.navigationBarHeight/)
    assert.match(markup, /navigation\.sideWidth/)
  }
  assert.match(loginScript, /wx\.switchTab\(\{ url: '\/pages\/index\/index' \}\)/)
  assert.match(registerScript, /wx\.switchTab\(\{ url: '\/pages\/index\/index' \}\)/)
  assert.match(authStyle, /\.custom-nav-bar\s*\{[\s\S]*?min-height:\s*44px;/)
  assert.match(authStyle, /@media\s*\(prefers-reduced-motion:\s*reduce\)/)
})

test('identity navigation reserves the status bar and WeChat menu capsule', () => {
  const metrics = navigationUtils.getNavigationMetrics({
    getWindowInfo: () => ({ statusBarHeight: 24, windowWidth: 390 }),
    getMenuButtonBoundingClientRect: () => ({ top: 30, left: 292, height: 32 })
  })

  assert.deepEqual(plain(metrics), {
    statusBarHeight: 24,
    navigationBarHeight: 44,
    sideWidth: 106
  })
  assert.deepEqual(
    plain(navigationUtils.getNavigationMetrics({})),
    plain(navigationUtils.DEFAULT_NAVIGATION_METRICS)
  )
})

test('login submits only a fresh WeChat code, prevents repeats and keeps failures visible', async () => {
  let storedToken = ''
  let wxLoginMode = 'success'
  let loginResponse = Promise.resolve({ token: 'TOKEN-LOGIN', newlyRegistered: false })
  const loginCalls = []
  const savedTokens = []
  const relaunches = []
  const switchTabs = []
  const redirects = []
  let wxLoginCalls = 0
  const authApi = {
    login(code) {
      loginCalls.push(code)
      return loginResponse
    }
  }
  const wx = {
    login(options) {
      wxLoginCalls += 1
      if (wxLoginMode === 'missing-code') {
        options.success({})
      } else if (wxLoginMode === 'fail') {
        options.fail()
      } else {
        options.success({ code: 'WX-LOGIN-CODE' })
      }
    },
    reLaunch(options) { relaunches.push(plain(options)) },
    switchTab(options) { switchTabs.push(plain(options)) },
    redirectTo(options) { redirects.push(plain(options)) }
  }
  const definition = await loadPage('pages/login/login.js', {
    '../../api/auth': authApi,
    '../../utils/navigation': navigationUtils,
    '../../utils/request': {
      getToken: () => storedToken,
      setToken(token) {
        savedTokens.push(token)
        storedToken = token
      }
    }
  }, wx)

  const page = mountPage(definition)
  page.goHome()
  page.goRegister()
  assert.deepEqual(switchTabs, [{ url: '/pages/index/index' }])
  assert.deepEqual(redirects, [{ url: '/pages/register/register' }])

  page.onLogin()
  page.onLogin()
  await flushPromises()
  assert.equal(wxLoginCalls, 1)
  assert.deepEqual(loginCalls, ['WX-LOGIN-CODE'])
  assert.deepEqual(savedTokens, ['TOKEN-LOGIN'])
  assert.deepEqual(relaunches, [{ url: '/pages/profile/edit' }])
  assert.equal(page.data.loading, false)
  assert.equal(page.data.error, '')

  const alreadyLoggedIn = mountPage(definition)
  alreadyLoggedIn.onShow()
  assert.deepEqual(relaunches.at(-1), { url: '/pages/index/index' })

  storedToken = ''
  loginResponse = Promise.reject({ code: 'INVITE_CODE_REQUIRED', message: '首次注册必须填写有效邀请码' })
  const unregistered = mountPage(definition)
  unregistered.onLogin()
  await flushPromises()
  assert.equal(unregistered.data.loading, false)
  assert.equal(unregistered.data.error, '当前微信尚未注册，请先完成注册')

  wxLoginMode = 'missing-code'
  const missingCode = mountPage(definition)
  missingCode.onLogin()
  assert.equal(missingCode.data.loading, false)
  assert.equal(missingCode.data.error, '获取微信登录凭证失败，请重试')

  wxLoginMode = 'fail'
  const unavailable = mountPage(definition)
  unavailable.onLogin()
  assert.equal(unavailable.data.loading, false)
  assert.equal(unavailable.data.error, '微信登录不可用，请稍后重试')
})

test('registration requires exactly one credential and accepts existing or claimed identities', async () => {
  let wxLoginMode = 'success'
  let deferredWxLogin
  let inviteResponse = Promise.resolve({ token: 'TOKEN-REGISTER', newlyRegistered: true })
  let claimResponse = Promise.resolve({ token: 'TOKEN-CLAIM', newlyRegistered: false })
  const inviteCalls = []
  const claimCalls = []
  const savedTokens = []
  const relaunches = []
  const switchTabs = []
  const redirects = []
  const toasts = []
  let wxLoginCalls = 0
  const authApi = {
    registerWithInvite(code, inviteCode) {
      inviteCalls.push({ code, inviteCode })
      return inviteResponse
    },
    claimSponsor(code, sponsorClaimSecret) {
      claimCalls.push({ code, sponsorClaimSecret })
      return claimResponse
    }
  }
  const wx = {
    login(options) {
      wxLoginCalls += 1
      if (wxLoginMode === 'deferred') {
        deferredWxLogin = options
      } else if (wxLoginMode === 'fail') {
        options.fail()
      } else {
        options.success({ code: 'WX-REGISTER-CODE' })
      }
    },
    reLaunch(options) { relaunches.push(plain(options)) },
    switchTab(options) { switchTabs.push(plain(options)) },
    redirectTo(options) { redirects.push(plain(options)) },
    showToast(options) { toasts.push(plain(options)) }
  }
  const definition = await loadPage('pages/register/register.js', {
    '../../api/auth': authApi,
    '../../api/member': {
      me() { return Promise.resolve({}) },
      updateWeChatProfile() { return Promise.resolve({}) },
      uploadAvatar() { return Promise.resolve({}) }
    },
    '../../utils/navigation': navigationUtils,
    '../../utils/member-profile': {
      isLocalAvatarPath(value) {
        return /^(?:wxfile:\/\/|https?:\/\/tmp\/|\/?tmp\/)/i.test(String(value || '').trim())
      }
    },
    '../../utils/request': {
      getToken: () => '',
      setToken(token) { savedTokens.push(token) }
    }
  }, wx)

  const emptyPage = mountPage(definition)
  emptyPage.onRegister()
  assert.equal(wxLoginCalls, 0)
  assert.equal(emptyPage.data.fieldError, '请输入邀请码')
  emptyPage.goHome()
  emptyPage.goLogin()
  assert.deepEqual(switchTabs, [{ url: '/pages/index/index' }])
  assert.deepEqual(redirects, [{ url: '/pages/login/login' }])

  const generatedInvite = `MS${'a'.repeat(32)}`
  const invited = mountPage(definition)
  invited.onLoad({ inviteCode: encodeURIComponent(generatedInvite) })
  assert.equal(invited.data.inviteCode, generatedInvite)
  invited.onRegister()
  invited.onRegister()
  await flushPromises()
  assert.equal(wxLoginCalls, 1)
  assert.deepEqual(inviteCalls, [{ code: 'WX-REGISTER-CODE', inviteCode: generatedInvite }])
  assert.deepEqual(claimCalls, [])
  assert.deepEqual(savedTokens, ['TOKEN-REGISTER'])
  assert.equal(toasts.at(-1).title, '账号创建成功，请完善资料')
  assert.equal(invited.data.stage, 'profile')
  assert.equal(invited.data.inviteCode, '')
  assert.deepEqual(relaunches, [])

  const claim = mountPage(definition)
  claim.setData({ inviteCode: 'MUST-BE-CLEARED' })
  claim.toggleCredentialMode()
  assert.equal(claim.data.credentialMode, 'claim')
  assert.equal(claim.data.inviteCode, '')
  claim.onSponsorClaimInput({ detail: { value: 'SponsorClaimSecret2026StrongFixture' } })
  claim.onRegister()
  await flushPromises()
  assert.deepEqual(claimCalls, [{
    code: 'WX-REGISTER-CODE',
    sponsorClaimSecret: 'SponsorClaimSecret2026StrongFixture'
  }])
  assert.equal(inviteCalls.length, 1)
  assert.deepEqual(savedTokens, ['TOKEN-REGISTER', 'TOKEN-CLAIM'])
  assert.equal(toasts.at(-1).title, '账号认领成功，请完善资料')
  assert.equal(claim.data.stage, 'profile')
  assert.equal(claim.data.sponsorClaimSecret, '')

  const switchedBack = mountPage(definition)
  switchedBack.toggleCredentialMode()
  switchedBack.onSponsorClaimInput({ detail: { value: 'ONE-TIME-SECRET' } })
  switchedBack.toggleCredentialMode()
  assert.equal(switchedBack.data.credentialMode, 'invite')
  assert.equal(switchedBack.data.sponsorClaimSecret, '')

  wxLoginMode = 'deferred'
  inviteResponse = Promise.resolve({ token: 'TOKEN-SNAPSHOT', newlyRegistered: true })
  const snapshotPage = mountPage(definition)
  snapshotPage.onInviteInput({ detail: { value: 'ORIGINAL-INVITE' } })
  snapshotPage.onRegister()
  snapshotPage.onInviteInput({ detail: { value: 'CHANGED-AFTER-SUBMIT' } })
  deferredWxLogin.success({ code: 'WX-SNAPSHOT-CODE' })
  await flushPromises()
  assert.deepEqual(inviteCalls.at(-1), {
    code: 'WX-SNAPSHOT-CODE',
    inviteCode: 'ORIGINAL-INVITE'
  })

  wxLoginMode = 'success'
  inviteResponse = Promise.reject({ code: 'INVITE_CODE_EXPIRED', message: '邀请码已过期' })
  const expired = mountPage(definition)
  expired.onInviteInput({ detail: { value: 'EXPIRED-CODE' } })
  expired.onRegister()
  await flushPromises()
  assert.equal(expired.data.loading, false)
  assert.equal(expired.data.fieldError, '邀请码已过期')
  assert.equal(expired.data.formError, '')

  wxLoginMode = 'fail'
  const unavailable = mountPage(definition)
  unavailable.onInviteInput({ detail: { value: 'INVITE-CODE' } })
  unavailable.onRegister()
  assert.equal(unavailable.data.loading, false)
  assert.equal(unavailable.data.formError, '微信登录不可用，请稍后重试')
})
