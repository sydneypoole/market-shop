const catalogApi = require('../../api/catalog')
const cartApi = require('../../api/cart')
const { fenToYuan, resolveMediaUrl } = require('../../utils/format')

function parseAttrObject(attributesJson) {
  if (!attributesJson) {
    return null
  }
  try {
    const obj = typeof attributesJson === 'string' ? JSON.parse(attributesJson) : attributesJson
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
      return null
    }
    return obj
  } catch (e) {
    return null
  }
}

function attrValues(attributesJson) {
  const obj = parseAttrObject(attributesJson)
  if (!obj) {
    return ''
  }
  return Object.keys(obj)
    .map(function (k) {
      return obj[k]
    })
    .filter(Boolean)
    .join(' · ')
}

function attrRows(attributesJson) {
  const obj = parseAttrObject(attributesJson)
  if (!obj) {
    return []
  }
  return Object.keys(obj).map(function (k) {
    return { key: k, value: obj[k] == null ? '' : String(obj[k]) }
  })
}

function pickDefaultSku(skus) {
  if (!skus || !skus.length) {
    return null
  }
  for (let i = 0; i < skus.length; i++) {
    if (Number(skus[i].inventory) > 0) {
      return skus[i]
    }
  }
  return skus[0]
}

Page({
  data: {
    loading: true,
    error: '',
    productId: '',
    detail: null,
    coverUrl: '',
    name: '',
    priceText: '0.00',
    marketPriceText: '',
    showMemberTag: false,
    attrSummary: '',
    attrRows: [],
    descriptionHtml: '',
    selectedSkuId: 0,
    sheetVisible: false,
    sheetMode: 'cart'
  },

  onLoad(query) {
    const id = query && (query.id || query.productId)
    if (!id) {
      this.setData({ loading: false, error: '缺少商品 ID' })
      return
    }
    this.setData({ productId: String(id) })
    this.loadDetail(String(id))
  },

  loadDetail(id) {
    this.setData({ loading: true, error: '' })
    catalogApi
      .product(id)
      .then((data) => {
        const detail = data || {}
        const product = detail.product || {}
        const skus = detail.skus || []
        const selected = pickDefaultSku(skus)
        const attrsSource =
          (selected && selected.attributesJson) || product.attributesJson || null
        const marketFen = selected
          ? selected.marketPriceFen
          : product.marketPriceFen
        const priceFen = selected ? selected.priceFen : product.priceFen || 0
        const marketPriceText =
          marketFen != null && Number(marketFen) > Number(priceFen)
            ? fenToYuan(marketFen)
            : ''

        this.setData({
          loading: false,
          error: '',
          detail: detail,
          coverUrl: resolveMediaUrl(product.coverUrl || ''),
          name: product.name || '',
          priceText: fenToYuan(priceFen),
          marketPriceText: marketPriceText,
          showMemberTag: !!marketPriceText,
          attrSummary: attrValues(attrsSource),
          attrRows: attrRows(attrsSource),
          descriptionHtml: detail.descriptionHtml || '',
          selectedSkuId: selected ? selected.skuId : 0
        })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '加载商品失败'
        })
      })
  },

  onContact() {
    wx.showToast({ title: '暂未开放', icon: 'none' })
  },

  onGoCart() {
    wx.switchTab({ url: '/pages/cart/cart' })
  },

  openSheet(mode) {
    if (!this.data.detail) {
      return
    }
    this.setData({
      sheetVisible: true,
      sheetMode: mode === 'buy' ? 'buy' : 'cart'
    })
  },

  onAddCart() {
    this.openSheet('cart')
  },

  onBuyNow() {
    this.openSheet('buy')
  },

  onSheetClose() {
    this.setData({ sheetVisible: false })
  },

  onSheetConfirm(e) {
    const detail = e.detail || {}
    const skuId = detail.skuId
    const quantity = detail.quantity || 1
    if (!skuId) {
      return
    }

    if (this.data.sheetMode === 'buy') {
      this.setData({ sheetVisible: false })
      wx.navigateTo({
        url: '/pages/order/confirm?skuId=' + skuId + '&quantity=' + quantity
      })
      return
    }

    cartApi
      .setItem(skuId, quantity, true)
      .then(() => {
        this.setData({ sheetVisible: false, selectedSkuId: skuId })
        wx.showToast({ title: '已加入购物车', icon: 'none' })
      })
      .catch((err) => {
        wx.showToast({
          title: (err && err.message) || '加入购物车失败',
          icon: 'none'
        })
      })
  }
})
