const cartApi = require('../../api/cart')
const addressApi = require('../../api/address')
const catalogApi = require('../../api/catalog')
const orderApi = require('../../api/order')
const { fenToYuan, resolveMediaUrl } = require('../../utils/format')
const { getToken, isConflict } = require('../../utils/request')
const { makeClientRequestId } = require('../../utils/client-request')

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
  const inventory = Number(item.inventory)
  const inventoryKnown = Number.isFinite(inventory) && inventory >= 0
  const available = inventoryKnown && inventory > 0 && quantity <= inventory
  return {
    skuId: item.skuId,
    productName: productName,
    skuName: skuName,
    displayName: displayName,
    coverUrl: resolveMediaUrl(item.coverUrl),
    priceFen: priceFen,
    quantity: quantity,
    inventory: inventory,
    available: available,
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

Page({
  data: {
    fromCart: false,
    loading: true,
    loadError: '',
    submitting: false,
    address: null,
    addressPhoneMasked: '',
    addressFull: '',
    goods: [],
    hasUnavailableGoods: false,
    goodsAmountFen: 0,
    goodsAmountText: '0.00',
    totalText: '0.00',
    remark: ''
  },

  _entry: null,
  _loaded: false,
  _clientRequestId: '',

  onLoad(query) {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    const q = query || {}
    const fromCart = q.from === 'cart'
    const productId = q.productId != null && q.productId !== '' ? Number(q.productId) : 0
    const skuId = q.skuId != null && q.skuId !== '' ? Number(q.skuId) : 0
    const quantity = q.quantity != null && q.quantity !== '' ? Number(q.quantity) : 0
    this._entry = {
      fromCart: fromCart,
      productId: productId,
      skuId: skuId,
      quantity: quantity > 0 ? quantity : 1
    }
    this._clientRequestId = makeClientRequestId('checkout')
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
      hasUnavailableGoods: list.some(function (item) { return item.available !== true }),
      goodsAmountFen: goodsAmountFen,
      goodsAmountText: fenToYuan(goodsAmountFen),
      totalText: fenToYuan(goodsAmountFen)
    })
  },

  bootstrap() {
    this.setData({ loading: true, loadError: '' })
    const entry = this._entry || {}
    const goodsPromise = entry.fromCart
      ? this.loadCartGoods()
      : this.loadDirectGoods(entry.productId, entry.skuId, entry.quantity)
    const addressPromise = addressApi.list()

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
        this.setData({
          loading: false,
          loadError: (err && err.message) || '加载结算信息失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  loadCartGoods() {
    return cartApi.list().then(function (rows) {
      const list = Array.isArray(rows) ? rows : []
      const goods = list
        .filter(function (row) {
          return row && row.selected === true && Number(row.quantity) > 0
        })
        .map(mapGoodsLine)
      if (goods.some(function (item) { return !item.available })) {
        return Promise.reject({
          code: 'INVENTORY_UNAVAILABLE',
          message: '已选商品库存不足，请返回购物车调整'
        })
      }
      return goods
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
          coverUrl: base.coverUrl,
          inventory: base.inventory
        }
      }
    }
    return null
  },

  snapshotFromHit(hit, quantity) {
    const base = hit.base
    const sku = hit.sku
    const line = mapGoodsLine({
      skuId: sku.skuId,
      productName: base.name || base.productName || '',
      skuName: sku.skuName || base.skuName || '',
      coverUrl: base.coverUrl || sku.coverUrl || '',
      priceFen: sku.priceFen != null ? sku.priceFen : base.priceFen,
      quantity: quantity,
      inventory: sku.inventory
    })
    if (!line.available) {
      throw {
        code: 'INVENTORY_UNAVAILABLE',
        message: '商品库存不足，请重新选择规格或数量'
      }
    }
    return [line]
  },

  loadDirectGoods(productId, skuId, quantity) {
    if (!skuId) {
      return Promise.reject({ code: 'INVALID_SKU', message: '缺少商品规格' })
    }
    const self = this
    if (productId) {
      return catalogApi.product(productId).then(function (detail) {
        const hit = self.findSkuInDetail(detail, skuId)
        if (!hit) {
          return Promise.reject({ code: 'SKU_NOT_FOUND', message: '未找到对应商品规格' })
        }
        return self.snapshotFromHit(hit, quantity)
      })
    }
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
    const self = this
    wx.navigateTo({
      url: '/pages/address/list?select=1',
      events: {
        // 辅通道：list 若 emit selectAddress 则即时应用
        selectAddress: function (addr) {
          if (addr) {
            self.applyAddress(addr)
          }
        }
      },
      success: function () {
        // 主通道仍由 list 写入 globalData.selectedAddress，返回时 onShow 消费
      }
    })
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
    if (this.data.hasUnavailableGoods || goods.some(function (item) { return item.available !== true })) {
      wx.showToast({ title: '商品库存不足，正在刷新', icon: 'none' })
      this.bootstrap()
      return
    }

    const payload = {
      clientRequestId: this._clientRequestId || makeClientRequestId('checkout'),
      source: 'MINIPROGRAM',
      buyerNote: (this.data.remark || '').trim() || undefined,
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
    this._clientRequestId = payload.clientRequestId

    this.setData({ submitting: true })
    const fromCart = this.data.fromCart
    const totalFen = this.data.goodsAmountFen

    orderApi
      .submit(payload)
      .then((order) => {
        this._clientRequestId = ''
        const clearPromise = fromCart
          ? this.clearPurchasedCart(goods)
          : Promise.resolve()
        return clearPromise
          .catch(function () {
            wx.showToast({ title: '订单已提交，购物车同步失败', icon: 'none' })
          })
          .then(function () {
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
        if (isConflict(err)) {
          wx.showToast({ title: '结算信息已变化，正在刷新', icon: 'none' })
          this.bootstrap()
          return
        }
        wx.showToast({ title: (err && err.message) || '提交订单失败', icon: 'none', duration: 2500 })
      })
  },

  clearPurchasedCart(goods) {
    const tasks = (goods || []).map(function (g) {
      return cartApi.setItem(g.skuId, 0, true)
    })
    return Promise.all(tasks)
  }
})
