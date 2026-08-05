const { request } = require('../utils/request')

function list(page, size) {
  const p = page || 1
  const s = size || 20
  return request('/notifications?page=' + p + '&size=' + s)
}

function unreadCount() {
  return request('/notifications/unread-count')
}

function markRead(id) {
  return request('/notifications/' + id + '/read', {
    method: 'POST'
  })
}

module.exports = {
  list,
  unreadCount,
  markRead
}
