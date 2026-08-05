const cartApi = require('../../api/cart')
const addressApi = require('../../api/address')
const catalogApi = require('../../api/catalog')
const orderApi = require('../../api/order')
const { fenToYuan, resolveMediaUrl } = require('../../utils/format')
const { getToken } = require('../../utils/request')

function maskPhone(phone) {
  const s = String(phone || '')
  if (s.length < 7) {
    return s
  }
  return s.slice(0, 3) + '****' + s.slice(-4)
}

function buildAddressFull(addr) {
  if (!addr) {
    return ''
  }
  return [addr.province, addr.city, addr.district, addr.detailAddress].filter(Boolean).join(' ')
}

function pickDefaultAddress(list) {
  if (!list || !list.length) {
    return null
  }
  for (let i = 0; i < list.length; i++) {
    if (list[i].defaultAddress) {
      return list[i]
    }
  }
  return list[0]
}

function mapGoodsLine(item) {
  const productName = item.productName || ''
  const skuName = item.skuName || ''
  let displayName = productName
  if (skuName && skuName !== productName) {
    displayName = productName ? productName + ' · ' + skuName : skuName
  }
  const priceFen = Number(item.priceFen) || 0
  const quantity = Math.max(1, Number(item.quantity) || 1)
  return {
    skuId: item.skuId,
    productName: productName,
    skuName: skuName,
    displayName: displayName,
    coverUrl: resolveMediaUrl(item.coverUrl),
    priceFen: priceFen,
    quantity: quantity,
    priceText: fenToYuan(priceFen)
  }
}

function sumGoodsFen(goods) {
  let total = 0
  for (let i = 0; i < goods.length; i++) {
    total += (Number(goods[i].priceFen) || 0) * (Number(goods[i].quantity) || 0)
  }
  return total
}

function makeClientRequestId() {
  return String(Date.now()) + '-' + Math.random().toString(36).slice(2, 10)
}

