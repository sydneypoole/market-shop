const { resolveMediaUrl } = require('./format')

function normalizeNickname(value) {
  return String(value || '').trim()
}

function nicknameValidationError(value) {
  const nickname = normalizeNickname(value)
  if (!nickname) {
    return '请选择或输入微信昵称'
  }
  if (Array.from(nickname).length > 32 || /[\u0000-\u001f\u007f-\u009f]/.test(nickname)) {
    return '昵称不能超过 32 个字符，且不能包含控制字符'
  }
  return ''
}

function nicknameInitial(value) {
  const characters = Array.from(normalizeNickname(value))
  return characters.length ? characters[0] : '会'
}

function resolveOwnedAvatarUrl(value) {
  const path = String(value || '').trim()
  if (!/^\/api\/v1\/member-avatars\/\d+$/.test(path)) {
    return ''
  }
  return resolveMediaUrl(path)
}

function isLocalAvatarPath(value) {
  const path = String(value || '').trim()
  return /^(?:wxfile:\/\/|https?:\/\/tmp\/|\/?tmp\/)/i.test(path)
}

module.exports = {
  normalizeNickname,
  nicknameValidationError,
  nicknameInitial,
  resolveOwnedAvatarUrl,
  isLocalAvatarPath
}
