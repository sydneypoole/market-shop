const catalogApi = require('../../api/catalog')
const { resolveMediaUrl } = require('../../utils/format')

function pickPriceFen(p) {
  if (p == null) return 0
  const min = Number(p.minPriceFen)
  if (Number.isFinite(min) && min > 0) return min
  const price = Number(p.priceFen)
  return Number.isFinite(price) ? price : 0
}

function mapProductCard(p) {
  return {
    productId: p.productId,
    cover: resolveMediaUrl(p.coverUrl || ''),
    name: p.name || '',
    subtitle: p.subtitle || '',
    priceFen: pickPriceFen(p),
    marketPriceFen: Number(p.marketPriceFen) || 0
  }
}

Page({
  data: {
    keyword: '',
    focused: true,
    allProducts: [],
    products: [],
    total: 0,
    searched: false,
    loading: true
  },

  onLoad() {
    this.loadProducts()
  },

  loadProducts() {
    this.setData({ loading: true })
    catalogApi
      .products()
      .then(function (list) {
        const all = (Array.isArray(list) ? list : []).map(mapProductCard)
        this.setData({ allProducts: all, loading: false })
        if (this.data.keyword) {
          this.runSearch(this.data.keyword)
        }
      }.bind(this))
      .catch(function () {
        this.setData({ allProducts: [], loading: false })
      }.bind(this))
  },

  onInput(e) {
    this.setData({ keyword: e.detail.value || '' })
  },

  onConfirm() {
    this.runSearch(this.data.keyword)
  },

  onClearKeyword() {
    this.setData({
      keyword: '',
      products: [],
      total: 0,
      searched: false,
      focused: true
    })
  },

  onClearSearch() {
    this.onClearKeyword()
  },

  runSearch(raw) {
    const keyword = String(raw || '').trim()
    if (!keyword) {
      this.setData({
        keyword: '',
        products: [],
        total: 0,
        searched: false
      })
      return
    }
    const lower = keyword.toLowerCase()
    const products = this.data.allProducts.filter(function (p) {
      const name = String(p.name || '').toLowerCase()
      const subtitle = String(p.subtitle || '').toLowerCase()
      return name.indexOf(lower) >= 0 || subtitle.indexOf(lower) >= 0
    })
    this.setData({
      keyword: keyword,
      products: products,
      total: products.length,
      searched: true
    })
  },

  onTapProduct(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    wx.navigateTo({ url: '/pages/goods/detail?id=' + id })
  }
})
