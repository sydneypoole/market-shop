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
    categoryId: p.categoryId,
    cover: resolveMediaUrl(p.coverUrl || ''),
    name: p.name || '',
    priceFen: pickPriceFen(p),
    marketPriceFen: Number(p.marketPriceFen) || 0,
    sortPrice: pickPriceFen(p)
  }
}

Page({
  data: {
    categoryId: null,
    categoryName: '',
    sortKey: 'default',
    priceDir: 'asc',
    source: [],
    products: [],
    total: 0,
    loading: true
  },

  onLoad(query) {
    const categoryId = query.categoryId != null && query.categoryId !== '' ? query.categoryId : null
    let name = ''
    try {
      name = query.name ? decodeURIComponent(query.name) : ''
    } catch (e) {
      name = query.name || ''
    }
    if (name) {
      wx.setNavigationBarTitle({ title: name })
    }
    this.setData({
      categoryId: categoryId,
      categoryName: name
    })
    this.loadProducts()
  },

  loadProducts() {
    this.setData({ loading: true })
    catalogApi
      .products()
      .then(function (list) {
        const all = (Array.isArray(list) ? list : []).map(mapProductCard)
        const categoryId = this.data.categoryId
        let source = all
        if (categoryId != null && categoryId !== '') {
          const id = Number(categoryId)
          source = all.filter(function (p) {
            return Number(p.categoryId) === id
          })
        }
        this.setData({ source: source, loading: false })
        this.applySort()
      }.bind(this))
      .catch(function () {
        this.setData({ source: [], products: [], total: 0, loading: false })
      }.bind(this))
  },

  applySort() {
    const sortKey = this.data.sortKey
    const priceDir = this.data.priceDir
    const list = this.data.source.slice()

    if (sortKey === 'price') {
      list.sort(function (a, b) {
        const diff = a.sortPrice - b.sortPrice
        return priceDir === 'desc' ? -diff : diff
      })
    } else if (sortKey === 'new') {
      list.sort(function (a, b) {
        return Number(b.productId) - Number(a.productId)
      })
    }

    this.setData({
      products: list,
      total: list.length
    })
  },

  onSortDefault() {
    if (this.data.sortKey === 'default') return
    this.setData({ sortKey: 'default' })
    this.applySort()
  },

  onSortPrice() {
    if (this.data.sortKey === 'price') {
      const next = this.data.priceDir === 'asc' ? 'desc' : 'asc'
      this.setData({ priceDir: next })
    } else {
      this.setData({ sortKey: 'price', priceDir: 'asc' })
    }
    this.applySort()
  },

  onSortNew() {
    if (this.data.sortKey === 'new') return
    this.setData({ sortKey: 'new' })
    this.applySort()
  },

  onTapProduct(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    wx.navigateTo({ url: '/pages/goods/detail?id=' + id })
  }
})
