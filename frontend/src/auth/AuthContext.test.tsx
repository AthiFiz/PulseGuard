import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from './AuthContext'
import { ProtectedRoute } from './ProtectedRoute'
import { getToken, setToken } from './tokenStorage'

const USER = {
  id: 1,
  email: 'user@example.com',
  displayName: 'Test User',
  systemRole: 'USER',
  enabled: true,
  createdAt: '2026-08-01T00:00:00Z',
}

function jsonResponse(body: unknown, status = 200) {
  return { ok: status < 400, status, json: async () => body }
}

function Probe() {
  const { user, isAuthenticated, logout } = useAuth()
  return (
    <div>
      <span>{isAuthenticated ? `signed in as ${user?.displayName}` : 'signed out'}</span>
      <button type="button" onClick={logout}>
        Log out
      </button>
    </div>
  )
}

function renderWithAuth(ui: React.ReactNode, initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>{ui}</AuthProvider>
    </MemoryRouter>,
  )
}

describe('AuthProvider', () => {
  it('starts signed out when no token is stored, without calling the API', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderWithAuth(<Probe />)

    expect(await screen.findByText('signed out')).toBeTruthy()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  /** A stored token is only a claim; it is verified before anything renders. */
  it('restores the session from a stored token via /auth/me', async () => {
    setToken('a-valid-token')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(USER))
    vi.stubGlobal('fetch', fetchMock)

    renderWithAuth(<Probe />)

    expect(await screen.findByText('signed in as Test User')).toBeTruthy()
    expect(fetchMock.mock.calls[0][0]).toBe('http://localhost:8080/api/v1/auth/me')
  })

  it('clears an expired token instead of trusting it', async () => {
    setToken('an-expired-token')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ code: 'AUTHENTICATION_REQUIRED', message: 'Authentication is required' }, 401),
      ),
    )

    renderWithAuth(<Probe />)

    expect(await screen.findByText('signed out')).toBeTruthy()
    await waitFor(() => expect(getToken()).toBeNull())
  })

  it('logout drops the token and the user', async () => {
    setToken('a-valid-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(USER)))

    renderWithAuth(<Probe />)
    await screen.findByText('signed in as Test User')

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }))

    expect(await screen.findByText('signed out')).toBeTruthy()
    expect(getToken()).toBeNull()
  })
})

describe('ProtectedRoute', () => {
  it('sends an unauthenticated visitor to the login page', async () => {
    vi.stubGlobal('fetch', vi.fn())

    renderWithAuth(
      <Routes>
        <Route path="/login" element={<p>Login page</p>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/projects" element={<p>Projects page</p>} />
        </Route>
      </Routes>,
      '/projects',
    )

    expect(await screen.findByText('Login page')).toBeTruthy()
  })

  it('lets an authenticated visitor through', async () => {
    setToken('a-valid-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(USER)))

    renderWithAuth(
      <Routes>
        <Route path="/login" element={<p>Login page</p>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/projects" element={<p>Projects page</p>} />
        </Route>
      </Routes>,
      '/projects',
    )

    expect(await screen.findByText('Projects page')).toBeTruthy()
  })

  /**
   * The guard must wait for the token check. Deciding early would bounce a
   * perfectly valid session to login on every browser refresh.
   */
  it('waits while a stored token is being verified', async () => {
    setToken('a-valid-token')
    let resolve: ((value: unknown) => void) | undefined
    vi.stubGlobal(
      'fetch',
      vi.fn().mockReturnValue(
        new Promise((r) => {
          resolve = r
        }),
      ),
    )

    renderWithAuth(
      <Routes>
        <Route path="/login" element={<p>Login page</p>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/projects" element={<p>Projects page</p>} />
        </Route>
      </Routes>,
      '/projects',
    )

    expect(screen.getByText('Restoring your session…')).toBeTruthy()
    expect(screen.queryByText('Login page')).toBeNull()

    resolve?.(jsonResponse(USER))
    expect(await screen.findByText('Projects page')).toBeTruthy()
  })
})
