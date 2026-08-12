/**
 * The only place the access token is read or written.
 *
 * sessionStorage rather than localStorage: the token dies with the tab, which
 * limits how long a forgotten session lingers on a shared machine. The backend
 * offers no refresh token and no revocation, so keeping the window short is the
 * only lever available.
 *
 * The token is treated as opaque. It is never decoded to decide what the user
 * may do — the backend answers that.
 */
const TOKEN_KEY = 'pulseguard.accessToken'

export function getToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    // Private browsing modes can throw on storage access.
    return null
  }
}

export function setToken(token: string): void {
  try {
    sessionStorage.setItem(TOKEN_KEY, token)
  } catch {
    // Nothing useful to do; the session simply will not survive a reload.
  }
}

export function clearToken(): void {
  try {
    sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // Ignored for the same reason.
  }
}
