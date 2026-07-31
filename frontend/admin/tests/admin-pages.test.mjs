import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('typed navigation registry is the single source for routes, sidebar, breadcrumbs and landing', async () => {
  const [navigation, main, app, session, pageHeader] = await Promise.all([
    source('admin-navigation.ts'), source('main.ts'), source('App.vue'), source('session.ts'),
    source('components/admin/PageHeader.vue')
  ])
  const expected = new Map([
    ['/orders', 'order:read'], ['/catalog', 'catalog:read'], ['/rules', 'rule:publish'],
    ['/after-sales', 'aftersale:review'], ['/members', 'member:read'], ['/content', 'content:write'],
    ['/templates', 'storefront:template:manage'], ['/accounts', 'admin:account:manage'],
    ['/audit', 'audit:read'], ['/settings', 'system:setting:manage']
  ])
  for (const [path, permission] of expected) {
    assert.match(navigation, new RegExp(`path: '${path.replace('/', '\\/')}'.{0,260}permission: '${permission}'`, 's'))
  }
  for (const group of ['工作台', '交易履约', '商品运营', '会员增长', '平台治理']) assert.match(navigation, new RegExp(group))
  assert.match(main, /adminNavigation\.map/)
  assert.match(main, /permission: item\.permission/)
  assert.match(main, /router\.beforeEach/)
  assert.match(main, /createWebHistory\(import\.meta\.env\.BASE_URL\)/)
  assert.match(app, /adminNavigationGroups\.flatMap/)
  assert.match(session, /firstAllowedNavigationPath\(can\)/)
  assert.match(pageHeader, /navigationBreadcrumbs/)
})

