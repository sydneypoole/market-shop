const catalogApi = require('../../api/catalog')
const { resolveMediaUrl } = require('../../utils/format')

const FALLBACK_HERO_TAG = '内容导览'
const FALLBACK_HERO_TITLE = '认识宏杉生物'
const FALLBACK_HERO_META = '品牌内容'
const FALLBACK_STORY = {
  id: null,
  title: '认识宏杉生物',
  summary: '了解品牌理念与平台服务。',
  coverUrl: '',
  tag: '品牌内容',
  meta: '内容导览'
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
    announcement: '',
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
    this.loadHome({ background: this._loaded === true })
  },

  loadHome(options) {
    const background = !!(options && options.background && this._loaded)
    if (!background) {
      this.setData({ loading: true, error: '' })
    }
    Promise.all([
      catalogApi.categories(),
      catalogApi.products(),
      catalogApi.contents()
    ])
      .then((results) => {
        const categories = results[0]
        const products = results[1]
        const contents = results[2]
        const cats = Array.isArray(categories) ? categories.slice() : []
        cats.sort(function (a, b) {
          return (a.sortOrder || 0) - (b.sortOrder || 0)
        })

        const list = Array.isArray(products) ? products : []
        const cards = list.slice(0, 6).map(mapProductCard)

        const items = Array.isArray(contents) ? contents : []
        let announcement = ''
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
            heroTag = '内容导览'
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
            tag: '品牌内容',
            meta: '内容导览'
          }
        }

        this._loaded = true
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
        if (background) {
          wx.showToast({ title: (err && err.message) || '首页刷新失败', icon: 'none' })
          return
        }
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
