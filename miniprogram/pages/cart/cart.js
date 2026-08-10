const cartApi = require('../../api/cart')
const { fenToYuan, resolveMediaUrl } = require('../../utils/format')
const { isConflict } = require('../../utils/request')

function buildView(items) {
  const list = (items || []).map(function (item) {
    const qty = Number(item.quantity) || 0
    const inv = Number(item.inventory)
    const inventoryKnown = Number.isFinite(inv) && inv >= 0
    const maxQty = inventoryKnown ? Math.min(99, inv) : 0
    const available = maxQty > 0 && qty > 0 && qty <= maxQty
    let inventoryHint = ''
    if (!inventoryKnown) {
      inventoryHint = '库存信息暂不可用'
    } else if (inv <= 0) {
      inventoryHint = '暂时无库存'
    } else if (qty <= 0) {
      inventoryHint = '商品数量无效'
    } else if (qty > maxQty) {
      inventoryHint = '库存不足，当前仅剩 ' + maxQty + ' 件'
    }
    return {
      id: item.id,
      skuId: item.skuId,
      productName: item.productName || '',
      skuName: item.skuName || '',
      coverUrl: resolveMediaUrl(item.coverUrl || ''),
      priceFen: item.priceFen || 0,
      priceText: fenToYuan(item.priceFen || 0),
      quantity: qty,
      selected: item.selected === true,
      available: available,
      inventoryHint: inventoryHint,
      inventory: inv,
      maxQuantity: maxQty
    }
  })

  let totalFen = 0
  let selectedCount = 0
  let checkoutCount = 0
  let hasUnavailableSelected = false
  const availableRows = list.filter(function (row) { return row.available })
  let allSelected = availableRows.length > 0
  list.forEach(function (row) {
    if (row.selected) {
      selectedCount += 1
      if (row.available) {
        checkoutCount += 1
        totalFen += Number(row.priceFen) * Number(row.quantity)
      } else {
        hasUnavailableSelected = true
      }
    }
    if (row.available && !row.selected) {
      allSelected = false
    }
  })

  return {
    items: list,
    empty: list.length === 0,
    allSelected: allSelected,
    selectedCount: selectedCount,
    checkoutCount: checkoutCount,
    hasUnavailableSelected: hasUnavailableSelected,
    totalText: fenToYuan(totalFen)
  }
}

