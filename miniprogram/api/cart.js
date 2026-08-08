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

function incrementItem(skuId, increment) {
  const delta = Math.max(1, Number(increment) || 1)
  return list().then(function (rows) {
    const items = Array.isArray(rows) ? rows : []
    const existing = items.find(function (item) {
      return Number(item.skuId) === Number(skuId)
    })
    const current = existing ? Number(existing.quantity) || 0 : 0
    const inventory = existing ? Number(existing.inventory) : NaN
    const maxQuantity = Math.min(99, Number.isFinite(inventory) && inventory >= 0 ? inventory : 99)
    const next = current + delta
    if (next > maxQuantity) {
      return Promise.reject({
        code: 'QUANTITY_LIMIT',
        message: maxQuantity > current ? '最多还能加入 ' + (maxQuantity - current) + ' 件' : '购物车数量已达上限',
        status: 0,
        statusCode: 0,
        data: null
      })
    }
    return setItem(skuId, next, true).then(function () {
      return next
    })
  })
}

module.exports = {
  list,
  setItem,
  incrementItem
}
