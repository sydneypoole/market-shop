const { fenToYuan, resolveMediaUrl } = require('../../utils/format')

function parseAttrs(attributesJson) {
  if (!attributesJson) {
    return ''
  }
  try {
    const obj = typeof attributesJson === 'string' ? JSON.parse(attributesJson) : attributesJson
    if (!obj || typeof obj !== 'object') {
      return ''
    }
    return Object.keys(obj)
      .map(function (k) {
        return obj[k]
      })
      .filter(Boolean)
      .join(' · ')
  } catch (e) {
    return ''
  }
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

Component({
  properties: {
    visible: { type: Boolean, value: false },
    product: { type: Object, value: null },
    mode: { type: String, value: 'cart' }
  },

  data: {
    skuOptions: [],
    selectedSkuId: 0,
    quantity: 1,
    maxQuantity: 1,
    inventory: 0,
    priceText: '0.00',
    coverUrl: '',
    selectedLabel: ''
  },

  observers: {
    'visible, product': function (visible, product) {
      if (!visible || !product) {
        return
      }
      this.resetFromProduct(product)
    }
  },

  methods: {
    noop() {},

    resetFromProduct(product) {
      const detail = product || {}
      const base = detail.product || detail
      const skus = detail.skus || []
      const options = skus.map(function (sku) {
        const attr = parseAttrs(sku.attributesJson)
        return {
          skuId: sku.skuId,
          skuName: sku.skuName,
          priceFen: sku.priceFen,
          inventory: sku.inventory,
          label: attr || sku.skuName || '默认规格'
        }
      })
      const selected = pickDefaultSku(skus)
      const selectedSkuId = selected ? selected.skuId : 0
      const inventory = selected ? Number(selected.inventory) || 0 : 0
      const maxQuantity = Math.min(99, Math.max(0, inventory) || 0)
      this.setData({
        skuOptions: options,
        selectedSkuId: selectedSkuId,
        quantity: 1,
        maxQuantity: maxQuantity > 0 ? maxQuantity : 1,
        inventory: inventory,
        priceText: fenToYuan(selected ? selected.priceFen : base.priceFen || 0),
        coverUrl: resolveMediaUrl(base.coverUrl || ''),
        selectedLabel: selected
          ? parseAttrs(selected.attributesJson) || selected.skuName || '默认规格'
          : ''
      })
    },

    onSelectSku(e) {
      const id = Number(e.currentTarget.dataset.id)
      const option = (this.data.skuOptions || []).find(function (item) {
        return item.skuId === id
      })
      if (!option || option.inventory <= 0) {
        return
      }
      const inventory = Number(option.inventory) || 0
      const maxQuantity = Math.min(99, inventory)
      this.setData({
        selectedSkuId: id,
        quantity: 1,
        inventory: inventory,
        maxQuantity: maxQuantity > 0 ? maxQuantity : 1,
        priceText: fenToYuan(option.priceFen),
        selectedLabel: option.label
      })
    },

    onQtyChange(e) {
      this.setData({ quantity: e.detail })
    },

    onClose() {
      this.triggerEvent('close')
    },

    onConfirm() {
      if (!this.data.selectedSkuId || this.data.inventory <= 0) {
        return
      }
      this.triggerEvent('confirm', {
        skuId: this.data.selectedSkuId,
        quantity: this.data.quantity
      })
    }
  }
})
