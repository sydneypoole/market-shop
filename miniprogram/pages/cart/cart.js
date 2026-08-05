const cartApi = require('../../api/cart')
const { fenToYuan, resolveMediaUrl } = require('../../utils/format')

function buildView(items) {
  const list = (items || []).map(function (item) {
    const qty = Number(item.quantity) || 0
    const inv = Number(item.inventory)
    const maxQty = Math.min(99, Number.isFinite(inv) && inv >= 0 ? inv : 99)
    return {
      id: item.id,
      skuId: item.skuId,
      productName: item.productName || '',
      skuName: item.skuName || '',
      coverUrl: resolveMediaUrl(item.coverUrl || ''),
      priceFen: item.priceFen || 0,
      priceText: fenToYuan(item.priceFen || 0),
      quantity: qty,
      selected: !!item.selected,
      inventory: inv,
      maxQuantity: maxQty > 0 ? maxQty : 1
    }
  })

  let totalFen = 0
  let selectedCount = 0
  let allSelected = list.length > 0
  list.forEach(function (row) {
    if (row.selected) {
      selectedCount += 1
      totalFen += Number(row.priceFen) * Number(row.quantity)
    } else {
      allSelected = false
    }
  })

  return {
    items: list,
    empty: list.length === 0,
    allSelected: allSelected,
    selectedCount: selectedCount,
    totalText: fenToYuan(totalFen)
  }
}

Page({
  data: {
    loading: true,
    items: [],
    empty: true,
    allSelected: false,
    selectedCount: 0,
    totalText: '0.00',
    editing: false,
    busy: false
  },

  onShow() {
    this.loadCart()
  },

  loadCart() {
    this.setData({ loading: true })
    cartApi
      .list()
      .then((data) => {
        const view = buildView(Array.isArray(data) ? data : [])
        this.setData({
          loading: false,
          items: view.items,
          empty: view.empty,
          allSelected: view.allSelected,
          selectedCount: view.selectedCount,
          totalText: view.totalText,
          editing: view.empty ? false : this.data.editing
        })
      })
      .catch((err) => {
        this.setData({ loading: false })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({
          title: (err && err.message) || '加载购物车失败',
          icon: 'none'
        })
      })
  },

  applyView(items) {
    const view = buildView(items)
    this.setData({
      items: view.items,
      empty: view.empty,
      allSelected: view.allSelected,
      selectedCount: view.selectedCount,
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
    if (!item) {
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
      if (!!item.selected === next) {
        return Promise.resolve()
      }
      return cartApi.setItem(item.skuId, item.quantity, next)
    })
    Promise.all(tasks)
      .then(() => {
        this.setData({ busy: false })
        this.loadCart()
      })
      .catch((err) => {
        this.setData({ busy: false })
        wx.showToast({
          title: (err && err.message) || '更新失败',
          icon: 'none'
        })
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
    if (!item) {
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
        wx.showToast({
          title: (err && err.message) || '更新失败',
          icon: 'none'
        })
        this.loadCart()
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
            wx.showToast({
              title: (err && err.message) || '删除失败',
              icon: 'none'
            })
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
    const n = this.data.selectedCount
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
