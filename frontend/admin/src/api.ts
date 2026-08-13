export type Envelope<T> = {
  success: boolean
  code?: string
  message: string
  data: T
}

export type AdminApiErrorKind = 'network' | 'invalid-response' | 'http' | 'business'

export class AdminApiError extends Error {
  readonly status: number
  readonly code: string
  readonly kind: AdminApiErrorKind

  constructor(options: { message: string; status?: number; code?: string; kind: AdminApiErrorKind }) {
    super(options.message)
    this.name = 'AdminApiError'
    this.status = options.status ?? 0
    this.code = options.code ?? 'ADMIN_REQUEST_FAILED'
    this.kind = options.kind
  }
}

let unauthorizedHandler: (() => void) | undefined

export function setAdminUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isEnvelope<T>(value: unknown): value is Envelope<T> {
  return isRecord(value)
    && typeof value.success === 'boolean'
    && typeof value.message === 'string'
    && (value.code === undefined || typeof value.code === 'string')
    && Object.hasOwn(value, 'data')
}

function adminPath(path: string) {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  return `${base}${path}`
}

function currentApplicationPath() {
  if (typeof window === 'undefined') return '/'
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  let pathname = window.location.pathname
  if (base && pathname === base) pathname = '/'
  else if (base && pathname.startsWith(`${base}/`)) pathname = pathname.slice(base.length)
  return `${pathname || '/'}${window.location.search}${window.location.hash}`
}

function redirectToLogin() {
  unauthorizedHandler?.()
  if (typeof window === 'undefined') return
  const loginPath = adminPath('/login')
  if (window.location.pathname === loginPath) return
  const redirect = currentApplicationPath()
  window.location.assign(`${loginPath}?redirect=${encodeURIComponent(redirect)}`)
}

export function isAdminApiError(value: unknown): value is AdminApiError {
  return value instanceof AdminApiError
}

export function adminErrorMessage(value: unknown, fallback = '操作未完成，请稍后重试') {
  return value instanceof Error && value.message.trim() ? value.message : fallback
}

export function isConflictError(value: unknown) {
  return isAdminApiError(value) && value.status === 409
}

export async function adminApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')

  let response: Response
  try {
    response = await fetch(`/api/v1/admin${path}`, { ...init, headers, credentials: 'include' })
  } catch {
    throw new AdminApiError({
      message: '网络连接失败，请检查网络后重试',
      code: 'ADMIN_NETWORK_ERROR',
      kind: 'network'
    })
  }

  let parsed: unknown
  try {
    parsed = await response.json()
  } catch {
    if (response.status === 401) redirectToLogin()
    throw new AdminApiError({
      message: '服务响应格式异常，请稍后重试',
      status: response.status,
      code: 'ADMIN_INVALID_RESPONSE',
      kind: 'invalid-response'
    })
  }

  if (!isEnvelope<T>(parsed)) {
    if (response.status === 401) redirectToLogin()
    throw new AdminApiError({
      message: '服务响应格式异常，请稍后重试',
      status: response.status,
      code: 'ADMIN_INVALID_RESPONSE',
      kind: 'invalid-response'
    })
  }

  if (!response.ok || !parsed.success) {
    if (response.status === 401) redirectToLogin()
    throw new AdminApiError({
      message: parsed.message || (response.status === 403 ? '当前账号没有执行此操作的权限' : '请求失败'),
      status: response.status,
      code: parsed.code || `HTTP_${response.status}`,
      kind: response.ok ? 'business' : 'http'
    })
  }

  return parsed.data
}

export async function adminDownload(path: string): Promise<Blob> {
  let response: Response
  try {
    response = await fetch(`/api/v1/admin${path}`, { credentials: 'include' })
  } catch {
    throw new AdminApiError({
      message: '网络连接失败，请检查网络后重试',
      code: 'ADMIN_NETWORK_ERROR',
      kind: 'network'
    })
  }

  if (!response.ok) {
    let parsed: unknown
    try { parsed = await response.json() } catch { parsed = undefined }
    if (response.status === 401) redirectToLogin()
    const envelope = isEnvelope<unknown>(parsed) ? parsed : undefined
    throw new AdminApiError({
      message: envelope?.message
        || (response.status === 403 ? '当前账号没有执行此操作的权限' : '导出请求失败，请稍后重试'),
      status: response.status,
      code: envelope?.code || `HTTP_${response.status}`,
      kind: 'http'
    })
  }

  try {
    return await response.blob()
  } catch {
    throw new AdminApiError({
      message: '导出文件读取失败，请稍后重试',
      status: response.status,
      code: 'ADMIN_DOWNLOAD_INVALID_RESPONSE',
      kind: 'invalid-response'
    })
  }
}

export const money = (fen: number) => `¥${(fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
export const dateTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN') : '未记录'
export const fileSize = (bytes: number) => bytes < 1024 * 1024
  ? `${(bytes / 1024).toFixed(1)} KB`
  : `${(bytes / 1024 / 1024).toFixed(2)} MB`

export function queryString(values: Record<string, string | number | undefined | null>) {
  return new URLSearchParams(
    Object.entries(values)
      .filter(([, value]) => value !== '' && value !== undefined && value !== null)
      .map(([key, value]) => [key, String(value)])
  ).toString()
}
