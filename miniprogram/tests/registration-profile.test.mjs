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
        return value || ''
      }
    }
  }
})

function mountPage(definition) {
  const instance = {
    ...definition,
    data: plain(definition.data || {}),
    setData(patch) { Object.assign(this.data, plain(patch)) }
  }
  for (const [name, handler] of Object.entries(definition)) {
    if (typeof handler === 'function') instance[name] = handler.bind(instance)
  }
  return instance
}

async function settle() {
  for (let index = 0; index < 3; index += 1) {
    await new Promise((resolvePromise) => setImmediate(resolvePromise))
  }
}

async function loadRegister(fixture) {
  let definition
  await loadCommonJs('pages/register/register.js', {
    globals: {
      wx: fixture.wx,
      Page(value) { definition = value }
    },
    requireMap: {
      '../../api/auth': fixture.authApi,
      '../../api/member': fixture.memberApi,
      '../../utils/navigation': navigationUtils,
      '../../utils/member-profile': memberProfileUtils,
      '../../utils/request': {
        getToken: () => fixture.storedToken,
        setToken(token) {
          fixture.savedTokens.push(token)
          fixture.storedToken = token
        }
      }
    }
  })
  return definition
}

function createFixture() {
  const inviteCalls = []
  const claimCalls = []
  const nicknameCalls = []
  const avatarCalls = []
  const savedTokens = []
  const switchTabs = []
  const redirects = []
  const toasts = []
  const privacyContracts = []
  const wxLoginCodes = ['LOGIN-CODE-1']
  let inviteResponse = Promise.resolve({ token: 'MEMBER-TOKEN', nickname: '宏杉会员-ABC12345' })
  let claimResponse = Promise.resolve({ token: 'SPONSOR-TOKEN', nickname: '商城发起人' })
  let nicknameResponse = Promise.resolve({ nickname: '微信昵称' })
  let avatarResponse = Promise.resolve({ avatarUrl: '/api/v1/member-avatars/7' })
  let loginMode = 'success'
  let privacyMode = 'allow'
  let wxLoginCalls = 0
  const fixture = {
    storedToken: '',
    savedTokens,
    inviteCalls,
    claimCalls,
    nicknameCalls,
    avatarCalls,
    switchTabs,
    redirects,
    toasts,
    privacyContracts,
    authApi: {
      registerWithInvite(code, inviteCode) {
        inviteCalls.push({ code, inviteCode })
        return inviteResponse
      },
      claimSponsor(code, sponsorClaimSecret) {
        claimCalls.push({ code, sponsorClaimSecret })
        return claimResponse
      }
    },
    memberApi: {
      updateNickname(nickname) {
        nicknameCalls.push(nickname)
        return typeof nicknameResponse === 'function'
          ? nicknameResponse(nickname)
          : nicknameResponse
      },
      uploadAvatar(filePath) {
        avatarCalls.push(filePath)
        return typeof avatarResponse === 'function'
          ? avatarResponse(filePath)
          : avatarResponse
      }
    },
    wx: {
      login(options) {
        wxLoginCalls += 1
        if (loginMode === 'fail') return options.fail()
        options.success({ code: wxLoginCodes.shift() || `LOGIN-CODE-${wxLoginCalls}` })
      },
      requirePrivacyAuthorize(options) {
        if (privacyMode === 'allow') options.success()
        else options.fail({ errMsg: 'requirePrivacyAuthorize:fail user deny' })
      },
      openPrivacyContract(options) {
        privacyContracts.push('open')
        if (options && options.success) options.success()
      },
      switchTab(options) { switchTabs.push(plain(options)) },
      redirectTo(options) { redirects.push(plain(options)) },
      showToast(options) { toasts.push(plain(options)) },
      getUserProfile() { throw new Error('deprecated profile API must not be called') },
      getUserInfo() { throw new Error('deprecated profile API must not be called') },
      getPhoneNumber() { throw new Error('registration must not request phone permission') }
    },
    get wxLoginCalls() { return wxLoginCalls },
    setLoginCodes(...codes) { wxLoginCodes.splice(0, wxLoginCodes.length, ...codes) },
    setLoginMode(value) { loginMode = value },
    setPrivacyMode(value) { privacyMode = value },
    setInviteResponse(value) { inviteResponse = value },
    setNicknameResponse(value) { nicknameResponse = value },
    setAvatarResponse(value) { avatarResponse = value }
  }
  return fixture
}

function prepareProfile(page, nickname = '微信昵称', avatar = 'wxfile://tmp/register-avatar.png') {
  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  page.onNicknameInput({ detail: { value: nickname } })
  page.onChooseAvatar({ detail: { avatarUrl: avatar } })
}

