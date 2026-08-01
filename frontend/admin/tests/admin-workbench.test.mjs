import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('all protected pages use the shared workbench page primitive and no browser-native dialogs', async () => {
  const directory = new URL('../src/views/', import.meta.url)
  const files = (await readdir(directory)).filter(file => file.endsWith('View.vue') && file !== 'LoginView.vue')
  assert.equal(files.length, 11)
  for (const file of files) {
    const view = await readFile(new URL(file, directory), 'utf8')
    assert.match(view, /<PageHeader/, `${file} must use PageHeader`)
    assert.doesNotMatch(view, /\b(prompt|confirm|alert)\s*\(/, `${file} contains a browser-native dialog`)
    assert.doesNotMatch(view, /class="page-title"|class="modal-mask"/, `${file} contains a legacy page/modal shell`)
  }
})

test('shared dialog and drawer implement modal semantics, focus containment and close protection', async () => {
  const [dialog, drawer, business] = await Promise.all([
    source('components/admin/BaseDialog.vue'), source('components/admin/DetailDrawer.vue'),
    source('components/admin/BusinessActionDialog.vue')
  ])
  for (const fragment of [
    'role="dialog"', 'aria-modal="true"', "event.key === 'Escape'", "event.key !== 'Tab'",
    'previousFocus', 'focusableSelector', 'requestClose', 'props.submitting', 'props.dirty',
    'document.body.style.overflow', '<Teleport to="body"', '{ immediate: true }'
  ]) assert.ok(dialog.includes(fragment), `BaseDialog is missing ${fragment}`)
  assert.match(drawer, /placement="right"/)
  assert.match(business, /type="password"/)
  assert.match(business, /autocomplete="current-password"/)
  assert.match(business, /影响说明/)
  assert.match(business, /clearSecrets/)
})

test('shared list, feedback and semantic token primitives cover state and responsive behavior', async () => {
  const [filter, table, alert, toast, status, styles, app] = await Promise.all([
    source('components/admin/FilterBar.vue'), source('components/admin/TableFrame.vue'),
    source('components/admin/InlineAlert.vue'), source('components/admin/ToastRegion.vue'),
    source('components/admin/StatusTag.vue'), source('styles.css'), source('App.vue')
  ])
  assert.match(filter, /已应用条件/)
  assert.match(filter, /@submit\.prevent/)
  assert.match(table, /正在加载/)
  assert.match(table, /数据加载失败/)
  assert.match(table, /emptyTitle/)
  assert.match(alert, /role=/)
  assert.match(toast, /aria-live="polite"/)
  assert.match(status, /status-tag--/)
  for (const token of ['--color-canvas', '--color-brand', '--color-danger', '--space-4', '--radius-lg', '--layer-overlay', '--focus-ring']) {
    assert.match(styles, new RegExp(token))
  }
  assert.match(styles, /@media \(max-width: 720px\)/)
  assert.match(styles, /prefers-reduced-motion/)
  assert.match(app, /adminNavigationGroups/)
  assert.match(app, /<ToastRegion/)
})

test('API errors preserve HTTP status, business code and message with consistent auth/conflict helpers', async () => {
  const [api, session] = await Promise.all([source('api.ts'), source('session.ts')])
  assert.match(api, /class AdminApiError extends Error/)
  assert.match(api, /readonly status/)
  assert.match(api, /readonly code/)
  assert.match(api, /readonly kind/)
  assert.match(api, /parsed\.code/)
  assert.match(api, /response\.status/)
  assert.match(api, /isConflictError/)
  assert.match(api, /response\.status === 401/)
  assert.match(api, /setAdminUnauthorizedHandler/)
  assert.match(api, /adminDownload/)
  assert.match(api, /redirect=\$\{encodeURIComponent\(redirect\)\}/)
  assert.match(session, /setAdminUnauthorizedHandler\(clearAdminSession\)/)
  assert.match(session, /safeAdminRedirect/)
  assert.match(session, /target\.origin === origin/)
})

test('admin post-login redirects reject browser URL normalization escapes', async () => {
  const sourceText = await source('session.ts')
  const start = sourceText.indexOf('export function safeAdminRedirect')
  const helperStart = sourceText.indexOf('const ADMIN_REDIRECT_CONTROL_CHARACTERS', start)
  assert.ok(start >= 0 && helperStart > start, 'safeAdminRedirect implementation must remain testable')
  const implementation = sourceText.slice(start, helperStart)
    .replaceAll('export ', '')
    .replaceAll(': unknown', '')
    .replaceAll(': string', '')
  const helper = sourceText.slice(helperStart)
    .replaceAll(': string', '')
  const safeAdminRedirect = new Function(
    `const firstAllowedPath = () => '/'; ${helper}; ${implementation}; return safeAdminRedirect`
  )()

  assert.equal(safeAdminRedirect('/orders/42?tab=proof#latest', '/'), '/orders/42?tab=proof#latest')
  for (const value of [
    '//evil.example',
    '/\\evil.example',
    '/%5C%5Cevil.example',
    '/\u0000evil',
    '/%0Aevil',
    '/%ZZ'
  ]) {
    assert.equal(safeAdminRedirect(value, '/'), '/', `unsafe admin redirect should fall back: ${JSON.stringify(value)}`)
  }
})

test('filters and exports use applied snapshots and URL state instead of draft input', async () => {
  const [orders, audit, afterSales, members, catalog, accounts] = await Promise.all([
    source('views/OrdersView.vue'), source('views/AuditView.vue'),
    source('views/AfterSalesView.vue'), source('views/MembersView.vue'),
    source('views/CatalogView.vue'), source('views/AccountsView.vue')
  ])
  for (const view of [orders, audit, afterSales, members]) {
    assert.match(view, /draftFilters/)
    assert.match(view, /appliedFilters/)
    assert.match(view, /router\.push/)
  }
  for (const view of [catalog, accounts]) {
    assert.match(view, /draftKeyword/)
    assert.match(view, /appliedKeyword/)
    assert.match(view, /useRoute/)
    assert.match(view, /router\.push/)
  }
  assert.match(orders, /apiParams\(false\)/)
  assert.match(audit, /params\(false\)/)
  assert.match(orders, /adminDownload/)
  assert.match(audit, /adminDownload/)
  assert.match(audit, /最多导出 10,000 条/)
})

test('published content cannot silently become a draft and narrow tables become labeled cards', async () => {
  const [content, styles] = await Promise.all([source('views/ContentView.vue'), source('styles.css')])
  assert.match(content, /editing\.value\?\.status === 'PUBLISHED'/)
  assert.match(content, /row\.status !== 'PUBLISHED'[^]*编辑草稿/)
  assert.match(content, /下线后再编辑/)
  assert.doesNotMatch(content, /Object\.assign\(form, saved, \{ status: 'DRAFT' \}\)/)

  const directory = new URL('../src/views/', import.meta.url)
  for (const file of ['OrdersView.vue', 'AfterSalesView.vue', 'MembersView.vue', 'CatalogView.vue', 'AccountsView.vue', 'AuditView.vue', 'ContentView.vue', 'RulesView.vue']) {
    const view = await readFile(new URL(file, directory), 'utf8')
    assert.match(view, /class="responsive-table"/, `${file} must opt into narrow-screen cards`)
    assert.match(view, /data-label="操作"/, `${file} must keep actions labeled at 390px`)
  }
  assert.match(styles, /\.responsive-table td::before/)
  assert.match(styles, /content: attr\(data-label\)/)
})
