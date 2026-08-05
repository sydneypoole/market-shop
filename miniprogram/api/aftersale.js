const { request, uploadFile } = require('../utils/request')

function apply(payload) {
  return request('/after-sales', {
    method: 'POST',
    data: payload
  })
}

function list() {
  return request('/after-sales')
}

function superiorList() {
  return request('/after-sales/superior')
}

function detail(id) {
  return request('/after-sales/' + id)
}

function returnShipment(id, payload) {
  return request('/after-sales/' + id + '/return-shipment', {
    method: 'POST',
    data: payload
  })
}

function confirmRefund(id) {
  return request('/after-sales/' + id + '/confirm-refund', {
    method: 'POST'
  })
}

function cancel(id, reason) {
  return request('/after-sales/' + id + '/cancel', {
    method: 'POST',
    data: { reason: reason || '' }
  })
}

function superiorConfirmOfflineRefund(id) {
  return request('/after-sales/superior/' + id + '/confirm-offline-refund', {
    method: 'POST'
  })
}

function uploadProof(id, filePath) {
  return uploadFile('/after-sales/' + id + '/proofs', filePath)
}

function proofs(id) {
  return request('/after-sales/' + id + '/proofs')
}

function proofDownload(proofId) {
  return request('/after-sale-proofs/' + proofId + '/download')
}

module.exports = {
  apply,
  list,
  superiorList,
  detail,
  returnShipment,
  confirmRefund,
  cancel,
  superiorConfirmOfflineRefund,
  uploadProof,
  proofs,
  proofDownload
}
