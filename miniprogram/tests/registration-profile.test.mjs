import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

const navigationUtils = await loadCommonJs('utils/navigation.js')

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

function flushPromises() {
  return new Promise((resolvePromise) => setImmediate(resolvePromise))
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
      '../../utils/navigation': navigationUtils,
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
  const savedTokens = []
  const switchTabs = []
  const redirects = []
  const wxLoginCodes = ['LOGIN-CODE-1']
  let inviteResponse = Promise.resolve({ token: 'MEMBER-TOKEN', nickname: '宏杉会员-ABC12345' })
  let claimResponse = Promise.resolve({ token: 'SPONSOR-TOKEN', nickname: '商城发起人' })
  let loginMode = 'success'
  let wxLoginCalls = 0
  const fixture = {
    storedToken: '', savedTokens, inviteCalls, claimCalls, switchTabs, redirects,
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
    wx: {
      login(options) {
        wxLoginCalls += 1
        if (loginMode === 'fail') return options.fail()
        options.success({ code: wxLoginCodes.shift() || `LOGIN-CODE-${wxLoginCalls}` })
      },
      switchTab(options) { switchTabs.push(plain(options)) },
      redirectTo(options) { redirects.push(plain(options)) },
      showToast() {}
    },
    get wxLoginCalls() { return wxLoginCalls },
    setLoginCodes(...codes) { wxLoginCodes.splice(0, wxLoginCodes.length, ...codes) },
    setLoginMode(value) { loginMode = value },
    setInviteResponse(value) { inviteResponse = value }
  }
  return fixture
}

test('public registration renders only invitation and one register button with no profile or phone collection', async () => {
  const [markup, script, authApi] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'pages/register/register.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'api/auth.js'), 'utf8')
  ])
  assert.equal((markup.match(/aria-label="邀请码"/g) || []).length, 1)
  assert.match(markup, /'一键注册'/)
  assert.doesNotMatch(markup, /chooseAvatar|type="nickname"|getPhoneNumber|手机号/)
  assert.doesNotMatch(script, /getUserProfile|getUserInfo|getPhoneNumber|phoneCode|chooseAvatar|nickname/)
  assert.match(authApi, /registerWithInvite\(code, inviteCode\)/)
  assert.match(authApi, /data:\s*body/)
  assert.doesNotMatch(authApi, /uploadPublicFile|uploadFile|phoneCode|nickname|avatar/)
})

test('missing invitation never obtains a login code or submits registration', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({})
  page.onRegister()
  assert.equal(fixture.wxLoginCalls, 0)
  assert.deepEqual(fixture.inviteCalls, [])
  assert.match(page.data.fieldError, /邀请码/)
})

test('one click uses one fresh login code and one JSON registration then goes home', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'INVITE-ONE' })
  page.onRegister()
  page.onRegister()
  await flushPromises()
  assert.equal(fixture.wxLoginCalls, 1)
  assert.deepEqual(fixture.inviteCalls, [{ code: 'LOGIN-CODE-1', inviteCode: 'INVITE-ONE' }])
  assert.deepEqual(fixture.savedTokens, ['MEMBER-TOKEN'])
  assert.deepEqual(fixture.switchTabs, [{ url: '/pages/index/index' }])
})

test('registration accepts the URL-encoded invitation component from the native share path', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  const invitation = '邀请 +/?&='
  page.onLoad({ inviteCode: encodeURIComponent(invitation) })

  assert.equal(page.data.inviteCode, invitation)
  page.onRegister()
  await flushPromises()
  assert.deepEqual(fixture.inviteCalls, [{
    code: 'LOGIN-CODE-1',
    inviteCode: invitation
  }])
})

test('failed registration keeps invitation and retry obtains a new login code', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ inviteCode: 'RETRY-INVITE' })
  fixture.setInviteResponse(Promise.reject({ code: 'NETWORK_ERROR', message: '网络异常' }))
  page.onRegister()
  await flushPromises()
  assert.equal(page.data.inviteCode, 'RETRY-INVITE')

  fixture.setLoginCodes('LOGIN-CODE-FRESH')
  fixture.setInviteResponse(Promise.resolve({ token: 'TOKEN-FRESH' }))
  page.onRegister()
  await flushPromises()
  assert.equal(fixture.wxLoginCalls, 2)
  assert.deepEqual(fixture.inviteCalls.at(-1), {
    code: 'LOGIN-CODE-FRESH', inviteCode: 'RETRY-INVITE'
  })
})

test('sponsor claim is explicit query mode and submits no public invitation', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ mode: 'sponsor', inviteCode: 'MUST-NOT-LOAD' })
  page.onSponsorClaimInput({ detail: { value: 'SponsorClaimSecret2026StrongFixture' } })
  page.onRegister()
  await flushPromises()
  assert.deepEqual(fixture.claimCalls, [{
    code: 'LOGIN-CODE-1', sponsorClaimSecret: 'SponsorClaimSecret2026StrongFixture'
  }])
  assert.deepEqual(fixture.inviteCalls, [])
})

test('sponsor login failure preserves and names the claim secret instead of an invitation', async () => {
  const fixture = createFixture()
  fixture.setLoginMode('fail')
  const page = mountPage(await loadRegister(fixture))
  page.onLoad({ mode: 'sponsor' })
  page.onSponsorClaimInput({ detail: { value: 'SponsorClaimSecret2026StrongFixture' } })

  page.onRegister()

  assert.equal(fixture.wxLoginCalls, 1)
  assert.equal(page.data.sponsorClaimSecret, 'SponsorClaimSecret2026StrongFixture')
  assert.match(page.data.formError, /已保留认领密钥/)
  assert.doesNotMatch(page.data.formError, /已保留邀请码/)
  assert.deepEqual(fixture.claimCalls, [])
})