test('storefront template studio supports presets, dirty protection, narrow-screen tabs and safe publishing', async () => {
  const [templates, preview, navigation, styles] = await Promise.all([
    source('views/TemplatesView.vue'), source('components/TemplatePreview.vue'),
    source('admin-navigation.ts'), source('styles.css')
  ])
  for (const preset of ['EDITORIAL', 'VIBRANT', 'MINIMAL']) assert.match(templates, new RegExp(preset))
  for (const action of ['/storefront/templates', '/duplicate', '/publish', "method: 'DELETE'", '<TemplatePreview']) {
    assert.ok(templates.includes(action), `TemplatesView is missing ${action}`)
  }
  assert.match(templates, /expectedVersion/)
  assert.match(templates, /editorDirty/)
  assert.match(templates, /beforeunload/)
  assert.match(templates, /onBeforeRouteLeave/)
  assert.match(templates, /resolvePendingLeave/)
  assert.match(templates, /discardEditorOpen/)
  assert.match(templates, /mobilePanel/)
  assert.match(templates, /<BusinessActionDialog/)
  assert.doesNotMatch(templates, /\b(prompt|confirm|alert)\s*\(/)
  assert.match(preview, /device-mobile/)
  assert.match(preview, /PRODUCT_COLLECTION/)
  assert.match(navigation, /商城模板/)
  assert.match(styles, /\.admin-shell > aside/)
  assert.match(styles, /\.workspace > main/)
  assert.doesNotMatch(styles, /(?:^|\n)aside\s*\{/)
  assert.doesNotMatch(styles, /\.workspace main\s*\{/)
})

test('order and after-sale pages keep authoritative detail, proof, URL filter and partial retry workflows', async () => {
  const [orders, afterSales] = await Promise.all([
    source('views/OrdersView.vue'), source('views/AfterSalesView.vue')
  ])
  for (const fragment of [
    '/orders/search?', '/orders/${orderId}/notes', '/orders/${orderId}/proofs',
    '/orders/batch-ship', '<PaginationBar', '<DetailDrawer', '<FilterBar', 'appliedFilters',
    'detailRequestSequence', 'selected.value.filter(id => !succeeded.has(id))'
  ]) assert.ok(orders.includes(fragment), `OrdersView is missing ${fragment}`)
  assert.ok(orders.indexOf('detailNotes.value = []') < orders.indexOf('adminApi<Note[]>(`/orders/${orderId}/notes`)'))
  assert.ok(orders.indexOf('detailProofs.value = []') < orders.indexOf('adminApi<Proof[]>(`/orders/${orderId}/proofs`)'))
  assert.doesNotMatch(orders, /PENDING_ADMIN_REVIEW[^]{0,500}@click="review/)

  for (const fragment of [
    '/after-sales/${row.id}/proofs', '/after-sale-proofs/${proof.id}/download',
    "adminApi<Settings>('/settings')", '<PaginationBar', '<DetailDrawer', '<BusinessActionDialog',
    'proofRequestSequence', 'proofs.value = []'
  ]) assert.ok(afterSales.includes(fragment), `AfterSalesView is missing ${fragment}`)
  assert.ok(afterSales.indexOf('proofs.value = []') < afterSales.indexOf('adminApi<Proof[]>(`/after-sales/${row.id}/proofs`)'))
  assert.doesNotMatch(afterSales, /演示退货地址|demo-return/i)
})

test('catalog, rules, accounts and settings enforce P0 safety workflows', async () => {
  const [catalog, rules, accounts, settings, picker] = await Promise.all([
    source('views/CatalogView.vue'), source('views/RulesView.vue'), source('views/AccountsView.vue'),
    source('views/SettingsView.vue'), source('components/AssetPicker.vue')
  ])
  assert.match(catalog, /<AssetPicker/)
  assert.match(catalog, /\/inventory-adjustments/)
  assert.match(catalog, /inventory\.requestId = crypto\.randomUUID\(\)/)
  assert.match(catalog, /商品资料与库存调整是两个独立/)
  assert.match(catalog, /historyRequestSequence/)
  assert.ok(catalog.indexOf('history.value = []') < catalog.indexOf('adminApi<Adjustment[]>'))
  assert.match(catalog, /当前库存（只读）/)

  assert.match(rules, /DIRECT_REFERRAL_POINTS/)
  assert.match(rules, /高级：查看或编辑原始参数/)
  assert.match(rules, /type LoadState = 'unloaded' \| 'loading' \| 'loaded' \| 'error'/)
  assert.match(rules, /validatedPayload/)
  assert.match(rules, /diffRows/)
  assert.ok(rules.indexOf("'/rules/validate'") < rules.indexOf("adminApi<Rule>('/rules'"))
  assert.match(rules, /编辑与发布已锁定/)
  assert.match(rules, /publishableRuleTypeOptions/)
  assert.doesNotMatch(rules, /form\.ruleType === 'ORDER_TIMER'/)
  assert.match(rules, /committed = true/)

  assert.match(accounts, /\/accounts\/\$\{account\.id\}\/unlock/)
  assert.match(accounts, /method: 'DELETE'/)
  assert.match(accounts, /permission-grid/)
  assert.match(accounts, /clearCreateSecrets/)
  assert.match(accounts, /function clearSensitive/)
  assert.match(accounts, /type="password" autocomplete="current-password"/)
  assert.match(accounts, /仅超级管理员/)

  assert.match(settings, /afterSaleReturnAddress/)
  assert.match(settings, /ORDER_TIMERS/)
  assert.match(settings, /timerState/)
  assert.match(settings, /timerBaseline/)
  assert.match(settings, /策略编辑与发布已锁定/)
  assert.match(settings, /await loadTimers\(\)/)
  assert.match(settings, /committed = true/)
  assert.match(settings, /不要重复发布/)

  assert.match(picker, /<BusinessActionDialog/)
  assert.doesNotMatch([catalog, rules, accounts, settings, picker].join('\n'), /\b(prompt|confirm|alert)\s*\(/)
})

test('HTML previews remain sandboxed and uploads use FormData', async () => {
  const [catalog, content, editor, picker, api, main, packageJson] = await Promise.all([
    source('views/CatalogView.vue'), source('views/ContentView.vue'),
    source('components/RichTextEditor.vue'), source('components/AssetPicker.vue'),
    source('api.ts'), source('main.ts'), readFile(new URL('../package.json', import.meta.url), 'utf8')
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
  assert.match(picker, /10 \* 1024 \* 1024/)
  assert.match(api, /!\(init\.body instanceof FormData\)/)
  assert.match(api, /import\.meta\.env\.BASE_URL/)
})

test('member detail shows FIFO frozen-point batch traceability', async () => {
  const members = await source('views/MembersView.vue')
  for (const fragment of ['sourceOrderId', 'ruleVersionId', 'frozenBatchId', 'frozenBatchRemainingPoints', 'B 池批次']) {
    assert.match(members, new RegExp(fragment.replace('池', '\\s*池')))
  }
  assert.match(members, /requestId\.value = crypto\.randomUUID\(\)/)
  assert.match(members, /<DetailDrawer/)
  assert.match(members, /<BusinessActionDialog/)
})
