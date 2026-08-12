import { clearToken, getToken } from '../auth/tokenStorage'
import { apiUrl } from './config'
import type { ApiErrorResponse, ApiFieldError } from '../types/api'

/**
 * A failed API call, carrying the backend's own error code so callers can
 * branch on it without matching message text.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: ApiFieldError[]

  constructor(status: number, code: string, message: string, fieldErrors: ApiFieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

/**
 * Called when any authenticated request comes back 401.
 *
 * The AuthContext registers itself here at startup, so expiry is handled once
 * rather than in every page that happens to make a call.
 */
type UnauthorizedHandler = () => void
let onUnauthorized: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler
}

interface RequestOptions {
  method?: string
  body?: unknown
  /**
   * Login and registration are the only calls made without a token. Marking
   * them public stops a 401 from those endpoints triggering the global
   * "session expired" path, which would fight with the login form trying to
   * show "invalid email or password".
   */
  isPublic?: boolean
  query?: Record<string, string | number | null | undefined>
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, isPublic = false, query } = options

  const headers: Record<string, string> = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const token = getToken()
  if (token && !isPublic) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(apiUrl(path) + buildQueryString(query), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 401 && !isPublic) {
    // The token is gone or expired. Clear it before notifying, so nothing can
    // retry with the same dead credential.
    clearToken()
    onUnauthorized?.()
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  // 204 No Content has an empty body; calling json() on it throws.
  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

/**
 * Only sends parameters that have a value. Passing `outcome=` or `from=`
 * empty would be rejected by the backend rather than treated as "no filter".
 */
function buildQueryString(query?: Record<string, string | number | null | undefined>): string {
  if (!query) {
    return ''
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== null && value !== undefined && value !== '') {
      params.set(key, String(value))
    }
  }
  const serialised = params.toString()
  return serialised ? `?${serialised}` : ''
}

/**
 * Turns an error response into an ApiError.
 *
 * Falls back gracefully when the body is not the expected shape — a proxy
 * returning HTML, or a network layer producing something unparseable, must not
 * surface to the user as "[object Object]".
 */
async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as ApiErrorResponse
    if (body && typeof body.message === 'string' && typeof body.code === 'string') {
      return new ApiError(response.status, body.code, body.message, body.errors ?? [])
    }
  } catch {
    // Body was empty or not JSON; fall through to the generic message.
  }
  return new ApiError(response.status, 'UNKNOWN_ERROR', `Request failed (HTTP ${response.status})`)
}

export const apiClient = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown, isPublic = false) =>
    request<T>(path, { method: 'POST', body, isPublic }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
