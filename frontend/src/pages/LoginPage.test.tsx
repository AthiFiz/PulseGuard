import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { clearToken } from '../auth/tokenStorage'
import { LoginPage } from './LoginPage'

const AUTH_RESPONSE = {
  tokenType: 'Bearer',
  accessToken: 'a.test.token',
  expiresInSeconds: 3600,
  user: {
    id: 1,
    email: 'admin@example.com',
    displayName: 'Project Admin',
    systemRole: 'USER',
  },
}

/** Stands in for every real page, and reports the path it was reached at. */
function LandedAt() {
  const location = useLocation()
  return <div data-testid="landed-at">{location.pathname}</div>
}

/**
 * Renders the login page as though the route guard had just interrupted a
 * journey to {@code interruptedPath}, signs in, and reports where that landed.
 */
async function landingAfterLoginFrom(interruptedPath: string) {
  render(
    <MemoryRouter
      initialEntries={[{ pathname: '/login', state: { from: { pathname: interruptedPath } } }]}
    >
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="*" element={<LandedAt />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )

  await userEvent.type(await screen.findByLabelText(/email/i), 'admin@example.com')
  await userEvent.type(screen.getByLabelText(/password/i), 'SecurePassword123!')
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

  return (await screen.findByTestId('landed-at')).textContent
}

/**
 * The wiring, not the rule.
 *
 * <p>{@code safeRedirect.test.ts} covers which paths are acceptable. What this
 * checks is that the login page actually consults it — the check is one line,
 * and removing it would leave every unit test above still passing.
 */
describe('LoginPage redirect', () => {
  beforeEach(() => {
    clearToken()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => AUTH_RESPONSE,
      })),
    )
  })

  it('resumes the journey the route guard interrupted', async () => {
    expect(await landingAfterLoginFrom('/monitors/25')).toBe('/monitors/25')
  })

  /**
   * The remembered path came from the address bar. A link to
   * `https://pulseguard.example/\evil.example` would otherwise bounce the user
   * off-site immediately after a genuine login.
   */
  it('refuses to leave the site for a path someone else chose', async () => {
    expect(await landingAfterLoginFrom('/\\evil.example')).toBe('/projects')
  })

  it('refuses a protocol-relative destination', async () => {
    expect(await landingAfterLoginFrom('//evil.example')).toBe('/projects')
  })
})
