const { getBaseUrl } = require('./config')

function fenToYuan(fen) {
  const n = Number(fen)
  if (!Number.isFinite(n)) {
    return '0.00'
  }
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(Math.round(n))
  const yuan = Math.floor(abs / 100)
  const cent = abs % 100
  const yuanText = String(yuan).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const centText = cent < 10 ? '0' + cent : String(cent)
  return sign + yuanText + '.' + centText
}

function resolveMediaUrl(url) {
  if (!url) {
    return ''
  }
  const s = String(url)
  if (/^https?:\/\//i.test(s) || s.indexOf('data:') === 0 || s.indexOf('wxfile://') === 0) {
    return s
  }
  if (s.indexOf('//') === 0) {
    return 'https:' + s
  }
  const baseUrl = getBaseUrl()
  if (s.charAt(0) === '/') {
    return baseUrl + s
  }
  return baseUrl + '/' + s
}

function resolveRichTextMedia(html) {
  if (!html) {
    return ''
  }
  return String(html).replace(/\bsrc\s*=\s*(['"])([^'"]+)\1/gi, function (match, quote, value) {
    return 'src=' + quote + resolveMediaUrl(value) + quote
  })
}

function pad2(n) {
  return n < 10 ? '0' + n : String(n)
}

function dateTime(iso) {
  if (!iso) {
    return ''
  }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) {
    return String(iso)
  }
  return (
    d.getFullYear() +
    '-' +
    pad2(d.getMonth() + 1) +
    '-' +
    pad2(d.getDate()) +
    ' ' +
    pad2(d.getHours()) +
    ':' +
    pad2(d.getMinutes())
  )
}

function fileSize(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n < 0) {
    return '0 B'
  }
  if (n < 1024) {
    return n + ' B'
  }
  if (n < 1024 * 1024) {
    return (n / 1024).toFixed(1) + ' KB'
  }
  return (n / (1024 * 1024)).toFixed(1) + ' MB'
}

module.exports = {
  fenToYuan,
  resolveMediaUrl,
  resolveRichTextMedia,
  dateTime,
  fileSize
}
