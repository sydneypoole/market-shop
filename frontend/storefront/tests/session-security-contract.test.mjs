import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = path => readFile(new URL(path, import.meta.url), 'utf8')

test('storefront login is cookie-only and development entry is capability gated fail-closed', async () => {
  const [api, login] = await Promise.all([
    source('../src/api.ts'),
    source('../src/views/LoginView.vue')
  ])

  assert.match(api, /credentials: 'include'/)
  assert.doesNotMatch(api, /localStorage|sessionStorage|Authorization/)
  assert.match(login, /api<RuntimeCapabilities>\('\/system\/capabilities'\)/)
  assert.match(login, /devLoginEnabled: false, wechatLoginEnabled: false/)
  assert.match(login, /<details v-if="capabilities\.devLoginEnabled">/)
  assert.match(login, /route\.query\.inviteCode \|\| ''/)
  assert.match(login, /api<\{ authorizationUrl: string \}>\('\/auth\/wechat\/authorize', \{\s*method: 'POST'/s)
  assert.match(login, /sponsorClaimSecret/)
  assert.match(login, /finally \{[\s\S]*busyWechat\.value = undefined/)
  assert.doesNotMatch(login, /wechat\/authorize\?/)
  assert.doesNotMatch(login, /BOOTSTRAP2026/)
  assert.doesNotMatch(login, /tokenValue|tokenName/)
})

test('post-login redirects reject browser URL normalization escapes', async () => {
  const sourceText = await source('../src/api.ts')
  const start = sourceText.indexOf('export function safeRedirect')
  const end = sourceText.indexOf('\n\nexport function redirectToLogin', start)
  assert.ok(start >= 0 && end > start, 'safeRedirect implementation must remain testable')
  const safeRedirect = new Function(
    `${sourceText.slice(start, end)
      .replaceAll('export ', '')
      .replaceAll(': unknown', '')
      .replaceAll(': string', '')}; return safeRedirect`
  )()

  assert.equal(safeRedirect('/orders/42?tab=proof#latest'), '/orders/42?tab=proof#latest')
  assert.equal(safeRedirect('https://bad.example', 'https://evil.example'), '/')
  for (const value of [
    '//evil.example',
    '/\\\\evil.example',
    '/%5C%5Cevil.example',
    '/\u0000evil',
    '/%0Aevil',
    'https://evil.example/orders',
    'javascript:alert(1)',
    '/%ZZ'
  ]) {
    assert.equal(safeRedirect(value), '/', `unsafe redirect should fall back: ${JSON.stringify(value)}`)
  }
})

test('admin login keeps secrets empty and relies only on the HttpOnly cookie session', async () => {
  const [api, login, session] = await Promise.all([
    source('../../admin/src/api.ts'),
    source('../../admin/src/views/LoginView.vue'),
    source('../../admin/src/session.ts')
  ])

  assert.match(api, /credentials: 'include'/)
  assert.doesNotMatch(api, /localStorage|sessionStorage|Authorization/)
  assert.match(login, /const password = ref\(''\)/)
  assert.doesNotMatch(login, /value="[^\"]+"[^>]+type="password"/)
  assert.doesNotMatch(login + session, /tokenValue|tokenName/)
})

test('all unauthorized response shapes enter the login flow', async () => {
  const api = await source('../src/api.ts')
  assert.match(api, /catch \{[\s\S]*response\.status === 401[\s\S]*redirectToLogin\(\)/)
  assert.match(api, /if \(!isEnvelope<T>\(raw\)\) \{[\s\S]*response\.status === 401[\s\S]*redirectToLogin\(\)/)
})
