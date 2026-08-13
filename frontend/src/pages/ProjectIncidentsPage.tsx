import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { incidentApi } from '../api/incidentApi'
import { IncidentStatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/States'
import { useProject } from './ProjectLayout'
import { formatDateTime, formatDuration, formatNumber } from '../utils/format'
import type { Incident, IncidentStatus } from '../types/incident'
import type { PageResponse } from '../types/api'

const PAGE_SIZE = 20

/**
 * A project's outage history.
 *
 * <p>Read-only for everyone, viewers included. Incidents are produced by the
 * monitoring engine, so there is no action to offer and no role to check.
 */
export function ProjectIncidentsPage() {
  const { project } = useProject()
  const [searchParams] = useSearchParams()

  // The dashboard's "Open incidents" card links here with ?status=OPEN. The
  // parameter seeds the filter once; the control owns it afterwards.
  const initialStatus = searchParams.get('status')
  const [status, setStatus] = useState<IncidentStatus | ''>(
    initialStatus === 'OPEN' || initialStatus === 'RESOLVED' ? initialStatus : '',
  )
  const [page, setPage] = useState(0)

  const [incidents, setIncidents] = useState<PageResponse<Incident> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      setIncidents(
        await incidentApi.listForProject(project.id, {
          page,
          size: PAGE_SIZE,
          status: status || null,
        }),
      )
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Unable to load incidents.')
    } finally {
      setIsLoading(false)
    }
  }, [project.id, page, status])

  useEffect(() => {
    void load()
  }, [load])

  function changeStatus(next: IncidentStatus | '') {
    // A different filter makes the current page number meaningless.
    setStatus(next)
    setPage(0)
  }

  return (
    <div>
      <div className="section-header">
        <div>
          <h2>Incidents</h2>
          <p className="section-header__meta">
            One incident covers a whole outage, however many checks failed during it.
          </p>
        </div>
        <div className="section-header__actions">
          <button type="button" className="btn btn--secondary" onClick={() => void load()}>
            Refresh
          </button>
        </div>
      </div>

      <div className="filters">
        <div className="field">
          <label htmlFor="incident-status">Status</label>
          <select
            id="incident-status"
            value={status}
            onChange={(event) => changeStatus(event.target.value as IncidentStatus | '')}
          >
            <option value="">All</option>
            <option value="OPEN">Open</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </div>
      </div>

      {isLoading && <LoadingState message="Loading incidents…" />}

      {!isLoading && error && <ErrorState message={error} onRetry={() => void load()} />}

      {!isLoading && !error && incidents && incidents.content.length === 0 && (
        <EmptyState
          title={status === '' ? 'No incidents yet' : 'No incidents match this filter'}
          description={
            status === ''
              ? 'Nothing has gone down yet. An incident is opened automatically when a monitor reaches its failure threshold.'
              : 'Try a different status.'
          }
        />
      )}

      {!isLoading && !error && incidents && incidents.content.length > 0 && (
        <div className="card">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Status</th>
                  <th scope="col">Monitor</th>
                  <th scope="col">Opened</th>
                  <th scope="col">Resolved</th>
                  <th scope="col">Duration</th>
                  <th scope="col" />
                </tr>
              </thead>
              <tbody>
                {incidents.content.map((incident) => (
                  <tr key={incident.id}>
                    <td>
                      <IncidentStatusBadge status={incident.status} />
                    </td>
                    <td>{incident.monitorName}</td>
                    <td>{formatDateTime(incident.openedAt)}</td>
                    {/* An open incident has no end, and the dash says so. */}
                    <td>{formatDateTime(incident.resolvedAt)}</td>
                    <td>{formatDuration(incident.openedAt, incident.resolvedAt)}</td>
                    <td>
                      <Link to={`/incidents/${incident.id}`}>Details</Link>
                    </td>
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
              disabled={incidents.first}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </button>
            <span className="pagination__info">
              Page {incidents.page + 1} of {Math.max(incidents.totalPages, 1)} ·{' '}
              {formatNumber(incidents.totalElements)} incidents
            </span>
            <button
              type="button"
              className="btn btn--secondary"
              disabled={incidents.last}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
