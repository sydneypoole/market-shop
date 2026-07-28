import DOMPurify from 'dompurify'

export function sanitizeRichText(html: string) {
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'iframe', 'object', 'embed', 'svg', 'math'],
    FORBID_ATTR: ['style'],
    ADD_ATTR: ['data-list'],
    ALLOW_DATA_ATTR: false
  })
}
