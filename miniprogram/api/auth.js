const { request } = require('../utils/request')

function login(code, inviteCode, sponsorClaimSecret) {
  const body = { code: code }
  if (inviteCode) {
    body.inviteCode = inviteCode
  }
  if (sponsorClaimSecret) {
    body.sponsorClaimSecret = sponsorClaimSecret
  }
  return request('/auth/wechat/miniprogram/login', {
    method: 'POST',
    data: body,
    auth: false
  })
}

function me() {
  return request('/auth/me')
}

function logout() {
  return request('/auth/logout', { method: 'POST' })
}

module.exports = {
  login,
  me,
  logout
}
