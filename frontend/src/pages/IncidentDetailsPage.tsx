import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { incidentApi } from '../api/incidentApi'
import { IncidentStatusBadge } from '../components/StatusBadge'
import { ErrorState, LoadingState } from '../components/States'
import { formatDateTime, formatDuration, formatNumber } from '../utils/format'
import type { Incident } from '../types/incident'

/**
 * One outage in full.
 *
 * <p>Read-only by design: there is no acknowledge, assign or close. An incident
 * ends when the monitored service answers again, and nothing a user clicks can
 * bring that forward.
 */
export function IncidentDetailsPage() {
  const { incidentId: incidentIdParam } = useParams()
  const incidentId = Number(incidentIdParam)

  const [incident, setIncident] = useState<Incident | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      setIncident(await incidentApi.get(incidentId))
    } catch (caught) {
      // An incident in someone else's project answers 404 exactly like one that
      // never existed, and the message is shown as the backend worded it.
      setError(caught instanceof ApiError ? caught.message : 'Unable to load this incident.')
    } finally {
      setIsLoading(false)
    }
  }, [incidentId])

  useEffect(() => {
    void load()
  }, [load])

  if (isLoading) {
    return <LoadingState message="Loading incident…" />
  }

  if (error || !incident) {
    return <ErrorState message={error ?? 'Incident not found.'} onRetry={() => void load()} />
  }

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <p className="breadcrumb">
            <Link to={`/projects/${incident.projectId}/incidents`}>← Back to incidents</Link>
          </p>
          <h1>
            Incident #{incident.id} <IncidentStatusBadge status={incident.status} />
          </h1>
          <p className="page__subtitle">
            {incident.status === 'OPEN'
              ? 'This outage is still ongoing. It will resolve automatically on the next successful check.'
              : 'This outage ended when the monitor answered successfully again.'}
          </p>
        </div>
      </div>

      <section className="card">
        <h2>Details</h2>
        <dl className="detail-grid">
          <Detail
            label="Monitor"
            value={
              <Link to={`/monitors/${incident.monitorId}`}>{incident.monitorName}</Link>
            }
          />
          <Detail label="Opened at" value={formatDateTime(incident.openedAt)} />
          <Detail label="Resolved at" value={formatDateTime(incident.resolvedAt)} />
          <Detail
            label="Duration"
            value={formatDuration(incident.openedAt, incident.resolvedAt)}
          />
          {/* The checks either side of the outage, by id. They are the raw
              evidence behind the two timestamps above. */}
          <Detail label="Opening check" value={formatNumber(incident.openingCheckId)} />
          <Detail label="Resolution check" value={formatNumber(incident.resolutionCheckId)} />
        </dl>
      </section>
    </div>
  )
}

function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="detail">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}