test('registration uses native WeChat nickname and avatar capabilities without deprecated APIs or phone collection', async () => {
  const [markup, styles, script, authApi] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'pages/register/register.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.wxss'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'api/auth.js'), 'utf8')
  ])

  assert.equal((markup.match(/aria-label="邀请码"/g) || []).length, 1)
  assert.match(markup, /open-type="chooseAvatar"/)
  assert.match(markup, /bindchooseavatar="onChooseAvatar"/)
  assert.match(markup, /type="nickname"/)
  assert.match(markup, /bindinput="onNicknameInput"/)
  assert.match(markup, /bindchange="onPrivacyAgreementChange"/)
  assert.match(markup, /《用户隐私保护指引》/)
  assert.ok(
    markup.indexOf('class="profile-setup"') < markup.indexOf('class="credential-field"'),
    '微信头像和昵称区域必须位于邀请码或认领密钥之前'
  )
  const avatarIndex = markup.indexOf('class="avatar-picker"')
  const nicknameIndex = markup.indexOf('class="nickname-input')
  const privacyActionsIndex = markup.indexOf('class="privacy-actions"')
  const privacyErrorIndex = markup.indexOf('wx:if="{{privacyError}}"')
  const credentialIndex = markup.indexOf('class="credential-field"')
  assert.ok(
    avatarIndex < nicknameIndex
      && nicknameIndex < privacyActionsIndex
      && privacyActionsIndex < privacyErrorIndex
      && privacyErrorIndex < credentialIndex,
    '注册资料必须按头像、昵称、隐私条款及凭据的顺序排列'
  )
  assert.match(
    styles,
    /\.wechat-profile-row\s*\{[\s\S]*?flex-direction:\s*column;/,
    '头像和昵称必须分为上下两行'
  )
  assert.doesNotMatch(markup, /getPhoneNumber|手机号/)
  assert.match(script, /wx\.requirePrivacyAuthorize\s*\(/)
  assert.match(script, /setToken\(data\.token\)[\s\S]*?saveNicknamePhase\(\)/)
  assert.match(script, /memberApi[\s\S]*?updateNickname\(nickname\)/)
  assert.match(script, /memberApi[\s\S]*?uploadAvatar\(this\.data\.avatarTempPath\)/)
  assert.doesNotMatch(script, /getUserProfile|getUserInfo|getPhoneNumber|setStorage/)
  assert.match(authApi, /registerWithInvite\(code, inviteCode\)/)
  assert.doesNotMatch(authApi, /nickname|avatar|phoneCode/)
})

test('missing invitation or missing WeChat profile never obtains a login code', async () => {
  const fixture = createFixture()
  const missingInvitation = mountPage(await loadRegister(fixture))
  missingInvitation.onLoad({})
  missingInvitation.onRegister()
  assert.equal(fixture.wxLoginCalls, 0)
  assert.match(missingInvitation.data.fieldError, /邀请码/)

  const missingProfile = mountPage(await loadRegister(fixture))
  missingProfile.onLoad({ inviteCode: 'INVITE-ONE' })
  missingProfile.onRegister()
  assert.equal(fixture.wxLoginCalls, 0)
  assert.match(missingProfile.data.privacyError, /隐私/)
  assert.match(missingProfile.data.nicknameError, /昵称/)
  assert.match(missingProfile.data.avatarError, /头像/)
})

test('one click registers once, saves nickname, uploads avatar and then enters the storefront', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'INVITE-ONE' })
  prepareProfile(page)

  page.onRegister()
  page.onRegister()
  await settle()

  assert.equal(fixture.wxLoginCalls, 1)
  assert.deepEqual(fixture.inviteCalls, [{ code: 'LOGIN-CODE-1', inviteCode: 'INVITE-ONE' }])
  assert.deepEqual(fixture.savedTokens, ['MEMBER-TOKEN'])
  assert.deepEqual(fixture.nicknameCalls, ['微信昵称'])
  assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/register-avatar.png'])
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
})

test('registration accepts URL and scene invitation forms while retaining the selected profile', async () => {
  for (const query of [
    { inviteCode: encodeURIComponent('邀请 +/?&=') },
    { scene: encodeURIComponent('邀请 +/?&=') }
  ]) {
    const fixture = createFixture()
    const page = mountPage(await loadRegister(fixture))
    page.onLoad(query)
    prepareProfile(page, '杉杉', 'wxfile://tmp/shan-avatar.png')
    page.onRegister()
    await settle()

    assert.equal(page.data.inviteCode, '')
    assert.deepEqual(fixture.inviteCalls, [{
      code: 'LOGIN-CODE-1',
      inviteCode: '邀请 +/?&='
    }])
    assert.deepEqual(fixture.nicknameCalls, ['杉杉'])
    assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/shan-avatar.png'])
  }
})

test('failed account registration preserves invitation and selected profile for a fresh-code retry', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'RETRY-INVITE' })
  prepareProfile(page)
  fixture.setInviteResponse(Promise.reject({ code: 'NETWORK_ERROR', message: '网络异常' }))

  page.onRegister()
  await settle()
  assert.equal(page.data.inviteCode, 'RETRY-INVITE')
  assert.equal(page.data.nickname, '微信昵称')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/register-avatar.png')

  fixture.setLoginCodes('LOGIN-CODE-FRESH')
  fixture.setInviteResponse(Promise.resolve({ token: 'TOKEN-FRESH' }))
  page.onRegister()
  await settle()

  assert.equal(fixture.wxLoginCalls, 2)
  assert.deepEqual(fixture.inviteCalls.at(-1), {
    code: 'LOGIN-CODE-FRESH', inviteCode: 'RETRY-INVITE'
  })
  assert.deepEqual(fixture.nicknameCalls, ['微信昵称'])
  assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/register-avatar.png'])
})

