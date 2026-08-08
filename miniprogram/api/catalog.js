const { request } = require('../utils/request')

function products() {
  return request('/catalog/products', { auth: false })
}

function product(id) {
  return request('/catalog/products/' + id, { auth: false })
}

function categories() {
  return request('/catalog/categories', { auth: false })
}

function contents() {
  return request('/content', { auth: false })
}

function content(id) {
  return request('/content/' + id, { auth: false })
}

module.exports = {
  products,
  product,
  categories,
  contents,
  content
}
