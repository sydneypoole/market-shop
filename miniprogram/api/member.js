const { request, uploadFile } = require('../utils/request')

function me() {
  return request('/membership/me')
}

function updateWeChatProfile(nickname, phoneCode) {
  return request('/membership/wechat-profile', {
    method: 'PUT',
    data: {
      nickname: nickname,
      phoneCode: phoneCode
    }
  })
}

function updateNickname(nickname) {
  return request('/membership/nickname', {
    method: 'PUT',
    data: {
      nickname: nickname
    }
  })
}

function uploadAvatar(filePath) {
  return uploadFile('/membership/avatar', filePath)
}

function invitation() {
  return request('/membership/invitation')
}

function invitationWxacode() {
  return request('/membership/invitation/wxacode')
}

function ensureInvitation() {
  return request('/membership/invitation', {
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
  updateWeChatProfile,
  updateNickname,
  uploadAvatar,
  invitation,
  invitationWxacode,
  ensureInvitation,
  directMembers,
  ledger
}
