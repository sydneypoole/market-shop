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
