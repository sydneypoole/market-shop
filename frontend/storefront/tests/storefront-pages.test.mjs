import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = path => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('member routes are guarded while catalog and current rules remain public', async () => {
  const main = await source('main.ts')
  for (const route of [
    '/cart',
    '/checkout',
    '/orders',
    '/orders/:id',
    '/membership',
    '/addresses',
    '/after-sales',
    '/after-sales/:id',
    '/notifications'
  ]) {
    assert.match(
      main,
      new RegExp(`path: '${route.replaceAll('/', '\\/')}'.+requiresAuth: true`),
      `${route} must require a member session`
    )
  }
  assert.match(main, /router\.beforeEach/)
  assert.match(main, /requireUserSession\(to\.fullPath\)/)
  assert.match(main, /path: '\/rules', component: RulesView/)
})

test('order and after-sale detail pages expose items, logistics, timelines and short-lived proofs', async () => {
  const [order, afterSale, proofGallery] = await Promise.all([
    source('views/OrderDetailView.vue'),
    source('views/AfterSaleDetailView.vue'),
    source('components/ProofGallery.vue')
  ])

  for (const fragment of [
    '/orders/${orderId.value}',
    '/orders/${orderId.value}/proofs',
    'detail.items',
    'detail.shipment',
    '<ProofGallery'
  ]) {
    assert.ok(order.includes(fragment), `OrderDetailView is missing ${fragment}`)
  }
  for (const fragment of [
    '/after-sales/${afterSaleId.value}',
    '/after-sales/${afterSaleId.value}/proofs',
    'returnAddress',
    'timeline',
    '<ProofGallery'
  ]) {
    assert.ok(afterSale.includes(fragment), `AfterSaleDetailView is missing ${fragment}`)
  }
  assert.match(proofGallery, /\/order-proofs\/\$\{id\}\/download/)
  assert.match(proofGallery, /\/after-sale-proofs\/\$\{id\}\/download/)
  assert.match(proofGallery, /previewUrl\.value = ''/)
})

test('rules use backend active versions and product HTML is sanitized before rendering', async () => {
  const [rules, product, sanitizer] = await Promise.all([
    source('views/RulesView.vue'),
    source('views/ProductView.vue'),
    source('utils/sanitize.ts')
  ])

  assert.match(rules, /api<RuleView\[\]>\('\/rules\/active'\)/)
  assert.doesNotMatch(rules, /¥298|¥1,998/)
  assert.match(product, /v-html="safeDescription"/)
  assert.doesNotMatch(product, /v-html="detail\.descriptionHtml"/)
  assert.match(sanitizer, /DOMPurify\.sanitize/)
  assert.match(sanitizer, /FORBID_TAGS/)
})

test('network lists keep loading, filters, pagination and duplicate-submit guards', async () => {
  const paths = [
    'views/OrdersView.vue',
    'views/AfterSalesView.vue',
    'views/NotificationsView.vue',
    'views/MembershipView.vue'
  ]
  const contents = await Promise.all(paths.map(source))
  for (const [index, content] of contents.entries()) {
    assert.match(content, /loading/, `${paths[index]} needs a loading state`)
    assert.match(content, /<PaginationBar/, `${paths[index]} needs pagination`)
  }
  assert.match(contents[0], /busyOrderId/)
  assert.match(contents[1], /query/)
  assert.match(contents[2], /readingId/)
  assert.match(contents[3], /directQuery/)

  const allViews = await Promise.all([
    source('views/OrdersView.vue'),
    source('views/AfterSalesView.vue'),
    source('views/MembershipView.vue'),
    source('views/AddressesView.vue')
  ])
  for (const content of allViews) {
    assert.doesNotMatch(content, /\b(prompt|confirm|alert)\s*\(/)
  }
})

test('storefront uses real product media with premium responsive and accessible fallbacks', async () => {
  const [home, product, cart, checkout, media, styles] = await Promise.all([
    source('views/HomeView.vue'),
    source('views/ProductView.vue'),
    source('views/CartView.vue'),
    source('views/CheckoutView.vue'),
    source('components/ProductMedia.vue'),
    source('styles.css')
  ])

  assert.match(home, /storefront-hero\.webp/)
  assert.match(home, /ProductMedia/)
  assert.match(home, /:src="product\.coverUrl"/)
  assert.match(product, /:src="detail\.product\.coverUrl"/)
  assert.match(cart, /:src="item\.coverUrl"/)
  assert.match(checkout, /:src="item\.coverUrl"/)
  assert.match(media, /:alt="alt"/)
  assert.match(media, /@error="failed = true"/)
  assert.match(styles, /@media \(max-width: 720px\)/)
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/)
})
