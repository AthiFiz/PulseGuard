import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { setToken } from '../auth/tokenStorage'
import { IncidentDetailsPage } from './IncidentDetailsPage'
import { ProjectIncidentsPage } from './ProjectIncidentsPage'
import { ProjectLayout } from './ProjectLayout'

const USER = {
  id: 1,
  email: 'user@example.com',
  displayName: 'Project Admin',
  systemRole: 'USER',
  enabled: true,
  createdAt: '2026-08-01T00:00:00Z',
}

const PROJECT = {
  id: 10,
  name: 'Production APIs',
  description: 'Production monitoring',
  createdBy: { id: 1, email: 'user@example.com', displayName: 'Project Admin' },
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
}

function member(userId: number, role: 'PROJECT_ADMIN' | 'VIEWER') {
  return {
    memberId: 100 + userId,
    userId,
    email: 'someone@example.com',
    displayName: 'Someone',
    role,
    joinedAt: '2026-08-01T00:00:00Z',
  }
}

const RESOLVED = {
  id: 41,
  projectId: 10,
  monitorId: 25,
  monitorName: 'Payment API',
  status: 'RESOLVED',
  openedAt: '2026-08-12T10:10:00Z',
  resolvedAt: '2026-08-12T10:18:00Z',
  openingCheckId: 1501,
  resolutionCheckId: 1517,
}

const OPEN = {
  id: 42,
  projectId: 10,
  monitorId: 25,
  monitorName: 'Payment API',
  status: 'OPEN',
  openedAt: '2026-08-12T11:00:00Z',
  resolvedAt: null,
  openingCheckId: 1600,
  resolutionCheckId: null,
}

function incidentPage(content: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    first: true,
    last: true,
    ...overrides,
  }
}

