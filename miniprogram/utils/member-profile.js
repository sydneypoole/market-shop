const { resolveMediaUrl } = require('./format')

function nicknameInitial(value) {
  const characters = Array.from(String(value || '').trim())
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
  nicknameInitial,
  resolveOwnedAvatarUrl,
  isLocalAvatarPath
}
