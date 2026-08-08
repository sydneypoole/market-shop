function makeClientRequestId(prefix) {
  const scope = String(prefix || 'request').replace(/[^a-z0-9_-]/gi, '').slice(0, 16) || 'request'
  return scope + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

module.exports = {
  makeClientRequestId
}