function routeFetch(routes: Array<[RegExp, unknown]>) {
  const fetchMock = vi.fn(async (url: string) => {
    for (const [pattern, body] of routes) {
      if (pattern.test(url)) {
        return { ok: true, status: 200, json: async () => body }
      }
    }
    throw new Error(`Unexpected request in test: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** The list lives inside the project shell, so the shell has to load too. */
function renderIncidentList(routes: Array<[RegExp, unknown]>, path = '/projects/10/incidents') {
  const fetchMock = routeFetch(routes)
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/projects/:projectId" element={<ProjectLayout />}>
            <Route path="incidents" element={<ProjectIncidentsPage />} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
  return fetchMock
}

function renderIncidentDetail(routes: Array<[RegExp, unknown]>) {
  routeFetch(routes)
  render(
    <MemoryRouter initialEntries={['/incidents/41']}>
      <AuthProvider>
        <Routes>
          <Route path="/incidents/:incidentId" element={<IncidentDetailsPage />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

const PROJECT_ROUTES: Array<[RegExp, unknown]> = [
  [/\/auth\/me$/, USER],
  [/\/projects\/10$/, PROJECT],
  [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
]

beforeEach(() => {
  setToken('a-valid-token')
})

describe('ProjectIncidentsPage', () => {
  it('renders open and resolved incidents together', async () => {
    renderIncidentList([
      ...PROJECT_ROUTES,
      [/\/projects\/10\/incidents/, incidentPage([OPEN, RESOLVED])],
    ])

    expect(await screen.findAllByText('Payment API')).toHaveLength(2)
    // Scoped to the body: "Resolved" is also a column header and a filter option.
    const body = screen.getAllByRole('rowgroup')[1]
    expect(within(body).getByText('Open')).toBeTruthy()
    expect(within(body).getByText('Resolved')).toBeTruthy()
  })

  /** Duration is presentation only — the backend stores no such column. */
  it('shows a duration for a resolved incident and none for an open one', async () => {
    renderIncidentList([
      ...PROJECT_ROUTES,
      [/\/projects\/10\/incidents/, incidentPage([OPEN, RESOLVED])],
    ])

    // 10:10 to 10:18 is eight minutes.
    expect(await screen.findByText('8m 0s')).toBeTruthy()
    expect(screen.getAllByText('Payment API')).toHaveLength(2)
    // The open one has neither a resolved time nor a duration.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
  })

  /** A healthy project has no incidents, and that is not an error. */
  it('shows an empty state rather than implying something is broken', async () => {
    renderIncidentList([...PROJECT_ROUTES, [/\/projects\/10\/incidents/, incidentPage([])]])

    expect(await screen.findByText('No incidents yet')).toBeTruthy()
  })

  it('asks the API for the selected status rather than filtering in the browser', async () => {
    const fetchMock = renderIncidentList([
      ...PROJECT_ROUTES,
      [/\/projects\/10\/incidents/, incidentPage([RESOLVED])],
    ])

    await screen.findAllByText('Payment API')
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'OPEN')

    await waitFor(() => {
      const urls = fetchMock.mock.calls.map((call) => String(call[0]))
      expect(urls.some((url) => url.includes('status=OPEN'))).toBe(true)
    })
  })

  /** The dashboard's Open incidents card links here with ?status=OPEN. */
  it('starts on the open filter when the URL asks for it', async () => {
    const fetchMock = renderIncidentList(
      [...PROJECT_ROUTES, [/\/projects\/10\/incidents/, incidentPage([OPEN])]],
      '/projects/10/incidents?status=OPEN',
    )

    await screen.findAllByText('Payment API')
    const urls = fetchMock.mock.calls.map((call) => String(call[0]))
    expect(urls.some((url) => url.includes('status=OPEN'))).toBe(true)
  })

  it('requests the next page from the API when Next is pressed', async () => {
    const fetchMock = renderIncidentList([
      ...PROJECT_ROUTES,
      [/\/projects\/10\/incidents/, incidentPage([RESOLVED], { totalPages: 3, last: false })],
    ])

    await screen.findAllByText('Payment API')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      const urls = fetchMock.mock.calls.map((call) => String(call[0]))
      expect(urls.some((url) => url.includes('page=1'))).toBe(true)
    })
  })

  /** Incidents are system-generated; there is nothing for a user to do here. */
  it('offers no way to create or resolve an incident', async () => {
    renderIncidentList([
      ...PROJECT_ROUTES,
      [/\/projects\/10\/incidents/, incidentPage([OPEN])],
    ])

    await screen.findAllByText('Payment API')
    expect(screen.queryByRole('button', { name: /acknowledge/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /resolve/i })).toBeNull()
    expect(screen.queryByRole('link', { name: /new incident/i })).toBeNull()
  })
})

describe('IncidentDetailsPage', () => {
  it('renders a resolved incident with both timestamps and its checks', async () => {
    renderIncidentDetail([
      [/\/auth\/me$/, USER],
      [/\/incidents\/41$/, RESOLVED],
    ])

    expect(await screen.findByText('Incident #41')).toBeTruthy()
    expect(screen.getByText('Resolved')).toBeTruthy()
    expect(screen.getByText('Opened at')).toBeTruthy()
    expect(screen.getByText('8m 0s')).toBeTruthy()
    expect(screen.getByText('1,501')).toBeTruthy()
    expect(screen.getByText('1,517')).toBeTruthy()
  })

  it('links the monitor to its own page', async () => {
    renderIncidentDetail([
      [/\/auth\/me$/, USER],
      [/\/incidents\/41$/, RESOLVED],
    ])

    const link = await screen.findByRole('link', { name: 'Payment API' })
    expect(link.getAttribute('href')).toBe('/monitors/25')
  })

  it('invents no resolution for an incident that is still open', async () => {
    renderIncidentDetail([
      [/\/auth\/me$/, USER],
      [/\/incidents\/41$/, { ...OPEN, id: 41 }],
    ])

    expect(await screen.findByText('Open')).toBeTruthy()
    expect(screen.getByText(/still ongoing/)).toBeTruthy()
    // Resolved, Duration and Resolution check all have nothing to show.
    expect(screen.getAllByText('—')).toHaveLength(3)
  })

  /** An incident in someone else's project is hidden exactly like a missing one. */
  it('shows the not-found message for an inaccessible incident', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (/\/auth\/me$/.test(url)) {
          return { ok: true, status: 200, json: async () => USER }
        }
        return {
          ok: false,
          status: 404,
          json: async () => ({ code: 'INCIDENT_NOT_FOUND', message: 'Incident not found' }),
        }
      }),
    )

    render(
      <MemoryRouter initialEntries={['/incidents/41']}>
        <AuthProvider>
          <Routes>
            <Route path="/incidents/:incidentId" element={<IncidentDetailsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Incident not found')).toBeTruthy()
  })
})