test('avatar retry does not repeat account registration or a saved nickname', async () => {
  const fixture = createFixture()
  fixture.setAvatarResponse(() => Promise.reject({ code: 'NETWORK_ERROR', message: '头像网络失败' }))
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'INVITE-ONE' })
  prepareProfile(page)

  page.onRegister()
  await settle()
  assert.equal(page.data.accountCreated, true)
  assert.equal(page.data.nicknameSaved, true)
  assert.match(page.data.formError, /只需重试头像/)

  fixture.setAvatarResponse(Promise.resolve({ avatarUrl: '/api/v1/member-avatars/7' }))
  page.onRegister()
  await settle()

  assert.equal(fixture.wxLoginCalls, 1)
  assert.equal(fixture.inviteCalls.length, 1)
  assert.deepEqual(fixture.nicknameCalls, ['微信昵称'])
  assert.deepEqual(fixture.avatarCalls, [
    'wxfile://tmp/register-avatar.png',
    'wxfile://tmp/register-avatar.png'
  ])
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
})

test('nickname retry does not repeat account registration and proceeds to avatar upload', async () => {
  const fixture = createFixture()
  fixture.setNicknameResponse(() => Promise.reject({ code: 'NETWORK_ERROR', message: '昵称网络失败' }))
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'INVITE-ONE' })
  prepareProfile(page)

  page.onRegister()
  await settle()
  assert.equal(page.data.accountCreated, true)
  assert.equal(page.data.nicknameSaved, false)
  assert.deepEqual(fixture.avatarCalls, [])

  page.onNicknameInput({ detail: { value: '重试昵称' } })
  fixture.setNicknameResponse(Promise.resolve({ nickname: '重试昵称' }))
  page.onRegister()
  await settle()

  assert.equal(fixture.wxLoginCalls, 1)
  assert.equal(fixture.inviteCalls.length, 1)
  assert.deepEqual(fixture.nicknameCalls, ['微信昵称', '重试昵称'])
  assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/register-avatar.png'])
})

test('privacy refusal remains visible and blocks native profile collection and registration', async () => {
  const fixture = createFixture()
  fixture.setPrivacyMode('deny')
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'INVITE-ONE' })

  page.onPrivacyAgreementChange({ detail: { value: ['accepted'] } })
  assert.equal(page.data.privacyAuthorized, false)
  assert.match(page.data.privacyError, /隐私保护指引/)
  page.onNicknameInput({ detail: { value: '不应写入' } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp/no.png' } })
  page.onRegister()

  assert.equal(page.data.nickname, '')
  assert.equal(page.data.avatarTempPath, '')
  assert.equal(fixture.wxLoginCalls, 0)
  page.openPrivacyContract()
  assert.deepEqual(fixture.privacyContracts, ['open'])
})

test('sponsor mode ignores public invitations and saves the selected WeChat profile', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ mode: 'sponsor', inviteCode: 'MUST-NOT-LOAD', scene: 'MUST-NOT-LOAD' })
  page.onSponsorClaimInput({ detail: { value: 'SponsorClaimSecret2026StrongFixture' } })
  prepareProfile(page, '发起人微信昵称', 'wxfile://tmp/sponsor-avatar.png')

  page.onRegister()
  await settle()

  assert.equal(page.data.credentialMode, 'claim')
  assert.deepEqual(fixture.claimCalls, [{
    code: 'LOGIN-CODE-1', sponsorClaimSecret: 'SponsorClaimSecret2026StrongFixture'
  }])
  assert.deepEqual(fixture.inviteCalls, [])
  assert.deepEqual(fixture.nicknameCalls, ['发起人微信昵称'])
  assert.deepEqual(fixture.avatarCalls, ['wxfile://tmp/sponsor-avatar.png'])
})

test('sponsor login failure preserves the claim secret and selected profile', async () => {
  const fixture = createFixture()
  fixture.setLoginMode('fail')
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ mode: 'sponsor' })
  page.onSponsorClaimInput({ detail: { value: 'SponsorClaimSecret2026StrongFixture' } })
  prepareProfile(page)

  page.onRegister()

  assert.equal(fixture.wxLoginCalls, 1)
  assert.equal(page.data.sponsorClaimSecret, 'SponsorClaimSecret2026StrongFixture')
  assert.equal(page.data.avatarTempPath, 'wxfile://tmp/register-avatar.png')
  assert.match(page.data.formError, /已保留认领密钥/)
  assert.doesNotMatch(page.data.formError, /已保留邀请码/)
  assert.deepEqual(fixture.claimCalls, [])
})
