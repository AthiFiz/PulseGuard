import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { setToken } from '../auth/tokenStorage'
import { ProjectLayout } from './ProjectLayout'
import { ProjectDashboardPage } from './ProjectDashboardPage'
import { ProjectMonitorsPage } from './ProjectMonitorsPage'
import { MonitorDetailsPage } from './MonitorDetailsPage'
import { MonitorFormPage } from './MonitorFormPage'

const ADMIN_USER = {
  id: 1,
  email: 'admin@example.com',
  displayName: 'Project Admin',
  systemRole: 'USER',
  enabled: true,
  createdAt: '2026-08-01T00:00:00Z',
}

const PROJECT = {
  id: 10,
  name: 'Production APIs',
  description: 'Production monitoring',
  createdBy: { id: 1, email: 'admin@example.com', displayName: 'Project Admin' },
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

const MONITOR = {
  id: 25,
  projectId: 10,
  name: 'Payment API',
  description: 'Payment health endpoint',
  url: 'https://api.example.com/health',
  httpMethod: 'GET',
  expectedStatusCode: 200,
  intervalSeconds: 60,
  timeoutSeconds: 5,
  failureThreshold: 3,
  consecutiveFailures: 0,
  currentStatus: 'UP',
  lastCheckedAt: '2026-08-12T08:00:00Z',
  nextCheckAt: '2026-08-12T08:01:00Z',
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
}

/**
 * Routes fetch calls by URL, so a page under test can be given exactly the
 * responses it needs. Anything unrouted fails loudly rather than silently
 * returning undefined.
 */
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

function renderProjectRoute(ui: React.ReactNode, path: string, routePattern: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/projects/:projectId" element={<ProjectLayout />}>
            <Route path={routePattern} element={ui} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  setToken('a-valid-token')
})

describe('ProjectDashboardPage', () => {
  const DASHBOARD = {
    projectId: 10,
    generatedAt: '2026-08-12T08:30:00Z',
    window: { from: '2026-08-11T08:30:00Z', to: '2026-08-12T08:30:00Z' },
    monitors: { total: 10, up: 7, down: 1, unknown: 1, paused: 1 },
    openIncidents: 1,
    checks: {
      total: 1430,
      successful: 1400,
      failed: 30,
      uptimePercentage: 97.9,
      averageResponseTimeMs: 132.5,
    },
    recentFailures: [
      {
        monitorId: 25,
        monitorName: 'Payment API',
        checkedAt: '2026-08-12T08:20:00Z',
        httpStatusCode: 500,
        errorType: 'UNEXPECTED_STATUS',
        errorMessage: 'Expected HTTP 200 but received 500',
      },
    ],
  }

  it('renders the monitor counts and check figures from the backend', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/projects\/10\/dashboard/, DASHBOARD],
    ])

    renderProjectRoute(<ProjectDashboardPage />, '/projects/10/dashboard', 'dashboard')

    expect(await screen.findByText('97.90%')).toBeTruthy()
    expect(screen.getByText('1,430')).toBeTruthy()
    expect(screen.getByText('133 ms')).toBeTruthy()
    // Status counts are rendered exactly as the backend reported them.
    expect(screen.getByText('7')).toBeTruthy()
  })

  /**
   * The count comes from the dashboard response. Fetching every incident and
   * counting them in the browser would be a second, slower source of truth.
   */
  it('shows the open incident count from the dashboard response', async () => {
    const fetchMock = routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/projects\/10\/dashboard/, DASHBOARD],
    ])

    renderProjectRoute(<ProjectDashboardPage />, '/projects/10/dashboard', 'dashboard')

    expect(await screen.findByText('Open incidents')).toBeTruthy()
    const urls = fetchMock.mock.calls.map((call) => String(call[0]))
    expect(urls.some((url) => url.includes('/incidents'))).toBe(false)
  })

  it('links the open incident count to the filtered incident list', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/projects\/10\/dashboard/, DASHBOARD],
    ])

    renderProjectRoute(<ProjectDashboardPage />, '/projects/10/dashboard', 'dashboard')

    const link = await screen.findByRole('link', { name: /Open incidents/ })
    expect(link.getAttribute('href')).toBe('/projects/10/incidents?status=OPEN')
  })

  /**
   * No checks means unknown availability, not zero. Rendering 0% would claim an
   * outage that never happened.
   */
  it('shows a dash rather than 0% when there is no monitoring data', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [
        /\/projects\/10\/dashboard/,
        {
          ...DASHBOARD,
          monitors: { total: 2, up: 0, down: 0, unknown: 2, paused: 0 },
          openIncidents: 0,
          checks: {
            total: 0,
            successful: 0,
            failed: 0,
            uptimePercentage: null,
            averageResponseTimeMs: null,
          },
          recentFailures: [],
        },
      ],
    ])

    renderProjectRoute(<ProjectDashboardPage />, '/projects/10/dashboard', 'dashboard')

    await screen.findByText('Overview')
    expect(screen.queryByText('0.00%')).toBeNull()
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    expect(screen.getByText('No failures in this window')).toBeTruthy()
  })
})

