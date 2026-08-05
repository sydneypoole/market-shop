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
    marketPriceFen: Number(p.marketPriceFen) || 0
  }
}

Page({
  data: {
    categories: [],
    activeId: null,
    activeName: '',
    allProducts: [],
    products: [],
    loading: true
  },

  onShow() {
    this.loadData()
  },

  loadData() {
    this.setData({ loading: true })
    Promise.all([
      catalogApi.categories().catch(function () { return [] }),
      catalogApi.products().catch(function () { return [] })
    ])
      .then(function (results) {
        const categories = results[0]
        const products = results[1]
        const cats = Array.isArray(categories) ? categories.slice() : []
        cats.sort(function (a, b) {
          return (a.sortOrder || 0) - (b.sortOrder || 0)
        })

        const all = (Array.isArray(products) ? products : []).map(mapProductCard)
        let activeId = this.data.activeId
        if (activeId == null && cats.length) {
          activeId = cats[0].id
        } else if (activeId != null) {
          activeId = Number(activeId)
        }

        const active = cats.find(function (c) {
          return Number(c.id) === Number(activeId)
        })
        const activeName = active ? active.name : ''
        const filtered = this.filterByCategory(all, activeId)

        this.setData({
          categories: cats,
          activeId: activeId,
          activeName: activeName,
          allProducts: all,
          products: filtered,
          loading: false
        })
      }.bind(this))
      .catch(function () {
        this.setData({ loading: false })
      }.bind(this))
  },

  filterByCategory(all, categoryId) {
    if (categoryId == null || categoryId === '' || categoryId === 'all') {
      return all.slice()
    }
    const id = Number(categoryId)
    return all.filter(function (p) {
      return Number(p.categoryId) === id
    })
  },

  onTapSearch() {
    wx.navigateTo({ url: '/pages/search/search' })
  },

  onSelectCategory(e) {
    const id = Number(e.currentTarget.dataset.id)
    const name = e.currentTarget.dataset.name || ''
    const products = this.filterByCategory(this.data.allProducts, id)
    this.setData({
      activeId: id,
      activeName: name,
      products: products
    })
  },

  onTapProduct(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    wx.navigateTo({ url: '/pages/goods/detail?id=' + id })
  }
})
