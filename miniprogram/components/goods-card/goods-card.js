const { fenToYuan, resolveMediaUrl } = require('../../utils/format')

Component({
  properties: {
    cover: { type: String, value: '' },
    name: { type: String, value: '' },
    priceFen: { type: Number, value: 0 },
    marketPriceFen: { type: Number, value: 0 }
  },

  data: {
    priceText: '0.00',
    marketPriceText: '',
    coverUrl: ''
  },

  observers: {
    cover: function (cover) {
      this.setData({ coverUrl: resolveMediaUrl(cover) })
    },
    'priceFen, marketPriceFen': function (priceFen, marketPriceFen) {
      const priceText = fenToYuan(priceFen)
      let marketPriceText = ''
      if (marketPriceFen && Number(marketPriceFen) > Number(priceFen)) {
        marketPriceText = fenToYuan(marketPriceFen)
      }
      this.setData({ priceText: priceText, marketPriceText: marketPriceText })
    }
  },

  methods: {
    onTap() {
      this.triggerEvent('tap')
    }
  }
})
