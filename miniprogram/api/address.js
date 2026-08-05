const { request } = require('../utils/request')

function list() {
  return request('/addresses')
}

function create(body) {
  return request('/addresses', {
    method: 'POST',
    data: body
  })
}

function update(id, body) {
  return request('/addresses/' + id, {
    method: 'PUT',
    data: body
  })
}

function remove(id, version) {
  return request('/addresses/' + id + '?version=' + version, {
    method: 'DELETE'
  })
}

module.exports = {
  list,
  create,
  update,
  remove
}
