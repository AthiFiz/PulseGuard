import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import { LoadingState } from '../components/States'

/**
 * Gate for every application route.
 *
 * While the stored token is still being verified nothing is decided — rendering
 * a redirect during that window would bounce a perfectly valid session to the
 * login page on every refresh.
 */
export function ProtectedRoute() {
  const { isAuthenticated, isInitializing } = useAuth()
  const location = useLocation()

  if (isInitializing) {
    return <LoadingState message="Restoring your session…" />
  }

  if (!isAuthenticated) {
    // Remember where they were headed so login can send them back.
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <Outlet />
}

/** The mirror image: keeps a signed-in user off the login and register pages. */
export function PublicOnlyRoute() {
  const { isAuthenticated, isInitializing } = useAuth()

  if (isInitializing) {
    return <LoadingState message="Restoring your session…" />
  }

  if (isAuthenticated) {
    return <Navigate to="/projects" replace />
  }

  return <Outlet />
}
