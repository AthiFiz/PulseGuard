import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { useAuth } from '../auth/AuthContext'
import { safeRedirectPath } from '../auth/safeRedirect'

interface LocationState {
  from?: { pathname: string }
  message?: string
}

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const state = location.state as LocationState | null

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setIsSubmitting(true)

    try {
      await login({ email, password })
      // Back to wherever the guard interrupted, or the project list. The
      // remembered path came from the address bar, so it is checked before
      // being navigated to — see safeRedirectPath.
      navigate(safeRedirectPath(state?.from?.pathname), { replace: true })
    } catch (caught) {
      // The backend answers identically for an unknown email, a wrong password
      // and a disabled account, and this shows exactly what it said — nothing
      // here should hint at which one it was.
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'Unable to sign in. Check that the Control API is running.',
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="auth">
      <div className="auth__card">
        <div className="auth__brand">
          <span className="brand__mark" aria-hidden="true" />
          PulseGuard
        </div>
        <h1>Sign in</h1>
        <p className="auth__subtitle">API monitoring and incident management.</p>

        {state?.message && (
          <p className="alert alert--success" role="status">
            {state.message}
          </p>
        )}
        {error && (
          <p className="alert alert--error" role="alert">
            {error}
          </p>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>

          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>

          <button type="submit" className="btn btn--primary btn--block" disabled={isSubmitting}>
            {isSubmitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="auth__footer">
          No account? <Link to="/register">Create one</Link>
        </p>
      </div>
    </div>
  )
}
