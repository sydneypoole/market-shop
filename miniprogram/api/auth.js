const { request } = require('../utils/request')

function exchangeWeChatCode(body) {
  return request('/auth/wechat/miniprogram/login', {
    method: 'POST',
    data: body,
    auth: false
  })
}

function login(code) {
  return exchangeWeChatCode({ code: code })
}

function registerWithInvite(code, inviteCode) {
  return exchangeWeChatCode({ code: code, inviteCode: inviteCode })
}

function claimSponsor(code, sponsorClaimSecret) {
  return exchangeWeChatCode({ code: code, sponsorClaimSecret: sponsorClaimSecret })
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
