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
  if (typeof value !== 'string' || !isSafeAdminRelativeRedirect(value)) return fallback
  try {
    const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin
    const target = new URL(value, origin)
    return target.origin === origin
      && target.username === ''
      && target.password === ''
      && (target.protocol === 'http:' || target.protocol === 'https:')
      ? `${target.pathname}${target.search}${target.hash}`
      : fallback
  } catch {
    return fallback
  }
}

const ADMIN_REDIRECT_CONTROL_CHARACTERS = /[\u0000-\u001F\u007F-\u009F]/

function isSafeAdminRelativeRedirect(value: string) {
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')
    || ADMIN_REDIRECT_CONTROL_CHARACTERS.test(value)) return false

  let decoded: string
  try {
    decoded = decodeURIComponent(value)
  } catch {
    return false
  }
  return !decoded.startsWith('//') && !decoded.includes('\\')
    && !ADMIN_REDIRECT_CONTROL_CHARACTERS.test(decoded)
}
