import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { authApi } from '../api/authApi'
import { setUnauthorizedHandler } from '../api/apiClient'
import { clearToken, getToken, setToken } from './tokenStorage'
import type { LoginRequest, User } from '../types/auth'

interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  /** True until the stored token has been checked, so guards do not act early. */
  isInitializing: boolean
  login: (request: LoginRequest) => Promise<void>
  logout: () => void
  refreshCurrentUser: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isInitializing, setIsInitializing] = useState(true)

  const logout = useCallback(() => {
    // There is no server-side revocation, so "logout" is entirely local: drop
    // the token and forget the user. The token itself stays valid until it
    // expires, which is why its lifetime is one hour.
    clearToken()
    setUser(null)
  }, [])

  /**
   * A 401 from any authenticated call means the session is over. Handling it
   * here rather than per page keeps every screen from needing its own expiry
   * logic, and guarantees the app cannot sit showing stale protected content.
   */
  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null))
    return () => setUnauthorizedHandler(null)
  }, [])

  /**
   * On startup, a stored token is only a claim. It is verified against
   * /auth/me before any protected content renders — the token may have expired
   * while the tab was closed.
   */
  useEffect(() => {
    let active = true

    async function restoreSession() {
      if (!getToken()) {
        if (active) {
          setIsInitializing(false)
        }
        return
      }

      try {
        const currentUser = await authApi.me()
        if (active) {
          setUser(currentUser)
        }
      } catch {
        // Expired or rejected: the client has already cleared the token.
        if (active) {
          clearToken()
          setUser(null)
        }
      } finally {
        if (active) {
          setIsInitializing(false)
        }
      }
    }

    void restoreSession()
    return () => {
      active = false
    }
  }, [])

  const login = useCallback(async (request: LoginRequest) => {
    const response = await authApi.login(request)
    setToken(response.accessToken)
    // The login response carries a summary; fetch the full user so every screen
    // sees the same shape regardless of how the session began.
    const currentUser = await authApi.me()
    setUser(currentUser)
  }, [])

  const refreshCurrentUser = useCallback(async () => {
    setUser(await authApi.me())
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isInitializing,
      login,
      logout,
      refreshCurrentUser,
    }),
    [user, isInitializing, login, logout, refreshCurrentUser],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside an AuthProvider')
  }
  return context
}
