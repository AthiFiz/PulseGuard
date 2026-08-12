import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'

/**
 * The shell every authenticated page renders inside: brand, primary navigation,
 * and the current user with a way out.
 */
export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  function handleLogout() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="app">
      <header className="app__header">
        <Link to="/projects" className="brand">
          <span className="brand__mark" aria-hidden="true" />
          PulseGuard
        </Link>

        <nav className="app__nav" aria-label="Main">
          <NavLink to="/projects" className={navClass}>
            Projects
          </NavLink>
        </nav>

        <div className="app__user">
          {user && (
            <span className="app__user-name">
              {user.displayName}
              {user.systemRole === 'ADMIN' && <span className="tag tag--admin">Admin</span>}
            </span>
          )}
          <button type="button" className="btn btn--ghost" onClick={handleLogout}>
            Log out
          </button>
        </div>
      </header>

      <main className="app__main">
        <Outlet />
      </main>
    </div>
  )
}

function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'app__nav-link app__nav-link--active' : 'app__nav-link'
}
