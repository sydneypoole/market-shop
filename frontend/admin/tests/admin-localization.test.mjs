import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('shared localization dictionary covers every major admin business domain', async () => {
  const localization = await source('localization.ts')
  const expectedLabels = [
    '待直属上级确认',
    '待后台审核',
    '基础会员',
    '体验官',
    '超级会员',
    '分红会员',
    '升级专区',
    '复购专区',
    '首页横幅',
    '个人订单升级任务',
    '后台账号管理',
    '商城用户',
    '创建后台账号',
    '直属推荐积分奖励'
  ]

  for (const label of expectedLabels) {
    assert.ok(localization.includes(`label: '${label}'`) || localization.includes(`: '${label}'`), `missing Chinese label: ${label}`)
  }
  assert.match(localization, /export function labelOf/)
  assert.match(localization, /unknownLabel/)
  assert.match(localization, /export const permissionLabel/)
  assert.match(localization, /export function auditActionLabel/)
})

test('admin selectors render Chinese labels while preserving backend enum values', async () => {
  const pages = await Promise.all([
    source('views/OrdersView.vue'),
    source('views/AfterSalesView.vue'),
    source('views/CatalogView.vue'),
    source('views/ContentView.vue'),
    source('views/MembersView.vue'),
    source('views/RulesView.vue')
  ])

  for (const page of pages) {
    assert.doesNotMatch(
      page,
      /<option(?:\s[^>]*)?>(?:ACTIVE|DISABLED|LOCKED|ON_SALE|OFF_SALE|UPGRADE|REPURCHASE|BANNER|ANNOUNCEMENT|QUICK_ENTRY|HELP|DRAFT|PUBLISHED|OFFLINE|SELF_ORDER_TASK|DIRECT_REFERRAL_TASK|DIRECT_REFERRAL_POINTS|FROZEN_POINTS_RELEASE|INACTIVITY_DOWNGRADE|ORDER_TIMER)<\/option>/,
      'select options must not expose backend enum values as labels'
    )
  }

  assert.match(pages[0], /:value="option\.value">\{\{ option\.label \}\}/)
  assert.match(pages[2], /salesSceneOptions/)
  assert.match(pages[3], /contentTypeOptions/)
  assert.match(pages[4], /memberLevelOptions/)
  assert.match(pages[5], /ruleTypeOptions/)
})

test('admin templates do not directly render known enum fields or English decorative headings', async () => {
  const viewsUrl = new URL('../src/views/', import.meta.url)
  const files = (await readdir(viewsUrl)).filter(file => file.endsWith('.vue'))
  const views = await Promise.all(files.map(file => readFile(new URL(file, viewsUrl), 'utf8')))
  const combined = views.join('\n')

  assert.doesNotMatch(
    combined,
    /\{\{\s*(?:row|detail|rule|item|proof)\.(?:status|type|ruleType|salesScene|contentType|actorType|action|resourceType|proofType|mediaType|entryType|triggerType|beforeLevel|afterLevel)\s*\}\}/
  )
  assert.doesNotMatch(
    combined,
    /MARKET OPERATIONS|STAFF ACCESS|ORDER DETAIL|AFTER-SALE DETAIL|RustFS|后台账号与 RBAC/
  )
  assert.match(combined, /商城运营中心/)
  assert.match(combined, /订单详情/)
  assert.match(combined, /售后详情/)
  assert.match(combined, /后台账号与权限/)
})
