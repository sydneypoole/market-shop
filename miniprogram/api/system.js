const { request } = require('../utils/request')

function about() {
  return request('/system/about', { auth: false })
}

function capabilities() {
  return request('/system/capabilities', { auth: false })
}

module.exports = {
  about,
  capabilities
}
