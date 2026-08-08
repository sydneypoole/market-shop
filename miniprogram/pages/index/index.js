const catalogApi = require('../../api/catalog')
const { resolveMediaUrl } = require('../../utils/format')

const FALLBACK_ANNOUNCEMENT = '邀请制会员商城 · 本周甄选已更新'
const FALLBACK_HERO_TAG = '本周甄选'
const FALLBACK_HERO_TITLE = '把日常，过成值得收藏的片段'
const FALLBACK_HERO_META = 'EDITORIAL · 2026 第 32 周'
const FALLBACK_STORY = {
  id: null,
  title: '一只杯子的烧成记',
  summary: '从揉泥、拉坯到 1280°C 的窑火，记录匠人手中的三十六道工序。',
  coverUrl: '',
  tag: '品牌故事',
  meta: '5 分钟阅读'
}

function isAnnouncement(type) {
  const t = String(type || '').toUpperCase()
  return t === 'ANNOUNCEMENT' || t.indexOf('ANNOUNCE') >= 0
}

function isBanner(type) {
  const t = String(type || '').toUpperCase()
  return t === 'BANNER' || t === 'HERO' || t.indexOf('BANNER') >= 0 || t.indexOf('EDITORIAL') >= 0
}

function pickPriceFen(p) {
  if (p == null) return 0
  const min = Number(p.minPriceFen)
  const max = Number(p.maxPriceFen)
  if (Number.isFinite(min) && min > 0) return min
  const price = Number(p.priceFen)
  return Number.isFinite(price) ? price : 0
}

function mapProductCard(p) {
  return {
    productId: p.productId,
    cover: resolveMediaUrl(p.coverUrl || ''),
    name: p.name || '',
    priceFen: pickPriceFen(p),
    marketPriceFen: Number(p.marketPriceFen) || 0
  }
}

Page({
  data: {
    announcement: FALLBACK_ANNOUNCEMENT,
    categories: [],
    heroTag: FALLBACK_HERO_TAG,
    heroTitle: FALLBACK_HERO_TITLE,
    heroMeta: FALLBACK_HERO_META,
    heroCover: '',
    heroId: null,
    products: [],
    story: FALLBACK_STORY,
    loading: true,
    error: ''
  },

  onShow() {
    this.loadHome()
  },

  loadHome() {
    this.setData({ loading: true, error: '' })
    Promise.all([
      catalogApi.categories(),
      catalogApi.products(),
      catalogApi.contents()
    ])
      .then(([categories, products, contents]) => {
        const cats = Array.isArray(categories) ? categories.slice() : []
        cats.sort(function (a, b) {
          return (a.sortOrder || 0) - (b.sortOrder || 0)
        })

        const list = Array.isArray(products) ? products : []
        const cards = list.slice(0, 6).map(mapProductCard)

        const items = Array.isArray(contents) ? contents : []
        let announcement = FALLBACK_ANNOUNCEMENT
        const ann = items.find(function (c) {
          return isAnnouncement(c.type)
        })
        if (ann && (ann.title || ann.summary)) {
          announcement = ann.title || ann.summary
        }

        let heroTag = FALLBACK_HERO_TAG
        let heroTitle = FALLBACK_HERO_TITLE
        let heroMeta = FALLBACK_HERO_META
        let heroCover = ''
        let heroId = null
        const banner = items.find(function (c) {
          return isBanner(c.type)
        })
        if (banner) {
          heroId = banner.id
          heroTitle = banner.title || heroTitle
          heroMeta = banner.summary || heroMeta
          heroCover = resolveMediaUrl(banner.coverUrl || '')
          if (banner.summary) {
            heroTag = '本周甄选'
          }
        }

        let story = FALLBACK_STORY
        const storyItem = items.find(function (c) {
          return !isAnnouncement(c.type) && !isBanner(c.type)
        })
        if (storyItem) {
          story = {
            id: storyItem.id,
            title: storyItem.title || FALLBACK_STORY.title,
            summary: storyItem.summary || FALLBACK_STORY.summary,
            coverUrl: resolveMediaUrl(storyItem.coverUrl || ''),
            tag: '品牌故事',
            meta: '5 分钟阅读'
          }
        }

        this.setData({
          announcement: announcement,
          categories: cats,
          heroTag: heroTag,
          heroTitle: heroTitle,
          heroMeta: heroMeta,
          heroCover: heroCover,
          heroId: heroId,
          products: cards,
          story: story,
          loading: false
        })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '首页加载失败，请稍后重试'
        })
      })
  },

  onTapSearch() {
    wx.navigateTo({ url: '/pages/search/search' })
  },

  onTapCategory(e) {
    const id = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name || ''
    if (id == null || id === '') {
      wx.switchTab({ url: '/pages/category/category' })
      return
    }
    wx.navigateTo({
      url: '/pages/goods/list?categoryId=' + id + '&name=' + encodeURIComponent(name)
    })
  },

  onTapAllProducts() {
    wx.switchTab({ url: '/pages/category/category' })
  },

  onTapProduct(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    wx.navigateTo({ url: '/pages/goods/detail?id=' + id })
  },

  onTapContent(e) {
    const id = e.currentTarget.dataset.id
    if (id == null || id === '') {
      return
    }
    wx.navigateTo({ url: '/pages/content/detail?id=' + id })
  }
})
