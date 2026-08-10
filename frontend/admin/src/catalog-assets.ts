import { adminApi } from './api'

export type CatalogAsset = {
  id: number
  originalFilename: string
  mediaType: string
  sizeBytes: number
  uploadedByAdminId: number
  url: string
  createdAt: string
}

const CATALOG_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])

export const CATALOG_IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp'
export const CATALOG_IMAGE_MAX_BYTES = 10 * 1024 * 1024

export function catalogImageValidationMessage(file: File): string {
  if (file.size === 0) return '图片文件不能为空'
  if (!CATALOG_IMAGE_TYPES.has(file.type)) return '仅支持 JPG、PNG 或 WebP 图片'
  if (file.size > CATALOG_IMAGE_MAX_BYTES) return '图片大小不可超过 10 MB'
  return ''
}

export async function uploadCatalogImage(file: File): Promise<CatalogAsset> {
  const validationMessage = catalogImageValidationMessage(file)
  if (validationMessage) throw new Error(validationMessage)

  const form = new FormData()
  form.append('file', file)
  return adminApi<CatalogAsset>('/catalog/assets', { method: 'POST', body: form })
}