describe('ProjectMonitorsPage', () => {
  it('renders each monitor with its current status', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [
        /\/projects\/10\/monitors$/,
        [MONITOR, { ...MONITOR, id: 26, name: 'Customer API', currentStatus: 'DOWN' }],
      ],
    ])

    renderProjectRoute(<ProjectMonitorsPage />, '/projects/10/monitors', 'monitors')

    expect(await screen.findByText('Payment API')).toBeTruthy()
    expect(screen.getByText('Up')).toBeTruthy()
    expect(screen.getByText('Down')).toBeTruthy()
  })

  it('offers management actions to a project admin', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/projects\/10\/monitors$/, [MONITOR]],
    ])

    renderProjectRoute(<ProjectMonitorsPage />, '/projects/10/monitors', 'monitors')

    expect(await screen.findByRole('link', { name: 'New monitor' })).toBeTruthy()
  })

  /** Hiding the control is a usability choice; the backend still refuses it. */
  it('hides management actions from a viewer', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'VIEWER')]],
      [/\/projects\/10\/monitors$/, [MONITOR]],
    ])

    renderProjectRoute(<ProjectMonitorsPage />, '/projects/10/monitors', 'monitors')

    expect(await screen.findByText('Payment API')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'New monitor' })).toBeNull()
    expect(screen.getByText('Read only')).toBeTruthy()
  })

  it('shows an empty state when the project has no monitors', async () => {
    routeFetch([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10$/, PROJECT],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/projects\/10\/monitors$/, []],
    ])

    renderProjectRoute(<ProjectMonitorsPage />, '/projects/10/monitors', 'monitors')

    expect(await screen.findByText('No monitors yet')).toBeTruthy()
  })
})

