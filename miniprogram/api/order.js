const { request, uploadFile } = require('../utils/request')

function submit(payload) {
  return request('/orders', {
    method: 'POST',
    data: payload
  })
}

function list() {
  return request('/orders')
}

function detail(id) {
  return request('/orders/' + id)
}

function cancel(id, reason) {
  return request('/orders/' + id + '/cancel', {
    method: 'POST',
    data: { reason: reason || '' }
  })
}

function receive(id) {
  return request('/orders/' + id + '/receive', {
    method: 'POST'
  })
}

function uploadProof(id, filePath) {
  return uploadFile('/orders/' + id + '/proofs', filePath)
}

function proofs(id) {
  return request('/orders/' + id + '/proofs')
}

function proofDownload(proofId) {
  return request('/order-proofs/' + proofId + '/download')
}

module.exports = {
  submit,
  list,
  detail,
  cancel,
  receive,
  uploadProof,
  proofs,
  proofDownload
}
