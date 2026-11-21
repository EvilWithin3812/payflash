import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiErrorBody, Role, TokenResponse } from './types'

/*
 * Tokens live in localStorage to keep this demo simple. For a production banking app you'd keep the
 * refresh token in an httpOnly, SameSite cookie so page scripts (and any XSS) can never read it.
 */
const ACCESS = 'pf.access'
const REFRESH = 'pf.refresh'

export interface Session {
  userId: number
  email: string
  role: Role
  emailVerified: boolean
}

export const tokens = {
  get access() {
    return localStorage.getItem(ACCESS)
  },
  get refresh() {
    return localStorage.getItem(REFRESH)
  },
  save(t: TokenResponse) {
    localStorage.setItem(ACCESS, t.accessToken)
    localStorage.setItem(REFRESH, t.refreshToken)
    window.dispatchEvent(new Event('pf-auth'))
  },
  clear() {
    localStorage.removeItem(ACCESS)
    localStorage.removeItem(REFRESH)
    window.dispatchEvent(new Event('pf-auth'))
  },
}

/** Reads the (unverified) JWT payload for display only. The server verifies every request. */
export function currentSession(): Session | null {
  const token = tokens.access ?? tokens.refresh
  if (!token) return null
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return { userId: Number(payload.sub), email: payload.email, role: payload.role, emailVerified: payload.ev !== false }
  } catch {
    return null
  }
}

export const api = axios.create({ baseURL: '/api/v1', timeout: 15_000 })

api.interceptors.request.use((config) => {
  const token = tokens.access
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Access tokens live 15 minutes. On a 401, swap the refresh token for a new pair once, then retry.
// Concurrent 401s share one refresh call instead of racing.
let refreshing: Promise<string | null> | null = null

/** Swaps the refresh token for a new pair now (e.g. right after the email is verified, to pick up the change). */
export async function refreshSession(): Promise<boolean> {
  return (await refreshAccessToken()) !== null
}

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = tokens.refresh
  if (!refreshToken) return null
  try {
    const { data } = await axios.post<TokenResponse>('/api/v1/auth/refresh', { refreshToken })
    tokens.save(data)
    return data.accessToken
  } catch {
    tokens.clear()
    return null
  }
}

api.interceptors.response.use(undefined, async (error: AxiosError) => {
  const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
  const isAuthCall = original?.url?.startsWith('/auth/')
  if (error.response?.status === 401 && original && !original._retried && !isAuthCall) {
    original._retried = true
    refreshing ??= refreshAccessToken().finally(() => (refreshing = null))
    const fresh = await refreshing
    if (fresh) {
      original.headers.Authorization = `Bearer ${fresh}`
      return api(original)
    }
  }
  throw error
})

/** Turns any failed call into a readable message, using the API's {code, message} error body. */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as ApiErrorBody | undefined
    if (body?.fieldErrors && Object.keys(body.fieldErrors).length > 0) {
      return Object.entries(body.fieldErrors)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join(' · ')
    }
    if (body?.message) return body.message
    if (err.code === 'ECONNABORTED') return 'The server took too long to respond.'
    if (!err.response) return 'Cannot reach the server. Is the backend running?'
  }
  return 'Something went wrong.'
}

export function errorCode(err: unknown): string | undefined {
  return axios.isAxiosError(err) ? (err.response?.data as ApiErrorBody | undefined)?.code : undefined
}

/** One key per user action: retries of the same action reuse it, so money never moves twice. */
export function newIdempotencyKey(): string {
  // randomUUID only exists in secure contexts (https or localhost); fall back for plain-http LAN access.
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const b = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
}
