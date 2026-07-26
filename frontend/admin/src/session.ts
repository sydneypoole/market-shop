import { reactive } from 'vue'
import { adminApi } from './api'

export type AdminSession = {
  adminId: number
  displayName: string
  username: string
  roles: string[]
  permissions: string[]
  mustChangePassword: boolean
}

export const adminSession = reactive<{ current?: AdminSession; loaded: boolean }>({
  current: undefined,
  loaded: false
})

export async function loadAdminSession(force = false) {
  if (adminSession.loaded && !force) return adminSession.current
  adminSession.current = await adminApi<AdminSession>('/auth/me')
  adminSession.loaded = true
  return adminSession.current
}

export function clearAdminSession() {
  adminSession.current = undefined
  adminSession.loaded = false
}

export function can(permission?: string) {
  if (!permission) return true
  return adminSession.current?.permissions.includes(permission) ?? false
}

export function firstAllowedPath() {
  const candidates: Array<[string, string]> = [
    ['order:read', '/'],
    ['catalog:read', '/catalog'],
    ['rule:publish', '/rules'],
    ['aftersale:review', '/after-sales'],
    ['member:read', '/members'],
    ['content:write', '/content'],
    ['admin:account:manage', '/accounts'],
    ['audit:read', '/audit'],
    ['system:setting:manage', '/settings']
  ]
  return candidates.find(([permission]) => can(permission))?.[1] ?? '/login'
}
