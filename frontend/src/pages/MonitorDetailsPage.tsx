import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { monitorApi } from '../api/monitorApi'
import { monitoringApi } from '../api/monitoringApi'
import { projectApi } from '../api/projectApi'
import { useAuth } from '../auth/AuthContext'
import { canManageMonitors, findOwnMembership } from '../auth/permissions'
import { OutcomeBadge, StatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/States'
import {
  formatDateTime,
  formatMilliseconds,
  formatNumber,
  formatSeconds,
  formatText,
  formatUptime,
  localInputToIso,
} from '../utils/format'
import type { Monitor } from '../types/monitor'
import type { MonitorCheck, MonitorCheckOutcome, MonitorStatistics } from '../types/monitoring'
import type { PageResponse } from '../types/api'

const PAGE_SIZE = 50

export function MonitorDetailsPage() {
  const { monitorId: monitorIdParam } = useParams()
  const monitorId = Number(monitorIdParam)
  const navigate = useNavigate()
  const { user } = useAuth()

  const [monitor, setMonitor] = useState<Monitor | null>(null)
  const [statistics, setStatistics] = useState<MonitorStatistics | null>(null)
  const [canManage, setCanManage] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [isActing, setIsActing] = useState(false)

  // History has its own loading lifecycle so paging does not blank the page.
  const [history, setHistory] = useState<PageResponse<MonitorCheck> | null>(null)
  const [isHistoryLoading, setIsHistoryLoading] = useState(true)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const [outcome, setOutcome] = useState<MonitorCheckOutcome | ''>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  const loadMonitor = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      const [loadedMonitor, loadedStatistics] = await Promise.all([
        monitorApi.get(monitorId),
        monitoringApi.statistics(monitorId),
      ])
      setMonitor(loadedMonitor)
      setStatistics(loadedStatistics)

      // A monitor carries no membership of its own, so the caller's role is
      // resolved from the owning project's member list.
      try {
        const members = await projectApi.listMembers(loadedMonitor.projectId)
        setCanManage(canManageMonitors(user, findOwnMembership(user, members)))
      } catch {
        // A system administrator can read a monitor without being a member, so
        // failing to read the member list is not fatal.
        setCanManage(user?.systemRole === 'ADMIN')
      }
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Unable to load this monitor.')
    } finally {
      setIsLoading(false)
    }
  }, [monitorId, user])

  const loadHistory = useCallback(async () => {
    setIsHistoryLoading(true)
    setHistoryError(null)
    try {
      setHistory(
        await monitoringApi.checkHistory(monitorId, {
          page,
          size: PAGE_SIZE,
          outcome: outcome || null,
          from: localInputToIso(from),
          to: localInputToIso(to),
        }),
      )
    } catch (caught) {
      setHistoryError(
        caught instanceof ApiError ? caught.message : 'Unable to load the check history.',
      )
    } finally {
      setIsHistoryLoading(false)
    }
  }, [monitorId, page, outcome, from, to])

  useEffect(() => {
    void loadMonitor()
  }, [loadMonitor])

  useEffect(() => {
    void loadHistory()
  }, [loadHistory])

  async function runAction(action: () => Promise<Monitor>) {
    setActionError(null)
    setIsActing(true)
    try {
      // The backend returns the updated monitor; its state is used verbatim
      // rather than guessing what the new status should be.
      setMonitor(await action())
      setStatistics(await monitoringApi.statistics(monitorId))
    } catch (caught) {
      setActionError(caught instanceof ApiError ? caught.message : 'That action failed.')
    } finally {
      setIsActing(false)
    }
  }

  async function handleDelete() {
    if (!monitor) {
      return
    }
    if (!globalThis.confirm(`Delete "${monitor.name}"? Its check history will be removed too.`)) {
      return
    }
    setActionError(null)
    setIsActing(true)
    try {
      await monitorApi.remove(monitor.id)
      navigate(`/projects/${monitor.projectId}/monitors`, { replace: true })
    } catch (caught) {
      setActionError(caught instanceof ApiError ? caught.message : 'Unable to delete the monitor.')
      setIsActing(false)
    }
  }

  function applyFilters() {
    // A filter change makes the current page number meaningless.
    setPage(0)
    void loadHistory()
  }

  if (isLoading) {
    return <LoadingState message="Loading monitor…" />
  }

  if (error || !monitor) {
    return <ErrorState message={error ?? 'Monitor not found.'} onRetry={() => void loadMonitor()} />
  }

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <p className="breadcrumb">
            <Link to={`/projects/${monitor.projectId}/monitors`}>← Back to monitors</Link>
          </p>
          <h1>
            {monitor.name} <StatusBadge status={monitor.currentStatus} />
          </h1>
          <p className="page__subtitle cell--url">{monitor.url}</p>
        </div>

        {canManage && (
          <div className="section-header__actions">
            {monitor.currentStatus === 'PAUSED' ? (
              <button
                type="button"
                className="btn btn--secondary"
                disabled={isActing}
                onClick={() => void runAction(() => monitorApi.resume(monitor.id))}
              >
                Resume
              </button>
            ) : (
              <button
                type="button"
                className="btn btn--secondary"
                disabled={isActing}
                onClick={() => void runAction(() => monitorApi.pause(monitor.id))}
              >
                Pause
              </button>
            )}
            <Link to={`/monitors/${monitor.id}/edit`} className="btn btn--secondary">
              Edit
            </Link>
            <button
              type="button"
              className="btn btn--danger"
              disabled={isActing}
              onClick={() => void handleDelete()}
            >
              Delete
            </button>
          </div>
        )}
      </div>

      {actionError && (
        <p className="alert alert--error" role="alert">
          {actionError}
        </p>
      )}

      <section className="card">
        <h2>Configuration</h2>
        <dl className="detail-grid">
          <Detail label="Description" value={formatText(monitor.description)} />
          <Detail label="Method" value={monitor.httpMethod} />
          <Detail label="Expected status" value={String(monitor.expectedStatusCode)} />
          <Detail label="Interval" value={formatSeconds(monitor.intervalSeconds)} />
          <Detail label="Timeout" value={formatSeconds(monitor.timeoutSeconds)} />
          <Detail label="Failure threshold" value={String(monitor.failureThreshold)} />
          <Detail label="Consecutive failures" value={String(monitor.consecutiveFailures)} />
          <Detail label="Last checked" value={formatDateTime(monitor.lastCheckedAt)} />
          <Detail label="Next check" value={formatDateTime(monitor.nextCheckAt)} />
        </dl>
      </section>

      <section>
        <div className="section-header">
          <h2>Statistics</h2>
          <p className="section-header__meta">All recorded history</p>
        </div>
        {/* Every figure below is the backend's own aggregate. Nothing here is
            recalculated from the history page on screen. */}
        <div className="stat-grid">
          <Stat label="Uptime" value={formatUptime(statistics?.uptimePercentage ?? null)} />
          <Stat label="Checks" value={formatNumber(statistics?.totalChecks ?? null)} />
          <Stat label="Successful" value={formatNumber(statistics?.successfulChecks ?? null)} tone="up" />
          <Stat label="Failed" value={formatNumber(statistics?.failedChecks ?? null)} tone="down" />
          <Stat label="Avg response" value={formatMilliseconds(statistics?.averageResponseTimeMs ?? null)} />
          <Stat label="Fastest" value={formatMilliseconds(statistics?.minimumResponseTimeMs ?? null)} />
          <Stat label="Slowest" value={formatMilliseconds(statistics?.maximumResponseTimeMs ?? null)} />
          <Stat label="Last check" value={formatDateTime(statistics?.lastCheckedAt ?? null)} />
        </div>
      </section>

      <section className="card">
        <div className="section-header">
          <h2>Check history</h2>
          <button type="button" className="btn btn--secondary" onClick={() => void loadHistory()}>
            Refresh
          </button>
        </div>

        <div className="filters">
          <div className="field">
            <label htmlFor="outcome">Outcome</label>
            <select
              id="outcome"
              value={outcome}
              onChange={(event) => {
                setOutcome(event.target.value as MonitorCheckOutcome | '')
                setPage(0)
              }}
            >
              <option value="">All</option>
              <option value="SUCCESS">Success</option>
              <option value="FAILURE">Failure</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="from">From</label>
            <input
              id="from"
              type="datetime-local"
              value={from}
              onChange={(event) => setFrom(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="to">To</label>
            <input
              id="to"
              type="datetime-local"
              value={to}
              onChange={(event) => setTo(event.target.value)}
            />
          </div>
          <button type="button" className="btn btn--secondary" onClick={applyFilters}>
            Apply
          </button>
          {(from || to || outcome) && (
            <button
              type="button"
              className="btn btn--ghost"
              onClick={() => {
                setFrom('')
                setTo('')
                setOutcome('')
                setPage(0)
              }}
            >
              Clear
            </button>
          )}
        </div>

        {isHistoryLoading && <LoadingState message="Loading history…" />}

        {!isHistoryLoading && historyError && (
          <ErrorState message={historyError} onRetry={() => void loadHistory()} />
        )}

        {!isHistoryLoading && !historyError && history && history.content.length === 0 && (
          <EmptyState
            title="No checks recorded"
            description="Nothing matches these filters yet. The worker records a row each time it checks this monitor."
          />
        )}

        {!isHistoryLoading && !historyError && history && history.content.length > 0 && (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Checked at</th>
                    <th scope="col">Outcome</th>
                    <th scope="col">HTTP</th>
                    <th scope="col">Response</th>
                    <th scope="col">Error</th>
                    <th scope="col">Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {history.content.map((check) => (
                    <tr key={check.id}>
                      <td>{formatDateTime(check.checkedAt)}</td>
                      <td>
                        <OutcomeBadge outcome={check.outcome} />
                      </td>
                      {/* Null status and duration are real: a DNS failure never
                          got a response to measure. */}
                      <td>{formatText(check.httpStatusCode?.toString())}</td>
                      <td>{formatMilliseconds(check.responseTimeMs)}</td>
                      <td>{formatText(check.errorType)}</td>
                      <td className="cell--detail">{formatText(check.errorMessage)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Each page is a fresh request — nothing is sliced client-side. */}
            <div className="pagination">
              <button
                type="button"
                className="btn btn--secondary"
                disabled={history.first}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                Previous
              </button>
              <span className="pagination__info">
                Page {history.page + 1} of {Math.max(history.totalPages, 1)} ·{' '}
                {formatNumber(history.totalElements)} checks
              </span>
              <button
                type="button"
                className="btn btn--secondary"
                disabled={history.last}
                onClick={() => setPage((current) => current + 1)}
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="detail">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone?: 'up' | 'down'
}) {
  return (
    <div className={tone ? `stat stat--${tone}` : 'stat'}>
      <span className="stat__label">{label}</span>
      <span className="stat__value">{value}</span>
    </div>
  )
}
