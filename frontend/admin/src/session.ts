import { reactive } from 'vue'
import { adminApi, setAdminUnauthorizedHandler } from './api'
import { firstAllowedNavigationPath } from './admin-navigation'

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

setAdminUnauthorizedHandler(clearAdminSession)

export function can(permission?: string) {
  if (!permission) return true
  return adminSession.current?.permissions.includes(permission) ?? false
}

export function firstAllowedPath() {
  return firstAllowedNavigationPath(can)
}

export function safeAdminRedirect(value: unknown, fallback = firstAllowedPath()) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return fallback
  try {
    const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin
    const target = new URL(value, origin)
    return target.origin === origin ? `${target.pathname}${target.search}${target.hash}` : fallback
  } catch {
    return fallback
  }
}
