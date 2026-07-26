export type Envelope<T> = { success: boolean; code?: string; message: string; data: T }

function adminPath(path: string) {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  return `${base}${path}`
}

export async function adminApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(`/api/v1/admin${path}`, { ...init, headers, credentials: 'include' })
  let body: Envelope<T>
  try {
    body = (await response.json()) as Envelope<T>
  } catch {
    throw new Error('服务响应格式异常，请稍后重试')
  }
  if (!response.ok || !body.success) {
    const loginPath = adminPath('/login')
    if (response.status === 401 && location.pathname !== loginPath) location.href = loginPath
    throw new Error(body.message || '请求失败')
  }
  return body.data
}

export const money = (fen: number) => `¥${(fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
export const dateTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN') : '—'
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
export const statusText: Record<string, string> = {
  PENDING_SUPERIOR_CONFIRMATION: '待上级确认',
  PENDING_ADMIN_REVIEW: '待后台审核',
  PENDING_SHIPMENT: '待发货',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  SUPERIOR_REJECTED: '上级拒绝',
  ADMIN_REJECTED: '后台拒绝',
  PENDING_ADMIN_REVIEW_AFTERSALE: '待售后审核',
  AWAITING_RETURN: '待用户回寄',
  RETURN_SHIPPED: '用户已回寄',
  PENDING_OFFLINE_REFUND: '待线下退款',
  PENDING_BUYER_REFUND_CONFIRMATION: '待用户确认退款',
  REJECTED: '已拒绝'
}