describe('MonitorDetailsPage', () => {
  const STATISTICS = {
    monitorId: 25,
    from: null,
    to: null,
    totalChecks: 120,
    successfulChecks: 118,
    failedChecks: 2,
    uptimePercentage: 98.33,
    averageResponseTimeMs: 121.42,
    minimumResponseTimeMs: 82,
    maximumResponseTimeMs: 645,
    lastCheckedAt: '2026-08-12T08:00:00Z',
    currentStatus: 'UP',
  }

  function historyPage(page: number, last: boolean) {
    return {
      content: [
        {
          id: 1000 + page,
          checkedAt: '2026-08-12T08:00:00Z',
          outcome: 'SUCCESS',
          httpStatusCode: 200,
          responseTimeMs: 124,
          errorType: null,
          errorMessage: null,
        },
      ],
      page,
      size: 50,
      totalElements: 120,
      totalPages: 3,
      first: page === 0,
      last,
    }
  }

  function renderDetails(routes: Array<[RegExp, unknown]>) {
    const fetchMock = routeFetch(routes)
    render(
      <MemoryRouter initialEntries={['/monitors/25']}>
        <AuthProvider>
          <Routes>
            <Route path="/monitors/:monitorId" element={<MonitorDetailsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )
    return fetchMock
  }

  it('shows backend statistics rather than recomputing them from the page of history', async () => {
    renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, STATISTICS],
      [/\/monitors\/25\/checks/, historyPage(0, false)],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    // 98.33% comes from 120 checks, while only one row is on screen.
    expect(await screen.findByText('98.33%')).toBeTruthy()
    expect(screen.getByText('120')).toBeTruthy()
  })

  it('renders an empty history without breaking the table', async () => {
    renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, { ...STATISTICS, totalChecks: 0, uptimePercentage: null }],
      [
        /\/monitors\/25\/checks/,
        { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0, first: true, last: true },
      ],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    expect(await screen.findByText('No checks recorded')).toBeTruthy()
  })

  /** Each page must be a fresh request, not a slice of already-fetched rows. */
  it('requests the next page from the API when Next is pressed', async () => {
    const fetchMock = renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, STATISTICS],
      [/\/monitors\/25\/checks/, historyPage(0, false)],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    await screen.findByText('Check history')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      const requestedPages = fetchMock.mock.calls
        .map((call) => String(call[0]))
        .filter((url) => url.includes('/checks'))
      expect(requestedPages.some((url) => url.includes('page=1'))).toBe(true)
    })
  })

  it('sends the outcome filter to the API', async () => {
    const fetchMock = renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, STATISTICS],
      [/\/monitors\/25\/checks/, historyPage(0, true)],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    await screen.findByText('Check history')
    await userEvent.selectOptions(screen.getByLabelText('Outcome'), 'FAILURE')

    await waitFor(() => {
      const urls = fetchMock.mock.calls.map((call) => String(call[0]))
      expect(urls.some((url) => url.includes('outcome=FAILURE'))).toBe(true)
    })
  })

  it('hides pause, edit and delete from a viewer', async () => {
    renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, STATISTICS],
      [/\/monitors\/25\/checks/, historyPage(0, true)],
      [/\/projects\/10\/members$/, [member(1, 'VIEWER')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    await screen.findByText('Check history')
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Edit' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
  })

  it('offers pause, edit and delete to a project admin', async () => {
    renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, STATISTICS],
      [/\/monitors\/25\/checks/, historyPage(0, true)],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    expect(await screen.findByRole('button', { name: 'Pause' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Edit' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Delete' })).toBeTruthy()
  })

  it('offers Resume instead of Pause for a paused monitor', async () => {
    renderDetails([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/monitors\/25\/statistics/, { ...STATISTICS, currentStatus: 'PAUSED' }],
      [/\/monitors\/25\/checks/, historyPage(0, true)],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, { ...MONITOR, currentStatus: 'PAUSED' }],
    ])

    expect(await screen.findByRole('button', { name: 'Resume' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull()
  })
})

/**
 * The edit form is reachable by URL, outside the project layout that hides the
 * link. It therefore has to resolve the caller's role for itself.
 */
describe('MonitorFormPage', () => {
  function renderEditForm(routes: Array<[RegExp, unknown]>) {
    routeFetch(routes)
    render(
      <MemoryRouter initialEntries={['/monitors/25/edit']}>
        <AuthProvider>
          <Routes>
            <Route path="/monitors/:monitorId/edit" element={<MonitorFormPage mode="edit" />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )
  }

  it('refuses a viewer who reaches the edit URL directly', async () => {
    renderEditForm([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10\/members$/, [member(1, 'VIEWER')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    expect(
      await screen.findByText(
        'You need the project admin role to add or change monitors in this project.',
      ),
    ).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save changes' })).toBeNull()
  })

  it('shows the populated form to a project admin', async () => {
    renderEditForm([
      [/\/auth\/me$/, ADMIN_USER],
      [/\/projects\/10\/members$/, [member(1, 'PROJECT_ADMIN')]],
      [/\/monitors\/25$/, MONITOR],
    ])

    expect(await screen.findByRole('button', { name: 'Save changes' })).toBeTruthy()
    expect(screen.getByLabelText('Name').getAttribute('value')).toBe('Payment API')
  })
})
