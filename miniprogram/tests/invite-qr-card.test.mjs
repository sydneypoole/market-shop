import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'

import { loadCommonJs, miniprogramRoot, plain } from './helpers.mjs'

function mountPage(definition) {
  const instance = {
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

async function loadInvite(fixture) {
  let definition
  await loadCommonJs('pages/member/invite.js', {
    globals: {
      wx: fixture.wx,
      Page(value) { definition = value }
    },
    requireMap: {
      '../../api/member': fixture.memberApi,
      '../../utils/format': {
        fenToYuan() { return '0.00' },
        dateTime(value) { return value || '' }
      },
      '../../utils/request': {
        getToken() { return fixture.token }
      }
    }
  })
  return definition
}

function createFixture(options = {}) {
  const wxacodeCalls = []
  const writeFiles = []
  const savedAlbums = []
  let invitation = Object.hasOwn(options, 'invitation')
    ? options.invitation
    : {
        code: 'MSABCDEF1234',
        status: 'ACTIVE',
        useCount: 2,
        registrationPath: '/pages/register/register?inviteCode=MSABCDEF1234',
        expiresAt: '2027-08-13T00:00:00Z'
      }
  let wxacode = Object.hasOwn(options, 'wxacode')
    ? options.wxacode
    : {
        contentType: 'image/png',
        imageBase64: 'aW52aXRlLXFy'
      }
  const fixture = {
    token: Object.hasOwn(options, 'token') ? options.token : 'MEMBER-TOKEN',
    wxacodeCalls,
    writeFiles,
    savedAlbums,
    memberApi: {
      invitation() { return Promise.resolve(invitation) },
      directMembers() { return Promise.resolve([]) },
      invitationWxacode() {
        wxacodeCalls.push({})
        return Promise.resolve(wxacode)
      },
      createInvitation() { return Promise.resolve(invitation) },
      revokeInvitation() { return Promise.resolve() },
      regenerateInvitation() { return Promise.resolve(invitation) }
    },
    wx: {
      env: { USER_DATA_PATH: '/tmp/wx' },
      reLaunch() {},
      showToast() {},
      showModal() {},
      setClipboardData() {},
      pageScrollTo() {},
      createSelectorQuery() {
        return {
          in() { return this },
          select() { return this },
          selectViewport() { return this },
          boundingClientRect() { return this },
          scrollOffset() { return this },
          exec() {}
        }
      },
      getFileSystemManager() {
        return {
          writeFile(options) {
            writeFiles.push(plain(options))
            if (options && options.success) options.success()
          }
        }
      },
      saveImageToPhotosAlbum(options) {
        savedAlbums.push(plain(options))
        if (options && options.success) options.success()
      }
    }
  }
  return fixture
}

test('invite page renders a brand QR card with save and native share, never a sponsor secret', async () => {
  const [markup, script, config, api] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'pages/member/invite.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/member/invite.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/member/invite.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'api/member.js'), 'utf8')
  ])
  assert.match(markup, /宏杉生物/)
  assert.match(markup, /qrImage/)
  assert.match(markup, /保存二维码卡片/)
  assert.match(markup, /open-type="share"/)
  assert.match(markup, /bindtap="onSaveCard"/)
  assert.doesNotMatch(markup, /sponsorClaimSecret|认领密钥/)
  assert.match(script, /onShareAppMessage/)
  assert.doesNotMatch(script, /sponsorClaimSecret/)
  assert.match(config, /"enableShareAppMessage":\s*true/)
  assert.match(api, /invitationWxacode\s*\(/)
  assert.match(api, /\/membership\/invitation\/wxacode/)
})

test('active invitation loads the official wxacode card and shares the native registration path', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadInvite(fixture))
  page.onShow()
  await flushPromises()
  await flushPromises()

  assert.equal(page.data.invitation.code, 'MSABCDEF1234')
  assert.equal(
    page.data.invitation.registrationPath,
    '/pages/register/register?inviteCode=MSABCDEF1234'
  )
  assert.equal(page.data.qrImage, 'data:image/png;base64,aW52aXRlLXFy')
  assert.equal(fixture.wxacodeCalls.length, 1)

  const share = page.onShareAppMessage()
  assert.match(share.title, /宏杉生物/)
  assert.equal(share.path, 'pages/register/register?inviteCode=MSABCDEF1234')
  assert.equal(share.imageUrl, '/assets/brand/logo.png')
  assert.doesNotMatch(share.imageUrl, /^data:/)
  assert.doesNotMatch(JSON.stringify(share), /sponsorClaimSecret|认领密钥/)
})

test('missing invitation never mints a wxacode or a share path', async () => {
  const fixture = createFixture({ invitation: null })
  const page = mountPage(await loadInvite(fixture))
  page.onShow()
  await flushPromises()
  await flushPromises()

  assert.equal(page.data.invitation, null)
  assert.equal(page.data.qrImage || '', '')
  assert.equal(fixture.wxacodeCalls.length, 0)
  assert.equal(page.onShareAppMessage(), undefined)
})

test('saving the QR card writes the authenticated image to the album', async () => {
  const fixture = createFixture()
  const page = mountPage(await loadInvite(fixture))
  page.onShow()
  await flushPromises()
  await flushPromises()

  page.onSaveCard()
  assert.equal(fixture.writeFiles.length, 1)
  assert.equal(fixture.writeFiles[0].encoding, 'base64')
  assert.equal(fixture.writeFiles[0].data, 'aW52aXRlLXFy')
  assert.equal(fixture.savedAlbums.length, 1)
  assert.equal(fixture.savedAlbums[0].filePath, fixture.writeFiles[0].filePath)
})
