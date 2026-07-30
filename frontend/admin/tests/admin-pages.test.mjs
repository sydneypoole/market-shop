import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('every protected admin route declares its backend permission', async () => {
  const main = await source('main.ts')
  const expected = new Map([
    ['/orders', 'order:read'],
    ['/catalog', 'catalog:read'],
    ['/rules', 'rule:publish'],
    ['/after-sales', 'aftersale:review'],
    ['/members', 'member:read'],
    ['/content', 'content:write'],
    ['/templates', 'storefront:template:manage'],
    ['/accounts', 'admin:account:manage'],
    ['/audit', 'audit:read'],
    ['/settings', 'system:setting:manage']
  ])

  for (const [path, permission] of expected) {
    assert.match(
      main,
      new RegExp(`path: '${path.replace('/', '\\/')}'.+permission: '${permission}'`),
      `${path} must remain protected by ${permission}`
    )
  }
  assert.match(main, /router\.beforeEach/)
  assert.match(main, /loadAdminSession\(\)/)
  assert.match(main, /createWebHistory\(import\.meta\.env\.BASE_URL\)/)
})

test('storefront template studio supports three presets, responsive preview and safe publishing', async () => {
  const [templates, preview, app, localization, styles] = await Promise.all([
    source('views/TemplatesView.vue'),
    source('components/TemplatePreview.vue'),
    source('App.vue'),
    source('localization.ts'),
    source('styles.css')
  ])

  for (const preset of ['EDITORIAL', 'VIBRANT', 'MINIMAL']) {
    assert.match(templates, new RegExp(preset))
  }
  for (const action of [
    '/storefront/templates',
    '/duplicate',
    '/publish',
    'method: \'DELETE\'',
    '<TemplatePreview'
  ]) {
    assert.ok(templates.includes(action), `TemplatesView is missing ${action}`)
  }
  assert.match(templates, /device === 'mobile'/)
  assert.match(templates, /expectedVersion/)
  assert.doesNotMatch(templates, /\b(prompt|confirm|alert)\s*\(/)
  assert.match(preview, /device-mobile/)
  assert.match(preview, /PRODUCT_COLLECTION/)
  assert.match(app, /商城模板/)
  assert.match(localization, /storefront:template:manage.*商城模板管理/)
  assert.match(styles, /\.admin-shell > aside/)
  assert.match(styles, /\.workspace > main/)
  assert.doesNotMatch(styles, /(?:^|\n)aside\s*\{/)
  assert.doesNotMatch(styles, /\.workspace main\s*\{/)
})

test('order and after-sale pages keep detail, proof and pagination workflows', async () => {
  const [orders, afterSales] = await Promise.all([
    source('views/OrdersView.vue'),
    source('views/AfterSalesView.vue')
  ])

  for (const fragment of [
    '/orders/search?',
    '/orders/${row.id}/notes',
    '/orders/${row.id}/proofs',
    '/orders/batch-ship',
    '<PaginationBar'
  ]) {
    assert.ok(orders.includes(fragment), `OrdersView is missing ${fragment}`)
  }
  for (const fragment of [
    '/after-sales/${row.id}/proofs',
    '/after-sale-proofs/${proof.id}/download',
    "adminApi<Settings>('/settings')",
    '<PaginationBar'
  ]) {
    assert.ok(afterSales.includes(fragment), `AfterSalesView is missing ${fragment}`)
  }
  assert.doesNotMatch(afterSales, /演示退货地址|demo-return/i)
})

test('catalog, rules and access-control pages expose completed management controls', async () => {
  const [catalog, rules, accounts, settings] = await Promise.all([
    source('views/CatalogView.vue'),
    source('views/RulesView.vue'),
    source('views/AccountsView.vue'),
    source('views/SettingsView.vue')
  ])

  assert.match(catalog, /<AssetPicker/)
  assert.match(catalog, /\/inventory-adjustments/)
  assert.match(catalog, /新增商品规格/)
  assert.match(rules, /DIRECT_REFERRAL_POINTS/)
  assert.match(rules, /高级：查看或编辑原始参数/)
  assert.match(accounts, /\/accounts\/\$\{row\.id\}\/unlock/)
  assert.match(accounts, /method:'DELETE'/)
  assert.match(accounts, /permission-grid/)
  assert.match(settings, /afterSaleReturnAddress/)
  assert.match(settings, /ORDER_TIMERS/)
})

test('HTML previews remain sandboxed and uploads use FormData', async () => {
  const [catalog, content, editor, picker, api, main, packageJson] = await Promise.all([
    source('views/CatalogView.vue'),
    source('views/ContentView.vue'),
    source('components/RichTextEditor.vue'),
    source('components/AssetPicker.vue'),
    source('api.ts'),
    source('main.ts'),
    readFile(new URL('../package.json', import.meta.url), 'utf8')
  ])

  assert.match(catalog, /sandbox=""/)
  assert.match(content, /sandbox=""/)
  assert.match(catalog, /<RichTextEditor[^>]+v-model="product\.descriptionHtml"/)
  assert.match(content, /<RichTextEditor[^>]+v-model="form\.bodyHtml"/)
  assert.match(editor, /QuillEditor/)
  assert.match(editor, /content-type="html"/)
  assert.match(main, /@vueup\/vue-quill\/dist\/vue-quill\.snow\.css/)
  assert.match(packageJson, /"@vueup\/vue-quill"/)
  assert.match(picker, /new FormData\(\)/)
  assert.match(api, /!\(init\.body instanceof FormData\)/)
  assert.match(api, /import\.meta\.env\.BASE_URL/)
})

test('member detail shows FIFO frozen-point batch traceability', async () => {
  const members = await source('views/MembersView.vue')

  assert.match(members, /sourceOrderId/)
  assert.match(members, /ruleVersionId/)
  assert.match(members, /frozenBatchId/)
  assert.match(members, /frozenBatchRemainingPoints/)
  assert.match(members, /B 池批次/)
})
