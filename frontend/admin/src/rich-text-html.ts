const STABLE_CATALOG_IMAGE_PATH = /^\/api\/v1\/catalog\/assets\/[1-9]\d*$/
const SAFE_IMAGE_WIDTH = /^(?:100|[1-9]\d?)%$/

export type NormalizedRichTextHtml = Readonly<{
  html: string
  removedImages: number
}>

export function normalizeImageWidth(value: string | null | undefined): string {
  const normalized = value?.trim() || ''
  if (!SAFE_IMAGE_WIDTH.test(normalized)) return ''
  const percentage = Number.parseInt(normalized, 10)
  return percentage >= 10 && percentage <= 100 ? `${percentage}%` : ''
}

export function stableCatalogImagePath(
  value: string | null | undefined,
  currentOrigin = typeof window === 'undefined' ? '' : window.location.origin
): string {
  const normalized = value?.trim() || ''
  if (!normalized || /^(?:data|blob|javascript|wxfile):/i.test(normalized) || normalized.startsWith('//')) {
    return ''
  }
  if (STABLE_CATALOG_IMAGE_PATH.test(normalized)) return normalized
  if (!currentOrigin) return ''

  try {
    const resolved = new URL(normalized, currentOrigin)
    if (resolved.origin !== currentOrigin || resolved.search || resolved.hash) return ''
    return STABLE_CATALOG_IMAGE_PATH.test(resolved.pathname) ? resolved.pathname : ''
  } catch {
    return ''
  }
}

function cleanImageElement(image: HTMLImageElement): boolean {
  const src = stableCatalogImagePath(image.getAttribute('src'))
  if (!src) {
    image.remove()
    return false
  }

  const alt = (image.getAttribute('alt') || '').replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 160)
  const width = normalizeImageWidth(image.getAttribute('width'))
  for (const attribute of Array.from(image.attributes)) image.removeAttribute(attribute.name)
  image.setAttribute('src', src)
  if (alt) image.setAttribute('alt', alt)
  if (width) image.setAttribute('width', width)
  return true
}

/**
 * Normalizes the image-specific part of Quill HTML before it reaches a form model.
 * The backend owns the complete HTML allow-list; this client-side pass prevents
 * previews and drafts from retaining provider URLs, external images or temporary data.
 */
export function normalizeRichTextImages(html: string): NormalizedRichTextHtml {
  if (!html || typeof document === 'undefined') return { html: html || '', removedImages: 0 }
  const template = document.createElement('template')
  template.innerHTML = html
  let removedImages = 0
  for (const image of Array.from(template.content.querySelectorAll('img'))) {
    if (!cleanImageElement(image)) removedImages += 1
  }
  return { html: template.innerHTML, removedImages }
}
