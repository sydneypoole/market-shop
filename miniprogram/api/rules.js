const { request } = require('../utils/request')

function active() {
  return request('/rules/active')
}

module.exports = {
  active
}
