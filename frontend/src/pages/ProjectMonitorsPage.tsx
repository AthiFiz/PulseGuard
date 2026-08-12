import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { monitorApi } from '../api/monitorApi'
import { StatusBadge } from '../components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '../components/States'
import { useProject } from './ProjectLayout'
import { formatDateTime, formatSeconds } from '../utils/format'
import type { Monitor } from '../types/monitor'

export function ProjectMonitorsPage() {
  const { project, canManage } = useProject()
  const [monitors, setMonitors] = useState<Monitor[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      setMonitors(await monitorApi.listForProject(project.id))
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Unable to load monitors.')
    } finally {
      setIsLoading(false)
    }
  }, [project.id])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <div>
      <div className="section-header">
        <h2>Monitors</h2>
        <div className="section-header__actions">
          <button type="button" className="btn btn--secondary" onClick={() => void load()}>
            Refresh
          </button>
          {/* Hidden for a VIEWER purely to avoid offering an action that would
              be refused. The backend rejects it regardless. */}
          {canManage && (
            <Link to={`/projects/${project.id}/monitors/new`} className="btn btn--primary">
              New monitor
            </Link>
          )}
        </div>
      </div>

      {isLoading && <LoadingState message="Loading monitors…" />}

      {!isLoading && error && <ErrorState message={error} onRetry={() => void load()} />}

      {!isLoading && !error && monitors.length === 0 && (
        <EmptyState
          title="No monitors yet"
          description={
            canManage
              ? 'Add a monitor to start checking an endpoint.'
              : 'Nobody has added a monitor to this project yet.'
          }
          action={
            canManage ? (
              <Link to={`/projects/${project.id}/monitors/new`} className="btn btn--primary">
                Add a monitor
              </Link>
            ) : undefined
          }
        />
      )}

      {!isLoading && !error && monitors.length > 0 && (
        <div className="card">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Status</th>
                  <th scope="col">URL</th>
                  <th scope="col">Expects</th>
                  <th scope="col">Interval</th>
                  <th scope="col">Last checked</th>
                  <th scope="col">Next check</th>
                </tr>
              </thead>
              <tbody>
                {monitors.map((monitor) => (
                  <tr key={monitor.id}>
                    <td>
                      <Link to={`/monitors/${monitor.id}`}>{monitor.name}</Link>
                    </td>
                    <td>
                      {/* Always the backend's current status — never derived
                          from the check history. */}
                      <StatusBadge status={monitor.currentStatus} />
                    </td>
                    <td className="cell--url">{monitor.url}</td>
                    <td>
                      {monitor.httpMethod} {monitor.expectedStatusCode}
                    </td>
                    <td>{formatSeconds(monitor.intervalSeconds)}</td>
                    <td>{formatDateTime(monitor.lastCheckedAt)}</td>
                    <td>{formatDateTime(monitor.nextCheckAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
