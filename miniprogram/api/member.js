const { request } = require('../utils/request')

function me() {
  return request('/membership/me')
}

function invitation() {
  return request('/membership/invitation')
}

function createInvitation() {
  return request('/membership/invitation', {
    method: 'POST'
  })
}

function revokeInvitation() {
  return request('/membership/invitation/revoke', {
    method: 'POST'
  })
}

function regenerateInvitation(validityDays) {
  const days = validityDays || 365
  return request('/membership/invitation/regenerate?validityDays=' + days, {
    method: 'POST'
  })
}

function directMembers() {
  return request('/membership/direct-members')
}

function ledger() {
  return request('/membership/ledger')
}

module.exports = {
  me,
  invitation,
  createInvitation,
  revokeInvitation,
  regenerateInvitation,
  directMembers,
  ledger
}
