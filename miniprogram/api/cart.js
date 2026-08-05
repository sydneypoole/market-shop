const { request } = require('../utils/request')

function list() {
  return request('/cart')
}

function setItem(skuId, quantity, selected) {
  return request('/cart/items/' + skuId, {
    method: 'PUT',
    data: {
      quantity: quantity,
      selected: selected !== false
    }
  })
}

module.exports = {
  list,
  setItem
}
