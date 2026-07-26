import { ApiError, api, safeRedirect } from './api'

export type UserSession = {
  userId: number
  publicId: string
  nickname: string
}

export async function loadUserSession() {
  return api<UserSession>('/auth/me', {}, { redirectOnUnauthorized: false })
}

export async function requireUserSession(redirect: string) {
  try {
    await loadUserSession()
    return true
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      return {
        path: '/login',
        query: {
          redirect: safeRedirect(redirect),
          reason: 'login-required'
        }
      }
    }
    return {
      path: '/login',
      query: {
        redirect: safeRedirect(redirect),
        reason: 'session-check-failed'
      }
    }
  }
}
