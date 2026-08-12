import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { monitoringApi } from '../api/monitoringApi'
import { EmptyState, ErrorState, LoadingState } from '../components/States'
import { useProject } from './ProjectLayout'
import { formatDateTime, formatMilliseconds, formatNumber, formatText, formatUptime } from '../utils/format'
import type { ProjectDashboard } from '../types/monitoring'

export function ProjectDashboardPage() {
  const { project } = useProject()
  const [dashboard, setDashboard] = useState<ProjectDashboard | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      setDashboard(await monitoringApi.dashboard(project.id))
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Unable to load the dashboard.')
    } finally {
      setIsLoading(false)
    }
  }, [project.id])

  useEffect(() => {
    void load()
  }, [load])

  if (isLoading) {
    return <LoadingState message="Loading dashboard…" />
  }

  if (error || !dashboard) {
    return <ErrorState message={error ?? 'No dashboard data.'} onRetry={() => void load()} />
  }

  const { monitors, checks, window, recentFailures } = dashboard

  return (
    <div className="dashboard">
      <div className="section-header">
        <div>
          <h2>Overview</h2>
          <p className="section-header__meta">
            Checks measured {formatDateTime(window.from)} → {formatDateTime(window.to)} · generated{' '}
            {formatDateTime(dashboard.generatedAt)}
          </p>
        </div>
        {/* Manual refresh only. The worker writes continuously, but polling the
            dashboard every few seconds would add complexity for no real gain. */}
        <button type="button" className="btn btn--secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>

      {/* Current state — these counts describe now, not the window above. */}
      <div className="stat-grid">
        <StatCard label="Monitors" value={formatNumber(monitors.total)} />
        <StatCard label="Up" value={formatNumber(monitors.up)} tone="up" />
        <StatCard label="Down" value={formatNumber(monitors.down)} tone="down" />
        <StatCard label="Unknown" value={formatNumber(monitors.unknown)} tone="unknown" />
        <StatCard label="Paused" value={formatNumber(monitors.paused)} tone="paused" />
      </div>

      <div className="stat-grid">
        {/* Uptime and averages come straight from the backend. The frontend
            never recomputes them — averaging monitor percentages in the browser
            would give a different, wrong answer. */}
        <StatCard label="Uptime" value={formatUptime(checks.uptimePercentage)} />
        <StatCard label="Checks" value={formatNumber(checks.total)} />
        <StatCard label="Successful" value={formatNumber(checks.successful)} tone="up" />
        <StatCard label="Failed" value={formatNumber(checks.failed)} tone="down" />
        <StatCard label="Avg response" value={formatMilliseconds(checks.averageResponseTimeMs)} />
      </div>

      <section className="card">
        <h2>Recent failures</h2>
        {recentFailures.length === 0 ? (
          <EmptyState
            title="No failures in this window"
            description="Nothing has failed in the period above."
          />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Monitor</th>
                  <th scope="col">Checked</th>
                  <th scope="col">HTTP</th>
                  <th scope="col">Error</th>
                  <th scope="col">Detail</th>
                </tr>
              </thead>
              <tbody>
                {recentFailures.map((failure, index) => (
                  <tr key={`${failure.monitorId}-${failure.checkedAt}-${index}`}>
                    <td>
                      <Link to={`/monitors/${failure.monitorId}`}>{failure.monitorName}</Link>
                    </td>
                    <td>{formatDateTime(failure.checkedAt)}</td>
                    <td>{formatText(failure.httpStatusCode?.toString())}</td>
                    <td>{formatText(failure.errorType)}</td>
                    <td className="cell--detail">{formatText(failure.errorMessage)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}

function StatCard({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone?: 'up' | 'down' | 'unknown' | 'paused'
}) {
  return (
    <div className={tone ? `stat stat--${tone}` : 'stat'}>
      <span className="stat__label">{label}</span>
      <span className="stat__value">{value}</span>
    </div>
  )
}