Page({
  data: {
    fromCart: false,
    loading: true,
    submitting: false,
    address: null,
    addressPhoneMasked: '',
    addressFull: '',
    goods: [],
    goodsAmountFen: 0,
    goodsAmountText: '0.00',
    totalText: '0.00',
    remark: ''
  },

  _entry: null,
  _loaded: false,

  onLoad(query) {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    const q = query || {}
    const fromCart = q.from === 'cart'
    const skuId = q.skuId != null && q.skuId !== '' ? Number(q.skuId) : 0
    const quantity = q.quantity != null && q.quantity !== '' ? Number(q.quantity) : 0
    this._entry = {
      fromCart: fromCart,
      skuId: skuId,
      quantity: quantity > 0 ? quantity : 1
    }
    this.setData({ fromCart: fromCart })
    this.bootstrap()
  },

  onShow() {
    if (!getToken()) {
      return
    }
    this.consumeSelectedAddress()
  },

  consumeSelectedAddress() {
    try {
      const app = getApp()
      const selected = app && app.globalData && app.globalData.selectedAddress
      if (!selected) {
        return
      }
      app.globalData.selectedAddress = null
      this.applyAddress(selected)
    } catch (e) {
      // ignore
    }
  },

  applyAddress(addr) {
    if (!addr) {
      this.setData({
        address: null,
        addressPhoneMasked: '',
        addressFull: ''
      })
      return
    }
    this.setData({
      address: addr,
      addressPhoneMasked: maskPhone(addr.phone),
      addressFull: buildAddressFull(addr)
    })
  },

  applyGoods(goods) {
    const list = goods || []
    const goodsAmountFen = sumGoodsFen(list)
    this.setData({
      goods: list,
      goodsAmountFen: goodsAmountFen,
      goodsAmountText: fenToYuan(goodsAmountFen),
      totalText: fenToYuan(goodsAmountFen)
    })
  },

  bootstrap() {
    this.setData({ loading: true })
    const entry = this._entry || {}
    const goodsPromise = entry.fromCart
      ? this.loadCartGoods()
      : this.loadDirectGoods(entry.skuId, entry.quantity)
    const addressPromise = addressApi.list().catch(function () {
      return []
    })

    Promise.all([goodsPromise, addressPromise])
      .then((results) => {
        const goods = results[0] || []
        const addresses = results[1] || []
        if (!goods.length) {
          this.setData({ loading: false })
          this.applyGoods([])
          wx.showToast({ title: '没有可结算的商品', icon: 'none' })
          return
        }
        this.applyGoods(goods)
        if (!this.data.address) {
          this.applyAddress(pickDefaultAddress(addresses))
        }
        this._loaded = true
        this.setData({ loading: false })
        this.consumeSelectedAddress()
      })
      .catch((err) => {
        this.setData({ loading: false })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({
          title: (err && err.message) || '加载结算信息失败',
          icon: 'none'
        })
      })
  },

  loadCartGoods() {
    return cartApi.list().then(function (rows) {
      const list = Array.isArray(rows) ? rows : []
      return list
        .filter(function (row) {
          return row && row.selected === true && Number(row.quantity) > 0
        })
        .map(mapGoodsLine)
    })
  },

  findSkuInDetail(detail, skuId) {
    const base = (detail && detail.product) || detail || {}
    const skus = (detail && detail.skus) || []
    for (let i = 0; i < skus.length; i++) {
      if (Number(skus[i].skuId) === Number(skuId)) {
        return { base: base, sku: skus[i] }
      }
    }
    if (Number(base.skuId) === Number(skuId)) {
      return {
        base: base,
        sku: {
          skuId: base.skuId,
          skuName: base.skuName,
          priceFen: base.priceFen,
          coverUrl: base.coverUrl
        }
      }
    }
    return null
  },

  snapshotFromHit(hit, quantity) {
    const base = hit.base
    const sku = hit.sku
    return [
      mapGoodsLine({
        skuId: sku.skuId,
        productName: base.name || base.productName || '',
        skuName: sku.skuName || base.skuName || '',
        coverUrl: base.coverUrl || sku.coverUrl || '',
        priceFen: sku.priceFen != null ? sku.priceFen : base.priceFen,
        quantity: quantity
      })
    ]
  },

  loadDirectGoods(skuId, quantity) {
    if (!skuId) {
      return Promise.reject({ code: 'INVALID_SKU', message: '缺少商品规格' })
    }
    const self = this
    return catalogApi.products().then(function (products) {
      const list = Array.isArray(products) ? products : []
      let preferredId = 0
      for (let i = 0; i < list.length; i++) {
        if (Number(list[i].skuId) === Number(skuId)) {
          preferredId = list[i].productId
          break
        }
      }

      const orderedIds = []
      const seen = {}
      if (preferredId) {
        orderedIds.push(preferredId)
        seen[preferredId] = true
      }
      for (let i = 0; i < list.length; i++) {
        const pid = list[i].productId
        if (pid && !seen[pid]) {
          seen[pid] = true
          orderedIds.push(pid)
        }
      }

      function tryNext(index) {
        if (index >= orderedIds.length) {
          return Promise.reject({ code: 'SKU_NOT_FOUND', message: '未找到对应商品规格' })
        }
        return catalogApi.product(orderedIds[index]).then(function (detail) {
          const hit = self.findSkuInDetail(detail, skuId)
          if (hit) {
            return self.snapshotFromHit(hit, quantity)
          }
          return tryNext(index + 1)
        })
      }

      return tryNext(0)
    })
  },

  onSelectAddress() {
    wx.navigateTo({ url: '/pages/address/list?select=1' })
  },

  onRemarkInput(e) {
    this.setData({ remark: (e.detail && e.detail.value) || '' })
  },

  onSubmit() {
    if (this.data.submitting || this.data.loading) {
      return
    }
    const address = this.data.address
    if (!address) {
      wx.showToast({ title: '请选择收货地址', icon: 'none' })
      return
    }
    const goods = this.data.goods || []
    if (!goods.length) {
      wx.showToast({ title: '没有可结算的商品', icon: 'none' })
      return
    }

    const payload = {
      clientRequestId: makeClientRequestId(),
      source: 'MINIPROGRAM',
      address: {
        recipientName: address.recipientName,
        phone: address.phone,
        province: address.province,
        city: address.city,
        district: address.district,
        detailAddress: address.detailAddress,
        postalCode: address.postalCode || undefined
      },
      items: goods.map(function (g) {
        return {
          skuId: g.skuId,
          quantity: g.quantity
        }
      })
    }
    // SubmitOrderRequest 无 remark 字段：备注仅 UI 展示，不提交

    this.setData({ submitting: true })
    const fromCart = this.data.fromCart
    const totalFen = this.data.goodsAmountFen

    orderApi
      .submit(payload)
      .then((order) => {
        const clearPromise = fromCart
          ? this.clearPurchasedCart(goods)
          : Promise.resolve()
        return clearPromise.then(function () {
          return order
        })
      })
      .then((order) => {
        this.setData({ submitting: false })
        const id = order && order.id != null ? order.id : ''
        const orderNo = (order && order.orderNo) || ''
        const total = order && order.totalAmountFen != null ? order.totalAmountFen : totalFen
        wx.redirectTo({
          url:
            '/pages/order/success?orderNo=' +
            encodeURIComponent(orderNo) +
            '&total=' +
            encodeURIComponent(String(total)) +
            '&id=' +
            encodeURIComponent(String(id))
        })
      })
      .catch((err) => {
        this.setData({ submitting: false })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({
          title: (err && err.message) || '提交订单失败',
          icon: 'none',
          duration: 2500
        })
      })
  },

  clearPurchasedCart(goods) {
    const tasks = (goods || []).map(function (g) {
      return cartApi.setItem(g.skuId, 0, true).catch(function () {
        return null
      })
    })
    return Promise.all(tasks)
  }
})
