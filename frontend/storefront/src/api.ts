export type ApiEnvelope<T> = {
  success: boolean
  code: string
  message: string
  data: T
}

type ApiOptions = {
  redirectOnUnauthorized?: boolean
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function isEnvelope<T>(value: unknown): value is ApiEnvelope<T> {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Record<string, unknown>
  return typeof candidate.success === 'boolean'
    && typeof candidate.code === 'string'
    && typeof candidate.message === 'string'
    && 'data' in candidate
}

export function safeRedirect(value: unknown, fallback = '/') {
  const safeFallback = typeof fallback === 'string' && isSafeRelativeRedirect(fallback) ? fallback : '/'
  if (typeof value !== 'string' || !isSafeRelativeRedirect(value)) return safeFallback
  return value
}

const REDIRECT_CONTROL_CHARACTERS = /[\u0000-\u001F\u007F-\u009F]/

/**
 * Keep post-login navigation on this application origin.  Checking only the
 * first two characters is insufficient: browsers normalize a raw backslash
 * in `/\\evil.example` into a scheme-relative URL before navigation.
 */
function isSafeRelativeRedirect(value: string) {
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')
    || REDIRECT_CONTROL_CHARACTERS.test(value)) return false

  let decoded: string
  try {
    decoded = decodeURIComponent(value)
  } catch {
    return false
  }
  if (decoded.startsWith('//') || decoded.includes('\\')
    || REDIRECT_CONTROL_CHARACTERS.test(decoded)) return false

  try {
    const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin
    const target = new URL(value, origin)
    return target.origin === origin
      && target.username === ''
      && target.password === ''
      && (target.protocol === 'http:' || target.protocol === 'https:')
  } catch {
    return false
  }
}

export function redirectToLogin(reason = 'session-expired') {
  if (location.pathname.startsWith('/login')) return
  const redirect = `${location.pathname}${location.search}${location.hash}`
  const query = new URLSearchParams({ redirect, reason })
  location.assign(`/login?${query.toString()}`)
}

export async function api<T>(
  path: string,
  init: RequestInit = {},
  options: ApiOptions = {}
): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      ...init,
      headers,
      credentials: 'include'
    })
  } catch {
    throw new ApiError('网络连接失败，请检查网络后重试', 0, 'NETWORK_ERROR')
  }

  let raw: unknown
  try {
    raw = await response.json()
  } catch {
    if (response.status === 401 && options.redirectOnUnauthorized !== false) {
      redirectToLogin()
    }
    throw new ApiError('服务器返回了无法识别的数据，请稍后重试', response.status, 'INVALID_RESPONSE')
  }
  if (!isEnvelope<T>(raw)) {
    if (response.status === 401 && options.redirectOnUnauthorized !== false) {
      redirectToLogin()
    }
    throw new ApiError('服务器返回格式异常，请稍后重试', response.status, 'INVALID_RESPONSE')
  }

  const body = raw
  if (!response.ok || !body.success) {
    if (response.status === 401 && options.redirectOnUnauthorized !== false) {
      redirectToLogin()
    }
    throw new ApiError(body.message || '请求失败', response.status, body.code)
  }
  return body.data
}

export const money = (fen: number) =>
  new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(fen / 100)

export const dateTime = (value?: string) =>
  value ? new Date(value).toLocaleString('zh-CN') : '—'

export const fileSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export { orderStatusLabel, statusText } from './order-status'