Page({
  data: {
    loading: true,
    error: '',
    items: [],
    empty: true,
    allSelected: false,
    selectedCount: 0,
    checkoutCount: 0,
    hasUnavailableSelected: false,
    totalText: '0.00',
    editing: false,
    busy: false
  },

  onShow() {
    this.loadCart()
  },

  loadCart() {
    this.setData({ loading: true, error: '' })
    return cartApi
      .list()
      .then((data) => {
        const view = buildView(Array.isArray(data) ? data : [])
        this.setData({
          loading: false,
          items: view.items,
          empty: view.empty,
          allSelected: view.allSelected,
          selectedCount: view.selectedCount,
          checkoutCount: view.checkoutCount,
          hasUnavailableSelected: view.hasUnavailableSelected,
          totalText: view.totalText,
          editing: view.empty ? false : this.data.editing
        })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '加载购物车失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  applyView(items) {
    const view = buildView(items)
    this.setData({
      items: view.items,
      empty: view.empty,
      allSelected: view.allSelected,
      selectedCount: view.selectedCount,
      checkoutCount: view.checkoutCount,
      hasUnavailableSelected: view.hasUnavailableSelected,
      totalText: view.totalText,
      editing: view.empty ? false : this.data.editing
    })
  },

  onToggleEdit() {
    if (this.data.empty) {
      return
    }
    this.setData({ editing: !this.data.editing })
  },

  onToggleItem(e) {
    if (this.data.busy) {
      return
    }
    const skuId = e.currentTarget.dataset.skuid
    const item = (this.data.items || []).find(function (row) {
      return row.skuId === skuId
    })
    if (!item || (!item.available && !item.selected)) {
      return
    }
    this.updateItem(skuId, item.quantity, !item.selected)
  },

  onToggleAll() {
    if (this.data.busy || this.data.empty) {
      return
    }
    const next = !this.data.allSelected
    const items = this.data.items || []
    this.setData({ busy: true })
    const tasks = items.map(function (item) {
      const desired = item.available ? next : false
      if (item.selected === desired) {
        return Promise.resolve()
      }
      return cartApi.setItem(item.skuId, item.quantity, desired)
    })
    Promise.all(tasks)
      .then(() => {
        this.setData({ busy: false })
        this.loadCart()
      })
      .catch((err) => {
        this.setData({ busy: false })
        wx.showToast({ title: isConflict(err) ? '购物车已变化，正在刷新' : ((err && err.message) || '更新失败'), icon: 'none' })
        this.loadCart()
      })
  },

  onQtyChange(e) {
    if (this.data.busy) {
      return
    }
    const skuId = e.currentTarget.dataset.skuid
    const nextQty = Number(e.detail)
    const item = (this.data.items || []).find(function (row) {
      return row.skuId === skuId
    })
    if (!item || !item.available || nextQty > item.maxQuantity) {
      return
    }

    if (nextQty <= 0) {
      wx.showModal({
        title: '删除商品',
        content: '确定从购物车移除该商品？',
        success: (res) => {
          if (res.confirm) {
            this.updateItem(skuId, 0, item.selected)
          } else {
            // force re-render stepper value
            this.setData({ items: this.data.items.slice() })
          }
        }
      })
      return
    }

    this.updateItem(skuId, nextQty, item.selected)
  },

  updateItem(skuId, quantity, selected) {
    this.setData({ busy: true })
    cartApi
      .setItem(skuId, quantity, selected)
      .then(() => {
        this.setData({ busy: false })
        this.loadCart()
      })
      .catch((err) => {
        this.setData({ busy: false })
        wx.showToast({ title: isConflict(err) ? '购物车已变化，正在刷新' : ((err && err.message) || '更新失败'), icon: 'none' })
        this.loadCart()
      })
  },

  onRemoveUnavailable(e) {
    if (this.data.busy) {
      return
    }
    const skuId = e.currentTarget.dataset.skuid
    const item = (this.data.items || []).find(function (row) {
      return row.skuId === skuId
    })
    if (!item || item.available) {
      return
    }
    wx.showModal({
      title: '移除商品',
      content: '该商品当前库存不足，确定从购物车移除？',
      success: (res) => {
        if (res.confirm && !this.data.busy) {
          this.updateItem(skuId, 0, false)
        }
      }
    })
  },

  onDeleteSelected() {
    if (this.data.busy) {
      return
    }
    const selected = (this.data.items || []).filter(function (item) {
      return item.selected
    })
    if (!selected.length) {
      wx.showToast({ title: '请先选择商品', icon: 'none' })
      return
    }
    wx.showModal({
      title: '批量删除',
      content: '确定删除已选中的 ' + selected.length + ' 件商品？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.setData({ busy: true })
        const tasks = selected.map(function (item) {
          return cartApi.setItem(item.skuId, 0, item.selected)
        })
        Promise.all(tasks)
          .then(() => {
            this.setData({ busy: false, editing: false })
            this.loadCart()
          })
          .catch((err) => {
            this.setData({ busy: false })
            wx.showToast({ title: isConflict(err) ? '购物车已变化，正在刷新' : ((err && err.message) || '删除失败'), icon: 'none' })
            this.loadCart()
          })
      }
    })
  },

  onCheckout() {
    if (this.data.editing) {
      this.onDeleteSelected()
      return
    }
    if (this.data.hasUnavailableSelected) {
      wx.showToast({ title: '已选商品库存不足，请取消选择或移除', icon: 'none' })
      return
    }
    const n = this.data.checkoutCount
    if (!n) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/order/confirm?from=cart' })
  },

  onEmptyAction() {
    wx.switchTab({ url: '/pages/index/index' })
  }
})
