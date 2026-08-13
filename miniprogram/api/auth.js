const { request } = require('../utils/request')

function postPublicAuth(path, body) {
  return request(path, {
    method: 'POST',
    data: body,
    auth: false
  })
}

function login(code) {
  return postPublicAuth('/auth/wechat/miniprogram/login', { code: code })
}

function registerWithInvite(code, inviteCode) {
  return postPublicAuth('/auth/wechat/miniprogram/register', {
    code: code,
    inviteCode: inviteCode
  })
}

function claimSponsor(code, sponsorClaimSecret) {
  return postPublicAuth('/auth/wechat/miniprogram/register', {
    code: code,
    sponsorClaimSecret: sponsorClaimSecret
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
  registerWithInvite,
  claimSponsor,
  me,
  logout
}
