import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = path => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('admin shell uses the restrained plum workbench system and a single icon family', async () => {
  const [app, navigation, icon, styles, packageJson] = await Promise.all([
    source('App.vue'),
    source('admin-navigation.ts'),
    source('components/admin/AdminIcon.vue'),
    source('styles.css'),
    readFile(new URL('../package.json', import.meta.url), 'utf8')
  ])

  assert.match(packageJson, /"@phosphor-icons\/vue"/)
  assert.match(icon, /from '@phosphor-icons\/vue'/)
  assert.match(app, /<AdminIcon :name="item\.icon"/)
  assert.doesNotMatch(app, /<i aria-hidden="true">\{\{ item\.icon \}\}<\/i>/)
  for (const name of ['dashboard', 'orders', 'after-sales', 'catalog', 'content', 'members', 'rules', 'accounts', 'audit', 'settings']) {
    assert.match(navigation, new RegExp(`icon: '${name}'`))
  }
  for (const token of ['--color-brand: #78476f', '--color-canvas: #f4f4f6', '--color-brand-soft', '--color-border-strong']) {
    assert.ok(styles.includes(token), `missing design token ${token}`)
  }
  assert.match(styles, /prefers-reduced-motion/)
  assert.match(styles, /prefers-reduced-transparency/)
  assert.match(styles, /:focus-visible/)
  assert.match(styles, /button:disabled/)
})

test('login and shared feedback surfaces use complete accessible states without text glyph icons', async () => {
  const [login, styles, dialog, alert, table, toast, business] = await Promise.all([
    source('views/LoginView.vue'),
    source('styles.css'),
    source('components/admin/BaseDialog.vue'),
    source('components/admin/InlineAlert.vue'),
    source('components/admin/TableFrame.vue'),
    source('components/admin/ToastRegion.vue'),
    source('components/admin/BusinessActionDialog.vue')
  ])

  assert.match(login, /min-height: 100dvh/)
  assert.match(login, /\.login-entry \{ width: 100%; min-width: 0; display: flex;/)
  assert.match(login, /\.login-card \{ width: 100%; max-width: 430px; min-width: 0;/)
  assert.doesNotMatch(login, /\.login-card \{[^}]*width: min\(/)
  assert.match(login, /\.login-card \.field input \{ width: 100%; max-width: 100%;/)
  assert.match(login, /\.login-submit \{ width: 100%; max-width: 100%;/)
  assert.match(login, /\.login \{ width: 100%; min-width: 0; grid-template-columns: minmax\(0, 1fr\); \}/)
  assert.match(login, /\.login-identity, \.login-entry \{ width: 100%; min-width: 0; \}/)
  assert.match(login, /\.login-card \{ width: 100%; max-width: 100%; \}/)
  assert.match(styles, /html \{[^}]*overflow-x: clip;/)
  assert.match(styles, /body \{[^}]*overflow-x: clip;/)
  assert.match(login, /if \(busy\.value\) return/)
  assert.match(login, /<InlineAlert v-if="error"/)
  assert.match(login, /:disabled="busy"/)
  assert.match(login, /<AdminIcon v-else name="loading"/)
  assert.match(dialog, /<AdminIcon name="close"/)
  assert.match(alert, /toneIcon/)
  assert.match(table, /state-skeleton/)
  assert.match(table, /<AdminIcon name="empty"/)
  assert.match(toast, /<AdminIcon :name=/)
  assert.match(business, /<AdminIcon name="arrow-right"/)
  assert.doesNotMatch([dialog, alert, table, toast, business].join('\n'), />\s*[×✓◇→]\s*</)
})
