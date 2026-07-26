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
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return fallback
  return value
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
    throw new ApiError('服务器返回了无法识别的数据，请稍后重试', response.status, 'INVALID_RESPONSE')
  }
  if (!isEnvelope<T>(raw)) {
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

export const statusText: Record<string, string> = {
  PENDING_SUPERIOR_CONFIRMATION: '待上级确认',
  SUPERIOR_REJECTED: '上级已拒绝',
  PENDING_ADMIN_REVIEW: '待后台审核',
  ADMIN_REJECTED: '后台已拒绝',
  PENDING_SHIPMENT: '待发货',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  AWAITING_RETURN: '待回寄',
  RETURN_SHIPPED: '已回寄',
  PENDING_OFFLINE_REFUND: '待上级线下退款',
  PENDING_BUYER_REFUND_CONFIRMATION: '待买家确认退款',
  REJECTED: '已驳回'
}
