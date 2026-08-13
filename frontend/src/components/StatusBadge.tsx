import type { IncidentStatus } from '../types/incident'
import type { MonitorStatus } from '../types/monitor'

const LABELS: Record<MonitorStatus, string> = {
  UP: 'Up',
  DOWN: 'Down',
  UNKNOWN: 'Unknown',
  PAUSED: 'Paused',
}

/**
 * The single presentation of monitor status, used everywhere so a colour never
 * means one thing on the dashboard and another in a list.
 *
 * The status word is always rendered as text, not conveyed by colour alone.
 */
export function StatusBadge({ status }: { status: MonitorStatus }) {
  return (
    <span className={`badge badge--${status.toLowerCase()}`}>
      <span className="badge__dot" aria-hidden="true" />
      {LABELS[status]}
    </span>
  )
}

export function OutcomeBadge({ outcome }: { outcome: 'SUCCESS' | 'FAILURE' }) {
  return (
    <span className={`badge badge--${outcome === 'SUCCESS' ? 'up' : 'down'}`}>
      <span className="badge__dot" aria-hidden="true" />
      {outcome === 'SUCCESS' ? 'Success' : 'Failure'}
    </span>
  )
}

/**
 * Incident status, kept visually distinct from monitor status.
 *
 * They answer different questions: a monitor is UP or DOWN *now*, while an
 * incident is OPEN or RESOLVED as a matter of record. Sharing the badge would
 * invite reading a RESOLVED incident as a healthy monitor.
 */
export function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  return (
    <span className={`badge badge--incident-${status.toLowerCase()}`}>
      <span className="badge__dot" aria-hidden="true" />
      {status === 'OPEN' ? 'Open' : 'Resolved'}
    </span>
  )
}
